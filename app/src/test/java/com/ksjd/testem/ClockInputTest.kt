package com.ksjd.testem

import com.ksjd.testem.ui.screens.clockInput
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockInputTest {
    @Test
    fun digitsGetTheColon() {
        assertEquals("12:16", clockInput("1216"))
        assertEquals("9:16", clockInput("916"))
        assertEquals("12", clockInput("12"))
        assertEquals("12:16", clockInput("12.16"))
        assertEquals("12:16", clockInput("12:167"))
    }
}
