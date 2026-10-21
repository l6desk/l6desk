package io.github.l6desk.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the player's audio goes: a 4-channel, 48 kHz, 16-bit stream (USB 1/2 + USB 3/4 of the
 * L6max). [write] blocks while the output's buffer is full, so it paces playback.
 */
interface PcmOutput {
    fun write(bytes: ByteArray, length: Int)
    fun pause()
    fun resume()

    /** Frames actually played since the output opened. */
    val framesPlayed: Long

    /** Waits until everything written has been played. */
    fun drain()
    fun close()
}

/**
 * The bridge's music player and library. Each folder in [musicDir] is a playlist, and its WAV
 * files are its songs, kept in order by a number at the start of each name
 * ("The Beatles/01 Yesterday.wav"). The bridge owns those numbers: uploads go on the end, moving
 * or deleting a song renumbers the rest, and the app shows titles without them.
 *
 * Songs are played one at a time into [openOutput], the stereo audio on USB 1/2 (heard on CH7) or
 * USB 3/4 (CH8). Tapping a song in the app only cues it; PLAY plays the cued song. After a song
 * ends with "Stop", the next one is cued; after the user stops a song, that song stays cued.
 *
 * Uploads, and MP3/AAC/FLAC files copied into a playlist by hand, are converted by [converter] to
 * 48 kHz WAV once, when they arrive, so nothing but WAV is ever played. Music files left loose in
 * [musicDir] are moved into the playlist [DEFAULT_PLAYLIST]. Files are only renamed, moved or
 * converted once they've stopped changing (not while still being copied in). The folder is
 * rescanned every [rescanInterval]. Each song's end setting, the output, volume and playlist shown
 * are kept in `.l6max-player.json`.
 */
class WavPlayer(
    private val musicDir: File,
    private val scope: CoroutineScope,
    private val openOutput: () -> PcmOutput,
    private val outputAvailable: () -> Boolean,
    /** USB pairs the output can play (1 on a stereo-only device, e.g. Windows). */
    private val outputPairs: () -> Int = { 2 },
    private val rescanInterval: Duration = 3.seconds,
    private val converter: AudioConverter? = null,
    /** Uploads are refused when the card has less than this free (plus the file). */
    private val minFreeBytes: Long = 300L * 1024 * 1024,
    private val log: (String) -> Unit = {},
) : MusicPlayer {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val lock = Object()
    private var settings = loadSettings()
    private var playback: Playback? = null
    private var pendingSave: Job? = null

    /** Durations read from WAV headers, by path + size + modification time. */
    private val durations = mutableMapOf<String, Long>()

    /** Size and modification time of files at the last scan: a file is only touched once they stop changing. */
    private var lastSeen = mapOf<String, Pair<Long, Long>>()

    /** Files queued for conversion, and copied-in files that couldn't be converted (by path|size|time). */
    private val converting = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val conversions = Executors.newSingleThreadExecutor { Thread(it, "l6desk-convert").apply { isDaemon = true } }
    private var fileCounter = 0

    init {
        tidyUp()
        _state.update { it.copy(output = settings.output, volume = settings.volume, canEditPlaylists = true) }
        rescan()
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(rescanInterval)
                rescan()
            }
        }
    }

    override fun handle(command: PlayerCommand) {
        // A stopped song's thread is waited for outside the lock (it may need the lock to finish),
        // and a new song starts only after the old one has let go of the audio device.
        var finishing: Thread? = null
        var starting: Playback? = null
        synchronized(lock) {
            when (command) {
                is PlayerCommand.Play -> {
                    finishing = detachPlayback()
                    starting = prepare(command.file)
                }
                PlayerCommand.Pause -> playback?.let { p ->
                    p.paused = true
                    _state.update { it.copy(paused = true) }
                }
                PlayerCommand.Resume -> playback?.let { p ->
                    p.paused = false
                    lock.notifyAll()
                    _state.update { it.copy(paused = false) }
                }
                PlayerCommand.Stop -> {
                    val current = _state.value.playing
                    finishing = detachPlayback()
                    _state.update { it.copy(playing = null, paused = false, positionMs = 0, cued = current ?: it.cued) }
                }
                is PlayerCommand.SetOutput -> if (command.output == PlayerOutput.CH7 || _state.value.usbPairs >= 2) {
                    settings = settings.copy(output = command.output)
                    saveSettings()
                    _state.update { it.copy(output = command.output) }
                }
                is PlayerCommand.SetEnd -> {
                    settings = settings.copy(ends = settings.ends + (command.file to command.end))
                    saveSettings()
                    _tracks.update { list -> list.map { if (it.file == command.file) it.copy(end = command.end) else it } }
                }
                is PlayerCommand.SetVolume -> {
                    val v = command.volume.coerceIn(0, MAX_VOLUME)
                    settings = settings.copy(volume = v)
                    saveSoon() // a drag sends many; write the SD card once it settles
                    _state.update { it.copy(volume = v) }
                }
                is PlayerCommand.Cue -> if (_tracks.value.any { it.file == command.file }) _state.update { it.copy(cued = command.file) }
                is PlayerCommand.SelectPlaylist -> if (command.name in _state.value.playlists) select(command.name)
                is PlayerCommand.CreatePlaylist -> cleanName(command.name, MAX_PLAYLIST_NAME)?.let { name ->
                    File(musicDir, name).mkdirs()
                    refreshList()
                    if (name in _state.value.playlists) select(name)
                }
                is PlayerCommand.Move -> move(command.file, command.by)
                is PlayerCommand.Delete -> finishing = delete(command.file)
                is PlayerCommand.Rename -> renameSong(command.file, command.title)
                is PlayerCommand.RenamePlaylist -> renamePlaylist(command.name, command.to)
                is PlayerCommand.DeletePlaylist -> finishing = deletePlaylist(command.name)
            }
        }
        finishing?.join(2_000)
        starting?.thread?.start()
    }

    override fun startUpload(start: UploadStart, onStatus: (UploadStatus) -> Unit): UploadReceiver {
        val playlist = cleanName(start.playlist, MAX_PLAYLIST_NAME) ?: throw IllegalArgumentException("Choose a playlist first")
        if (!isAudioFile(start.name)) throw IllegalArgumentException("${start.name} isn't a music file the player takes (WAV, MP3, AAC, FLAC)")
        if (musicDir.usableSpace < minFreeBytes + start.size.coerceAtLeast(0) * 2) throw IllegalStateException("The bridge's SD card is nearly full")
        val dir = File(musicDir, playlist)
        val temp = synchronized(lock) {
            receiving[playlist] = (receiving[playlist] ?: 0) + 1
            dir.mkdirs()
            refreshList()
            File(dir, "$UPLOADING${++fileCounter}.${start.name.substringAfterLast('.').lowercase()}")
        }
        val out = temp.outputStream().buffered(64 * 1024)
        return object : UploadReceiver {
            private var received = 0L
            private var closed = false

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
                received += length
            }

            override fun finish() {
                out.close()
                closed = true
                onStatus(UploadStatus(start.id, UploadStage.CONVERTING, received))
                queueConversion(temp, dir, uploadTitle(start.name)) { result ->
                    doneReceiving(playlist)
                    onStatus(
                        result.fold(
                            { UploadStatus(start.id, UploadStage.DONE, received, file = it) },
                            { UploadStatus(start.id, UploadStage.FAILED, received, message = it.message ?: "Can't add ${start.name}") },
                        ),
                    )
                }
            }

            override fun abort() {
                if (closed) return
                closed = true
                runCatching { out.close() }
                temp.delete()
                doneReceiving(playlist)
            }
        }
    }

    /** Stops playback for good (the bridge is shutting down). */
    fun shutdown() {
        conversions.shutdownNow()
        synchronized(lock) {
            if (pendingSave?.isActive == true) saveSettings()
            detachPlayback()
        }?.join(2_000)
    }

    // ---- Playback -----------------------------------------------------------------------------

    /** Sets up [file] as the playing song (its thread is started by the caller). */
    private fun prepare(file: String): Playback? {
        val track = _tracks.value.firstOrNull { it.file == file } ?: return null
        if (!outputAvailable()) {
            _state.update { it.copy(playing = null, paused = false, error = "The L6max's USB audio isn't available at the bridge.") }
            return null
        }
        val p = Playback(track)
        playback = p
        _state.update { it.copy(playing = track.file, paused = false, positionMs = 0, cued = null, error = null) }
        return p
    }

    /** Tells the current playback to stop and forgets it; returns its thread to wait for. */
    private fun detachPlayback(): Thread? {
        val p = playback ?: return null
        playback = null
        p.stopped = true
        lock.notifyAll()
        return p.thread
    }

    /** One run of the output: a song, then any songs that follow it ("Play next", "Loop"). */
    private inner class Playback(first: Track) {
        @Volatile var stopped = false
        @Volatile var paused = false

        /** The song playing; its file is updated if the song is renumbered while it plays. */
        @Volatile var track = first
        val thread = Thread(::run, "l6desk-player").apply { isDaemon = true }

        private fun run() {
            var output: PcmOutput? = null
            try {
                output = openOutput()
                while (!stopped) {
                    val startFrame = framesWritten
                    playTrack(output, startFrame)
                    if (stopped) break
                    val next = synchronized(lock) { afterEnd(track) }
                    if (next == null) {
                        output.drain()
                        synchronized(lock) {
                            if (playback === this) {
                                playback = null
                                _state.update { it.copy(playing = null, paused = false, positionMs = 0, cued = cueAfter(track)) }
                            }
                        }
                        break
                    }
                    synchronized(lock) {
                        track = next
                        if (playback === this) _state.update { it.copy(playing = next.file, positionMs = 0) }
                    }
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (playback === this) {
                        playback = null
                        _state.update { it.copy(playing = null, paused = false, positionMs = 0, cued = track.file, error = "Playback stopped: ${e.message ?: e::class.simpleName}") }
                    }
                }
            } finally {
                runCatching { output?.close() }
            }
        }

        private var framesWritten = 0L

        /** The gain applied at the end of the last chunk; each chunk ramps from it to the new one (no zipper noise). */
        private var gain = volumeGain(_state.value.volume)

        private fun playTrack(output: PcmOutput, startFrame: Long) {
            // Opened under the lock: a rename (song or playlist) holds it while it moves files and
            // updates [track], so the name read here is always the file's current one.
            synchronized(lock) { openStereo48k(File(musicDir, track.file)) }.use { input ->
                val channels = input.channels
                val inFrame = input.frameBytes
                val inBuf = ByteArray(FRAMES_PER_CHUNK * inFrame)
                val outBuf = ByteArray(FRAMES_PER_CHUNK * OUT_FRAME)
                var lastReport = 0L
                while (!stopped) {
                    if (paused) {
                        output.pause()
                        synchronized(lock) { while (paused && !stopped) lock.wait(500) }
                        if (stopped) break
                        output.resume()
                    }
                    val n = readFully(input, inBuf)
                    if (n <= 0) break
                    val frames = n / inFrame
                    val pairOffset = if (_state.value.output == PlayerOutput.CH8) 4 else 0
                    java.util.Arrays.fill(outBuf, 0, frames * OUT_FRAME, 0)
                    val from = gain
                    val to = volumeGain(_state.value.volume)
                    gain = to
                    for (i in 0 until frames) {
                        val g = if (from == to) to else from + (to - from) * i / frames
                        val src = i * inFrame
                        val dst = i * OUT_FRAME + pairOffset
                        // Mono files play on both sides; anything past stereo is left out.
                        val right = if (channels >= 2) src + 2 else src
                        if (g == 1f) {
                            outBuf[dst] = inBuf[src]
                            outBuf[dst + 1] = inBuf[src + 1]
                            outBuf[dst + 2] = inBuf[right]
                            outBuf[dst + 3] = inBuf[right + 1]
                        } else {
                            putSample(outBuf, dst, (sample(inBuf, src) * g).toInt())
                            putSample(outBuf, dst + 2, (sample(inBuf, right) * g).toInt())
                        }
                    }
                    output.write(outBuf, frames * OUT_FRAME)
                    framesWritten += frames
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 250) {
                        lastReport = now
                        val played = (output.framesPlayed - startFrame).coerceAtLeast(0)
                        synchronized(lock) { if (playback === this) _state.update { it.copy(positionMs = played * 1000 / RATE) } }
                    }
                }
            }
        }
    }

    /** The song to play when [ended] finishes, by its end setting; null to stop. */
    private fun afterEnd(ended: Track): Track? {
        val current = _tracks.value.firstOrNull { it.file == ended.file } ?: ended
        return when (current.end) {
            TrackEnd.STOP -> null
            TrackEnd.LOOP -> current
            TrackEnd.NEXT -> next(current.file)
        }
    }

    /** What's cued after [ended] stops by itself: the next song, if its playlist is still the one shown. */
    private fun cueAfter(ended: Track): String? =
        if (ended.playlist == _state.value.playlist) next(ended.file)?.file else _state.value.cued

    /** The song after [file] in its playlist, or null at the end. */
    private fun next(file: String): Track? {
        val list = _tracks.value
        val i = list.indexOfFirst { it.file == file }
        return if (i >= 0) list.getOrNull(i + 1)?.takeIf { it.playlist == list[i].playlist } else null
    }

    // ---- Library ------------------------------------------------------------------------------

    private fun select(name: String) {
        settings = settings.copy(playlist = name)
        saveSettings()
        _state.update { s ->
            val keep = s.cued?.let { c -> _tracks.value.any { it.file == c && it.playlist == name } } == true
            s.copy(playlist = name, cued = if (keep) s.cued else firstIn(name))
        }
    }

    /** Uploads under way, by playlist (from the first byte until converted or abandoned). */
    private val receiving = mutableMapOf<String, Int>()

    private fun doneReceiving(playlist: String) = synchronized(lock) {
        val n = (receiving[playlist] ?: 0) - 1
        if (n > 0) receiving[playlist] = n else receiving.remove(playlist)
    }

    /** Songs are still arriving in [playlist] (uploading, or being converted): not the time to rename or delete it. */
    private fun busy(playlist: String): Boolean =
        playlist in receiving || converting.any { File(it).parentFile?.name == playlist }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    /**
     * Gives a song a new title: its file keeps its order number ("03 Old.wav" → "03 New.wav"), and
     * its end setting, and the song playing or cued, follow it.
     */
    private fun renameSong(file: String, title: String) {
        val track = _tracks.value.firstOrNull { it.file == file } ?: return
        val clean = cleanName(title, MAX_TITLE)?.removeSuffix(".wav")?.trim()?.ifEmpty { null } ?: return
        if (clean == track.title) return
        val f = File(musicDir, file)
        val prefix = numberOf(f.name)?.let { f.name.substringBefore(' ') + " " }.orEmpty()
        val target = File(f.parentFile, "$prefix$clean.wav")
        if (target.exists() && !target.name.equals(f.name, ignoreCase = true)) return fail("There's already a song called $clean here.")
        if (!f.moveTo(target)) return fail("Couldn't rename ${track.title}.")
        log("Renamed $file to ${rel(target)}")
        val to = rel(target)
        fun moved(x: String?) = if (x == file) to else x
        settings = settings.copy(ends = settings.ends.mapKeys { (k, _) -> moved(k) ?: k })
        playback?.let { p -> if (p.track.file == file) p.track = p.track.copy(file = to, title = titleOf(target.name)) }
        _state.update { s -> s.copy(playing = moved(s.playing), cued = moved(s.cued), error = null) }
        saveSettings()
        refreshList()
    }

    /**
     * Renames a playlist's folder, keeping everything that refers to its songs (end settings, the
     * song playing or cued, the playlist shown) pointing at the same songs.
     */
    private fun renamePlaylist(name: String, to: String) {
        if (name !in _state.value.playlists) return
        val target = cleanName(to, MAX_PLAYLIST_NAME) ?: return
        if (target == name) return
        if (busy(name)) return fail("Songs are still being added to $name: rename it when they're in.")
        if (_state.value.playlists.any { it != name && it.equals(target, ignoreCase = true) }) return fail("There's already a playlist called $target.")
        if (!File(musicDir, name).moveTo(File(musicDir, target))) {
            // Windows won't rename a folder while a file in it is open (Linux, so the Pi, doesn't mind).
            return fail(
                if (playback?.track?.playlist == name) "$name can't be renamed while one of its songs is playing on this computer: stop it first."
                else "Couldn't rename $name.",
            )
        }
        log("Renamed playlist $name to $target")
        fun moved(file: String?) = file?.let { if (it.startsWith("$name/")) "$target/" + it.removePrefix("$name/") else it }
        settings = settings.copy(
            ends = settings.ends.mapKeys { (k, _) -> moved(k) ?: k },
            playlist = if (settings.playlist == name) target else settings.playlist,
        )
        playback?.let { p -> if (p.track.playlist == name) p.track = p.track.copy(file = moved(p.track.file) ?: p.track.file, playlist = target) }
        _state.update { s -> s.copy(playing = moved(s.playing), cued = moved(s.cued), error = null) }
        saveSettings()
        refreshList()
    }

    /**
     * Deletes a playlist: its files, then its folder. Stops the song playing first if it's one of
     * them. Only files directly in the folder are deleted (a link is deleted, never what it points
     * to); anything else, such as a folder someone put there, is left, and so is the playlist.
     */
    private fun deletePlaylist(name: String): Thread? {
        if (name !in _state.value.playlists) return null
        if (busy(name)) {
            fail("Songs are still being added to $name: delete it when they're in.")
            return null
        }
        var finishing: Thread? = null
        if (playback?.track?.playlist == name) {
            finishing = detachPlayback()
            _state.update { it.copy(playing = null, paused = false, positionMs = 0) }
        }
        val dir = File(musicDir, name)
        for (f in dir.listFiles().orEmpty()) {
            PlatformFiles.deletePlainFileOrLink(f)
        }
        val gone = dir.delete()
        log(if (gone) "Deleted playlist $name" else "Deleted the songs in $name; the folder still holds other things")
        settings = settings.copy(
            ends = settings.ends.filterKeys { !it.startsWith("$name/") },
            playlist = if (settings.playlist == name) null else settings.playlist,
        )
        _state.update { s ->
            s.copy(
                cued = s.cued?.takeUnless { it.startsWith("$name/") },
                error = if (gone) null else "The songs in $name were deleted, but its folder on the Pi holds other things, so it's still there.",
            )
        }
        saveSettings()
        refreshList()
        return finishing
    }

    private fun firstIn(playlist: String?): String? = _tracks.value.firstOrNull { it.playlist == playlist }?.file

    private fun move(file: String, by: Int) {
        val track = _tracks.value.firstOrNull { it.file == file } ?: return
        val dir = File(musicDir, track.playlist)
        val songs = songFiles(dir).toMutableList()
        val i = songs.indexOfFirst { rel(it) == file }
        if (i < 0) return
        val f = songs.removeAt(i)
        songs.add((i + by).coerceIn(0, songs.size), f)
        renumber(dir, songs)
        refreshList()
    }

    /** Deletes a song (stopping it first if it's playing); returns the playback thread to wait for. */
    private fun delete(file: String): Thread? {
        val track = _tracks.value.firstOrNull { it.file == file } ?: return null
        var finishing: Thread? = null
        if (_state.value.playing == file) {
            finishing = detachPlayback()
            _state.update { it.copy(playing = null, paused = false, positionMs = 0) }
        }
        val after = next(file)?.file
        if (!File(musicDir, file).deleteFile()) {
            _state.update { it.copy(error = "Couldn't delete ${track.title}") }
            return finishing
        }
        log("Deleted $file")
        settings = settings.copy(ends = settings.ends - file)
        if (_state.value.cued == file) _state.update { it.copy(cued = after) }
        val dir = File(musicDir, track.playlist)
        renumber(dir, songFiles(dir).filter { numberOf(it.name) != null })
        refreshList()
        saveSettings()
        return finishing
    }

    /**
     * Renames [ordered] (songs in [dir]) to "01 Title.wav", "02 …" in that order, keeping every
     * reference to them (end settings, playing, cued) pointing at the same song. Returns old → new paths.
     */
    private fun renumber(dir: File, ordered: List<File>): Map<String, String> {
        val width = maxOf(2, ordered.size.toString().length)
        val changes = ordered.mapIndexedNotNull { i, f ->
            val target = "${(i + 1).toString().padStart(width, '0')} ${titleOf(f.name)}.wav"
            if (f.name == target) null else f to target
        }
        if (changes.isEmpty()) return emptyMap()
        // Two steps, so a song never lands on a name another is still using. The hidden name keeps
        // the title, so a song caught half-way (power cut) is found again (see tidyUp).
        val staged = changes.mapIndexedNotNull { i, (f, target) ->
            val temp = File(dir, "$RENAMING$i ${titleOf(f.name)}.wav")
            if (f.moveTo(temp)) Triple(f, temp, File(dir, target)) else null
        }
        val renamed = mutableMapOf<String, String>()
        for ((f, temp, target) in staged) {
            if (temp.moveTo(target)) renamed[rel(f)] = rel(target) else log("Couldn't rename ${temp.name} to ${target.name}")
        }
        settings = settings.copy(ends = settings.ends.mapKeys { (k, _) -> renamed[k] ?: k })
        _state.update { s -> s.copy(playing = s.playing?.let { renamed[it] ?: it }, cued = s.cued?.let { renamed[it] ?: it }) }
        playback?.let { p -> renamed[p.track.file]?.let { p.track = p.track.copy(file = it) } }
        saveSettings()
        return renamed
    }

    /**
     * Converts [source] into [dir] as the playlist's last song, called [title], on the conversion
     * thread; [onResult] gets the new song's path. The source is deleted afterwards.
     */
    private fun queueConversion(source: File, dir: File, title: String, onResult: ((Result<String>) -> Unit)? = null) {
        synchronized(lock) { converting += source.path }
        conversions.execute {
            val temp = File(dir, "$CONVERTING${synchronized(lock) { ++fileCounter }}.wav")
            val result = runCatching {
                when {
                    isPlayerWav(source) -> check(source.renameTo(temp)) { "Couldn't store ${source.name}" }
                    converter != null -> {
                        converter.convert(source, temp)
                        source.delete()
                    }
                    isReadableWav(source) -> check(source.renameTo(temp)) { "Couldn't store ${source.name}" }
                    else -> error("Only WAV files can be added: ffmpeg isn't installed on the bridge")
                }
                synchronized(lock) {
                    val numbered = songFiles(dir).filter { numberOf(it.name) != null }
                    val next = (numbered.mapNotNull { numberOf(it.name) }.maxOrNull() ?: 0) + 1
                    val target = File(dir, "${next.toString().padStart(2, '0')} $title.wav")
                    check(temp.moveTo(target)) { "Couldn't store $title" }
                    val renamed = renumber(dir, numbered + target)
                    refreshList()
                    (renamed[rel(target)] ?: rel(target)).also { log("Added $it") }
                }
            }
            result.onFailure {
                temp.delete()
                log("Couldn't add ${source.name}: ${it.message}")
            }
            synchronized(lock) {
                converting -= source.path
                if (result.isFailure && source.exists()) failed += key(source)
            }
            onResult?.invoke(result)
        }
    }

    private fun rescan() {
        val seen = mutableMapOf<String, Pair<Long, Long>>()
        fun settled(f: File): Boolean {
            val now = f.length() to f.lastModified()
            seen[f.path] = now
            return lastSeen[f.path] == now
        }
        synchronized(lock) {
            // Loose music files go into the default playlist.
            for (f in musicDir.listFiles { f -> f.isFile && !f.name.startsWith(".") && isAudioFile(f.name) }.orEmpty()) {
                if (!settled(f)) continue
                val dir = File(musicDir, DEFAULT_PLAYLIST).apply { mkdirs() }
                val target = unusedName(dir, f.name)
                if (f.renameTo(target)) {
                    settings.ends[f.name]?.let { end -> settings = settings.copy(ends = settings.ends - f.name + (rel(target) to end)) }
                    log("Moved ${f.name} into $DEFAULT_PLAYLIST")
                }
            }
            for (dir in playlistDirs()) {
                val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
                // Copied-in MP3/AAC/FLAC: converted like an upload.
                for (f in files) {
                    if (!isAudioFile(f.name) || f.name.endsWith(".wav", ignoreCase = true)) continue
                    if (settled(f) && f.path !in converting && key(f) !in failed) queueConversion(f, dir, uploadTitle(f.name))
                }
                // Copied-in WAVs without a number go on the end.
                val unnumbered = songFiles(dir).filter { numberOf(it.name) == null && settled(it) }
                if (unnumbered.isNotEmpty()) renumber(dir, songFiles(dir).filter { numberOf(it.name) != null } + unnumbered)
            }
            lastSeen = seen
            refreshList()
            val ready = outputAvailable()
            val pairs = if (ready) outputPairs() else _state.value.usbPairs
            // Only USB 1/2 here: the output is CH7, whatever was chosen before.
            if (pairs < 2 && settings.output != PlayerOutput.CH7) {
                settings = settings.copy(output = PlayerOutput.CH7)
                saveSettings()
            }
            _state.update { it.copy(ready = ready, usbPairs = pairs, output = if (pairs < 2) PlayerOutput.CH7 else it.output) }
        }
    }

    /** Rebuilds the song and playlist lists from the folder (no renaming). */
    private fun refreshList() {
        val dirs = playlistDirs()
        val list = dirs.flatMap { dir ->
            songFiles(dir).mapNotNull { f ->
                val path = rel(f)
                val ms = durations.getOrPut("$path|${f.length()}|${f.lastModified()}") { durationMs(f) ?: -1 }
                if (ms < 0) null else Track(path, titleOf(f.name), ms, settings.ends[path] ?: TrackEnd.STOP, dir.name)
            }
        }
        if (list != _tracks.value) _tracks.value = list
        val names = dirs.map { it.name }
        val shown = settings.playlist?.takeIf { it in names } ?: names.firstOrNull()
        _state.update { s ->
            s.copy(
                playlists = names,
                playlist = shown,
                cued = s.cued?.takeIf { c -> list.any { it.file == c } } ?: if (s.playing == null) list.firstOrNull { it.playlist == shown }?.file else s.cued,
            )
        }
    }

    private fun playlistDirs(): List<File> =
        musicDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedWith(compareBy({ it.name.lowercase() }, { it.name }))

    /** A playlist's songs in order: numbered ones by number, then any without a number by name. */
    private fun songFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name.endsWith(".wav", ignoreCase = true) }.orEmpty()
            .sortedWith(compareBy({ numberOf(it.name) == null }, { numberOf(it.name) ?: 0 }, { it.name.lowercase() }))

    private fun rel(f: File) = "${f.parentFile.name}/${f.name}"

    private fun key(f: File) = "${f.path}|${f.length()}|${f.lastModified()}"

    private fun unusedName(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}").also { n++ }
        return f
    }

    /** Clears up after an interrupted upload, conversion or renumbering (e.g. the power was pulled). */
    private fun tidyUp() {
        for (dir in playlistDirs()) {
            for (f in dir.listFiles().orEmpty()) {
                when {
                    f.name.startsWith(UPLOADING) || f.name.startsWith(CONVERTING) -> f.delete()
                    f.name.startsWith(RENAMING) -> f.renameTo(unusedName(dir, f.name.substringAfter(' ')))
                }
            }
        }
    }

    /**
     * Renames this to [target]. On Windows a file another program has open (often the antivirus,
     * just after the file was written) can't be renamed for a moment, so it tries for up to a second.
     */
    private fun File.moveTo(target: File): Boolean = retryOnWindows { renameTo(target) }

    /** Deletes this file, retrying on Windows like [moveTo]. */
    private fun File.deleteFile(): Boolean = retryOnWindows { delete() || !exists() }

    private fun retryOnWindows(action: () -> Boolean): Boolean {
        if (action()) return true
        if (!WINDOWS) return false
        repeat(10) {
            Thread.sleep(100)
            if (action()) return true
        }
        return false
    }

    private fun durationMs(f: File): Long? = runCatching {
        // Opened as for playback: Windows then still lets the file be renamed while it's being read.
        val info = PlatformFiles.openForReading(f).buffered(4096).use { WavInfo.read(it) }
        val whole = if (info.dataBytes == Long.MAX_VALUE) info.copy(dataBytes = f.length()) else info
        whole.durationMs.takeIf { it > 0 }
    }.getOrNull()

    // ---- Settings -----------------------------------------------------------------------------

    @Serializable
    private data class Settings(
        val output: PlayerOutput = PlayerOutput.CH7,
        val ends: Map<String, TrackEnd> = emptyMap(),
        val volume: Int = MAX_VOLUME,
        val playlist: String? = null,
    )

    private val settingsFile get() = File(musicDir, SETTINGS_FILE)

    private fun loadSettings(): Settings =
        runCatching { json.decodeFromString(Settings.serializer(), settingsFile.readText()) }.getOrNull() ?: Settings()

    private fun saveSoon() {
        pendingSave?.cancel()
        pendingSave = scope.launch(Dispatchers.IO) {
            delay(1.seconds)
            synchronized(lock) { saveSettings() }
        }
    }

    private fun saveSettings() {
        runCatching {
            val tmp = File(musicDir, "$SETTINGS_FILE.tmp")
            tmp.writeText(json.encodeToString(Settings.serializer(), settings))
            // Replacing: a plain rename can't overwrite an existing file on Windows.
            PlatformFiles.moveReplacing(tmp, settingsFile)
        }
    }

    companion object {
        const val RATE = WavReader.RATE.toLong()
        private val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")
        const val SETTINGS_FILE = ".l6max-player.json"

        /** Where music files left loose in the music folder are moved to. */
        const val DEFAULT_PLAYLIST = "Songs"
        private const val MAX_PLAYLIST_NAME = 60
        private const val MAX_TITLE = 100
        private const val UPLOADING = ".upload-"
        private const val CONVERTING = ".converting-"
        private const val RENAMING = ".renaming-"
        private const val OUT_FRAME = 8 // 4 channels × 16 bit
        private const val FRAMES_PER_CHUNK = 2048
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        private val NUMBERED = Regex("""^(\d{1,4}) .+""")

        /** The order number a song's file name starts with ("01 Yesterday.wav" → 1), or null. */
        fun numberOf(name: String): Int? = NUMBERED.find(name)?.groupValues?.get(1)?.toInt()

        /** The title shown for a song file: no order number, no ".wav". */
        fun titleOf(name: String): String {
            val base = if (name.endsWith(".wav", ignoreCase = true)) name.dropLast(4) else name
            return if (numberOf(base) != null) base.substringAfter(' ') else base
        }

        /**
         * The title for an uploaded file: no extension, and no album track number in front
         * ("03 - Yesterday.mp3" → "Yesterday"; "1999.mp3" keeps its name).
         */
        fun uploadTitle(name: String): String {
            val base = name.substringBeforeLast('.')
            val stripped = base.replaceFirst(Regex("""^\d{1,3}(\s*[-._)]\s*|\s+)(?=\S)"""), "")
            return cleanName(stripped, MAX_TITLE) ?: cleanName(base, MAX_TITLE) ?: "Song"
        }

        /** [name] made safe as a file or folder name, or null if nothing's left. */
        fun cleanName(name: String, max: Int): String? =
            name.map { if (it == '/' || it == '\\' || it.isISOControl()) ' ' else it }.joinToString("")
                .replace(Regex("""\s+"""), " ").trim().trimStart('.').trim().take(max).trim().ifEmpty { null }

        /** Already the player's own format: 48 kHz 16-bit PCM WAV, mono or stereo. */
        private fun isPlayerWav(f: File): Boolean = WavInfo.of(f)?.isPlayerFormat == true

        private fun isReadableWav(f: File): Boolean = runCatching { openStereo48k(f).close() }.isSuccess

        /** [file] as 48 kHz 16-bit signed little-endian PCM, with its own channel count. */
        fun openStereo48k(file: File): WavReader = WavReader(PlatformFiles.openForReading(file))

        private fun sample(buf: ByteArray, at: Int): Int = (buf[at].toInt() and 0xFF) or (buf[at + 1].toInt() shl 8)

        private fun putSample(buf: ByteArray, at: Int, value: Int) {
            buf[at] = value.toByte()
            buf[at + 1] = (value shr 8).toByte()
        }

        private fun readFully(input: WavReader, buf: ByteArray): Int {
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            return total - total % input.frameBytes
        }
    }
}
