package io.github.l6desk.model

/**
 * The L6max's CC table: the order the device stores it in (its settings snapshot) and the
 * factory assignments. Both verified on a real L6max, firmware 1.10 (see CLAUDE.md).
 */
object L6maxLayout {
    /** Per-channel fields, in table order. Each field covers channels 1–8 before the next. */
    val CHANNEL_FIELDS = listOf(
        ChannelParam.EQ_HIGH, ChannelParam.EQ_MID_FREQ, ChannelParam.EQ_MID, ChannelParam.EQ_LOW,
        ChannelParam.SUB_MIX, ChannelParam.AUX1, ChannelParam.AUX2, ChannelParam.EFX_SEND,
        ChannelParam.PAN, ChannelParam.LEVEL, ChannelParam.MUTE,
    )

    /** Every control in the device's CC table, in table order (94 entries). */
    val TABLE_ORDER: List<Control> = buildList {
        for (param in CHANNEL_FIELDS) for (ch in 1..CHANNEL_COUNT) add(Control.Channel(ch, param))
        add(Control.Channel(5, ChannelParam.MONO_X2))
        add(Control.Channel(6, ChannelParam.MONO_X2))
        add(Control.Channel(7, ChannelParam.USB_INPUT))
        add(Control.Channel(8, ChannelParam.USB_INPUT))
        add(Control.Global(GlobalParam.EFX_TYPE))
        add(Control.Global(GlobalParam.COMPRESSOR))
    }

    private val FACTORY_CCS: List<Int> = buildList {
        val firstCc = listOf(1, 11, 21, 33, 41, 49, 57, 65, 73, 81) // EQ_HIGH .. LEVEL, ch1
        for (base in firstCc) for (ch in 0 until CHANNEL_COUNT) add(base + ch)
        addAll(listOf(93, 94, 95, 102, 103, 104, 105, 106)) // MUTE ch1–8
        addAll(listOf(109, 110, 113, 114, 117, 119)) // MONO×2 5/6, USB 7/8, EFX TYPE, COMP
    }

    /** The four SOUND PAD buttons, played with MIDI notes. */
    const val PAD_COUNT = 4

    /** Factory pad notes: pad 1–4 = C4, D4, E4, F4 (✅ captured from fw 1.10). Before FACTORY_CC_MAP, which uses it. */
    val FACTORY_PAD_NOTES: List<Int?> = listOf(60, 62, 64, 65)

    val FACTORY_CC_MAP = CcMap(TABLE_ORDER.zip(FACTORY_CCS).toMap())

    /** CC numbers the L6max accepts (MIDI implementation chart): 1–31, 33–95, 102–119. */
    fun isAssignableCc(cc: Int): Boolean = cc in 1..31 || cc in 33..95 || cc in 102..119
}
