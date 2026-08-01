package com.hermeswebui.android.webview

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.content.ContextCompat
import com.hermeswebui.android.R
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.webui.HermesWebUiScripts
import java.io.OutputStream
import java.util.Base64

class HermesBlobDownloadCoordinator(
    private val context: Context,
    private val requestLegacyStoragePermission: () -> Unit,
    private val enqueueHttpDownload: (url: String, filename: String) -> Boolean
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var configuredServerUrl: String = ""
    private var scriptHandler: ScriptHandler? = null
    private var activeTransfer: Transfer? = null
    private val timeoutRunnable = Runnable { cancelActiveTransfer(showFailure = true) }

    @SuppressLint("RequiresFeature")
    fun install(view: WebView, serverUrl: String) {
        uninstallBridge()
        configuredServerUrl = serverUrl
        val origin = UrlOrigins.documentStartOriginRule(serverUrl) ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        var listenerInstalled = false
        try {
            WebViewCompat.addWebMessageListener(
                view,
                BlobDownloadProtocol.BRIDGE_NAME,
                setOf(origin)
            ) { _, message, sourceOrigin, isMainFrame, reply ->
                if (!isMainFrame ||
                    !UrlOrigins.hasSameOrigin(sourceOrigin.toString(), configuredServerUrl)
                ) {
                    return@addWebMessageListener
                }
                handleMessage(BlobDownloadProtocol.parse(message.data), reply)
            }
            listenerInstalled = true
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                view,
                HermesWebUiScripts.blobDownloadScript,
                setOf(origin)
            )
            webView = view
        } catch (_: RuntimeException) {
            if (listenerInstalled) {
                runCatching {
                    WebViewCompat.removeWebMessageListener(
                        view,
                        BlobDownloadProtocol.BRIDGE_NAME
                    )
                }
            }
            scriptHandler = null
            webView = null
        }
    }

    fun cancelForNavigation() {
        cancelActiveTransfer(showFailure = false)
    }

    fun close() {
        cancelActiveTransfer(showFailure = false)
        uninstallBridge()
    }

    private fun uninstallBridge() {
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        webView?.let { view ->
            runCatching {
                WebViewCompat.removeWebMessageListener(view, BlobDownloadProtocol.BRIDGE_NAME)
            }
        }
        webView = null
    }

    private fun handleMessage(
        message: BlobDownloadProtocol.Message?,
        reply: JavaScriptReplyProxy
    ) {
        if (message == null) return
        when (message) {
            is BlobDownloadProtocol.Message.Start -> start(message, reply)
            is BlobDownloadProtocol.Message.Chunk -> append(message, reply)
            is BlobDownloadProtocol.Message.Finish -> finish(message.id, reply)
            is BlobDownloadProtocol.Message.Abort -> {
                if (activeTransfer?.id == message.id) cancelActiveTransfer(showFailure = false)
                sendStatus(reply, message.id, "aborted")
            }
            is BlobDownloadProtocol.Message.Download -> download(message, reply)
        }
    }

    private fun download(
        message: BlobDownloadProtocol.Message.Download,
        reply: JavaScriptReplyProxy
    ) {
        if (!UrlOrigins.hasSameOrigin(message.url, configuredServerUrl)) {
            sendStatus(reply, message.id, "error")
            return
        }
        val accepted = runCatching {
            enqueueHttpDownload(message.url, message.filename)
        }.getOrDefault(false)
        sendStatus(reply, message.id, if (accepted) "success" else "error")
    }

    private fun start(message: BlobDownloadProtocol.Message.Start, reply: JavaScriptReplyProxy) {
        if (activeTransfer != null) {
            sendStatus(reply, message.id, "error")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLegacyStoragePermission()
            sendStatus(reply, message.id, "error")
            Toast.makeText(
                context,
                R.string.blob_download_storage_permission_required,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val filename = DownloadFilenameResolver.resolve(
            requestedFilename = message.filename,
            mimeType = message.mime,
            fallbackBaseName = context.getString(R.string.blob_download_fallback_name)
        )
        val target = createTarget(filename, message.mime)
        var uri: Uri? = null
        try {
            uri = context.contentResolver.insert(
                target.collection,
                target.values
            ) ?: error("MediaStore insert failed")
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: error("MediaStore stream failed")
            activeTransfer = Transfer(
                id = message.id,
                filename = filename,
                mime = message.mime,
                uri = uri,
                stream = stream,
                declaredBytes = message.size,
                legacyFile = target.legacyFile
            )
            armTimeout()
            Toast.makeText(context, R.string.blob_download_started, Toast.LENGTH_SHORT).show()
            sendStatus(reply, message.id, "started")
        } catch (_: Exception) {
            cleanupTarget(uri, target.legacyFile)
            sendStatus(reply, message.id, "error")
            Toast.makeText(context, R.string.blob_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun append(message: BlobDownloadProtocol.Message.Chunk, reply: JavaScriptReplyProxy) {
        val transfer = activeTransfer
        if (transfer == null || transfer.id != message.id) {
            sendStatus(reply, message.id, "error")
            return
        }
        if (message.sequence != transfer.nextSequence) {
            fail(message.id, reply)
            return
        }
        val decoded = try {
            Base64.getDecoder().decode(message.data)
        } catch (_: IllegalArgumentException) {
            fail(message.id, reply)
            return
        }
        val remaining = transfer.declaredBytes - transfer.writtenBytes
        if (decoded.isEmpty() ||
            decoded.size > BlobDownloadProtocol.CHUNK_BYTES ||
            decoded.size > remaining
        ) {
            fail(message.id, reply)
            return
        }
        try {
            transfer.stream.write(decoded)
            transfer.writtenBytes += decoded.size
            transfer.nextSequence += 1
            armTimeout()
            sendStatus(reply, message.id, "ack", sequence = message.sequence)
        } catch (_: Exception) {
            fail(message.id, reply)
        }
    }

    private fun finish(id: String, reply: JavaScriptReplyProxy) {
        val transfer = activeTransfer
        if (transfer == null || transfer.id != id) {
            sendStatus(reply, id, "error")
            return
        }
        if (transfer.writtenBytes != transfer.declaredBytes) {
            fail(id, reply)
            return
        }
        disarmTimeout()
        try {
            transfer.stream.flush()
            transfer.stream.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    put(MediaStore.Downloads.SIZE, transfer.writtenBytes)
                }
                check(context.contentResolver.update(transfer.uri, values, null, null) == 1)
            }
            activeTransfer = null
            sendStatus(reply, id, "success", filename = transfer.filename)
            Toast.makeText(context, R.string.blob_download_complete, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            activeTransfer = null
            cleanupTarget(transfer.uri, transfer.legacyFile)
            sendStatus(reply, id, "error")
            Toast.makeText(context, R.string.blob_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fail(id: String, reply: JavaScriptReplyProxy) {
        cancelActiveTransfer(showFailure = true)
        sendStatus(reply, id, "error")
    }

    private fun cancelActiveTransfer(showFailure: Boolean) {
        val transfer = activeTransfer ?: return
        activeTransfer = null
        disarmTimeout()
        runCatching { transfer.stream.close() }
        cleanupTarget(transfer.uri, transfer.legacyFile)
        if (showFailure) {
            Toast.makeText(context, R.string.blob_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupTarget(uri: Uri?, legacyFile: java.io.File?) {
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        legacyFile?.let { runCatching { it.delete() } }
    }

    private fun armTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, BlobDownloadProtocol.TRANSFER_TIMEOUT_MS)
    }

    private fun disarmTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    private fun sendStatus(
        reply: JavaScriptReplyProxy,
        id: String,
        status: String,
        sequence: Int? = null,
        filename: String? = null
    ) {
        val response = org.json.JSONObject()
            .put("type", "status")
            .put("id", id)
            .put("status", status)
        sequence?.let { response.put("sequence", it) }
        filename?.let { response.put("filename", it) }
        if (status == "error") {
            response.put("message", context.getString(R.string.blob_download_failed))
        }
        try {
            reply.postMessage(response.toString())
        } catch (_: RuntimeException) {
            if (activeTransfer?.id == id) cancelActiveTransfer(showFailure = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun createTarget(filename: String, mime: String): MediaStoreTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            return MediaStoreTarget(
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values = values,
                legacyFile = null
            )
        }
        val directory = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .apply { mkdirs() }
        val legacyFile = uniqueLegacyFile(directory, filename)
        values.put(MediaStore.MediaColumns.DATA, legacyFile.absolutePath)
        return MediaStoreTarget(
            collection = MediaStore.Files.getContentUri("external"),
            values = values,
            legacyFile = legacyFile
        )
    }

    private fun uniqueLegacyFile(directory: java.io.File, filename: String): java.io.File {
        val initial = java.io.File(directory, filename)
        if (!initial.exists()) return initial
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val suffix = if (dot > 0) filename.substring(dot) else ""
        for (index in 1..999) {
            val candidate = java.io.File(directory, "$stem ($index)$suffix")
            if (!candidate.exists()) return candidate
        }
        return java.io.File(directory, "${System.currentTimeMillis()}-$filename")
    }

    private data class Transfer(
        val id: String,
        val filename: String,
        val mime: String,
        val uri: Uri,
        val stream: OutputStream,
        val declaredBytes: Long,
        val legacyFile: java.io.File?,
        var writtenBytes: Long = 0,
        var nextSequence: Int = 0
    )

    private data class MediaStoreTarget(
        val collection: Uri,
        val values: ContentValues,
        val legacyFile: java.io.File?
    )
}
