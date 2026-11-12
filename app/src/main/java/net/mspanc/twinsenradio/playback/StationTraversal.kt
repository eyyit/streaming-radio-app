package net.mspanc.twinsenradio.playback

import kotlin.random.Random

/** Station traversal only: never mutates the displayed playlist order. */
class StationTraversal(private val random: Random = Random.Default) {
    data class Snapshot(val history: List<String>, val cursor: Int, val remaining: List<String>, val pool: List<String>)
    private val history = mutableListOf<String>()
    private var cursor = -1
    private val remaining = mutableListOf<String>()
    private var pool = emptyList<String>()
    fun restore(state: Snapshot) {
        history.clear(); history.addAll(state.history.takeLast(512))
        cursor = (state.cursor - (state.history.size-history.size)).coerceIn(-1,history.lastIndex.coerceAtLeast(-1))
        if(history.isNotEmpty() && cursor<0) cursor=0
        remaining.clear(); remaining.addAll(state.remaining.distinct()); pool=state.pool.distinct()
    }
    fun snapshot() = Snapshot(history.toList(),cursor,remaining.toList(),pool.toList())
    fun observe(id: String) {
        if (history.getOrNull(cursor)==id) return
        if (cursor+1<history.size) history.subList(cursor+1,history.size).clear()
        history.add(id); cursor=history.lastIndex; remaining.remove(id)
        if(history.size>512) { history.removeAt(0); cursor-- }
    }
    fun invalidateOrder() { pool=emptyList(); remaining.clear() }
    fun next(ids: List<String>, current: String?): String? {
        val target = peekNext(ids, current) ?: return null
        val forward = forwardIndex(ids.toSet())
        if (forward != null) {
            cursor = forward
        } else {
            cursor = history.lastIndex
            remaining.removeAt(0)
            observe(target)
        }
        return target
    }

    /** Prepare the same upcoming station that [next] will consume, keeping it on the deck. */
    fun peekNext(ids: List<String>, current: String?): String? {
        val allowed=ids.distinct(); if(allowed.isEmpty()) return null
        current?.let(::observe)
        val validIds = allowed.toSet()
        forwardIndex(validIds)?.let { return history[it] }
        if(pool.toSet()!=validIds) {
            pool=allowed; remaining.clear(); remaining.addAll(allowed.filter { it!=current }.shuffled(random))
        }
        remaining.retainAll(validIds)
        if(remaining.isEmpty()) {
            remaining.addAll(allowed.shuffled(random))
            if(remaining.size>1 && remaining[0]==current) {
                val first=remaining[0]; remaining[0]=remaining[1]; remaining[1]=first
            }
        }
        return remaining.first()
    }

    private fun forwardIndex(validIds: Set<String>): Int? =
        (cursor + 1..history.lastIndex).firstOrNull { history[it] in validIds }

    fun peekPrevious(validIds: Set<String>, current: String?): String? {
        val start = if (current != null && history.getOrNull(cursor) != current) cursor else cursor - 1
        return (start downTo 0).firstNotNullOfOrNull { history[it].takeIf(validIds::contains) }
    }
    fun previous(validIds: Set<String>, current: String?): String? {
        current?.let(::observe)
        val original=cursor
        while(cursor>0) { cursor--; if(history[cursor] in validIds) return history[cursor] }
        cursor=original
        return null
    }
}
