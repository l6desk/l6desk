package io.github.l6desk.model

/**
 * Which CC number each mixer control answers to (unmapped controls are simply absent), and which
 * MIDI note plays each sound pad (index 0 = pad 1; null = not mapped).
 */
data class CcMap(
    val assignments: Map<Control, Int>,
    val padNotes: List<Int?> = L6maxLayout.FACTORY_PAD_NOTES,
) {
    init {
        for ((control, cc) in assignments) {
            require(L6maxLayout.isAssignableCc(cc)) { "CC $cc for $control is outside the L6max's assignable range" }
        }
        require(padNotes.size == L6maxLayout.PAD_COUNT) { "need ${L6maxLayout.PAD_COUNT} pad notes, got ${padNotes.size}" }
        require(padNotes.all { it == null || it in MIDI_VALUE_RANGE }) { "pad notes must be 0–127: $padNotes" }
    }

    private val byCc: Map<Int, Control> by lazy {
        // If two controls share a CC, the first in table order wins; duplicates() reports it.
        val ordered = L6maxLayout.TABLE_ORDER.filter { it in assignments } + assignments.keys.filterNot { it in L6maxLayout.TABLE_ORDER }
        buildMap { for (c in ordered) assignments[c]?.let { cc -> if (cc !in this) put(cc, c) } }
    }

    operator fun get(control: Control): Int? = assignments[control]

    fun controlFor(cc: Int): Control? = byCc[cc]

    /** A copy with [control] assigned to [cc], or unmapped when [cc] is null. */
    fun with(control: Control, cc: Int?): CcMap =
        copy(assignments = if (cc == null) assignments - control else assignments + (control to cc))

    /** The note for sound pad [pad] (1–4), or null when it isn't mapped. */
    fun padNote(pad: Int): Int? = padNotes.getOrNull(pad - 1)

    /** The pad (1–4) that [note] plays, or null. */
    fun padFor(note: Int): Int? = padNotes.indexOf(note).takeIf { it >= 0 }?.plus(1)

    /** A copy with pad [pad] (1–4) on [note], or unmapped when [note] is null. */
    fun withPadNote(pad: Int, note: Int?): CcMap = copy(padNotes = padNotes.toMutableList().also { it[pad - 1] = note })

    /** CC numbers assigned to more than one control. */
    fun duplicates(): Map<Int, List<Control>> =
        assignments.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
}

/** A named CC map plus the mixer's MIDI channel (one channel for everything on the L6max). */
data class Profile(
    val name: String,
    val ccMap: CcMap,
    val midiChannel: Int = 1,
    /** True once the assignments are confirmed against real hardware. */
    val verified: Boolean = false,
) {
    init {
        require(midiChannel in 1..16) { "MIDI channel must be 1–16, was $midiChannel" }
    }

    companion object {
        val FACTORY = Profile("L6max factory", L6maxLayout.FACTORY_CC_MAP, midiChannel = 1, verified = true)
    }
}
