package org.beebplayer.android
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CatalogueSearchTest {
 @Test fun normalisedCatalogueRanksTitlesAndFindsDescriptions() {
  val items=listOf(Programme("a","Époque","", "French history"),Programme("b","Vigil"),Programme("c","Vigil: behind the scenes"),Programme("d","Drama",summary="A vigil at night"))
  val index=CatalogueSearch.index(items)
  assertEquals(listOf("b","c","d"),CatalogueSearch.search(index,"vIgIl").map {it.id})
  assertEquals("a",CatalogueSearch.search(index,"epoque history").single().id)
  assertTrue(CatalogueSearch.search(index,"   ").isEmpty())
 }
 @Test fun cancelledQueriesStopInsteadOfQueuingBehindTyping() {
  val index=CatalogueSearch.index((0..10000).map {Programme(it.toString(),"Programme $it")})
  try {Thread.currentThread().interrupt();assertTrue(runCatching {CatalogueSearch.search(index,"programme")}.exceptionOrNull() is CancellationException)} finally {Thread.interrupted()}
  assertEquals(10001,CatalogueSearch.search(index,"programme").size)
 }
}
