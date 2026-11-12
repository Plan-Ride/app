package com.ksjd.testem

import com.ksjd.testem.offline.OfflineTimetables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfflineSearchTest {
    private val day = LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(SK_ZONE).toEpochSecond()
    private fun s(h: Int, m: Int) = h * 3600 + m * 60

    // Stops: 0 A, 1 X, 2 Y, 3 B. Line 1: A 8:00 -> X 8:10. Line 2: X 8:11 -> B 8:30 (too tight a change).
    // Line 3: X 8:15 -> Y 8:20. Line 4: Y 8:25 -> B 8:40. Line 5: A 9:00 -> B 9:30 direct. Line 6 runs only tomorrow.
    private val pack = OfflineTimetables.Pack(
        version = 1, from = day,
        ids = arrayOf("a", "x", "y", "b"), names = arrayOf("A", "X", "Y", "B"),
        lines = arrayOf("1", "2", "3", "4", "5", "6"), headsigns = Array(6) { "" }, operators = Array(6) { "" },
        stops = arrayOf(intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(0, 3), intArrayOf(0, 3)),
        times = arrayOf(intArrayOf(s(8, 0), s(8, 10)), intArrayOf(s(8, 11), s(8, 30)), intArrayOf(s(8, 15), s(8, 20)),
            intArrayOf(s(8, 25), s(8, 40)), intArrayOf(s(9, 0), s(9, 30)), intArrayOf(s(7, 0), s(7, 5))),
        days = intArrayOf(1, 1, 1, 1, 1, 2)
    )

    @Test fun typedNamesFindStationsOffline() {
        // EM-0098: nothing offline matched unless typed exactly as named.
        val bb = OfflineTimetables.Pack(1, day, arrayOf("s1", "s2", "s3"), arrayOf("Banská Bystrica,,Nám.SNP", "Banská Bystrica,,AS", "Zvolen,,AS"),
            arrayOf("1"), arrayOf(""), arrayOf(""), arrayOf(intArrayOf(0, 1)), arrayOf(intArrayOf(s(8, 0), s(8, 10))), intArrayOf(1),
            lats = doubleArrayOf(48.73, 48.74, 48.57), lons = doubleArrayOf(19.15, 19.16, 19.12))
        assertEquals(listOf("s1"), OfflineTimetables.suggestIn(listOf(bb), "nam snp").map { it.value })
        assertEquals(listOf("s2", "s1"), OfflineTimetables.suggestIn(listOf(bb), "banska b").map { it.value })
        assertEquals("s3", OfflineTimetables.nearest(48.571, 19.121, 1500, listOf(bb))?.value)
        assertEquals(null, OfflineTimetables.nearest(48.0, 19.0, 1500, listOf(bb)))
        assertEquals("48.73", OfflineTimetables.suggestIn(listOf(bb), "snp").first().coorX)
        assertEquals("8:00", OfflineTimetables.search(null, "nam snp", null, "bystrica as", at(7, 0), 1, packs = listOf(bb))!!.first().departureTime)
    }

    @Test fun twoChangesWhenTheQuickOneIsTooTight() {
        val found = OfflineTimetables.searchPack(pack, setOf(0), setOf(3), at(7, 50), changeMin = 2)
        assertEquals(listOf("Bus 1", "Bus 3", "Bus 4"), found.first().segments.map { it.line })
        assertEquals("8:40", found.first().arrivalTime)
        // The next one is the direct bus at 9:00, then tomorrow's 7:00.
        assertEquals(listOf("9:00", "7:00"), found.drop(1).map { it.departureTime })
    }

    @Test fun aOneMinuteChangeIsFineWhenAllowed() {
        val found = OfflineTimetables.searchPack(pack, setOf(0), setOf(3), at(7, 50), changeMin = 1)
        assertEquals(listOf("Bus 1", "Bus 2"), found.first().segments.map { it.line })
    }

    @Test fun nothingBackwardsOrBeforeTheTime() {
        assertTrue(OfflineTimetables.searchPack(pack, setOf(3), setOf(0), at(7, 0), changeMin = 2).isEmpty())
        assertEquals("7:00", OfflineTimetables.searchPack(pack, setOf(0), setOf(3), at(9, 1), changeMin = 2).first().departureTime)
    }

    // 2.17.2: line 7 (A 10:00 -> X 10:10) goes on as line 8 (X 10:12 -> B 10:40): riders stay on, so a 2-minute gap is
    // no change even with 5 minutes asked for changes. Line 9 (X 10:30 -> B 10:50) would be a real change.
    private val through = OfflineTimetables.Pack(
        version = 1, from = day,
        ids = arrayOf("a", "x", "b"), names = arrayOf("A", "X", "B"),
        lines = arrayOf("7", "8", "9"), headsigns = Array(3) { "" }, operators = Array(3) { "" },
        stops = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(1, 2)),
        times = arrayOf(intArrayOf(s(10, 0), s(10, 10)), intArrayOf(s(10, 12), s(10, 40)), intArrayOf(s(10, 30), s(10, 50))),
        days = intArrayOf(1, 1, 1),
        continuesFrom = mapOf(1 to listOf(0 to 1))
    )

    @Test fun aBusGoingOnAsAnotherLineIsOneBus() {
        val found = OfflineTimetables.searchPack(through, setOf(0), setOf(2), at(9, 50), changeMin = 5).first()
        assertEquals(listOf("Bus 7"), found.segments.map { it.line })
        assertEquals("10:40", found.arrivalTime)
        assertEquals("B", found.segments.single().arrivalStop)
        assertEquals(listOf("8" to "X"), found.segments.single().goesOnAs)
    }

    @Test fun notOnADayItDoesnt() {
        // Every trip runs today and tomorrow (days 3), the bus goes on only today (1).
        val tomorrow = OfflineTimetables.Pack(through.version, through.from, through.ids, through.names, through.lines,
            through.headsigns, through.operators, through.stops, through.times, intArrayOf(3, 3, 3), mapOf(1 to listOf(0 to 1)))
        val found = OfflineTimetables.searchPack(tomorrow, setOf(0), setOf(2), day.plusDays(1).atTime(9, 50).atZone(SK_ZONE).toEpochSecond(), changeMin = 5).first()
        assertEquals(listOf("Bus 7", "Bus 9"), found.segments.map { it.line })
    }
}
