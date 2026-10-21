package io.github.l6desk.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileTextStoreTest {
    @Test
    fun writesAndReplacesFiles() {
        val dir = Files.createTempDirectory("l6desk-store").toFile()
        val store = FileTextStore(dir.resolve("nested"))
        assertNull(store.read("desk.json"))
        store.write("desk.json", "one")
        store.write("desk.json", "two")
        assertEquals("two", store.read("desk.json"))
        assertEquals(listOf("desk.json"), dir.resolve("nested").list()?.toList(), "no temporary file left behind")
        dir.deleteRecursively()
    }

    @Test
    fun settingsFromBeforeTheRenameAreMovedAcross() {
        val base = Files.createTempDirectory("l6desk-settings").toFile()
        val old = base.resolve("l6max-remote").apply { mkdirs(); resolve("desk.json").writeText("mine") }
        val dir = settingsDirMovingOld(base.resolve("l6desk"), old)
        assertEquals(base.resolve("l6desk"), dir)
        assertEquals("mine", FileTextStore(dir).read("desk.json"))
        assertEquals(false, old.exists())

        // Once the new folder exists, an old one (e.g. restored from a backup) is left alone.
        old.mkdirs()
        assertEquals(base.resolve("l6desk"), settingsDirMovingOld(base.resolve("l6desk"), old))
        // Nothing saved yet: the new folder, made on the first save.
        assertEquals(base.resolve("fresh"), settingsDirMovingOld(base.resolve("fresh"), base.resolve("none")))
        base.deleteRecursively()
    }
}
