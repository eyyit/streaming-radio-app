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
        val allowed=ids.distinct(); if(allowed.isEmpty()) return null
        current?.let(::observe)
        while(cursor+1<history.size) {
            cursor++
            if(history[cursor] in allowed) return history[cursor]
        }
        if(pool.toSet()!=allowed.toSet()) {
            pool=allowed; remaining.clear(); remaining.addAll(allowed.filter { it!=current }.shuffled(random))
        }
        remaining.retainAll(allowed.toSet())
        if(remaining.isEmpty()) {
            remaining.addAll(allowed.shuffled(random))
            if(remaining.size>1 && remaining[0]==current) {
                val first=remaining[0]; remaining[0]=remaining[1]; remaining[1]=first
            }
        }
        val result=remaining.removeAt(0); observe(result); return result
    }
    fun previous(validIds: Set<String>, current: String?): String? {
        current?.let(::observe)
        val original=cursor
        while(cursor>0) { cursor--; if(history[cursor] in validIds) return history[cursor] }
        cursor=original
        return null
    }
}
