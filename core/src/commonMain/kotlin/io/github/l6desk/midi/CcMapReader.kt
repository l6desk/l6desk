package io.github.l6desk.midi

import io.github.l6desk.model.CcMap

/**
 * A transport that can read the mixer's current CC map (CLAUDE.md, "Read map from mixer").
 * Over USB this uses the L6max Editor port, read-only, beside the Mixer Control port in use.
 */
interface CcMapReader {
    /** Reads the CC map of the mixer that [device] (the connected Mixer Control port) belongs to. */
    suspend fun readCcMap(device: MidiDeviceInfo): CcMap
}
