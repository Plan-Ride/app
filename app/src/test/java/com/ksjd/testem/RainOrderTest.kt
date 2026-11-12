package com.ksjd.testem

import org.junit.Assert.assertEquals
import org.junit.Test

class RainOrderTest {
    private fun c(id: String, leaves: String, arrives: String, walk: Int) = TimetableConnection(
        id, leaves, arrives, "", listOf(TimetableSegment(line = "Bus 1", operatorName = "", departureTime = leaves, departureStop = "A",
            arrivalTime = arrives, arrivalStop = "B", serviceDate = "2026-10-29")), walk
    )

    @Test
    fun aWalkSinksABusOnlyBelowDrierOnesArrivingSoonAfter() {
        // EM-0081: the 7:32 with a 6-minute walk was listed below an 8:50 bus.
        val order = rainOrder(listOf(c("walk", "7:32", "7:54", 6), c("dry", "7:40", "8:05", 0), c("late", "8:50", "9:33", 0)))
        assertEquals(listOf("dry", "walk", "late"), order.map { it.id })
    }

    @Test
    fun pastMidnightArrivesTheNextDay() {
        assertEquals(c("x", "23:50", "0:10", 0).arrivalMinute!! - c("y", "23:50", "23:55", 0).arrivalMinute!!, 15L)
    }
}
