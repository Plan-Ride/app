package com.ksjd.testem

import com.ksjd.testem.p2p.P2pPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.19.0: packets signed by emHUB's Python signer (a test key) are read here; anything changed, old or unsigned is not. */
class P2pPackTest {
    private val testKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9ycBkUUmjSsHQqQXEbDOI9XN4PGaC6WazxpIaidm+d3Q7CG/ddqzCyMAirQTGTvJLjrPkL8sIaIKV91G5g+VzA=="
    // The app's keys (emHUB's and the spare) with the test key as the "spare": either key is enough.
    private val keys = listOf(P2pPack.HUB_KEYS[0], testKey)
    private fun bytes(b64: String) = java.util.Base64.getDecoder().decode(b64)
    private val buses = bytes("eNoVjF0LgjAYhf/Lez1om6k58CLSMMLoyzJcF6Urc5HmSqXovzfPzXN4OJwvVMDgy6HhwAjiIDU5nDnofnr10nZMC/fRptYiSThchMjYkDKKqUUINfXcxiYyEKEYGcfjDxAofRz6q8ksuBXpWw7WZnDtyjB91lR19Bo5npDtfdPmTmh7cZ0/4+lofBtvSWEMDgvc4Pildsv7pJLWfJ9/cPbxy4u1fOzjNBLqVBxa14XfHyAvOSo=")
    private val board = bytes("eNpFj91ugkAQhV+FzK0k7i6CSNILIE1DSdP6U8W6XKBdLWEFZVmiNT6MD9Ir0/fqQki6N7PzzZlzMhc4gAMXCjUFB+sUMlUpCArqn1QNHI5MCzVPEVG144+64CzXNXfa6nIFVysK8/sPl1nSMp8XQpaMQhyrruwUnhSahTDCrcZLcpHdb5p3FlWZbpLOsEs0VCImTeqWsU9nQByCiIUxMZXGiHVlN9HsAWmtwuL3lm7Y//ZIbeeSczU7KYri+Ao6CHXsy+O7H4z9cpfjqDgcbXI6vhkmfw3T89OkN+/vF7PRsDJlP1q7MnjGchfs3MBGtcCLXt8dL6soQ+tv2997eGvM8gwdUxaK8deULc3KfYDrH75mZIY=")
    private val at = 1_795_600_000_000L

    @Test fun readsTheBusesPacketWithSeats() {
        val pack = P2pPack.read(buses, at + 60_000L, keys)
        assertNotNull(pack)
        assertEquals(at, pack!!.atMs)
        assertEquals(listOf(P2pPack.Bus("feed:42:20261125", 705, 3, 120, 3)), pack.buses)
        assertNull("a stop packet is not a buses packet", P2pPack.read(board, at, keys))
    }

    @Test fun readsTheStopPacket() {
        val b = P2pPack.readBoard(board, at, keys)
        assertNotNull(b)
        assertEquals("Zvolen, AS", b!!.stop)
        assertEquals(listOf("Výluka" to "Closure"), b.notices)
        assertEquals(
            listOf(P2pPack.Departure("Bus 60101", "Banská Bystrica, AS", at + 300_000L, 120, "feed:42:20261125", 3),
                P2pPack.Departure("R 842", "Košice", at + 900_000L, null, "x", 0)),
            b.departures
        )
        // A heard bus can be followed: emHUB's trip from its stop there, at its time.
        val ref = b.departures[0].trip()!!.ref
        assertEquals("hub:trip?id=feed%3A42%3A20261125&from=3", ref.scheduleUrl)
        assertEquals("Bus 60101", ref.line)
        assertNull("a buses packet is not a stop packet", P2pPack.readBoard(buses, at, keys))
    }

    @Test fun refusesUnknownKeysChangesAndOldPackets() {
        assertNull("signed by a key the app doesn't carry", P2pPack.read(buses, at))
        assertNull("too old", P2pPack.read(buses, at + P2pPack.MAX_AGE_MS + 1, keys))
        // The same packet with the delay changed: the signature no longer fits.
        val text = java.util.zip.InflaterInputStream(buses.inputStream()).readBytes().toString(Charsets.UTF_8).replace(",120,", ",900,")
        val forged = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
        assertNull("changed", P2pPack.read(forged, at, keys))
        assertNull("garbage", P2pPack.read(byteArrayOf(1, 2, 3), at, keys))
    }

    @Test fun aStopsCodeIsEmHubs() {
        // 2.19.0: the code a question carries; emHUB's test_hub.py checks the same stop gives 0x234534.
        assertEquals(0x234534, P2pPack.code("banska bystrica plazove kupalisko"))
    }
}
