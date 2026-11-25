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
    private val board = bytes("eNpFj8FugkAQhl+FzFUSWUCKJh7AErXgQQlN1eWwAUoJGyi7Cy0xPowP0pPpe3UlJJ3LzPzz/TOZC3zCAi4YOgwLpGIoZcbAMciaiIf4NJ9Z2iOkwsUwPnU1zSpVccKBq6R4PmN4vf/QtiSDtqI1b1mGIY5lx0bCbbliaUhDA+OSipf3m+L2XLAiIePC8aIhLyJdi1XpOyi2qQ8ev/69FUn2j80lVrWUxvEVVODym523X23XuRGeUhah/do1N0b50VjhhJSiOwZi2rCJHTyz0LR8UX85hZMcjruepuW0T7SmJv67N/MmqcjfaNVEEbL1b/YSbEgebp3lEq5/NTBdgQ==")
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
            listOf(P2pPack.Departure("Bus 60101", "Banská Bystrica, AS", at + 300_000L, 120), P2pPack.Departure("R 842", "Košice", at + 900_000L, null)),
            b.departures
        )
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
}
