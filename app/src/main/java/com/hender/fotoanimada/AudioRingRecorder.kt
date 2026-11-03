package com.hender.fotoanimada

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log

/**
 * Graba el micrófono sin parar en AAC y guarda solo los últimos segundos.
 * Los tiempos usan System.nanoTime(), el mismo reloj que MotionRecorder,
 * para que audio y video queden sincronizados.
 */
class AudioRingRecorder {

    /** Cuánto audio guardar. Se sube mientras se graba un video largo. */
    @Volatile
    var keepUs: Long = 6_000_000


    private val lock = Any()
    private val samples = ArrayDeque<MotionRecorder.Sample>()
    private var format: MediaFormat? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    class Snapshot(val format: MediaFormat, val samples: List<MotionRecorder.Sample>)

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-ring").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        synchronized(lock) {
            samples.clear()
            format = null
        }
    }

    /** Copia el audio entre fromUs y toUs (reloj nanoTime / 1000). */
    fun snapshot(fromUs: Long, toUs: Long): Snapshot? = synchronized(lock) {
        val f = format ?: return null
        val list = samples.filter { it.ptsUs in fromUs..toUs }
        if (list.isEmpty()) null else Snapshot(f, list)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192) * 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el micrófono", e)
            running = false
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running = false
            return
        }

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        rec.startRecording()

        val pcm = ByteArray(4096)
        val ts = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var framesRead = 0L
        var anchorUs = -1L
        var lastPts = -1L

        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val frames = n / 2

                // Tiempo del primer frame de este bloque
                var pts = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                    AudioRecord.SUCCESS
                ) {
                    ts.nanoTime / 1000 + (framesRead - ts.framePosition) * 1_000_000L / rate
                } else {
                    if (anchorUs < 0) anchorUs = System.nanoTime() / 1000 - frames * 1_000_000L / rate
                    anchorUs + framesRead * 1_000_000L / rate
                }
                if (pts <= lastPts) pts = lastPts + 1
                lastPts = pts
                framesRead += frames

                val idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val ib = enc.getInputBuffer(idx)!!
                    ib.clear()
                    val len = minOf(n, ib.remaining())
                    ib.put(pcm, 0, len)
                    enc.queueInputBuffer(idx, 0, len, pts, 0)
                }
                drain(enc, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error grabando audio", e)
        } finally {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
            try {
                enc.stop()
            } catch (_: Exception) {
            }
            enc.release()
        }
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val o = enc.dequeueOutputBuffer(info, 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    synchronized(lock) { format = enc.outputFormat }
                o >= 0 -> {
                    val buf = enc.getOutputBuffer(o)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !isConfig) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        synchronized(lock) {
                            samples.addLast(MotionRecorder.Sample(bytes, info.presentationTimeUs, 0))
                            val cutoff = info.presentationTimeUs - keepUs
                            while (samples.isNotEmpty() && samples.first().ptsUs < cutoff) {
                                samples.removeFirst()
                            }
                        }
                    }
                    enc.releaseOutputBuffer(o, false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AudioRing"
    }
}
