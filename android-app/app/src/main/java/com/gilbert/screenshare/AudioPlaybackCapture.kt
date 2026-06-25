package com.gilbert.screenshare

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import kotlin.concurrent.thread
import kotlin.math.max

class AudioPlaybackCapture(
    private val mediaProjection: MediaProjection,
    private val onPcmData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onStatus("系统声音采集需要 Android 10+")
            return
        }
        if (running) return

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer * 2, PCM_CHUNK_BYTES * 4)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        running = true
        worker = thread(name = "PlaybackCapture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(PCM_CHUNK_BYTES)
            try {
                audioRecord?.startRecording()
                onStatus("系统声音采集中")
                while (running) {
                    val read = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        onPcmData(buffer.copyOf(read))
                    }
                }
            } catch (error: Throwable) {
                if (running) {
                    onStatus("系统声音采集失败：${error.message}")
                }
            } finally {
                runCatching { audioRecord?.stop() }
                audioRecord?.release()
                audioRecord = null
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2
        const val PCM_CHUNK_BYTES = SAMPLE_RATE / 50 * CHANNELS * BYTES_PER_SAMPLE
    }
}
