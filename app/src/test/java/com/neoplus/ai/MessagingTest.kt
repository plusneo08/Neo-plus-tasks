package com.neoplus.ai

import com.neoplus.ai.messaging.LangDetect
import com.neoplus.ai.messaging.Triage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingTest {
    @Test fun detectsLanguages() {
        assertEquals("sw", LangDetect.guess("Habari, asante sana rafiki"))
        assertEquals("ny", LangDetect.guess("Muli bwanji, zikomo kwambiri"))
        assertEquals("fr", LangDetect.guess("Bonjour, merci beaucoup"))
        assertEquals("pt", LangDetect.guess("Olá, obrigado, tudo bem"))
        assertEquals("en", LangDetect.guess("Please call me when you can"))
        assertEquals("?", LangDetect.guess("12345"))
    }
    @Test fun triage() {
        assertEquals("HIGH", Triage.rate("This is urgent, call me"))
        assertEquals("SENSITIVE", Triage.rate("Please send money to this account"))
        assertEquals("NORMAL", Triage.rate("See you at lunch"))
    }
    @Test fun allowListIsStrict() {
        assertFalse(Triage.allowed("", "Peter"))
        assertTrue(Triage.allowed("peter, John", "Peter"))
        assertFalse(Triage.allowed("Peter", "Peter Jr"))
    }
}
