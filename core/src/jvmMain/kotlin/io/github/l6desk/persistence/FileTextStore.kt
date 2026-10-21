package io.github.l6desk.persistence

import java.io.File

/**
 * A [TextStore] in a directory. Writes go to a temporary file that is then renamed over the old
 * one, so a crash or power cut mid-write never leaves a half-written file.
 */
class FileTextStore(private val dir: File) : TextStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).delete()
            tmp.renameTo(File(dir, name))
        }
    }
}

/**
 * The desktop settings directory: `$XDG_CONFIG_HOME/l6desk` (Linux), `%APPDATA%\L6Desk` (Windows).
 * Settings saved before the app was renamed (`l6max-remote`, `L6max Remote`) are moved there.
 */
fun desktopSettingsDir(): File {
    val appData = System.getenv("APPDATA")
    if (System.getProperty("os.name").startsWith("Windows") && appData != null) {
        return settingsDirMovingOld(File(appData, "L6Desk"), File(appData, "L6max Remote"))
    }
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config"
    return settingsDirMovingOld(File(config, "l6desk"), File(config, "l6max-remote"))
}

/** [dir], first moving [old] to it if only [old] exists. If the move fails, [old] is used as it is. */
internal fun settingsDirMovingOld(dir: File, old: File): File = when {
    dir.exists() || !old.isDirectory -> dir
    old.renameTo(dir) -> dir
    else -> old
}
