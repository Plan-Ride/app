package com.ksjd.testem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The journey's price from emHUB's price lists (as GET /api/v1/fares sends them). */
class FaresTest {
    private val json = """{"tariffs": [
        {"id": "mhd-zv", "kind": "flat", "transferMinutes": 45, "transferCents": 0,
         "prices": {"full": [50, 60], "student": [27, 30], "ztp": [15, 20]}},
        {"id": "mhd-bb", "kind": "flat", "transferMinutes": 45, "transferCents": 10,
         "prices": {"full": [50, 100], "student": [30, 50], "donor": [15, null]}},
        {"id": "ids-bbsk", "kind": "units", "as": {"student": "discount", "ztp": "special"}, "special": [54, 60],
         "bands": [{"upTo": 4, "minutes": 60, "full": [41, 45], "discount": [41, 45]},
                   {"upTo": 25, "minutes": 120, "full": [99, 110], "discount": [90, 100]},
                   {"upTo": 35, "minutes": 150, "full": [131, 145], "discount": [120, 130]}]}]}"""
    private val tariffs = Fares.parse(json)!!

    private fun bus(line: String, operator: String, at: String, tariff: String?, units: Int? = null) =
        TimetableSegment(line, operator, at, "A", at, "B", tariff = tariff, units = units)

    private fun city(line: String, at: String, tariff: String = "mhd-zv") =
        bus("Bus $line", if (tariff == "mhd-zv") "MHD Zvolen" else "MHD Banská Bystrica", at, tariff)

    private fun region(at: String, units: Int?, line: String = "60101") = bus("Bus $line", "S.A.D. Zvolen a.s.", at, "ids-bbsk", units)

    private fun cost(vararg legs: TimetableSegment, category: String = "student", cityPass: Boolean = false, regionPass: Boolean = false) =
        Fares.cost(tariffs, legs.toList(), category, cityPass, regionPass)

    @Test
    fun theTableIsRead() {
        assertNotNull(tariffs["mhd-zv"])
        assertEquals(27, tariffs["mhd-zv"]!!.price("student"))
        assertEquals(50, tariffs["mhd-zv"]!!.price("senior"))          // not in the list: the full fare
        assertEquals(90, tariffs["ids-bbsk"]!!.price("student", 25))
        assertEquals(54, tariffs["ids-bbsk"]!!.price("ztp"))
        assertEquals(null, tariffs["ids-bbsk"]!!.price("full", 36))    // further than the price list goes
        assertEquals(null, Fares.parse("not json"))
    }

    @Test
    fun cityBusesAndTheirChanges() {
        assertEquals(JourneyCost(27, 0, false), cost(city("7", "13:00")))
        // Zvolen: a card ticket lasts 45 minutes with any number of changes.
        assertEquals(27, cost(city("7", "13:00"), city("8", "13:20"), city("9", "13:44")).sadzvCents)
        assertEquals(54, cost(city("7", "13:00"), city("8", "13:50")).sadzvCents)
        // Banská Bystrica: the first change within 45 minutes is 0,10, the next bus is a new ticket.
        assertEquals(40, cost(city("21", "13:00", "mhd-bb"), city("34", "13:20", "mhd-bb")).sadzvCents)
        assertEquals(70, cost(city("21", "13:00", "mhd-bb"), city("34", "13:20", "mhd-bb"), city("90", "13:30", "mhd-bb")).sadzvCents)
        // Over midnight.
        assertEquals(27, cost(city("7", "23:50"), city("8", "0:10")).sadzvCents)
    }

    @Test
    fun regionalBusesGoByUnits() {
        assertEquals(JourneyCost(0, 90, false), cost(region("7:00", 25)))
        // Two regional buses in a row ride on one ticket: 25 + 6 units.
        assertEquals(120, cost(region("7:00", 25), region("8:00", 6, "60103")).idsCents)
        // A city bus, then a regional one, then a city bus in Banská Bystrica: two credits.
        assertEquals(JourneyCost(57, 90, false), cost(city("7", "6:40"), region("7:00", 25), city("21", "7:40", "mhd-bb")))
        // ŤZP pays the special price, with or without units.
        assertEquals(54, cost(region("7:00", null), category = "ztp").idsCents)
    }

    @Test
    fun seasonTicketsAndUnknownPrices() {
        assertEquals(JourneyCost(0, 90, false, covered = true), cost(city("7", "6:40"), region("7:00", 25), cityPass = true))
        assertEquals(JourneyCost(27, 0, false, covered = true), cost(city("7", "6:40"), region("7:00", 25), regionPass = true))
        // No tariff from emHUB, no units, or a long-distance line of the same carrier: the price is a minimum.
        assertTrue(cost(city("7", "6:40"), bus("Bus 102401", "SAD Žilina a.s.", "7:00", null)).unknown)
        assertTrue(cost(region("7:00", null)).unknown)
        assertTrue(cost(region("7:00", 25, line = "602505")).unknown)
        assertFalse(cost(region("7:00", 25)).unknown)
        // 2.18.0: a train has no price here: the buses' price, marked "+ train", and not "from" (unknown).
        val withTrain = cost(TimetableSegment("R 845", "ŽSSK", "6:00", "A", "6:20", "B", group = "rail", vehicle = "rail"), city("7", "6:40"))
        assertEquals(JourneyCost(27, 0, false, train = true), withTrain)
    }

    @Test
    fun theCategoryComesFromTheAccounts() {
        assertEquals("student", Fares.category(listOf("Žiak/Študent do 26 rokov")))
        assertEquals("student", Fares.category(listOf("Zľavnené cestovné, žiak a študent 18-26 r., IDS Sieťovka 365 dní")))
        assertEquals("child", Fares.category(listOf("Zľavnené cestovné, mladistvý 16-18 r., IDS Sieťovka 30 dní")))
        assertEquals("ztp", Fares.category(listOf("Držiteľ preukazu ŤZP")))
        assertEquals("full", Fares.category(listOf("Public transport card")))
        assertEquals("full", Fares.category(emptyList()))
    }

    /** EM-0110: the city ticket bought before the journey, from the city buses the rider followed. */
    @Test
    fun aTicketBoughtBeforeCarriesItsChange() {
        val named = Fares.parse(json.replace("\"id\": \"mhd-zv\",", "\"id\": \"mhd-zv\", \"name\": \"MHD Zvolen\",")
            .replace("\"id\": \"mhd-bb\",", "\"id\": \"mhd-bb\", \"name\": \"MHD Banská Bystrica\","))!!
        val day = java.time.LocalDate.of(2026, 11, 24)
        fun ms(h: Int, m: Int) = day.atTime(h, m).atZone(SK_ZONE).toInstant().toEpochMilli()
        fun rode(town: String, h: Int, m: Int) = Fares.CityRide(town, ms(h, m))
        fun leg(at: String, tariff: String) = TimetableSegment("Bus 7", "MHD", at, "A", at, "B", tariff = tariff, serviceDate = day.toString())
        fun price(held: HeldTicket?, vararg legs: TimetableSegment) = Fares.cost(named, legs.toList(), "student", false, false, held).sadzvCents

        // Banská Bystrica: rode at 13:00, plans at 13:20: the next city bus is the change, 0,10.
        val held = Fares.heldTicket(named, listOf(rode("Banská Bystrica", 13, 0)), now = ms(13, 20))!!
        assertEquals(HeldTicket("mhd-bb", ms(13, 0), false), held)
        assertEquals(10, price(held, leg("13:30", "mhd-bb")))
        // ...only within its 45 minutes, and the bus after that change is a new ticket.
        assertEquals(30, price(held, leg("13:50", "mhd-bb")))
        assertEquals(40, price(held, leg("13:30", "mhd-bb"), leg("13:35", "mhd-bb")))
        // Rode twice already (the second was the change): a new ticket.
        val used = Fares.heldTicket(named, listOf(rode("Banská Bystrica", 13, 0), rode("Banská Bystrica", 13, 10)), now = ms(13, 20))!!
        assertTrue(used.changed)
        assertEquals(30, price(used, leg("13:30", "mhd-bb")))
        // A third ride after the change was a new ticket of its own, with its change unused.
        val third = Fares.heldTicket(named, listOf(rode("Banská Bystrica", 13, 0), rode("Banská Bystrica", 13, 10), rode("Banská Bystrica", 13, 15)), now = ms(13, 20))!!
        assertEquals(HeldTicket("mhd-bb", ms(13, 15), false), third)
        // Too old: nothing held.
        assertEquals(null, Fares.heldTicket(named, listOf(rode("Banská Bystrica", 13, 0)), now = ms(13, 50)))

        // Zvolen: the change costs nothing, any number of times, counted from the first ride.
        val zv = Fares.heldTicket(named, listOf(rode("Zvolen", 8, 0), rode("Zvolen", 8, 15)), now = ms(8, 20))!!
        assertEquals(HeldTicket("mhd-zv", ms(8, 0), false), zv)
        assertEquals(0, price(zv, leg("8:30", "mhd-zv")))
        // Another town's ticket doesn't help.
        assertEquals(27, price(held, leg("13:30", "mhd-zv")))

        // Which buses count: city buses and trolleybuses at a stop of either town, not regional lines or trains.
        assertEquals("Banská Bystrica", Fares.cityTown("6", "Banská Bystrica, Štadlerovo nábrežie"))
        assertEquals("Zvolen", Fares.cityTown("Bus 10", "Zvolen, Námestie SNP"))
        assertEquals(null, Fares.cityTown("060411", "Zvolen, AS"))
        assertEquals(null, Fares.cityTown("R 842", "Zvolen, osobná stanica"))
        assertEquals(null, Fares.cityTown("7", "Brezno, AS"))
    }
}
