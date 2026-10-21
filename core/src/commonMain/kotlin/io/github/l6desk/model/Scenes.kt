package io.github.l6desk.model

/** The four app scene memories. They live in the app, not on the mixer (the L6max's own scenes can't be read back). */
enum class SceneSlot { A, B, C, D }

/**
 * App scenes: snapshots of the desk that can be recalled in one go. Recalling sends the whole
 * snapshot, so the desk and the mixer match afterwards (unlike the L6max's own scenes, whose
 * contents the app can't know).
 */
data class SceneBank(val scenes: Map<SceneSlot, MixerState> = emptyMap()) {
    operator fun get(slot: SceneSlot): MixerState? = scenes[slot]

    /** Stores [desk] in [slot] (solo and instant mute aren't part of a scene). */
    fun store(slot: SceneSlot, desk: MixerState) = copy(scenes = scenes + (slot to desk.asSceneSnapshot()))

    fun clear(slot: SceneSlot) = copy(scenes = scenes - slot)
}

/** This desk as a scene: solos off and instant mute off (they're in-the-moment states). */
fun MixerState.asSceneSnapshot(): MixerState = copy(channels = channels.map { it.copy(solo = false) }, masterMute = false)

/**
 * This desk with [scene] recalled: the scene's levels, pan, EQ, mutes, MONO ×2, master and
 * globals. Kept from the current desk: channel names, instant mute (never released by a recall),
 * and any send or USB input the scene doesn't know (so a recall doesn't disturb a monitor mix or
 * an input it never saw).
 * Solos are cleared.
 */
fun MixerState.recall(scene: MixerState): MixerState = MixerState(
    channels = channels.zip(scene.channels).map { (current, snap) ->
        val params = snap.params.toMutableMap()
        for (p in LEARNED_PARAMS) if (p !in snap.params) current.params[p]?.let { params[p] = it }
        ChannelState(current.number, name = current.name, params = params, solo = false)
    },
    master = scene.master,
    masterMute = masterMute,
    globals = globals + scene.globals,
)
