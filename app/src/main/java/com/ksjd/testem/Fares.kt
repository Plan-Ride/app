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

/** What a journey takes from each credit. [unknown]: a bus on it has no price, so the sums are a minimum. */
data class JourneyCost(val sadzvCents: Int, val idsCents: Int, val unknown: Boolean) {
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
                id, flat = true,
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
    fun cost(tariffs: Map<String, Tariff>, segments: List<TimetableSegment>, category: String, cityPass: Boolean, regionPass: Boolean): JourneyCost {
        var sadzv = 0
        var ids = 0
        var unknown = false
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
                if (regionPass) return@forEach
                if (tariff.columns[category] != "special" && s.units == null) unknown = true
                if (region != null && region!!.id != tariff.id) closeRegion()
                region = tariff
                units += s.units ?: 0
                return@forEach
            }
            closeRegion()
            if (cityPass) return@forEach
            val at = minuteOfDay(s.departureTime)
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
        return JourneyCost(sadzv, ids, unknown)
    }

    private fun minuteOfDay(clock: String): Int =
        clock.split(":").let { (it.getOrNull(0)?.trim()?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0) }
}
