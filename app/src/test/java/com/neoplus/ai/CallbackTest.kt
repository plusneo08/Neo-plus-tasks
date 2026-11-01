package com.neoplus.ai

import com.neoplus.ai.calls.CallbackScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackTest {
    private val day = 24L * 60 * 60 * 1000
    @Test fun nextDailyIsTomorrowWhenOnTime() {
        assertEquals(1000L + day, CallbackScheduler.nextDaily(1000L, 1500L))
    }
    @Test fun nextDailySkipsMissedDays() {
        val next = CallbackScheduler.nextDaily(0L, 3 * day + 5)
        assertEquals(4 * day, next)
        assertTrue(next > 3 * day + 5)
    }
}
