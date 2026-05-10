package dev.typester.evencompanion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import dev.typester.evencompanion.core.EvenCore
import dev.typester.evencompanion.core.uniffi.CoreException
import dev.typester.evencompanion.location.FusedLocationStreamer
import dev.typester.evencompanion.location.PollingLocationProvider
import dev.typester.evencompanion.llm.GemmaLlmEngine
import dev.typester.evencompanion.stt.SherpaOnnxSttStreamer
import dev.typester.evencompanion.stt.VoskSttStreamer

class CoreService : Service() {

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var pollingProvider: PollingLocationProvider? = null
    private var voskStreamer: VoskSttStreamer? = null
    private var sherpaStreamer: SherpaOnnxSttStreamer? = null
    private var llmEngine: GemmaLlmEngine? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )

        try {
            EvenCore.instance.startServer(EvenCore.DEFAULT_PORT)
        } catch (_: CoreException) {}

        pollingProvider = PollingLocationProvider(fusedClient)
        EvenCore.instance.setLocationProvider(pollingProvider!!)
        EvenCore.instance.setLocationStreamer(
            FusedLocationStreamer(fusedClient) { loc -> EvenCore.instance.broadcastLocation(loc) }
        )

        val vosk = VoskSttStreamer(applicationContext)
        val sherpa = SherpaOnnxSttStreamer(applicationContext)
        voskStreamer = vosk
        sherpaStreamer = sherpa
        EvenCore.instance.registerSttStreamer("vosk", vosk)
        EvenCore.instance.registerSttStreamer("sherpa", sherpa)
        Thread { vosk.preload(); sherpa.preload() }.start()

        val gemma = GemmaLlmEngine(applicationContext)
        llmEngine = gemma
        EvenCore.instance.registerLlmEngine(gemma)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingProvider?.cleanup()
        voskStreamer?.cleanup()
        sherpaStreamer?.cleanup()
        llmEngine?.cleanup()
        try { EvenCore.instance.stopServer() } catch (_: CoreException) {}
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Even Companion")
            .setContentText("GPS server running on port ${EvenCore.DEFAULT_PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPS Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "core-service"
        const val NOTIFICATION_ID = 1

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, CoreService::class.java))

        fun stop(ctx: Context) =
            ctx.stopService(Intent(ctx, CoreService::class.java))
    }
}
