package com.ksjd.testem.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 2.19.0: delays for phones without data. A phone with data fetches emHUB's signed packet of the live buses around it
 * and broadcasts it (Bluetooth LE, Google Nearby); a phone without data only listens and takes a packet only when emHUB's
 * signature checks out, so nobody nearby can pass on made-up delays, and the listener never says where it is going.
 *
 * Wire: deflate(JSON {"p": payload, "s": base64 DER signature}), payload {"v": 1, "at": epoch s, "b": [[hub trip id,
 * sadzv line id, sadzv trip number, delay s]]} (emHUB's p2p_wire).
 */
object P2pPack {
    /** emHUB's public key (X.509, P-256). Its private half lives only on emHUB (/var/lib/emhub/p2p-key). */
    const val HUB_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhwRkR/AHxFZlZp95i14wiw439qLkjyTz4VJ/NB6vCXWT9VPVvCZ/xMShdlhHYfubR5j1fHdBH2pRetDDBgzXFQ=="

    /** Older than this a packet says nothing useful about where a bus is now. */
    const val MAX_AGE_MS = 15 * 60_000L

    /** A bus in a packet: emHUB's trip id, or for a S.A.D. Zvolen city bus its sadzv line id and trip number. */
    data class Bus(val tripId: String, val lineId: Long, val tripNumber: Int, val delaySeconds: Int)
    data class Pack(val atMs: Long, val buses: List<Bus>)

    // okio's Base64: java.util.Base64 is missing on Android 7 (API 24-25).
    private fun b64(s: String): ByteArray = okio.ByteString.Companion.run { s.decodeBase64() }!!.toByteArray()

    /** The packet if it is emHUB's own, not too old and not from the future; else null. */
    fun read(wire: ByteArray, nowMs: Long, key: String = HUB_KEY): Pack? = runCatching {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(wire)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
            out.write(buffer, 0, n)
            if (out.size() > 64_000) return null // a deflate bomb
        }
        inflater.end()
        val outer = com.google.gson.JsonParser.parseString(out.toString(Charsets.UTF_8.name())).asJsonObject
        val payload = outer.get("p").asString
        val publicKey = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(b64(key)))
        val check = java.security.Signature.getInstance("SHA256withECDSA")
        check.initVerify(publicKey)
        check.update(payload.toByteArray(Charsets.UTF_8))
        if (!check.verify(b64(outer.get("s").asString))) return null
        val p = com.google.gson.JsonParser.parseString(payload).asJsonObject
        if (p.get("v").asInt != 1) return null
        val at = p.get("at").asLong * 1000L
        // A phone's clock can be a little off; a packet much older (or from the future) is not taken.
        if (nowMs - at > MAX_AGE_MS || at - nowMs > 5 * 60_000L) return null
        Pack(at, p.getAsJsonArray("b").map { row ->
            val r = row.asJsonArray
            Bus(r[0].asString, r[1].asLong, r[2].asInt, r[3].asInt)
        })
    }.getOrNull()
}

/** The delays heard from nearby phones, newest packet per bus. */
object P2pStore {
    data class Heard(val delaySeconds: Int, val atMs: Long)

    private val _heard = MutableStateFlow<Map<String, Heard>>(emptyMap())
    val heard: StateFlow<Map<String, Heard>> = _heard

    fun add(pack: P2pPack.Pack) {
        _heard.value = (_heard.value + pack.buses.flatMap { b ->
            val seen = Heard(b.delaySeconds, pack.atMs)
            listOfNotNull(b.tripId to seen, if (b.lineId > 0) "sadzv:${b.lineId}:${b.tripNumber}" to seen else null)
        }.filter { (k, v) -> (_heard.value[k]?.atMs ?: 0L) <= v.atMs })
            .filterValues { System.currentTimeMillis() - it.atMs <= P2pPack.MAX_AGE_MS }
    }

    /** What nearby phones said about a bus: by its timetable trip id, or its sadzv line and trip number. */
    fun about(ref: com.ksjd.testem.live.TripRef, nowMs: Long = System.currentTimeMillis()): Heard? {
        val tripId = com.ksjd.testem.TimetableService.parseTripUrl(ref.scheduleUrl)?.first
        val keys = listOfNotNull(tripId, if (ref.lineId > 0) "sadzv:${ref.lineId}:${ref.tripNumber}" else null)
        return keys.firstNotNullOfOrNull { _heard.value[it] }?.takeIf { nowMs - it.atMs <= P2pPack.MAX_AGE_MS }
    }
}
