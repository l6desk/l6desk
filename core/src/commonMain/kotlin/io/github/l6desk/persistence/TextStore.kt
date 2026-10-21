package io.github.l6desk.persistence

/** Where the app keeps its small JSON files. One per name; writes replace the whole file. */
interface TextStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

/** A [TextStore] in memory, for tests and previews. */
class MemoryStore(initial: Map<String, String> = emptyMap()) : TextStore {
    val files = initial.toMutableMap()
    override fun read(name: String) = files[name]
    override fun write(name: String, text: String) {
        files[name] = text
    }
}
