package io.github.l6desk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import io.github.l6desk.player.PcmOutput

/**
 * The music player's audio on the tablet: the L6max's USB audio over USB-OTG, opened as a
 * 4-channel 48 kHz 16-bit stream (USB 1/2 → CH7, USB 3/4 → CH8) and routed to the L6max itself, not
 * the tablet's speakers. If the tablet only offers the L6max in stereo, USB 1/2 is used ([pairs] = 1).
 */
class AndroidPcmOutput private constructor(private val track: AudioTrack, private val stereo: Boolean) : PcmOutput {
    private var written = 0L
    private var buf = ByteArray(0)

    override fun write(bytes: ByteArray, length: Int) {
        val frames = length / IN_FRAME
        if (stereo) {
            // Keep the first pair (USB 1/2) of each 4-channel frame.
            if (buf.size < frames * 4) buf = ByteArray(frames * 4)
            for (f in 0 until frames) System.arraycopy(bytes, f * IN_FRAME, buf, f * 4, 4)
            writeAll(buf, frames * 4)
        } else {
            writeAll(bytes, length)
        }
        written += frames
    }

    private fun writeAll(b: ByteArray, length: Int) {
        var at = 0
        while (at < length) {
            val n = track.write(b, at, length - at, AudioTrack.WRITE_BLOCKING)
            if (n < 0) throw IllegalStateException("The L6max's USB audio stopped (error $n)")
            if (n == 0) Thread.sleep(5)
            at += n
        }
    }

    override fun pause() = track.pause()

    override fun resume() = track.play()

    override val framesPlayed: Long get() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun drain() {
        val deadline = System.currentTimeMillis() + 3_000
        while (framesPlayed < written && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    override fun close() {
        runCatching { track.stop() }
        track.release()
    }

    companion object {
        private const val TAG = "L6Desk"
        private const val RATE = 48_000
        private const val IN_FRAME = 8 // 4 channels × 16 bit

        /** The L6max among the tablet's audio outputs (plugged in by USB), or null. */
        fun l6max(context: Context): AudioDeviceInfo? {
            val am = context.getSystemService(AudioManager::class.java) ?: return null
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { d ->
                (d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                    "l6max" in d.productName.toString().lowercase().replace(" ", "")
            }
        }

        fun isAvailable(context: Context): Boolean = l6max(context) != null

        /** USB pairs the tablet can play into: 2 if it offers the L6max's 4 channels, else 1. */
        fun pairs(context: Context): Int {
            val d = l6max(context) ?: return 2
            val counts = d.channelCounts
            val indexMasks = d.channelIndexMasks
            return if (counts.isEmpty() || counts.any { it >= 4 } || indexMasks.any { Integer.bitCount(it) >= 4 }) 2 else 1
        }

        fun open(context: Context): PcmOutput {
            val device = l6max(context) ?: error("The L6max's USB audio isn't available: is it plugged into the tablet?")
            val four = pairs(context) == 2
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .apply { if (four) setChannelIndexMask(0b1111) else setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) }
                .build()
            val frame = if (four) 8 else 4
            val min = AudioTrack.getMinBufferSize(RATE, if (four) AudioFormat.CHANNEL_OUT_QUAD else AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, RATE / 5 * frame)) // ~200 ms: steady, and volume changes still feel quick
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Couldn't open the L6max's USB audio" }
            track.setPreferredDevice(device)
            track.play()
            Log.i(TAG, "Player output: ${device.productName}, ${if (four) "4" else "2"} channels, routed to ${track.routedDevice?.productName}")
            return AndroidPcmOutput(track, stereo = !four)
        }
    }
}
