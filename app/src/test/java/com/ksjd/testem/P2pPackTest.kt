package com.ksjd.testem

import com.ksjd.testem.p2p.P2pPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.19.0: a packet signed by emHUB's Python signer (test key) is read here; anything changed or old is not. */
class P2pPackTest {
    private val key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9ycBkUUmjSsHQqQXEbDOI9XN4PGaC6WazxpIaidm+d3Q7CG/ddqzCyMAirQTGTvJLjrPkL8sIaIKV91G5g+VzA=="
    private val wire = java.util.Base64.getDecoder().decode(
        "eNoVjEkLgkAYQP/Ldx5IJ0dJ6CAuLTjYokE5HhKnmsiF3FLxv6fv9HiHN0ABOgwMGga6jBjcq1m0FVGlmanEUwhDBg/OE13BOpawKsuYMECaRNASyViKohEQlNOK2oG5M45JunGvNOsz0rS+33HvLE6CWq39Snvrmf9UWh/4ZXGLDWEb34A63BOOsF0HExE4Zp5kXVo13cdU3rFm0rJW6mK79+gaxj+WgjXh"
    )
    private val at = 1_795_600_000_000L

    @Test fun readsEmHubsPacket() {
        val pack = P2pPack.read(wire, at + 60_000L, key)
        assertNotNull(pack)
        pack!!
        assertEquals(at, pack.atMs)
        assertEquals(listOf(P2pPack.Bus("feed:42:20261125", 705, 3, 120)), pack.buses)
    }

    @Test fun refusesAnotherKeyAChangeOrAnOldPacket() {
        assertNull("not emHUB's key", P2pPack.read(wire, at, P2pPack.HUB_KEY))
        assertNull("too old", P2pPack.read(wire, at + P2pPack.MAX_AGE_MS + 1, key))
        // The same packet with the delay changed: the signature no longer fits.
        val text = java.util.zip.InflaterInputStream(wire.inputStream()).readBytes().toString(Charsets.UTF_8).replace(",120]", ",900]")
        val forged = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
        assertNull("changed", P2pPack.read(forged, at, key))
        assertNull("garbage", P2pPack.read(byteArrayOf(1, 2, 3), at, key))
    }
}
