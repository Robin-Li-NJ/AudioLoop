package com.example.recoder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecord.READ_NON_BLOCKING
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.Nullable
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.log10

class ForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val input = intent.getStringExtra("inputExtra")
        createNotificationChannel()
        val notificationIntent = Intent(
            this,
            MainActivity::class.java
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foreground Service")
            .setContentText(input)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // do heavy work on a background thread
        StartRecorder()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        StopRecorder()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Foreground Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val manager = getSystemService(
            NotificationManager::class.java
        )
        manager.createNotificationChannel(serviceChannel)
    }


    // the audio recorder
    private var recorder: AudioRecord? = null
    private var tracker: AudioTrack? = null

    // are we currently sending audio data
    private var currentlySendingAudio = false

    fun StartRecorder() {
        Log.i(TAG, "Starting the audio stream")

        synchronized(this) {
            if (currentlySendingAudio) {
                Log.i(TAG, "Already sending audio")
                return
            }
            currentlySendingAudio = true
        }
        startStreaming()
    }
    fun StopRecorder() {
        Log.i(TAG, "Stopping the audio stream")
        synchronized(this) {
            if (!currentlySendingAudio) {
                Log.i(TAG, "Not sending audio")
                return
            }
            currentlySendingAudio = false
        }
        recorder!!.release()
        tracker?.release()
    }

    private fun startStreaming() {
        Log.i(
            TAG,
            "Starting the background thread (in this foreground service) to read the audio data"
        )

        val streamThread = Thread {
            try {
                val rate =
                    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM)
                val bufferSize = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                Log.d(
                    TAG,
                    "Creating the buffer of size $bufferSize"
                )
                val buffer = ShortArray(bufferSize)

                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                Log.d(TAG, "Creating the AudioRecord")
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@Thread
                }
                tracker = AudioTrack.Builder()
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    ).build()

                tracker?.play()

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                Log.d(TAG, "AudioRecord recording...")
                recorder!!.startRecording()

                while (currentlySendingAudio) {
                    // read the data into the buffer
                    val readSize = recorder!!.read(buffer, 0, buffer.size)
                    if (readSize > 0) {
                        val writtenSize = tracker?.write(buffer, 0, readSize)
                    }
                    // Log.i(TAG, "Read $readSize bytes, buffer size is ${buffer.size}, written $writtenSize")
                }

                Log.d(
                    TAG,
                    "AudioRecord finished recording"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception: $e")
            }
        }

        // start the thread
        streamThread.start()
    }

    companion object {
        const val CHANNEL_ID: String = "ForegroundServiceChannel"

        private const val TAG = "ForegroundService"
    }
}