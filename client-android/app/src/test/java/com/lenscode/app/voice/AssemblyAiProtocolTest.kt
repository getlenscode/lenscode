package com.lenscode.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssemblyAiProtocolTest {
    @Test
    fun `the stream url carries format, model and the token but never an api key`() {
        val url = AssemblyAiProtocol.streamUrl(
            "wss://streaming.assemblyai.com/v3/ws", "tok en+1", "universal-streaming-english", formatTurns = true,
        )
        assertTrue(url.startsWith("wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le"))
        assertTrue(url.contains("speech_model=universal-streaming-english"))
        assertTrue(url.contains("format_turns=true"))
        assertTrue(url.endsWith("token=tok+en%2B1"))
    }

    @Test
    fun `partial and final turns parse`() {
        val partial = AssemblyAiProtocol.parse(
            """{"type":"Turn","turn_order":0,"end_of_turn":false,"turn_is_formatted":false,"transcript":" one nine two "}""",
        )
        assertEquals(AssemblyEvent.Turn("one nine two", endOfTurn = false, formatted = false), partial)
        val final = AssemblyAiProtocol.parse(
            """{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"Add port 8443."}""",
        )
        assertEquals(AssemblyEvent.Turn("Add port 8443.", endOfTurn = true, formatted = true), final)
    }

    @Test
    fun `begin, termination and errors parse`() {
        assertEquals(AssemblyEvent.Begin, AssemblyAiProtocol.parse("""{"type":"Begin","id":"x","expires_at":1}"""))
        assertEquals(AssemblyEvent.Termination, AssemblyAiProtocol.parse("""{"type":"Termination"}"""))
        assertEquals(AssemblyEvent.Error("bad"), AssemblyAiProtocol.parse("""{"error":"bad"}"""))
    }

    @Test
    fun `unknown or malformed messages are ignored`() {
        assertEquals(AssemblyEvent.Ignored, AssemblyAiProtocol.parse("""{"type":"SomethingNew"}"""))
        assertEquals(AssemblyEvent.Ignored, AssemblyAiProtocol.parse("not json"))
    }

    @Test
    fun `a normal close says nothing, an abnormal one explains`() {
        assertEquals("", AssemblyAiProtocol.closeMessage(1000, ""))
        assertEquals("Speech service closed: Unauthorized", AssemblyAiProtocol.closeMessage(3100, "Unauthorized"))
        assertTrue(AssemblyAiProtocol.closeMessage(1006, "").contains("1006"))
    }
}

class AssemblyTurnsTest {
    private fun turn(text: String, end: Boolean, formatted: Boolean = false) =
        AssemblyEvent.Turn(text, endOfTurn = end, formatted = formatted)

    @Test
    fun `raw mode completes on the first end of turn and joins several turns`() {
        val t = AssemblyTurns(wantFormatted = false)
        assertEquals(null, t.onTurn(turn("one nine", false)))
        assertEquals("one nine", t.text)
        assertEquals("one nine two", t.onTurn(turn("one nine two", true)))
        t.onTurn(turn("dot one", false))
        assertEquals("one nine two dot one", t.text)
        t.onTurn(turn("dot one six eight", true))
        assertEquals("one nine two dot one six eight", t.finishedText)
    }

    @Test
    fun `formatted mode waits for the formatted copy and does not double count`() {
        val t = AssemblyTurns(wantFormatted = true)
        assertEquals(null, t.onTurn(turn("hello world", true, formatted = false)))
        assertEquals("hello world", t.text)
        assertEquals("Hello world.", t.onTurn(turn("Hello world.", true, formatted = true)))
        assertEquals("Hello world.", t.finishedText)
        assertEquals("Hello world.", t.text)
    }

    @Test
    fun `an empty finished turn is dropped`() {
        val t = AssemblyTurns(wantFormatted = false)
        assertEquals(null, t.onTurn(turn("", true)))
        assertEquals("", t.finishedText)
    }
}

class SpeechSetupMessageTest {
    @Test
    fun `a missing key tells the user where to paste it`() {
        val msg = speechSetupMessage(
            com.lenscode.app.net.AssemblyTokenResult.Unavailable("No key", keyMissing = true),
            "http://192.168.1.157:8137/setup",
        )
        assertTrue(msg.contains("AssemblyAI API key"))
        assertTrue(msg.contains("http://192.168.1.157:8137/setup"))
    }

    @Test
    fun `any other failure includes the reason`() {
        val msg = speechSetupMessage(
            com.lenscode.app.net.AssemblyTokenResult.Unavailable("AssemblyAI rejected that key."),
            "http://h/setup",
        )
        assertTrue(msg.contains("AssemblyAI rejected that key."))
    }
}
