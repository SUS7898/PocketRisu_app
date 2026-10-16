package com.sus7898.pocketrisu.log

/** Fixed-size ring of formatted log lines. Pure Kotlin so it is unit-testable. */
class LogBuffer(private val capacity: Int) {
    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    val size: Int
        @Synchronized get() = lines.size
}
