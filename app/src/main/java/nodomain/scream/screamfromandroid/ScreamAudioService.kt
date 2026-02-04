package nodomain.scream.screamfromandroid
// HEADER_SIZE = 5
// PACKET_SIZE = 1152 + HEADER_SIZE

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Service that captures device audio and streams it to a Scream receiver
 * Scream protocol: https://github.com/duncanthrax/scream
 */
class ScreamAudioService : Service() {

    companion object {
        private const val TAG = "ScreamAudioService"
        private const val CHANNEL_ID = "ScreamAudioChannel"
        private const val NOTIFICATION_ID = 1

        // Scream protocol defaults
        private const val SAMPLE_RATE = 44100
        private const val MULTIPLIER = 1
        private const val BITS_PER_SAMPLE = 16
        const val SCREAM_IP = "239.255.77.77" // Default Scream IP broadcast
        const val SCREAM_PORT = 4010 // Default Scream UDP port

        private const val PACKET_SIZE = 1152  // should be 1152 but smaller packets seem to cause less stutter, excludes 5 byte header
    }

    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var screamServerAddress: InetAddress? = null
    private var screamServerPort = SCREAM_PORT

    @Volatile
    private var isCapturing = false
    private var captureThread: Thread? = null

    private var mediaProjection: MediaProjection? = null
    private var resultCode: Int = -1
    private var resultData: Intent? = null

    private var audioManager: AudioManager? = null

    private lateinit var mediaSession: MediaSessionCompat
    private var originalVolume: Int? = null
    private var screamVolume: Float = 1.0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground immediately to avoid timeout
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        intent?.let {
            when (it.action) {
                "START_CAPTURE" -> {
                    val serverIp = it.getStringExtra("SERVER_IP") ?: SCREAM_IP
                    screamServerPort = it.getIntExtra("SERVER_PORT", SCREAM_PORT)
                    resultCode = it.getIntExtra("RESULT_CODE", 0)
                    resultData = it.getParcelableExtra("RESULT_DATA")

                    Log.d(TAG, "onStartCommand - serverIp: $serverIp, port: $screamServerPort, resultCode: $resultCode, hasData: ${resultData != null}")

                    if (resultCode == Activity.RESULT_OK && resultData != null) {
                        if (ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startCapture(serverIp)
                        } else {
                            Toast.makeText(this, "Grant capture permission so audio can be captured", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "Missing required parameters - IP: $serverIp, code: $resultCode, data: $resultData")
                        stopSelf()
                    }
                }
                "STOP_CAPTURE" -> stopCapture()
            }
        }

        return START_STICKY
    }

    private fun setupVolumeMixer(prefs: SharedPreferences) {
        // make extra volume bar for streaming
        mediaSession = MediaSessionCompat(this, "ScreamService")
        mediaSession.isActive = true
        screamVolume = prefs.getFloat("SCREAM_VOLUME", screamVolume)
        mediaSession.setPlaybackToRemote(object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE, 10, (screamVolume * 10).toInt()
        ) {
            override fun onSetVolumeTo(volume: Int) {
                screamVolume = (volume / 10f).coerceIn(0f, 1f)
                Log.d(TAG, "SetVolumeTo volume=$volume, screamVolume=$screamVolume")
                setCurrentVolume((screamVolume * 10).roundToInt())  // weird work around because it otherwise jumps back to 100%
                prefs.edit().putFloat("SCREAM_VOLUME",  screamVolume).apply()
            }

            override fun onAdjustVolume(direction: Int) {
                Log.d(TAG, "onAdjustVolume volume=$direction, screamVolume=$screamVolume")
                this.onSetVolumeTo ( ((screamVolume * 10).roundToInt() + direction).coerceIn(0, 10) )
                prefs.edit().putFloat("SCREAM_VOLUME",  screamVolume).apply()
                super.onAdjustVolume(direction)
            }
        })

        // PlaybackState puts the stream volume mixer as active
        val playbackState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(serverIp: String) {
        val prefs = this.applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
        val channels = prefs.getInt("SCREAM_CHANNELS", 2)
        setupVolumeMixer(prefs)

        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return
        }

        try {
            screamServerAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket()
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)

            var bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                when (channels) {
                    1 -> AudioFormat.CHANNEL_IN_MONO
                    else ->AudioFormat.CHANNEL_IN_STEREO
                },
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2 // Double buffer for safety
            bufferSize = max(bufferSize, PACKET_SIZE * 2)

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(when (channels) {
                    1 -> AudioFormat.CHANNEL_IN_MONO
                    else ->AudioFormat.CHANNEL_IN_STEREO
                })
                .build()

            // For Android 10+, use PLAYBACK capture to get internal audio
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Create AudioPlaybackCaptureConfiguration
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                // Fallback for older versions (microphone)
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    when (channels) {
                        1 -> AudioFormat.CHANNEL_IN_MONO
                        else ->AudioFormat.CHANNEL_IN_STEREO
                    },
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            isCapturing = true
            captureThread = Thread { captureAndStream(channels) }.apply { start() }

            audioRecord?.startRecording()

            val state = audioRecord?.state
            val recordingState = audioRecord?.recordingState

            Log.i(TAG, "AudioRecord initialized - State: $state, RecordingState: $recordingState")
            Log.i(TAG, "Audio capture started, streaming to $serverIp:$screamServerPort")

            val mute = prefs.getBoolean("SCREAM_MUTE", true)
            if (mute) {
                audioManager = getSystemService(AUDIO_SERVICE) as AudioManager  // to get current device volume
                originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            cleanup()
            stopSelf()
        }
    }

    private fun changeVolume(buffer: ByteArray, volume: Float) {
        if (volume<1) {
            for (i in buffer.indices) {
                buffer[i] = (buffer[i] * volume).toInt()
                    .coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
            }
        }
    }

    private fun captureAndStream(channels: Number) {
        val buffer = ByteArray(PACKET_SIZE)
        val screamHeader = createScreamHeader(channels)
        val packetBuffer = ByteBuffer.allocate(screamHeader.size + PACKET_SIZE)

        var packetCount = 0

        while (isCapturing) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, PACKET_SIZE) ?: 0
                changeVolume(buffer, screamVolume)
                if (bytesRead > 0 && !isSilent(buffer, bytesRead)) {
                    sendScreamPacket(buffer, bytesRead, screamHeader, packetBuffer)
                    packetCount++
                }
            } catch (e: IOException) {
                if (isCapturing) Log.e(TAG, "Error streaming audio", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in capture loop", e)
                break
            }
        }

        Log.i(TAG, "Capture thread ended")
    }

    private fun isSilent(buffer: ByteArray, bytesRead: Int, silenceThreshold: Int = 200): Boolean {
        var maxAmplitude = 0
        for (i in 0 until bytesRead step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF))
            maxAmplitude = maxOf(maxAmplitude, kotlin.math.abs(sample))
        }
        return maxAmplitude <= silenceThreshold
    }

    private fun sendScreamPacket(
        buffer: ByteArray,
        bytesRead: Int,
        screamHeader: ByteArray,
        packetBuffer: ByteBuffer
    ) {
        packetBuffer.clear()
        packetBuffer.put(screamHeader)
        packetBuffer.put(buffer, 0, bytesRead)

        val packet = ByteArray(packetBuffer.position())
        packetBuffer.flip()
        packetBuffer.get(packet)

        val udpPacket = DatagramPacket(packet, packet.size, screamServerAddress, screamServerPort)
        socket?.send(udpPacket)
    }

    /**
     * Creates Scream protocol header
     * Format (5 bytes):
     * - Byte 0: Sample rate (0=44100, 1=48000, 2=96000, 3=192000)
     * - Byte 1: Bits per sample (16 or 24)
     * - Byte 2: Number of channels (1-8)
     * - Bytes 3-4: Channel map (little endian)
     */
    private fun createScreamHeader(channels: Number): ByteArray {
        return byteArrayOf(
            (0x80 or (MULTIPLIER and 0x7F)).toByte(),  // Sample rate bit 7: 1 = 44100 Hz, and 0 = 48000 Hz
            BITS_PER_SAMPLE.toByte(), // Bits per sample
            channels.toByte(),      // Number of channels
            when (channels) {   // Channel map (stereo = 0x01 || 0x02 = 0x03)  // https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ksmedia/ns-ksmedia-waveformatextensible
                1 -> 0x01.toByte()
                else -> 0x03.toByte()
            },
            0x00                    // Little endian
        )
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping audio capture")
        isCapturing = false

        captureThread?.let {
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (audioManager!=null) {
            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (originalVolume != null && currentVolume == 0) audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume!!,0)
        }
        cleanup()
        stopForeground(true)
        stopSelf()
    }

    private fun cleanup() {
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audio record", e)
            }
        }
        audioRecord = null

        socket?.let {
            if (!it.isClosed) {
                it.close()
            }
        }
        socket = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scream Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streaming device audio to Scream receiver"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val deleteIntent = Intent(this, ScreamAudioService::class.java).apply {
            action = "STOP_CAPTURE"
        }

        val deletePendingIntent = PendingIntent.getService(
            this,
            0,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentText("Streaming audio to Scream receiver...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .setSound(null)

        val notification = builder.build()
        Log.d(TAG, "Notification created")
        return notification
    }

    override fun onDestroy() {
        stopCapture()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}