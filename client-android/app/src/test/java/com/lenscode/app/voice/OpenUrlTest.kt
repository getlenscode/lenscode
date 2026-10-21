package com.lenscode.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenUrlTest {

    // ── the voice step ───────────────────────────────────────────────────

    @Test
    fun `an open_url step becomes an OpenUrl action`() {
        val action = VoiceStep(type = "open_url", url = "localhost:9000").toVoiceAction()

        assertEquals(VoiceAction.OpenUrl("localhost:9000"), action)
    }

    @Test
    fun `an open_url step without an address does nothing`() {
        assertNull(VoiceStep(type = "open_url").toVoiceAction())
        assertNull(VoiceStep(type = "open_url", url = "  ").toVoiceAction())
    }

    @Test
    fun `an address named in the browser is not mistaken for an agent task`() {
        // The router must pick open_url, not ask_agent, for this — the step type is what carries it.
        val plan = VoiceCommandPlan(listOf(VoiceStep(type = "open_url", url = "example.com")))

        assertEquals(listOf<VoiceAction>(VoiceAction.OpenUrl("example.com")), plan.toActions())
    }

    // ── dictated addresses ───────────────────────────────────────────────

    @Test
    fun `a dictated localhost port is collapsed to host and port`() {
        assertEquals("localhost:9000", normalizeSpokenAddress("localhost colon nine zero zero zero"))
        assertEquals("localhost:3000", normalizeSpokenAddress("localhost:3000"))
    }

    // ── localhost on the agent's machine ─────────────────────────────────

    @Test
    fun `localhost is pointed at a machine named by an IP address`() {
        assertEquals(MachineAddress("192.168.1.50:9000"), addressForMachine("localhost:9000", "192.168.1.50"))
        assertEquals(MachineAddress("http://192.168.1.50:3000/x"), addressForMachine("http://127.0.0.1:3000/x", "192.168.1.50"))
    }

    @Test
    fun `localhost is pointed at a machine with a dot-local name`() {
        assertEquals(MachineAddress("octopi.local:5000"), addressForMachine("localhost:5000", "octopi.local"))
    }

    @Test
    fun `localhost on an ssh alias the headset cannot resolve is left alone with a warning`() {
        val result = addressForMachine("localhost:9000", "pastetreee")

        assertEquals("localhost:9000", result.address)
        assertEquals("\"localhost\" is the headset here; the server runs on pastetreee.", result.warning)
    }

    @Test
    fun `an address that names its own host is never rewritten`() {
        assertEquals(MachineAddress("192.168.1.99:8080"), addressForMachine("192.168.1.99:8080", "192.168.1.50"))
        assertEquals(MachineAddress("example.com"), addressForMachine("example.com", "pastetreee"))
    }

    @Test
    fun `with no machine picked localhost stays as typed`() {
        assertEquals(MachineAddress("localhost:9000"), addressForMachine("localhost:9000", "local"))
    }
}
