package net.mspanc.twinsenradio.playback
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
class StationTraversalTest {
 @Test fun cycleVisitsEachOtherStationOnceWithoutReorderingSource() {
  val ids=listOf("a","b","c","d"); val traversal=StationTraversal(Random(42)); traversal.observe("a")
  val visited=(1..3).map { traversal.next(ids,traversal.snapshot().history.last())!! }
  assertEquals(setOf("b","c","d"),visited.toSet()); assertEquals(listOf("a","b","c","d"),ids)
  assertNotEquals(visited.last(),traversal.next(ids,visited.last()))
 }
 @Test fun backAndForwardUseActualHistoryIncludingManualSelections() {
  val nav=StationTraversal(Random(7)); val ids=listOf("a","b","c","d")
  nav.observe("a"); val second=nav.next(ids,"a")!!; val manual=ids.first { it!="a" && it!=second }; nav.observe(manual)
  assertEquals(second,nav.previous(ids.toSet(),manual)); assertEquals(manual,nav.next(ids,second))
 }
 @Test fun snapshotRestoresTheSameUpcomingOrderAndHistory() {
  val ids=listOf("a","b","c","d"); val first=StationTraversal(Random(3)); first.observe("a")
  val selected=first.next(ids,"a")!!; val restored=StationTraversal(Random(99)); restored.restore(first.snapshot())
  assertEquals(first.next(ids,selected),restored.next(ids,selected))
  assertEquals(first.previous(ids.toSet(),first.snapshot().history.last()),restored.previous(ids.toSet(),restored.snapshot().history.last()))
 }
 @Test fun emptySingleAndChangedPoolsRemainSafe() {
  val nav=StationTraversal(Random(5)); assertNull(nav.next(emptyList(),null)); assertEquals("a",nav.next(listOf("a"),"a"))
  assertEquals("b",nav.next(listOf("b"),"a")); assertEquals("a",nav.previous(setOf("a","b"),"b"))
 }
}

