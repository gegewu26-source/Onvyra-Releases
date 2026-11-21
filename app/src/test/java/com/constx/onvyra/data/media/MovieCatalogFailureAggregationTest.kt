package com.constx.onvyra.data.media

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogFailureAggregationTest {
    @Test fun overlappingRetriesCountTheRealSourceOnlyOnceAndKeepTheWholePoolSize() {
        val first = MovieCatalogSearchReport(2, 1, 0, 0,
            listOf(MovieCatalogSourceFailure("a", MovieCatalogFailureCode.TIMEOUT)), 1, 1,
            24, setOf("a", "b"), setOf("a"), emptySet())
        val second = MovieCatalogSearchReport(2, 2, 2, 2, emptyList(), 0, 0,
            24, setOf("a", "b"), setOf("a", "b"), setOf("a", "b"), setOf("a", "b"))
        val report = requireNotNull(combineCatalogSearchReports(listOf(first, second)))
        assertEquals(2, report.totalSources)
        assertEquals(2, report.attemptedSources)
        assertEquals(2, report.successfulSources)
        assertEquals(2, report.emptySources)
        assertEquals(24, report.eligibleSources)
        assertEquals(0, report.timedOutSources)
        assertTrue(report.failures.isEmpty())
    }

    private val empty = MovieCatalogSearchReport(1, 1, 1, 1, emptyList(), 0, 0)
    @Test fun anEarlierFailureCannotBeDiscardedByALaterEmptyGroup() {
        val unavailable = MovieCatalogSearchReport(1, 1, 0, 0,
            listOf(MovieCatalogSourceFailure("fixture", MovieCatalogFailureCode.HTTP)), 0, 0)
        val error = combineCatalogLookupFailures(MovieCatalogSearchException(unavailable), MovieCatalogNoMatchException(empty))
        assertEquals("partial_search", error.catalogStatus)
        assertEquals(2, error.report!!.totalSources)
        assertEquals(1, error.report.successfulSources)
        assertEquals(1, error.report.failures.size)
    }
    @Test fun earlierMatchingMetadataAndUnknownErrorsAreNotTurnedIntoAbsence() {
        assertEquals("playback_failed", combineCatalogLookupFailures(
            MovieCatalogPlaybackException(empty.copy(emptySources = 0)), MovieCatalogNoMatchException(empty)).catalogStatus)
        val error = combineCatalogLookupFailures(IOException("https://private.example/?token=secret"), MovieCatalogNoMatchException(empty))
        assertEquals("partial_search", error.catalogStatus)
        assertFalse(error.message.orEmpty().contains("secret"))
    }
    @Test fun twoSuccessfullyQueriedEmptyGroupsRemainALimitedNoMatch() {
        val error = combineCatalogLookupFailures(MovieCatalogNoMatchException(empty), MovieCatalogNoMatchException(empty))
        assertEquals("no_match", error.catalogStatus)
        assertTrue(error.report!!.complete)
        assertEquals(2, error.report.successfulSources)
    }
}
