package com.lenscode.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live screen recording through MediaProjection. The projection captures what the system
 * composites for the display, so it is the only recording path that is not limited to the app's
 * own Compose layers (which is all the Snip tool can see). Whether the headset's camera
 * passthrough of the room is part of that composite is up to the XR system, not this app.
 *
 * Android 14+ requires the projection to be held by a foreground service of type
 * mediaProjection, hence a service rather than a plain helper. Output goes to
 * Movies/LensCode through MediaStore.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: ParcelFileDescriptor? = null
    private var uri: android.net.Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || recorder != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            startRecording(resultCode, data)
            recording.value = true
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            toast("Could not start recording.")
            cleanup(discard = true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(2) and 1.inv()
        val height = metrics.heightPixels.coerceAtLeast(2) and 1.inv()

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "lenscode-rec-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LensCode")
        }
        val target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        uri = target
        val fd = contentResolver.openFileDescriptor(target, "w") ?: error("cannot open $target")
        output = fd

        val rec = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(12_000_000)
            setOutputFile(fd.fileDescriptor)
            prepare()
        }
        recorder = rec

        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(resultCode, data) ?: error("no projection")
        projection = proj
        // Android 14+ requires a callback to be registered before createVirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }, null)
        display = proj.createVirtualDisplay(
            "lenscode-record", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null,
        )
        rec.start()
    }

    private fun stopRecording() {
        val saved = recorder != null
        cleanup(discard = false)
        if (saved) toast("Recording saved — Movies/LensCode.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup(discard: Boolean) {
        var ok = !discard
        runCatching { recorder?.stop() }.onFailure { ok = false } // throws if nothing was recorded
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { output?.close() }
        output = null
        if (!ok) uri?.let { runCatching { contentResolver.delete(it, null, null) } }
        uri = null
        recording.value = false
    }

    override fun onDestroy() {
        cleanup(discard = false)
        super.onDestroy()
    }

    private fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LensCode is recording the screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LensCodeRecord"
        private const val CHANNEL_ID = "screen-record"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.lenscode.app.STOP_RECORD"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        private val recording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = recording

        fun captureIntent(context: Context): Intent =
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenRecordService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
