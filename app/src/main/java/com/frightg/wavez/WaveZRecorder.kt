package com.frightg.wavez

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

class WaveZRecorder {
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private val chunks = ArrayList<ShortArray>()
    private var totalShorts = 0

    @Volatile
    var isRecording: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        sampleRate: Int = 44100,
        onMeterDb: (Float) -> Unit
    ): Boolean {
        if (isRecording) return false
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }

        synchronized(chunks) {
            chunks.clear()
            totalShorts = 0
        }
        recorder = ar
        isRecording = true
        ar.startRecording()

        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(minBuf / 2)
            while (isActive && isRecording) {
                val read = ar.read(buf, 0, buf.size)
                if (read > 0) {
                    val stereo = ShortArray(read * 2)
                    var peak = 0
                    for (i in 0 until read) {
                        val s = buf[i]
                        stereo[i * 2] = s
                        stereo[i * 2 + 1] = s
                        val a = abs(s.toInt())
                        if (a > peak) peak = a
                    }
                    synchronized(chunks) {
                        chunks.add(stereo)
                        totalShorts += stereo.size
                    }
                    val norm = peak / 32768.0
                    val db = if (norm > 1e-5) max(-96.0, 20.0 * log10(norm)).toFloat() else -96f
                    onMeterDb(db)
                }
            }
        }
        return true
    }

    fun stop(): ShortArray {
        isRecording = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recordJob?.cancel()
        recordJob = null

        synchronized(chunks) {
            val out = ShortArray(totalShorts)
            var offset = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, offset, c.size)
                offset += c.size
            }
            chunks.clear()
            totalShorts = 0
            return out
        }
    }
}
