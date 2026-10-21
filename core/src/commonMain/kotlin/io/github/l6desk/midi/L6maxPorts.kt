package io.github.l6desk.midi

/**
 * Recognises the L6max's three USB MIDI ports from the names each platform gives them
 * (CLAUDE.md, L6max MIDI facts). Select ports by these patterns, never by list position.
 *
 *  - macOS / Android:   "L6max Mixer Control Port", "L6max for L6 Editor Port", "L6max MIDI I/O Port"
 *  - Windows:           "MIDIIN2 (ZOOM L6max)", "MIDIOUT2 (ZOOM L6max)" (paired as "MIDI2 (ZOOM L6max)"), and the
 *                       first port just "L6max" or "ZOOM L6max"
 *  - Linux, Java Sound: "L6max [hw:4,0,1]"; the port names are lost, but the ALSA sub-device
 *    (last number, 0-based) is the port order. The card number changes between boots.
 */
object L6maxPorts {
    const val MIDI_IO = 1
    const val MIXER_CONTROL = 2
    const val EDITOR = 3

    val NAMES = mapOf(MIDI_IO to "MIDI I/O Port", MIXER_CONTROL to "Mixer Control Port", EDITOR to "Editor Port")

    private val L6MAX = Regex("""l6\s*max""", RegexOption.IGNORE_CASE)
    private val WINDOWS = Regex("""\bmidi(?:in|out)?\s*([123])\b""", RegexOption.IGNORE_CASE)

    /** Just the device's name (Windows names its first port so): "L6max", "ZOOM L6max". */
    private val BARE = Regex("""^\s*(zoom\s+)?l6\s*max\s*$""", RegexOption.IGNORE_CASE)
    private val ALSA = Regex("""\[hw:\d+,\d+,(\d+)]""")

    /** The L6max port number (1–3) a device name refers to, or null if it isn't an L6max port. */
    fun portNumber(name: String): Int? {
        if (!L6MAX.containsMatchIn(name)) return null
        val lower = name.lowercase()
        return when {
            "mixer control" in lower -> MIXER_CONTROL
            "editor" in lower -> EDITOR
            "midi i/o" in lower -> MIDI_IO
            else -> WINDOWS.find(name)?.groupValues?.get(1)?.toInt()
                ?: ALSA.find(name)?.groupValues?.get(1)?.toInt()?.plus(1)?.takeIf { it in 1..3 }
                ?: MIDI_IO.takeIf { BARE.matches(name) }
        }
    }

    /** A readable name, e.g. "L6max Mixer Control Port (hw:4,0,1)" for "L6max [hw:4,0,1]". */
    fun displayName(name: String): String {
        val port = portNumber(name) ?: return name
        val portName = NAMES.getValue(port)
        if (portName.lowercase() in name.lowercase()) return name
        val hw = ALSA.find(name)?.value?.trim('[', ']')
        return if (hw != null) "L6max $portName ($hw)" else "$name — $portName"
    }
}
