package io.github.l6desk.midi

/**
 * Bytes captured from a real Zoom L6max, firmware 1.10, over USB on Ubuntu (amidi).
 * Originals: docs/hardware-captures/.
 */
object HardwareCaptures {
    /** Reply to EditorOpen on the Editor port: the settings snapshot, factory CC map. */
    val EDITOR_OPEN_REPLY: List<Int> = parseHex(
        """
        F0 52 00 00 2A 2E 01 01 01 00 00 00 00 00 00 00 00 00 01 01 01 01 31 31 31 31 00 00 00 00 00 00
        00 01 02 03 04 05 06 07 08 0B 0C 0D 0E 0F 10 11 12 15 16 17 18 19 1A 1B 1C 21 22 23 24 25 26 27
        28 29 2A 2B 2C 2D 2E 2F 30 31 32 33 34 35 36 37 38 39 3A 3B 3C 3D 3E 3F 40 41 42 43 44 45 46 47
        48 49 4A 4B 4C 4D 4E 4F 50 51 52 53 54 55 56 57 58 5D 5E 5F 66 67 68 69 6A 6D 6E 71 72 75 77 3C
        3E 40 41 00 00 00 00 28 00 32 00 32 00 32 00 5A 00 1E 00 79 03 23 00 79 03 32 00 01 01 01 01 01
        01 01 01 01 01 01 01 01 01 01 01 01 01 00 00 00 00 00 00 00 00 F7
        """,
    )

    /**
     * Mixer Control port while CH1–4 level encoders were turned, CH1–4 mutes pressed on/off,
     * and CH1–2 pan turned (one message per line).
     */
    val HARDWARE_MOVES: List<List<Int>> = """
        B0 51 1A
        B0 51 19
        B0 51 17
        B0 51 14
        B0 52 01
        B0 52 05
        B0 52 08
        B0 52 0C
        B0 52 10
        B0 52 15
        B0 52 18
        B0 52 19
        B0 52 18
        B0 52 16
        B0 52 14
        B0 52 10
        B0 52 0F
        B0 53 01
        B0 53 06
        B0 53 09
        B0 53 0E
        B0 53 11
        B0 53 14
        B0 53 17
        B0 53 1A
        B0 53 1E
        B0 53 1F
        B0 53 1E
        B0 53 19
        B0 53 16
        B0 53 13
        B0 53 0E
        B0 53 08
        B0 53 07
        B0 53 06
        B0 53 02
        B0 53 00
        B0 54 01
        B0 54 06
        B0 54 0B
        B0 54 12
        B0 54 19
        B0 54 20
        B0 54 27
        B0 54 2A
        B0 54 30
        B0 54 35
        B0 54 3B
        B0 54 3C
        B0 54 3B
        B0 54 3A
        B0 54 36
        B0 54 32
        B0 54 2D
        B0 54 28
        B0 54 24
        B0 54 1E
        B0 54 19
        B0 54 14
        B0 54 11
        B0 54 0F
        B0 5D 7F
        B0 5D 00
        B0 5E 7F
        B0 5E 00
        B0 5F 7F
        B0 5F 00
        B0 66 7F
        B0 66 00
        B0 49 41
        B0 49 44
        B0 49 49
        B0 49 50
        B0 49 53
        B0 49 56
        B0 49 59
        B0 49 58
        B0 49 57
        B0 49 54
        B0 49 4F
        B0 49 4B
        B0 49 4A
        B0 49 49
        B0 4A 41
        B0 4A 46
        B0 4A 4D
        B0 4A 52
        B0 4A 55
        B0 4A 59
        B0 4A 5D
        B0 4A 5C
        B0 4A 59
        B0 4A 52
        B0 4A 4B
        B0 4A 46
        B0 4A 44
        B0 4A 40
    """.trim().lines().map { parseHex(it) }
}
