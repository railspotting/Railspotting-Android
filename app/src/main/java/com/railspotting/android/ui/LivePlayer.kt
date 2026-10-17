package com.railspotting.android.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.railspotting.android.core.Native
import kotlin.math.tanh

/**
 * Live listen: plays the voice call the core picks (the one on the air), on a thread of its own.
 * Between calls the core hands over nothing and the speaker is quiet. Runs only while the
 * Activity screen shows it.
 */
class LivePlayer {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        Native.setLive(true)
        thread = Thread({ loop() }, "live-listen").apply { start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        Native.setLive(false)
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        var track: AudioTrack? = null
        var rate = 0
        try {
            while (running) {
                val r = Native.audioRate()
                if (track == null || r != rate) {
                    track?.release()
                    rate = r
                    track = newTrack(rate)
                    track.play()
                }
                // a tenth of a second at most, waiting up to 200 ms for a call
                val data = Native.audio(rate / 10, 200) ?: continue
                if (data.isEmpty()) continue
                for (i in data.indices) data[i] = shape(data[i])
                track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track?.stop()
            track?.release()
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // a quarter second of buffer: little delay, few gaps
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 4))
            .build()
    }

    companion object {
        /** The FM discriminator's output is quiet: lift it, and round off peaks instead of clipping. */
        fun shape(x: Float): Float = tanh(GAIN * x)

        const val GAIN = 3f
    }
}
