package com.ksjd.testem.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 2.19.0: delays for phones without data. A phone with data fetches emHUB's signed packets and broadcasts them
 * (Bluetooth LE, Google Nearby); a phone without data listens, takes a packet only when emHUB's signature checks out, and
 * passes it on itself, so one phone with data reaches a whole bus. Nobody nearby can make up delays, and a listener never
 * says where it is going.
 *
 * Wire: deflate(JSON {"p": payload, "s": base64 DER signature}). Payloads (emHUB's p2p_wire, p2p_board_wire):
 * - buses {"v": 1, "k": "b", "at": epoch s, "r": [[hub trip id, sadzv line id, sadzv trip number, delay s, how full 0-3]]}
 * - stop {"v": 1, "k": "s", "at", "st": stop name, "n": [[notice sk, notice en]], "r": [[line, headsign, leaves epoch s, delay s or null]]}
 */
object P2pPack {
    /**
     * emHUB's public keys (X.509, P-256): its own (private half on emHUB, /var/lib/emhub/p2p-key) and a spare whose
     * private half is kept offline: if the first is ever lost or stolen, emHUB signs with the spare and apps keep working.
     */
    val HUB_KEYS = listOf(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhwRkR/AHxFZlZp95i14wiw439qLkjyTz4VJ/NB6vCXWT9VPVvCZ/xMShdlhHYfubR5j1fHdBH2pRetDDBgzXFQ==",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE6nhcom3zPrU9XRSbHcnHVUYXAAJm3oUrCatq3C+q2HrpvNP7uqcpTFnKUJjfbp3ZnGfhM9NMrD1kqOvUw0Y98w=="
    )

    /** Older than this a packet says nothing useful about where a bus is now. */
    const val MAX_AGE_MS = 15 * 60_000L

    /** A bus in a packet: emHUB's trip id, or for a S.A.D. Zvolen city bus its sadzv line id and trip number. */
    data class Bus(val tripId: String, val lineId: Long, val tripNumber: Int, val delaySeconds: Int, val crowd: Int = 0)
    data class Pack(val atMs: Long, val buses: List<Bus>)

    /** A heard departure; [tripId] and [seq] (its stop number here) let a phone without data follow it. */
    data class Departure(val line: String, val headsign: String, val leavesMs: Long, val delaySeconds: Int?, val tripId: String = "", val seq: Int = -1) {
        /** Following it: emHUB's trip (from its stop here), with the stops from the offline timetables until there is data. */
        fun trip(): com.ksjd.testem.live.TrackedTrip? {
            if (tripId.isBlank() || seq < 0) return null
            val local = java.time.Instant.ofEpochMilli(leavesMs).atZone(com.ksjd.testem.SK_ZONE)
            return com.ksjd.testem.live.TrackedTrip(
                com.ksjd.testem.live.TripRef(
                    line = line, lineId = 0L, routeNumber = "", tripNumber = 0, destination = headsign,
                    scheduleUrl = com.ksjd.testem.TimetableService.tripUrl(tripId, seq, null),
                    serviceDate = local.toLocalDate().toString(), plannedSecondOfDay = local.toLocalTime().toSecondOfDay()
                ),
                emptyList(), departsAtMs = leavesMs
            )
        }
    }
    /** A stop's next departures and its notices ([sk, en] each). */
    data class Board(val atMs: Long, val stop: String, val departures: List<Departure>, val notices: List<Pair<String, String>>)

    // okio's Base64: java.util.Base64 is missing on Android 7 (API 24-25).
    private fun b64(s: String): ByteArray = okio.ByteString.Companion.run { s.decodeBase64() }!!.toByteArray()

    /** Why the last packet was not taken, for the log ("not signed by Plan&Ride", "too old: 17 min"). */
    @Volatile var lastRefusal = ""
        private set

    private fun refuse(why: String): com.google.gson.JsonObject? { lastRefusal = why; return null }

    /** The payload if emHUB signed it (with either key), it is version 1 and neither too old nor from the future. */
    private fun payload(wire: ByteArray, nowMs: Long, keys: List<String>): com.google.gson.JsonObject? = runCatching {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(wire)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return refuse("cut short or not a packet")
            out.write(buffer, 0, n)
            if (out.size() > 64_000) return refuse("too large") // a deflate bomb
        }
        inflater.end()
        val outer = com.google.gson.JsonParser.parseString(out.toString(Charsets.UTF_8.name())).asJsonObject
        val text = outer.get("p").asString
        val signature = b64(outer.get("s").asString)
        val signed = keys.any { key ->
            runCatching {
                val publicKey = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(b64(key)))
                java.security.Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(text.toByteArray(Charsets.UTF_8))
                    verify(signature)
                }
            }.getOrDefault(false)
        }
        if (!signed) return refuse("not signed by Plan&Ride")
        val p = com.google.gson.JsonParser.parseString(text).asJsonObject
        if (p.get("v").asInt != 1) return refuse("version ${p.get("v")}")
        val at = p.get("at").asLong * 1000L
        // A phone's clock can be a little off; a packet much older (or from the future) is not taken.
        if (nowMs - at > MAX_AGE_MS) return refuse("too old: ${(nowMs - at) / 60_000} min (check the phone's clock)")
        if (at - nowMs > 5 * 60_000L) return refuse("from the future: ${(at - nowMs) / 60_000} min (check the phone's clock)")
        p
    }.getOrElse { refuse("not readable: ${it.javaClass.simpleName}") }

    /** A buses packet if it is emHUB's own and fresh; else null. */
    fun read(wire: ByteArray, nowMs: Long, keys: List<String> = HUB_KEYS): Pack? = payload(wire, nowMs, keys)
        ?.takeIf { (it.get("k")?.asString ?: "b") == "b" }?.let { p ->
            runCatching {
                Pack(p.get("at").asLong * 1000L, p.getAsJsonArray("r").map { row ->
                    val r = row.asJsonArray
                    Bus(r[0].asString, r[1].asLong, r[2].asInt, r[3].asInt, if (r.size() > 4) r[4].asInt else 0)
                })
            }.getOrNull()
        }

    /** A stop packet if it is emHUB's own and fresh; else null. */
    fun readBoard(wire: ByteArray, nowMs: Long, keys: List<String> = HUB_KEYS): Board? = payload(wire, nowMs, keys)
        ?.takeIf { it.get("k")?.asString == "s" }?.let { p ->
            runCatching {
                Board(
                    p.get("at").asLong * 1000L, p.get("st").asString,
                    p.getAsJsonArray("r").map { row ->
                        val r = row.asJsonArray
                        Departure(r[0].asString, r[1].asString, r[2].asLong * 1000L, r[3].takeUnless { it.isJsonNull }?.asInt,
                            if (r.size() > 5) r[4].asString else "", if (r.size() > 5) r[5].asInt else -1)
                    },
                    p.getAsJsonArray("n")?.map { n -> n.asJsonArray.let { it[0].asString to it[1].asString } }.orEmpty()
                )
            }.getOrNull()
        }
}

/** What was heard from nearby phones: the newest delay per bus, the newest stop board, and the packets to pass on. */
object P2pStore {
    data class Heard(val delaySeconds: Int, val atMs: Long, val crowd: Int = 0)

    private val _heard = MutableStateFlow<Map<String, Heard>>(emptyMap())
    val heard: StateFlow<Map<String, Heard>> = _heard
    private val _board = MutableStateFlow<P2pPack.Board?>(null)
    val board: StateFlow<P2pPack.Board?> = _board

    /** The newest packets heard (verified), passed on by this phone while it has no data. */
    @Volatile var busesWire: Pair<Long, ByteArray>? = null
        private set
    @Volatile var boardWire: Pair<Long, ByteArray>? = null
        private set

    /** For Settings: when this phone last heard a packet from a phone nearby, and last offered packets to others. */
    val lastHeardMs = MutableStateFlow(0L)
    val lastOfferedMs = MutableStateFlow(0L)

    /** Takes a received packet of either kind; true when it was emHUB's and fresh. */
    fun take(wire: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        val taken = takeOne(wire, nowMs)
        if (taken) lastHeardMs.value = nowMs
        return taken
    }

    private fun takeOne(wire: ByteArray, nowMs: Long): Boolean {
        P2pPack.read(wire, nowMs)?.let { pack ->
            add(pack)
            if ((busesWire?.first ?: 0L) < pack.atMs) busesWire = pack.atMs to wire
            return true
        }
        P2pPack.readBoard(wire, nowMs)?.let { board ->
            if ((_board.value?.atMs ?: 0L) < board.atMs) _board.value = board
            if ((boardWire?.first ?: 0L) < board.atMs) boardWire = board.atMs to wire
            return true
        }
        return false
    }

    fun add(pack: P2pPack.Pack) {
        _heard.value = (_heard.value + pack.buses.flatMap { b ->
            val seen = Heard(b.delaySeconds, pack.atMs, b.crowd)
            listOfNotNull(b.tripId to seen, if (b.lineId > 0) "sadzv:${b.lineId}:${b.tripNumber}" to seen else null)
        }.filter { (k, v) -> (_heard.value[k]?.atMs ?: 0L) <= v.atMs })
            .filterValues { System.currentTimeMillis() - it.atMs <= P2pPack.MAX_AGE_MS }
    }

    /** The stop board heard, while it is fresh. */
    fun boardNow(nowMs: Long = System.currentTimeMillis()): P2pPack.Board? =
        _board.value?.takeIf { nowMs - it.atMs <= P2pPack.MAX_AGE_MS }

    /** What nearby phones said about a bus: by its timetable trip id, or its sadzv line and trip number. */
    fun about(ref: com.ksjd.testem.live.TripRef, nowMs: Long = System.currentTimeMillis()): Heard? {
        val tripId = com.ksjd.testem.TimetableService.parseTripUrl(ref.scheduleUrl)?.first
        val keys = listOfNotNull(tripId, if (ref.lineId > 0) "sadzv:${ref.lineId}:${ref.tripNumber}" else null)
        return keys.firstNotNullOfOrNull { _heard.value[it] }?.takeIf { nowMs - it.atMs <= P2pPack.MAX_AGE_MS }
    }

    /** What nearby phones said about a S.A.D. Zvolen bus on a live board (its sadzv line id and trip number). */
    fun aboutSadzv(lineId: Long, tripNumber: Int, nowMs: Long = System.currentTimeMillis()): Heard? =
        _heard.value["sadzv:$lineId:$tripNumber"]?.takeIf { lineId > 0 && nowMs - it.atMs <= P2pPack.MAX_AGE_MS }

    /** Back online: what was heard is no longer needed (and not passed on). */
    fun clearWires() {
        busesWire = null
        boardWire = null
    }
}
