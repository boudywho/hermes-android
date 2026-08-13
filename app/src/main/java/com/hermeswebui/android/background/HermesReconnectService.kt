package com.hermeswebui.android.background

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class HermesReconnectService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private var sessionStreamJob: Job? = null

    // Reference to the in-flight SSE connection so cancellation can actively close the socket.
    // Cancelling sessionStreamJob alone does not interrupt the blocking readLine() in consumeSse
    // nor run the finally-disconnect, so the connection + IO thread would leak until the 45s read
    // timeout (or server close) on every reconnect/relaunch. @Volatile: written on the IO stream
    // thread, read from the main thread in cancelSessionStream().
    @Volatile
    private var activeStreamConnection: HttpURLConnection? = null
    private var activeServerUrl: String? = null
    private var activeSessionId: String? = null
    private var activeSessionTargetUrl: String? = null
    private var activeCookieHeader: String? = null
    private var activePollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS
    private var activeIsReconnecting: Boolean = false
    private var activeShowFullTextOnLockScreen: Boolean = false
    private var currentNotificationBody: String = ""
    private var currentNotificationTargetUrl: String? = null
    private var currentApprovalRequest: NotificationApprovalRequest? = null
    private val approvalStateLock = Any()
    private val respondedApprovalIds = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            handleExit(startId)
            return START_NOT_STICKY
        }
        val exitedUntilExplicitLaunch = runCatching {
            settingsRepository.isBackgroundActivityExitedUntilExplicitLaunch()
        }.getOrDefault(true)
        if (exitedUntilExplicitLaunch) {
            cancelSessionStream()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val recovered = if (intent == null) recoverAfterProcessRestart() else null
        if (intent == null && recovered == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val pollIntervalSeconds = intent?.getIntExtra(
            EXTRA_POLL_INTERVAL_SECONDS,
            DEFAULT_POLL_INTERVAL_SECONDS
        ) ?: recovered?.pollIntervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: recovered?.serverUrl
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: recovered?.sessionId
        val sessionTargetUrl =
            intent?.getStringExtra(EXTRA_SESSION_TARGET_URL) ?: recovered?.sessionTargetUrl
        val cookieHeader =
            intent?.getStringExtra(EXTRA_COOKIE_HEADER) ?: recovered?.cookieHeader
        val isReconnecting = intent?.getBooleanExtra(EXTRA_IS_RECONNECTING, false)
            ?: recovered?.isReconnecting
            ?: false
        val showFullTextOnLockScreen =
            intent?.getBooleanExtra(EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN, false)
                ?: recovered?.showFullTextOnLockScreen
                ?: false

        activeServerUrl = serverUrl
        activeSessionId = sessionId
        activeSessionTargetUrl = sessionTargetUrl
        activeCookieHeader = cookieHeader
        activePollIntervalSeconds = pollIntervalSeconds
        activeIsReconnecting = isReconnecting
        activeShowFullTextOnLockScreen = showFullTextOnLockScreen

        ensureBackgroundActivityChannel()
        val sessionStreamEnabled = !serverUrl.isNullOrBlank() && !sessionId.isNullOrBlank()
        Log.i(
            TAG,
            "Background session monitor started: reconnecting=$isReconnecting, sessionStreamEnabled=$sessionStreamEnabled, hasSession=${!sessionId.isNullOrBlank()}"
        )

        val notification = buildNotification(
            pollIntervalSeconds = pollIntervalSeconds,
            contentText = currentNotificationBody.ifBlank {
                defaultNotificationBody(pollIntervalSeconds, isReconnecting, sessionStreamEnabled)
            },
            targetUrl = currentNotificationTargetUrl ?: sessionTargetUrl,
            showFullTextOnLockScreen = showFullTextOnLockScreen,
            isReconnecting = isReconnecting,
            approvalRequest = currentApprovalRequest
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RECONNECT_NOTIFICATION_ID,
                notification,
                ReconnectForegroundServiceTypePolicy.foregroundServiceType(Build.VERSION.SDK_INT)
            )
        } else {
            startForeground(RECONNECT_NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_RESPOND_APPROVAL) {
            handleApprovalAction(intent)
            return START_STICKY
        }

        cancelSessionStream()
        sessionStreamJob = serviceScope.launch {
            monitorSessionUpdates(
                baseUrl = serverUrl,
                sessionId = sessionId,
                cookieHeader = cookieHeader,
                sessionTargetUrl = sessionTargetUrl,
                pollIntervalSeconds = pollIntervalSeconds,
                isReconnecting = isReconnecting,
                showFullTextOnLockScreen = showFullTextOnLockScreen
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelSessionStream()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelSessionStream()
        stopSelf(startId)
    }

    /**
     * Cancel the SSE stream job AND actively disconnect its socket. The disconnect unblocks the
     * coroutine's blocking readLine() so it throws, runs its finally, and stops the IO thread
     * instead of leaking it until the read timeout.
     */
    private fun cancelSessionStream() {
        sessionStreamJob?.cancel()
        sessionStreamJob = null
        runCatching { activeStreamConnection?.disconnect() }
        activeStreamConnection = null
    }

    private fun buildNotification(
        pollIntervalSeconds: Int,
        contentText: String,
        targetUrl: String?,
        showFullTextOnLockScreen: Boolean,
        isReconnecting: Boolean,
        approvalRequest: NotificationApprovalRequest?
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            RECONNECT_NOTIFICATION_ID,
            buildLaunchIntent(targetUrl),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitAction = buildExitAction()
        val publicNotification = NotificationCompat.Builder(
            this,
            ReconnectNotificationPolicy.CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.reconnect_notification_title))
            .setContentText(publicNotificationBody(isReconnecting))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(exitAction)
            .build()

        return NotificationCompat.Builder(this, ReconnectNotificationPolicy.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.reconnect_notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(
                if (showFullTextOnLockScreen) {
                    NotificationCompat.VISIBILITY_PUBLIC
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                }
            )
            .setPublicVersion(publicNotification)
            .apply {
                addApprovalActions(this, approvalRequest, targetUrl)
                addAction(exitAction)
            }
            .build()
    }

    private fun buildExitAction(): NotificationCompat.Action {
        val exitIntent = Intent(this, HermesReconnectService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            EXIT_ACTION_REQUEST_CODE,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            0,
            getString(R.string.reconnect_notification_exit_action),
            exitPendingIntent
        ).build()
    }

    private fun handleExit(startId: Int) {
        // Persist first. Even if encrypted persistence fails, teardown remains fail-closed.
        runCatching { settingsRepository.latchBackgroundActivityExit() }
        runCatching { cancelSessionStream() }
        runCatching { serviceScope.cancel() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching {
            NotificationManagerCompat.from(this).cancel(RECONNECT_NOTIFICATION_ID)
        }
        runCatching { stopSelfResult(startId) }
        runCatching {
            getSystemService(ActivityManager::class.java)
                ?.appTasks
                ?.forEach { it.finishAndRemoveTask() }
        }
        runCatching { HermesDebugLoggingService.stopForAppExit() }
        runCatching { HermesDebugLoggingService.stop(applicationContext) }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun ensureBackgroundActivityChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ReconnectNotificationPolicy.CHANNEL_ID,
            getString(R.string.background_activity_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.background_activity_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun addApprovalActions(
        builder: NotificationCompat.Builder,
        approvalRequest: NotificationApprovalRequest?,
        targetUrl: String?
    ) {
        val approval = approvalRequest ?: return
        val serverUrl = activeServerUrl ?: return
        val sessionId = activeSessionId ?: return
        val allowChoice = ApprovalActionSupport.preferredAllowChoice(approval.choices)
        val denyChoice = ApprovalActionSupport.denyChoice(approval.choices)

        if (allowChoice != null) {
            builder.addAction(
                0,
                ApprovalActionSupport.labelForChoice(allowChoice),
                buildApprovalActionPendingIntent(
                    serverUrl = serverUrl,
                    sessionId = sessionId,
                    cookieHeader = activeCookieHeader,
                    targetUrl = targetUrl,
                    approvalId = approval.approvalId,
                    choice = allowChoice
                )
            )
        }
        if (denyChoice != null) {
            builder.addAction(
                0,
                getString(R.string.approval_action_deny),
                buildApprovalActionPendingIntent(
                    serverUrl = serverUrl,
                    sessionId = sessionId,
                    cookieHeader = activeCookieHeader,
                    targetUrl = targetUrl,
                    approvalId = approval.approvalId,
                    choice = denyChoice
                )
            )
        }
    }

    private fun buildApprovalActionPendingIntent(
        serverUrl: String,
        sessionId: String,
        cookieHeader: String?,
        targetUrl: String?,
        approvalId: String,
        choice: String
    ): PendingIntent {
        val requestCode = ("$approvalId:$choice").hashCode()
        val intent = Intent(this, HermesReconnectService::class.java).apply {
            action = ACTION_RESPOND_APPROVAL
            putExtra(EXTRA_SERVER_URL, serverUrl)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_SESSION_TARGET_URL, targetUrl)
            putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
            putExtra(EXTRA_POLL_INTERVAL_SECONDS, activePollIntervalSeconds)
            putExtra(EXTRA_IS_RECONNECTING, activeIsReconnecting)
            putExtra(EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN, activeShowFullTextOnLockScreen)
            putExtra(EXTRA_APPROVAL_ID, approvalId)
            putExtra(EXTRA_APPROVAL_CHOICE, choice)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildLaunchIntent(targetUrl: String?): Intent {
        return Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION_URL
            if (!targetUrl.isNullOrBlank()) {
                putExtra(EXTRA_NOTIFICATION_URL, targetUrl)
            }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private fun defaultNotificationBody(
        pollIntervalSeconds: Int,
        isReconnecting: Boolean,
        sseTransportEnabled: Boolean
    ): String {
        if (!isReconnecting) {
            return if (sseTransportEnabled) {
                getString(R.string.reconnect_notification_body_session_stream)
            } else {
                getString(R.string.reconnect_notification_body_activity)
            }
        }
        if (sseTransportEnabled) {
            return getString(R.string.reconnect_notification_body_session_stream_reconnecting)
        }
        val normalizedInterval = pollIntervalSeconds.coerceAtLeast(1)
        return resources.getQuantityString(
            R.plurals.reconnect_notification_body_polling_interval,
            normalizedInterval,
            normalizedInterval
        )
    }

    private fun publicNotificationBody(isReconnecting: Boolean): String {
        return if (isReconnecting) {
            getString(R.string.reconnect_notification_body_public_reconnecting)
        } else {
            getString(R.string.reconnect_notification_body_public_activity)
        }
    }

    private fun handleApprovalAction(intent: Intent) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID)?.trim().orEmpty()
        val requestedChoice = ApprovalActionSupport.normalizeChoice(
            intent.getStringExtra(EXTRA_APPROVAL_CHOICE)
        ).orEmpty()
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
        val cookieHeader = intent.getStringExtra(EXTRA_COOKIE_HEADER)

        if (approvalId.isBlank() || requestedChoice.isBlank() || serverUrl.isBlank() || sessionId.isBlank()) {
            publishServiceState(
                body = getString(R.string.approval_notification_invalid),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
            return
        }

        val currentApprovalId = currentApprovalRequest?.approvalId
        if (currentApprovalId != null && currentApprovalId != approvalId) {
            publishServiceState(
                body = getString(R.string.approval_notification_expired),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
            return
        }

        if (!markApprovalInFlight(approvalId)) {
            publishServiceState(
                body = getString(R.string.approval_notification_already_sent),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
            return
        }

        publishServiceState(
            body = getString(R.string.approval_notification_sending),
            targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
            approvalRequest = null
        )

        serviceScope.launch {
            val pending = ApprovalClient.fetchPendingApproval(serverUrl, sessionId, cookieHeader)
            if (pending == null || pending.approvalId != approvalId) {
                removeApprovalInFlight(approvalId)
                publishServiceState(
                    body = getString(R.string.approval_notification_expired),
                    targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                    approvalRequest = null
                )
                return@launch
            }
            val pendingChoices = pending.choices.mapNotNull(ApprovalActionSupport::normalizeChoice)
            if (requestedChoice !in pendingChoices) {
                removeApprovalInFlight(approvalId)
                publishServiceState(
                    body = getString(R.string.approval_notification_invalid_choice),
                    targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                    approvalRequest = currentApprovalRequest
                )
                return@launch
            }

            val resolvedSessionId = pending.sessionId ?: sessionId
            val success = ApprovalClient.submitApprovalResponse(
                baseUrl = serverUrl,
                sessionId = resolvedSessionId,
                approvalId = approvalId,
                choice = requestedChoice,
                cookieHeader = cookieHeader
            )

            if (!success) {
                removeApprovalInFlight(approvalId)
                publishServiceState(
                    body = getString(R.string.approval_notification_failed),
                    targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                    approvalRequest = currentApprovalRequest
                )
                return@launch
            }

            publishServiceState(
                body = getString(
                    R.string.approval_notification_sent,
                    ApprovalActionSupport.labelForChoice(requestedChoice)
                ),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
        }
    }

    private suspend fun monitorSessionUpdates(
        baseUrl: String?,
        sessionId: String?,
        cookieHeader: String?,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int,
        isReconnecting: Boolean,
        showFullTextOnLockScreen: Boolean
    ) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val refreshedSessionId = sessionId
                ?: ReconnectSessionStreamSupport.sessionIdFromUrl(
                    runCatching { settingsRepository.getLastLoadedUrl() }.getOrNull()
                )
            val currentCookie = baseUrl
                ?.let { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
            val refreshedCookie = ReconnectSessionCookiePolicy.cookieHeader(
                currentCookie = currentCookie,
                initialCookie = cookieHeader
            )
            if (baseUrl.isNullOrBlank() ||
                refreshedSessionId.isNullOrBlank() ||
                refreshedCookie.isNullOrBlank()
            ) {
                delay(ReconnectRetryPolicy.delayMs(failures++))
                continue
            }
            val result = streamSessionUpdatesOnce(
                baseUrl = baseUrl,
                sessionId = refreshedSessionId,
                cookieHeader = refreshedCookie,
                sessionTargetUrl = sessionTargetUrl,
                pollIntervalSeconds = pollIntervalSeconds,
                isReconnecting = isReconnecting,
                showFullTextOnLockScreen = showFullTextOnLockScreen
            )
            failures = if (result == StreamResult.CONNECTED) 0 else failures
            delay(ReconnectRetryPolicy.delayMs(failures))
            if (result == StreamResult.UNAVAILABLE) failures += 1
        }
    }

    private fun streamSessionUpdatesOnce(
        baseUrl: String,
        sessionId: String,
        cookieHeader: String,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int,
        isReconnecting: Boolean,
        showFullTextOnLockScreen: Boolean
    ): StreamResult {
        val encodedSessionId = runCatching {
            URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        }.getOrNull() ?: return StreamResult.UNAVAILABLE
        // This persistent endpoint is also used by Hermes WebUI for durable session-scoped events.
        val url = runCatching {
            URI(baseUrl.trimEnd('/'))
                .resolve("/api/session/stream?session_id=$encodedSessionId")
                .toURL()
        }.getOrNull() ?: return StreamResult.UNAVAILABLE
        val connection = runCatching {
            url.openConnection() as HttpURLConnection
        }.getOrNull() ?: return StreamResult.UNAVAILABLE
        activeStreamConnection = connection
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cookie", cookieHeader)
            }
            val responseCode = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            if (responseCode !in 200..299 || !contentType.contains("text/event-stream", ignoreCase = true)) {
                return StreamResult.UNAVAILABLE
            }
            connection.inputStream.bufferedReader().use { reader ->
                consumeSse(
                    reader = reader,
                    baseUrl = baseUrl,
                    sessionTargetUrl = sessionTargetUrl,
                    pollIntervalSeconds = pollIntervalSeconds,
                    showFullTextOnLockScreen = showFullTextOnLockScreen,
                    isReconnecting = isReconnecting
                )
            }
            StreamResult.CONNECTED
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            StreamResult.UNAVAILABLE
        } finally {
            // Only clear the shared reference if it still points at this connection: a concurrent
            // relaunch may have already installed a newer one that must stay cancelable.
            if (activeStreamConnection === connection) activeStreamConnection = null
            connection.disconnect()
        }
    }

    private fun consumeSse(
        reader: BufferedReader,
        baseUrl: String,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int,
        showFullTextOnLockScreen: Boolean,
        isReconnecting: Boolean
    ) {
        var eventName: String? = null
        val dataLines = mutableListOf<String>()

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith(":") -> continue
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
                line.isBlank() -> {
                    val payload = dataLines.joinToString("\n")
                    if (payload.isNotBlank()) {
                        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
                            baseUrl = baseUrl,
                            fallbackTargetUrl = sessionTargetUrl,
                            eventName = eventName,
                            rawData = payload
                        )
                        if (update != null) {
                            publishNotificationUpdate(
                                update = update,
                                pollIntervalSeconds = pollIntervalSeconds,
                                showFullTextOnLockScreen = showFullTextOnLockScreen,
                                isReconnecting = isReconnecting
                            )
                            if (update.isTerminal) {
                                return
                            }
                        }
                    }
                    eventName = null
                    dataLines.clear()
                }
            }
        }
    }

    private fun recoverAfterProcessRestart(): StartConfiguration? {
        val repository = runCatching { settingsRepository }.getOrNull()
            ?: return null
        val settings = repository.getSettings(
            getString(R.string.default_server_url),
            getString(R.string.default_dashboard_url)
        )
        val policy = UrlPolicy(settings.allowedHosts)
        val recovered = BackgroundMonitorRestartPolicy.recover(
            enabled = repository.isBackgroundReconnectEnabled(),
            exitedUntilExplicitLaunch = repository.isBackgroundActivityExitedUntilExplicitLaunch(),
            configuredServerUrl = settings.serverUrl,
            lastLoadedUrl = repository.getLastLoadedUrl(),
            isTrustedUrl = policy::isAllowed
        ) ?: return null
        return StartConfiguration(
            pollIntervalSeconds = repository.getReconnectPollIntervalSeconds(),
            serverUrl = recovered.serverUrl,
            sessionId = recovered.sessionId,
            sessionTargetUrl = recovered.currentUrl,
            cookieHeader = runCatching {
                CookieManager.getInstance().getCookie(recovered.serverUrl)
            }.getOrNull(),
            isReconnecting = false,
            showFullTextOnLockScreen = repository.isBackgroundActivityFullTextEnabled()
        )
    }

    @SuppressLint("MissingPermission")
    private fun publishNotificationUpdate(
        update: ReconnectNotificationUpdate,
        pollIntervalSeconds: Int,
        showFullTextOnLockScreen: Boolean,
        isReconnecting: Boolean
    ) {
        if (update.approvalRequest?.approvalId != currentApprovalRequest?.approvalId) {
            clearRespondedApprovals()
        }
        currentApprovalRequest = update.approvalRequest
        currentNotificationBody = update.body
        currentNotificationTargetUrl = update.targetUrl
        val notification = buildNotification(
            pollIntervalSeconds = pollIntervalSeconds,
            contentText = update.body,
            targetUrl = update.targetUrl,
            showFullTextOnLockScreen = showFullTextOnLockScreen,
            isReconnecting = isReconnecting,
            approvalRequest = update.approvalRequest
        )
        NotificationManagerCompat.from(this).notify(RECONNECT_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun publishServiceState(
        body: String,
        targetUrl: String?,
        approvalRequest: NotificationApprovalRequest?
    ) {
        currentNotificationBody = body
        currentNotificationTargetUrl = targetUrl
        currentApprovalRequest = approvalRequest
        val notification = buildNotification(
            pollIntervalSeconds = activePollIntervalSeconds,
            contentText = body,
            targetUrl = targetUrl,
            showFullTextOnLockScreen = activeShowFullTextOnLockScreen,
            isReconnecting = activeIsReconnecting,
            approvalRequest = approvalRequest
        )
        NotificationManagerCompat.from(this).notify(RECONNECT_NOTIFICATION_ID, notification)
    }

    private fun markApprovalInFlight(approvalId: String): Boolean {
        synchronized(approvalStateLock) {
            if (approvalId in respondedApprovalIds) {
                return false
            }
            respondedApprovalIds += approvalId
            if (respondedApprovalIds.size > MAX_RESPONDED_APPROVAL_HISTORY) {
                respondedApprovalIds.firstOrNull()?.let { respondedApprovalIds.remove(it) }
            }
            return true
        }
    }

    private fun removeApprovalInFlight(approvalId: String) {
        synchronized(approvalStateLock) {
            respondedApprovalIds.remove(approvalId)
        }
    }

    private fun clearRespondedApprovals() {
        synchronized(approvalStateLock) {
            respondedApprovalIds.clear()
        }
    }

    companion object {
        private const val ACTION_EXIT = "com.hermeswebui.android.action.EXIT"
        private const val ACTION_RESPOND_APPROVAL = "com.hermeswebui.android.action.RESPOND_APPROVAL"
        private const val EXTRA_POLL_INTERVAL_SECONDS = "extra.POLL_INTERVAL_SECONDS"
        private const val EXTRA_SERVER_URL = "extra.SERVER_URL"
        private const val EXTRA_SESSION_ID = "extra.SESSION_ID"
        private const val EXTRA_SESSION_TARGET_URL = "extra.SESSION_TARGET_URL"
        private const val EXTRA_COOKIE_HEADER = "extra.COOKIE_HEADER"
        private const val EXTRA_IS_RECONNECTING = "extra.IS_RECONNECTING"
        private const val EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN = "extra.SHOW_FULL_TEXT_ON_LOCK_SCREEN"
        private const val EXTRA_APPROVAL_ID = "extra.APPROVAL_ID"
        private const val EXTRA_APPROVAL_CHOICE = "extra.APPROVAL_CHOICE"
        private const val RECONNECT_NOTIFICATION_ID = 20_001
        private const val EXIT_ACTION_REQUEST_CODE = 20_002
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 1
        private const val MAX_RESPONDED_APPROVAL_HISTORY = 64
        private const val ACTION_OPEN_NOTIFICATION_URL = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
        private const val EXTRA_NOTIFICATION_URL = "com.hermeswebui.android.extra.NOTIFICATION_URL"
        private const val TAG = "HermesReconnectService"

        fun start(
            context: Context,
            pollIntervalSeconds: Int,
            serverUrl: String,
            sessionId: String?,
            sessionTargetUrl: String?,
            cookieHeader: String?,
            isReconnecting: Boolean,
            showFullTextOnLockScreen: Boolean
        ) {
            val intent = Intent(context, HermesReconnectService::class.java).apply {
                putExtra(EXTRA_POLL_INTERVAL_SECONDS, pollIntervalSeconds.coerceAtLeast(1))
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_TARGET_URL, sessionTargetUrl)
                putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
                putExtra(EXTRA_IS_RECONNECTING, isReconnecting)
                putExtra(EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN, showFullTextOnLockScreen)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesReconnectService::class.java))
        }
    }

    private enum class StreamResult {
        CONNECTED,
        UNAVAILABLE
    }

    private data class StartConfiguration(
        val pollIntervalSeconds: Int,
        val serverUrl: String,
        val sessionId: String?,
        val sessionTargetUrl: String?,
        val cookieHeader: String?,
        val isReconnecting: Boolean,
        val showFullTextOnLockScreen: Boolean
    )
}
