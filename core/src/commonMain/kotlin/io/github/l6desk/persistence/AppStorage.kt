package io.github.l6desk.persistence

import io.github.l6desk.model.CHANNEL_COUNT
import io.github.l6desk.model.CcMap
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.ChannelState
import io.github.l6desk.model.Control
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.MIDI_VALUE_RANGE
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.Profile
import io.github.l6desk.model.SceneBank
import io.github.l6desk.model.SceneSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App-wide options that persist between runs. */
data class AppSettings(
    /** The device the user last picked, reconnected to when it's present. */
    val lastDeviceId: String? = null,
    val autoPushOnConnect: Boolean = false,
    val showMidiLog: Boolean = false,
    /** Wi-Fi bridges entered on this device, as "host" or "host:port". */
    val bridges: List<String> = emptyList(),
    /** Desktop: run the Wi-Fi bridge so a tablet can control the mixer connected here. */
    val bridgeMode: Boolean = false,
    val bridgePort: Int = io.github.l6desk.net.BridgeProtocol.DEFAULT_PORT,
    /** The send the SENDS view selects when opened (the last one used). */
    val lastSend: ChannelParam = ChannelParam.AUX1,
    /** Pressing scene A–D on the mixer recalls (and sends) the app's scene A–D instead. */
    val linkMixerScenes: Boolean = false,
    /**
     * Levels from before the player took a channel over (it sets the channel it plays on, CH7 or
     * CH8, to full while in use), by channel; restored afterwards. Saved so a restart in between
     * doesn't lose them.
     */
    val playerHeldLevels: Map<Int, Int>? = null,
    /**
     * CH7/CH8's USB switch from before choosing that channel as the player's output switched it to
     * USB (0 = its own inputs), by channel; given back with the levels.
     */
    val playerHeldUsb: Map<Int, Int>? = null,
    /** Closing the app while connected to a Pi bridge (one that allows it) shuts the Pi down too. */
    val powerOffBridgeOnExit: Boolean = true,
    /** Bridge PINs entered by the user, by device id (protocol v6), so they're asked for only once. */
    val bridgePins: Map<String, String> = emptyMap(),
    /** Desktop: the PIN tablets must give to use this computer's Bridge mode; empty for none. */
    val bridgeModePin: String = "",
    /** How RECORDINGS downloads (master / used tracks / all, 24-bit, normalised). */
    val recordingOptions: io.github.l6desk.recordings.RecordingOptions = io.github.l6desk.recordings.RecordingOptions(),
)

/** Named CC-map profiles. The factory profile is always present and can't be deleted. */
data class ProfileLibrary(val profiles: List<Profile>, val activeName: String) {
    val active: Profile get() = profiles.firstOrNull { it.name == activeName } ?: Profile.FACTORY

    fun withActive(name: String) = if (profiles.any { it.name == name }) copy(activeName = name) else this

    /** Adds or replaces [profile] and makes it active. */
    fun save(profile: Profile) = copy(profiles = profiles.filterNot { it.name == profile.name } + profile, activeName = profile.name)

    fun delete(name: String) =
        if (name == Profile.FACTORY.name) this
        else copy(profiles = profiles.filterNot { it.name == name }, activeName = if (activeName == name) Profile.FACTORY.name else activeName)

    companion object {
        val DEFAULT = ProfileLibrary(listOf(Profile.FACTORY), Profile.FACTORY.name)
    }
}

/**
 * Loads and saves the desk, the profiles and the settings as JSON files in a [TextStore]
 * (`desk.json`, `profiles.json`, `settings.json`). A missing file gives the defaults; a file that
 * can't be read is copied to `<name>.bad` and the defaults are used, so nothing is lost silently.
 */
class AppStorage(private val store: TextStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // ---- Desk --------------------------------------------------------------------------------

    /** The saved desk. Solo and master mute always start off: they are in-the-moment states. */
    fun loadDesk(): MixerState = load(DESK, DeskFile.serializer(), MixerState()) { it.toDesk() }

    fun saveDesk(state: MixerState) = save(DESK, DeskFile.serializer(), state.toDeskFile())

    // ---- App scenes ------------------------------------------------------------------------

    fun loadScenes(): SceneBank = load(SCENES, ScenesFile.serializer(), SceneBank()) { file ->
        SceneBank(file.scenes.mapNotNull { (key, desk) -> SceneSlot.entries.firstOrNull { it.name == key }?.let { it to desk.toDesk() } }.toMap())
    }

    fun saveScenes(bank: SceneBank) =
        save(SCENES, ScenesFile.serializer(), ScenesFile(scenes = bank.scenes.map { (slot, desk) -> slot.name to desk.toDeskFile() }.toMap()))

    // ---- Profiles ----------------------------------------------------------------------------

    fun loadProfiles(): ProfileLibrary = load(PROFILES, ProfilesFile.serializer(), ProfileLibrary.DEFAULT) { file ->
        val saved = file.profiles.mapNotNull { it.toProfile() }.filterNot { it.name == Profile.FACTORY.name }
        ProfileLibrary(listOf(Profile.FACTORY) + saved, file.active).let { it.withActive(file.active) }
    }

    fun saveProfiles(library: ProfileLibrary) = save(
        PROFILES,
        ProfilesFile.serializer(),
        ProfilesFile(
            active = library.activeName,
            profiles = library.profiles.filterNot { it.name == Profile.FACTORY.name }.map { it.toFile() },
        ),
    )

    // ---- Settings ----------------------------------------------------------------------------

    fun loadSettings(): AppSettings = load(SETTINGS, SettingsFile.serializer(), AppSettings()) {
        AppSettings(
            lastDeviceId = it.lastDeviceId,
            autoPushOnConnect = it.autoPushOnConnect,
            showMidiLog = it.showMidiLog,
            bridges = it.bridges.filter { b -> io.github.l6desk.net.BridgeAddress.parse(b) != null }.distinct(),
            bridgeMode = it.bridgeMode,
            bridgePort = it.bridgePort.takeIf { p -> p in 1..65535 } ?: io.github.l6desk.net.BridgeProtocol.DEFAULT_PORT,
            lastSend = io.github.l6desk.model.SEND_PARAMS.firstOrNull { p -> p.name == it.lastSend } ?: ChannelParam.AUX1,
            linkMixerScenes = it.linkMixerScenes,
            // Before 0.17 both CH7 and CH8 were held, as a list of two.
            playerHeldLevels = (it.playerHeld?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { ch -> ch to v } }?.toMap()
                ?: it.playerHeldLevels?.takeIf { l -> l.size == 2 }?.let { l -> mapOf(7 to l[0], 8 to l[1]) })
                ?.filterKeys { ch -> ch in 7..8 }?.mapValues { (_, v) -> v.coerceIn(0, 127) }?.ifEmpty { null },
            playerHeldUsb = it.playerHeldUsb?.mapNotNull { (k, v) -> k.toIntOrNull()?.takeIf { ch -> ch in 7..8 }?.let { ch -> ch to (if (v >= 64) 127 else 0) } }?.toMap()?.ifEmpty { null },
            powerOffBridgeOnExit = it.powerOffBridgeOnExit,
            bridgePins = it.bridgePins,
            bridgeModePin = it.bridgeModePin,
            recordingOptions = it.recordingOptions,
        )
    }

    fun saveSettings(settings: AppSettings) = save(
        SETTINGS,
        SettingsFile.serializer(),
        SettingsFile(
            lastDeviceId = settings.lastDeviceId,
            autoPushOnConnect = settings.autoPushOnConnect,
            showMidiLog = settings.showMidiLog,
            bridges = settings.bridges,
            bridgeMode = settings.bridgeMode,
            bridgePort = settings.bridgePort,
            lastSend = settings.lastSend.name,
            linkMixerScenes = settings.linkMixerScenes,
            playerHeld = settings.playerHeldLevels?.mapKeys { (ch, _) -> ch.toString() },
            playerHeldUsb = settings.playerHeldUsb?.mapKeys { (ch, _) -> ch.toString() },
            powerOffBridgeOnExit = settings.powerOffBridgeOnExit,
            bridgePins = settings.bridgePins,
            bridgeModePin = settings.bridgeModePin,
            recordingOptions = settings.recordingOptions,
        ),
    )

    // ---- Helpers -----------------------------------------------------------------------------

    private fun <F, T> load(name: String, serializer: kotlinx.serialization.KSerializer<F>, default: T, convert: (F) -> T): T {
        val text = store.read(name) ?: return default
        return try {
            convert(json.decodeFromString(serializer, text))
        } catch (e: Exception) {
            store.write("$name.bad", text)
            default
        }
    }

    private fun <F> save(name: String, serializer: kotlinx.serialization.KSerializer<F>, value: F) =
        store.write(name, json.encodeToString(serializer, value))

    companion object {
        const val DESK = "desk.json"
        const val PROFILES = "profiles.json"
        const val SETTINGS = "settings.json"
        const val SCENES = "scenes.json"

        /** "CH3:LEVEL" for a channel control, "EFX_TYPE" for a global one. */
        fun controlKey(control: Control): String = when (control) {
            is Control.Channel -> "CH${control.channel}:${control.param.name}"
            is Control.Global -> control.param.name
        }

        fun parseControlKey(key: String): Control? {
            val m = Regex("""CH(\d+):(\w+)""").matchEntire(key)
            if (m != null) {
                val ch = m.groupValues[1].toInt()
                val param = ChannelParam.entries.firstOrNull { it.name == m.groupValues[2] } ?: return null
                return if (param.availableOn(ch)) Control.Channel(ch, param) else null
            }
            return GlobalParam.entries.firstOrNull { it.name == key }?.let { Control.Global(it) }
        }
    }
}

/** A saved desk. Solo and master mute always start off: they are in-the-moment states. */
private fun DeskFile.toDesk(): MixerState {
    val channels = (1..CHANNEL_COUNT).map { n ->
        val saved = channels.firstOrNull { it.number == n }
        val params = saved?.params.orEmpty().mapNotNull { (key, value) ->
            val param = ChannelParam.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
            if (!param.availableOn(n) || value !in MIDI_VALUE_RANGE) null else param to value
        }.toMap()
        ChannelState(n, name = saved?.name?.takeIf { it.isNotBlank() }?.take(12) ?: "CH $n", params = params)
    }
    val globals = globals.mapNotNull { (key, value) ->
        GlobalParam.entries.firstOrNull { it.name == key }?.takeIf { value in MIDI_VALUE_RANGE }?.let { it to value }
    }.toMap()
    return MixerState(channels = channels, master = master.coerceIn(MIDI_VALUE_RANGE), globals = globals)
}

private fun MixerState.toDeskFile() = DeskFile(
    master = master,
    globals = globals.mapKeys { it.key.name },
    channels = channels.map { c -> ChannelFile(c.number, c.name, c.params.mapKeys { it.key.name }) },
)

private fun Profile.toFile() = ProfileFile(
    name = name,
    midiChannel = midiChannel,
    verified = verified,
    ccMap = ccMap.assignments.mapKeys { AppStorage.controlKey(it.key) },
    padNotes = ccMap.padNotes,
)

private fun ProfileFile.toProfile(): Profile? {
    if (name.isBlank() || midiChannel !in 1..16) return null
    val assignments = ccMap.mapNotNull { (key, cc) ->
        val control = AppStorage.parseControlKey(key) ?: return@mapNotNull null
        if (L6maxLayout.isAssignableCc(cc)) control to cc else null
    }.toMap()
    // Profiles saved before sound pads have no pad notes: they get the factory ones.
    val pads = padNotes?.takeIf { it.size == L6maxLayout.PAD_COUNT }?.map { n -> n?.takeIf { it in MIDI_VALUE_RANGE } }
        ?: L6maxLayout.FACTORY_PAD_NOTES
    return Profile(name.take(24), CcMap(assignments, pads), midiChannel, verified)
}

// ---- File formats (version 1) ---------------------------------------------------------------

@Serializable
private data class DeskFile(
    val version: Int = 1,
    val master: Int = MixerState.MASTER_UNITY,
    val globals: Map<String, Int> = emptyMap(),
    val channels: List<ChannelFile> = emptyList(),
)

@Serializable
private data class ScenesFile(val version: Int = 1, val scenes: Map<String, DeskFile> = emptyMap())

@Serializable
private data class ChannelFile(val number: Int, val name: String, val params: Map<String, Int> = emptyMap())

@Serializable
private data class ProfilesFile(val version: Int = 1, val active: String = Profile.FACTORY.name, val profiles: List<ProfileFile> = emptyList())

@Serializable
private data class ProfileFile(
    val name: String,
    val midiChannel: Int = 1,
    val verified: Boolean = false,
    val ccMap: Map<String, Int> = emptyMap(),
    /** Sound pad 1–4 notes (null = not mapped); absent in files saved before sound pads. */
    val padNotes: List<Int?>? = null,
)

@Serializable
private data class SettingsFile(
    val version: Int = 1,
    val lastDeviceId: String? = null,
    val autoPushOnConnect: Boolean = false,
    val showMidiLog: Boolean = false,
    /** Wi-Fi bridges entered on this device, as "host" or "host:port". */
    val bridges: List<String> = emptyList(),
    /** Desktop: run the Wi-Fi bridge so a tablet can control the mixer connected here. */
    val bridgeMode: Boolean = false,
    val bridgePort: Int = io.github.l6desk.net.BridgeProtocol.DEFAULT_PORT,
    val lastSend: String = "AUX1",
    val linkMixerScenes: Boolean = false,
    /** Before 0.17: CH7 and CH8's held levels (both were always held). Read, no longer written. */
    val playerHeldLevels: List<Int>? = null,
    /** The held levels by channel ("7", "8"). */
    val playerHeld: Map<String, Int>? = null,
    val playerHeldUsb: Map<String, Int>? = null,
    val powerOffBridgeOnExit: Boolean = true,
    val bridgePins: Map<String, String> = emptyMap(),
    val bridgeModePin: String = "",
    /** How RECORDINGS downloads (master / used tracks / all, 24-bit, normalised). */
    val recordingOptions: io.github.l6desk.recordings.RecordingOptions = io.github.l6desk.recordings.RecordingOptions(),
)
