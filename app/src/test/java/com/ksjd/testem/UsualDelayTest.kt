package com.ksjd.testem

import com.ksjd.testem.hub.TripHistory
import com.ksjd.testem.live.PositionSource
import com.ksjd.testem.live.TripDetail
import com.ksjd.testem.live.TripStop
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.17.2: how late a bus usually is, per stop instead of one figure for the whole trip. */
class UsualDelayTest {
    private val min = 60_000L
    private val stops = (0..3).map { TripStop("S$it", it + 1, it + 1, it * 10 * min, null) } // 0:00, 0:10, 0:20, 0:30
    private val history = TripHistory(delaySeconds = 120, runs = 5, lateRuns = 3, dayType = "workday", byStop = listOf(0, 60, 300, 600))

    @Test fun betweenTwoStopsItIsTheirAverage() {
        val detail = TripDetail("1", "S3", stops, null, history = history)
        assertEquals(0, history.now(detail, -1))               // before it leaves: the first stop's
        assertEquals(180, history.now(detail, 15 * min))       // between S1 (60) and S2 (300)
        assertEquals(600, history.now(detail, 40 * min))       // after the end: the last stop's
    }

    @Test fun eachStopKeepsItsOwnWhenTimesArePredicted() {
        val detail = TripDetail("1", "S3", stops, 180, history = history, positionSource = PositionSource.History)
        assertEquals(listOf(0, 60, 300, 600), stops.indices.map(detail::delayAt))
        // Live or reported delays stay one figure for the whole bus.
        assertEquals(listOf(180, 180, 180, 180), stops.indices.map(detail.copy(positionSource = PositionSource.Riders)::delayAt))
    }

    @Test fun anOlderHubWithoutStopsUsesTheWholeTrip() {
        val old = history.copy(byStop = null)
        assertEquals(120, old.atStop(2))
        assertEquals(120, old.now(TripDetail("1", "S3", stops, null, history = old), 15 * min))
    }
}
