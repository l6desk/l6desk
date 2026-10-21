package io.github.l6desk.player

import java.io.File

/** Turns a music file (MP3, AAC, FLAC, any WAV) into the player's own format: 48 kHz 16-bit stereo WAV. */
fun interface AudioConverter {
    /** Converts [input] into [output]; throws with a message for the app if it can't. */
    fun convert(input: File, output: File)
}
