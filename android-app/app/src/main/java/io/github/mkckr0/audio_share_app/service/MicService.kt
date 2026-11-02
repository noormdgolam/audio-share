/*
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Mic mode: streams the phone microphone to the PC as raw PCM over TCP
 *    (48 kHz, mono, signed 16-bit little-endian). The PC side listens on
 *    the audio port + 1 and plays it into a virtual cable that apps use as a mic.
 */

package io.github.mkckr0.audio_share_app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.mkckr0.audio_share_app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class MicService : Service() {

    companion object {
        private const val TAG = "MicService"
        private const val CHANNEL_ID = "mic"
        private const val NOTIFICATION_ID = 2
        const val SAMPLE_RATE = 48000
        const val ACTION_START = "mic.start"
        const val ACTION_STOP = "mic.stop"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _status = MutableStateFlow("")
        val status: StateFlow<String> = _status.asStateFlow()

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, MicService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (port !in 1..65535) return START_NOT_STICKY

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(host, port), type)

        if (worker == null) {
            _running.value = true
            worker = Thread({ streamLoop(host, port) }, "mic-stream").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun stopStreaming() {
        _running.value = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        worker?.interrupt()
        worker = null
        _status.value = "Mic stopped"
    }

    @SuppressLint("MissingPermission")
    private fun streamLoop(host: String, port: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _status.value = "Microphone permission is missing"
            _running.value = false
            stopSelf()
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 10 ms chunks keep the delay low; the record buffer is larger to survive hiccups
        val chunk = SAMPLE_RATE / 100 * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunk) * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            _status.value = "Could not open the microphone"
            record.release()
            _running.value = false
            stopSelf()
            return
        }
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
        noise?.enabled = true

        val buffer = ByteArray(chunk)
        record.startRecording()
        try {
            while (_running.value) {
                try {
                    _status.value = "Connecting to $host:$port ..."
                    Socket().use { s ->
                        socket = s
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(host, port), 3000)
                        _status.value = "Mic is live -> $host:$port"
                        val out = BufferedOutputStream(s.getOutputStream(), chunk)
                        while (_running.value) {
                            val n = record.read(buffer, 0, buffer.size)
                            if (n > 0) {
                                out.write(buffer, 0, n)
                                out.flush()
                            } else if (n < 0) {
                                throw IllegalStateException("AudioRecord.read error $n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!_running.value) break
                    Log.w(TAG, "mic stream error", e)
                    _status.value = "PC not reachable on $host:$port, retrying ..."
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            socket = null
            record.stop()
            record.release()
            noise?.release()
        }
    }

    private fun buildNotification(host: String, port: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mic", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Mic -> PC")
            .setContentText("$host:$port")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
