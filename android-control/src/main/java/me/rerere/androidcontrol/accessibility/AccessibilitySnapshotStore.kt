package me.rerere.androidcontrol.accessibility

/**
 * Only the small map is locked. Framework/node Binder calls must happen outside
 * this store so Android's Main-thread unbind/destroy callback never waits for a
 * target application which is slow to return its hierarchy.
 */
internal class AccessibilitySnapshotStore<K, V>(private val limit: Int) {
    data class Read<V>(val generation: Long, val sequence: Long, val previous: V?)
    private val lock = Any()
    private var generation = 0L
    private var sequence = 0L
    private val values = LinkedHashMap<K, V>()
    private val published = mutableMapOf<K, Long>()

    fun begin(key: K): Read<V> = synchronized(lock) { Read(generation, ++sequence, values[key]) }
    fun get(key: K): V? = synchronized(lock) { values[key] }
    fun publish(key: K, read: Read<V>, value: V): Boolean = synchronized(lock) {
        if (read.generation != generation || (published[key] ?: 0L) > read.sequence) return@synchronized false
        values[key] = value
        published[key] = read.sequence
        while (values.size > limit) values.keys.first().let { values.remove(it); published.remove(it) }
        true
    }
    fun remove(key: K, read: Read<V>) = synchronized(lock) {
        if (read.generation == generation && (published[key] ?: 0L) <= read.sequence) {
            values.remove(key)
            published.remove(key)
        }
    }
    fun disconnect() = synchronized(lock) {
        generation++
        values.clear()
        published.clear()
    }
}
