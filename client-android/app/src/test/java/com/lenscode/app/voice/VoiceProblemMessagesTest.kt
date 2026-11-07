package com.lenscode.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProblemMessagesTest {
    @Test
    fun `a missing OpenRouter key says where to add it`() {
        val msg = commandKeyMissingMessage("http://h:8137/setup")
        assertTrue(msg.contains("OpenRouter API key"))
        assertTrue(msg.contains("http://h:8137/setup"))
    }

    @Test
    fun `a failed routing call names the command and the cause`() {
        val msg = commandRoutingFailedMessage("open the diff", IllegalStateException("OpenRouter HTTP 404: no such model"), "http://h/setup")
        assertTrue(msg.contains("\"open the diff\""))
        assertTrue(msg.contains("OpenRouter HTTP 404: no such model"))
    }
}
