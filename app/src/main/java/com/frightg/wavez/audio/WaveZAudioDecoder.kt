package com.frightg.wavez.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class DecodedAudio(
    val fileName: String,
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val waveformPeaks: List<Float>
)

object WaveZAudioDecoder {

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "audio"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    suspend fun decodeUri(context: Context, uri: Uri, maxSeconds: Int = 480): DecodedAudio? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIdx = i
                    format = f
                    break
                }
            }
            if (trackIdx < 0 || format == null) return@withContext null

            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var pcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxShorts = maxSeconds * sampleRate * max(1, channels)
            val chunks = ArrayList<ShortArray>(256)
            var totalShorts = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val read = extractor.readSampleData(buf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                val arr: ShortArray
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = outBuf.asFloatBuffer()
                                    arr = ShortArray(fb.remaining())
                                    for (i in arr.indices) {
                                        arr[i] = (fb.get(i) * 32767f).coerceIn(-32768f, 32767f).roundToInt().toShort()
                                    }
                                } else {
                                    val sb = outBuf.asShortBuffer()
                                    arr = ShortArray(sb.remaining())
                                    sb.get(arr)
                                }
                                if (totalShorts + arr.size <= maxShorts) {
                                    chunks.add(arr)
                                    totalShorts += arr.size
                                } else {
                                    val rem = maxShorts - totalShorts
                                    if (rem > 0) {
                                        chunks.add(arr.copyOf(rem))
                                        totalShorts += rem
                                    }
                                    outputDone = true
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (totalShorts == 0 || channels <= 0) return@withContext null
            val merged = ShortArray(totalShorts)
            var pos = 0
            for (c in chunks) {
                System.arraycopy(c, 0, merged, pos, c.size)
                pos += c.size
            }

            val totalFrames = (totalShorts / channels).toLong()
            val peaks = buildPeaks(merged, channels, totalFrames, targetBins = 240)

            DecodedAudio(
                fileName = queryDisplayName(context, uri),
                samples = merged,
                sampleRate = sampleRate,
                channels = channels,
                totalFrames = totalFrames,
                waveformPeaks = peaks
            )
        } catch (_: Throwable) {
            null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun buildPeaks(samples: ShortArray, channels: Int, totalFrames: Long, targetBins: Int): List<Float> {
        if (totalFrames <= 0L) return emptyList()
        val bins = targetBins.coerceAtMost(totalFrames.toInt()).coerceAtLeast(1)
        val step = max(1L, totalFrames / bins)
        val out = ArrayList<Float>(bins)
        for (b in 0 until bins) {
            val fStart = b * step
            val fEnd = if (b == bins - 1) totalFrames else (b + 1) * step
            var peak = 0f
            var f = fStart
            val innerStep = max(1L, (fEnd - fStart) / 64L)
            while (f < fEnd) {
                val base = (f * channels).toInt()
                if (base < samples.size) {
                    val v = abs(samples[base].toFloat()) / 32768f
                    if (v > peak) peak = v
                }
                f += innerStep
            }
            out.add(peak)
        }
        return out
    }
}
