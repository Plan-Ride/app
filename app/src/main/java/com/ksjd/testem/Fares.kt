package com.ksjd.testem

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a journey costs and whether the rider's credit covers it. The prices are the carriers' own price lists, kept in
 * emHUB (GET /api/v1/fares, set in its dashboard); each planner leg says which tariff it goes by. All amounts in cents,
 * paid by transport card (the card price: that is what the credit pays).
 */
data class Tariff(
    val id: String,
    /** "MHD Zvolen", "MHD Banská Bystrica", "IDS BBSK": a city tariff's town is its name after "MHD ". */
    val name: String = "",
    /** One price for a ride (city buses); else the price goes by tariff units (IDS BBSK). */
    val flat: Boolean,
    /** Flat: a card ticket lasts this long; a change within it costs [transferCents] (0: any number of changes). */
    val transferMinutes: Int = 0,
    val transferCents: Int = 0,
    /** Flat: category -> cents. */
    val prices: Map<String, Int?> = emptyMap(),
    /** Units: category -> "full", "discount" or "special". */
    val columns: Map<String, String> = emptyMap(),
    val special: Int? = null,
    /** Units: (up to this many units, full, discounted). */
    val bands: List<Triple<Int, Int?, Int?>> = emptyList()
) {
    fun price(category: String, units: Int? = null): Int? = when {
        flat -> prices[category] ?: prices["full"]
        columns[category] == "special" -> special
        units == null -> null
        else -> bands.firstOrNull { units <= it.first }?.let { if (columns[category] == "discount") it.third else it.second }
    }
}

/**
 * EM-0110: a city ticket bought on the card shortly before (from the account's history): its change can still be used,
 * so the next city bus costs the change's price (Banská Bystrica 0,10 €, Zvolen nothing) instead of a new ticket.
 */
data class HeldTicket(val tariffId: String, val boughtMs: Long, val changed: Boolean)

/** What a journey takes from each credit. [unknown]: a bus on it has no price, so the sums are a minimum. */
data class JourneyCost(val sadzvCents: Int, val idsCents: Int, val unknown: Boolean, val train: Boolean = false,
                       /** A bus on it is covered by a valid season ticket. */ val covered: Boolean = false) {
    val totalCents: Int get() = sadzvCents + idsCents
}

object Fares {
    const val FULL = "full"

    /** The price lists, from the phone's copy at once and from emHUB when that copy is older than a day. */
    val tariffs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Tariff>?>(null)

    suspend fun refresh(context: android.content.Context) {
        val prefs = CredentialsManager(context)
        val saved = prefs.getFares()
        if (tariffs.value == null) tariffs.value = saved?.first?.let(::parse)
        if (saved != null && System.currentTimeMillis() - saved.second < 24 * 3_600_000L && tariffs.value != null) return
        if (!com.ksjd.testem.hub.HubClient.isConfigured) return
        val fresh = runCatching { com.ksjd.testem.hub.HubClient.fares() }.getOrNull() ?: return
        parse(fresh)?.let { tariffs.value = it; prefs.saveFares(fresh) }
    }

    fun parse(json: String): Map<String, Tariff>? = runCatching {
        fun cents(o: JsonObject, key: String): Int? =
            o.getAsJsonArray(key)?.get(0)?.takeIf { it.isJsonPrimitive }?.asInt   // [card, cash]: the card price
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("tariffs").map { it.asJsonObject }.associate { t ->
            val id = t.get("id").asString
            id to if (t.get("kind").asString == "flat") Tariff(
                id, name = t.get("name")?.asString.orEmpty(), flat = true,
                transferMinutes = t.get("transferMinutes")?.asInt ?: 0,
                transferCents = t.get("transferCents")?.asInt ?: 0,
                prices = t.getAsJsonObject("prices").let { p -> p.keySet().associateWith { cents(p, it) } }
            ) else Tariff(
                id, flat = false,
                columns = t.getAsJsonObject("as")?.let { a -> a.keySet().associateWith { a.get(it).asString } }.orEmpty(),
                special = cents(t, "special"),
                bands = t.getAsJsonArray("bands").map { it.asJsonObject }.map { b -> Triple(b.get("upTo").asInt, cents(b, "full"), cents(b, "discount")) }
            )
        }
    }.getOrNull()

    /**
     * The rider's category from what the accounts call it: the card's type at SAD Zvolen ("Žiak/Študent do 26 rokov")
     * or the name of an IDS BBSK ticket ("Zľavnené cestovné, žiak a študent 18-26 r., IDS Sieťovka 365 dní").
     */
    fun category(texts: List<String>): String {
        val t = texts.joinToString(" ").lowercase()
        return when {
            "sprievod" in t -> "companion"
            "ťzp" in t || "tzp" in t || "zdravotn" in t -> "ztp"
            "jansk" in t || "darc" in t -> "donor"
            "žiak" in t || "ziak" in t || "študent" in t || "student" in t -> "student"
            "dieťa" in t || "deti" in t || "mladistv" in t -> "child"
            "senior" in t || "dôchod" in t || "dochod" in t -> "senior"
            else -> FULL
        }
    }

    /**
     * What [segments] cost with the transport cards. [cityPass] / [regionPass]: a valid season ticket covers the city
     * buses / the IDS BBSK buses, so they cost nothing.
     * City buses: a card ticket lasts [Tariff.transferMinutes], and a change within it costs [Tariff.transferCents]
     * (Zvolen: nothing, any number of changes; Banská Bystrica: 0,10 € for the first change, then a new ticket).
     */
    fun cost(tariffs: Map<String, Tariff>, segments: List<TimetableSegment>, category: String, cityPass: Boolean, regionPass: Boolean,
             /** EM-0110: a city ticket from the card's history whose change may still be used. */ held: HeldTicket? = null): JourneyCost {
        var carried = held
        var sadzv = 0
        var ids = 0
        var unknown = false
        var train = false
        var covered = false
        var ticket: Triple<String, Int, Boolean>? = null   // city ticket: tariff, minute it was bought, a change already used
        var units = 0                                      // IDS BBSK buses one after another ride on one ticket
        var region: Tariff? = null
        fun closeRegion() {
            val t = region ?: return
            // ponytail: the units of the legs added up stand in for the units from the first stop to the last (the same
            // along one road, a little more on a detour); exact needs the zones of the whole ride from emHUB.
            t.price(category, units)?.let { ids += it } ?: run { unknown = true }
            region = null
            units = 0
        }
        segments.forEach { s ->
            if (s.group == "rail") {
                // 2.18.0: train fares aren't in emHUB's price lists: the price is the buses', "+ train".
                closeRegion()
                train = true
                return@forEach
            }
            val tariff = s.tariff?.let { tariffs[it] }
            // SAD Zvolen's long-distance lines (not 060xxx) aren't IDS BBSK, whatever emHUB guessed from the operator.
            val known = tariff != null && (tariff.flat || WalletRule.forLine(s.line, s.operatorName) == Wallet.IDS)
            if (!known) {
                closeRegion()
                unknown = true
                return@forEach
            }
            tariff!!
            if (!tariff.flat) {
                if (regionPass) { covered = true; return@forEach }
                if (tariff.columns[category] != "special" && s.units == null) unknown = true
                if (region != null && region!!.id != tariff.id) closeRegion()
                region = tariff
                units += s.units ?: 0
                return@forEach
            }
            closeRegion()
            if (cityPass) { covered = true; return@forEach }
            val at = minuteOfDay(s.departureTime)
            // EM-0110: the first city bus of the journey may ride on the ticket bought before it (within its time).
            val leaves = epochMs(s)
            val fromBefore = carried?.takeIf { ticket == null && it.tariffId == tariff.id && tariff.transferMinutes > 0 && leaves != null &&
                leaves - it.boughtMs in 0..tariff.transferMinutes * 60_000L && (tariff.transferCents == 0 || !it.changed) }
            carried = null
            if (fromBefore != null) {
                sadzv += tariff.transferCents
                val bought = java.time.Instant.ofEpochMilli(fromBefore.boughtMs).atZone(SK_ZONE)
                ticket = Triple(tariff.id, bought.hour * 60 + bought.minute, tariff.transferCents > 0)
                return@forEach
            }
            val held = ticket?.takeIf { (id, since, used) ->
                id == tariff.id && tariff.transferMinutes > 0 && (at - since + 1440) % 1440 <= tariff.transferMinutes &&
                    (tariff.transferCents == 0 || !used)
            }
            if (held != null) {
                sadzv += tariff.transferCents
                ticket = held.copy(third = true)
            } else {
                tariff.price(category)?.let { sadzv += it } ?: run { unknown = true }
                ticket = Triple(tariff.id, at, false)
            }
        }
        closeRegion()
        return JourneyCost(sadzv, ids, unknown, train, covered)
    }

    /** When a leg leaves (its date and time in Slovakia), null if either is missing. */
    private fun epochMs(s: TimetableSegment): Long? = runCatching {
        java.time.LocalDate.parse(s.serviceDate).atStartOfDay(SK_ZONE).plusMinutes(minuteOfDay(s.departureTime).toLong()).toInstant().toEpochMilli()
    }.getOrNull()

    /** EM-0110: a city bus the rider rode (followed in the app): its town ("Zvolen") and when it left their stop. */
    data class CityRide(val town: String, val boardedMs: Long)

    /** The town of a city bus boarded at [stopName] ("Banská Bystrica, Námestie slobody"), null for any other bus. */
    fun cityTown(line: String, stopName: String): String? {
        // Regional SAD lines are six digits (060411), trains have letters; city buses and trolleybuses are short numbers.
        // A train is its kind and number ("R 842", EM-0119): a word in front counts only when it is "Bus".
        val words = line.trim().split(" ")
        if ((words.size > 1 && words[0] != "Bus") || !Regex("""\d{1,3}[A-Z]?""").matches(words.last())) return null
        return listOf("Zvolen", "Banská Bystrica").firstOrNull { stopName.startsWith("$it,") }
    }

    /**
     * EM-0110: the city ticket still in its time, from the city buses the rider followed (the app knows when each left
     * their stop; the card's history arrives too late). Replayed by the tariff's rules: a ride within the ticket's time is
     * its change (Banská Bystrica one, at 0,10 €; Zvolen any number, free), else a new ticket. Null when there is none.
     */
    fun heldTicket(tariffs: Map<String, Tariff>, rides: List<CityRide>, now: Long = System.currentTimeMillis()): HeldTicket? {
        val byTown = tariffs.values.filter { it.flat && it.transferMinutes > 0 && it.name.startsWith("MHD ") }
            .associateBy { it.name.removePrefix("MHD ").trim() }
        var ticket: HeldTicket? = null
        rides.sortedBy { it.boardedMs }.forEach { r ->
            val t = byTown[r.town] ?: return@forEach
            val current = ticket
            ticket = if (current != null && current.tariffId == t.id && r.boardedMs - current.boughtMs <= t.transferMinutes * 60_000L &&
                (t.transferCents == 0 || !current.changed)) current.copy(changed = t.transferCents > 0)
            else HeldTicket(t.id, r.boardedMs, false)
        }
        val last = ticket ?: return null
        val minutes = tariffs[last.tariffId]?.transferMinutes ?: return null
        return last.takeIf { now - it.boughtMs <= minutes * 60_000L }
    }

    private fun minuteOfDay(clock: String): Int =
        clock.split(":").let { (it.getOrNull(0)?.trim()?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0) }
}
