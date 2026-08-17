package com.hermeswebui.android.background

import android.app.Service
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.hermeswebui.android.data.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean

/** Performs the process-wide shutdown requested specifically by removal from Recents. */
internal object HermesTaskRemovalShutdown {
    private val shutdownStarted = AtomicBoolean(false)

    fun run(service: Service) {
        if (!shutdownStarted.compareAndSet(false, true)) return

        val context = service.applicationContext

        // Persist first so the sticky reconnect service cannot recover during teardown.
        runCatching { SettingsRepository(context).latchBackgroundActivityExit() }
        runCatching { HermesReconnectService.stopForAppExit() }
        runCatching { HermesDebugLoggingService.stopForAppExit() }
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        runCatching {
            context.stopService(Intent(context, HermesReconnectService::class.java))
        }
        runCatching {
            context.stopService(Intent(context, HermesDebugLoggingService::class.java))
        }

        Process.killProcess(Process.myPid())
    }
}
