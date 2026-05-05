package com.example.eidatacollector.presentation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioDataCollector {

    companion object {
        const val SAMPLE_RATE = 16_000
    }

    val samples = mutableListOf<Short>()

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun start() {
        samples.clear()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2)  // at least 1 s of buffer
        )
        isRecording = true
        audioRecord?.startRecording()
        recordingThread = Thread {
            val chunk = ShortArray(1024)
            while (isRecording) {
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: 0
                if (read > 0) samples.addAll(chunk.take(read))
            }
        }.also { it.start() }
    }

    fun stop() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
