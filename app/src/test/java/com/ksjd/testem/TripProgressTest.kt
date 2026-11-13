package com.ksjd.testem

import com.ksjd.testem.hub.TripHistory
import com.ksjd.testem.live.PositionSource
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripDetail
import com.ksjd.testem.live.TripProgress
import com.ksjd.testem.live.TripRef
import com.ksjd.testem.live.TripStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The community buttons follow the predicted position, so they move on without taps. */
class TripProgressTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    // Boarding at stop 1 (19:35), then 20:00, then 20:35 where the rider gets off.
    private val stops = listOf(0L, 35L, 60L, 95L).mapIndexed { i, m -> TripStop("S$i", i + 1, i + 1, t0 + m * min, null) }
    private val trip = TrackedTrip(TripRef("2", 0, "", 0, "S3"), boardingPlatformIds = listOf(2), alightOrder = 4)
    private fun at(ms: Long, delaySeconds: Int? = null) = TripProgress.from(TripDetail("2", "S3", stops, delaySeconds), trip, ms)

    @Test
    fun earlyArrivalCountsAsOnTime_soTheRiderStaysAtTheStopUntilDeparture() {
        // "Bus is here" 6 minutes before departure is reported as delay 0 (it waits).
        val waiting = at(t0 + 29 * min, delaySeconds = 0)
        assertFalse(waiting.onBoard)
        assertEquals(1, waiting.boardingIndex)
        // Still waiting a moment before the timetable departure: "Bus is leaving" stays offered.
        assertFalse(at(t0 + 35 * min + 20_000, delaySeconds = 0).onBoard)
    }

    @Test
    fun aUsualDelayIsNotCountedOn_aKnownOneIs() {
        val due = t0 + 35 * min
        // EM-0096: usually 13 min late, nothing known: it can still come on time.
        assertEquals(due, TripProgress.earliestMs(due + 13 * min, due, 780, 780, positionKnown = false))
        assertEquals(due + 13 * min, TripProgress.earliestMs(due + 13 * min, due, 780, 0, positionKnown = true))
        // Usually 3 min early, nothing known: that counts.
        assertEquals(due - 3 * min, TripProgress.earliestMs(due - 3 * min, due, -180, -180, positionKnown = false))
    }

    @Test
    fun aMeasuredDelayReplacesTheUsualOneAtEveryStop() {
        val usual = TripDetail("2", "S3", stops, 780, history = TripHistory(780, 5, 5, "workday", listOf(780, 780, 780, 780)),
            positionSource = PositionSource.History)
        assertFalse(TripProgress.from(usual, trip, t0 + 40 * min).onBoard)
        // EM-0096: the phone measured 5 min late: 5 past the stop's time it's on the bus, whatever the prediction said.
        assertTrue(TripProgress.from(usual.withDelay(300), trip, t0 + 41 * min).onBoard)
    }

    @Test
    fun withoutAnyTap_theNextStopTakesOverOnItsOwn() {
        val justLeft = at(t0 + 36 * min, delaySeconds = 0)
        assertTrue(justLeft.onBoard)
        assertEquals(2, justLeft.nextIndex) // "At S2"
        val later = at(t0 + 61 * min, delaySeconds = 0)
        assertEquals(3, later.nextIndex)     // forgot to mark S2: now "At S3"
        assertTrue(later.nextIsAlight)
    }

    @Test
    fun aReportedDelayShiftsWhichStopIsNext() {
        // 10 minutes late: at 20:05 the bus has not reached S2 (due 20:10 now).
        assertEquals(2, at(t0 + 65 * min, delaySeconds = 600).nextIndex)
        assertEquals(3, at(t0 + 65 * min, delaySeconds = 0).nextIndex)
    }

    @Test
    fun onBoardThePhoneSaysWhichStopIsNext() {
        fun riding(ms: Long, index: Float, standingAt: Int? = null) =
            TripProgress.from(TripDetail("2", "S3", stops, 0), trip, ms, standingAt = standingAt, riderIndex = index)
        // By the clock the bus is arriving at S2 (due in 30 s), so "next" would already be S3; the phone is half way there.
        assertEquals(3, at(t0 + 60 * min - 30_000, delaySeconds = 0).shownNextIndex)
        assertEquals("S2", riding(t0 + 60 * min - 30_000, 1.5f).nextStopName)
        // By the clock the bus left S1 a moment ago and S2 is next; the phone is already past S2: S3, the rider's stop.
        val ahead = riding(t0 + 36 * min, 2.3f)
        assertEquals("S3", ahead.nextStopName)
        assertEquals(ahead.alightIndex, ahead.shownNextIndex)  // "get off at the next stop" goes out now
        // Standing at S2: it is "now", S3 is next.
        assertEquals("S3", riding(t0 + 60 * min, 2.0f, standingAt = 2).nextStopName)
        assertEquals("S2", riding(t0 + 60 * min, 2.0f, standingAt = 2).currentStopName)
    }

    @Test
    fun waitingAtTheirStopTheRiderDoesNotPutTheBusThere() {
        // EM-0107: the bus is due at S0 in 5 min; the phone stands at S1, where the rider gets on.
        val waiting = TripProgress.from(TripDetail("2", "S3", stops, null), trip, t0 - 5 * min, standingAt = 1)
        assertEquals(0, waiting.shownNextIndex)
        // Once the bus is due there, the stop is "now" as before.
        assertEquals("S1", TripProgress.from(TripDetail("2", "S3", stops, null), trip, t0 + 35 * min - 20_000, standingAt = 1).currentStopName)
    }
}
