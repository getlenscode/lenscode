package com.lenscode.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterVoiceRouterReplyTest {

    @Test
    fun `a plain JSON reply becomes a plan`() {
        val plan = parsePlan("""{"actions":[{"type":"navigate","view":"code"},{"type":"open_file","path":"a.kt","line":20}]}""")
        assertEquals(
            listOf(VoiceAction.Navigate("code"), VoiceAction.OpenFile("a.kt", 20)),
            plan.toActions(),
        )
    }

    @Test
    fun `fences, prose and unknown fields around the object are tolerated`() {
        val plan = parsePlan(
            "Sure!\n```json\n{\"actions\":[{\"type\":\"accept_changes\",\"confidence\":0.9}]}\n```\nDone.",
        )
        assertEquals(listOf<VoiceAction>(VoiceAction.AcceptChanges), plan.toActions())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a reply with no JSON is an error so the caller falls back`() {
        parsePlan("I can't help with that.")
    }

    @Test
    fun `the message content is pulled out of the chat completion`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"actions\":[]}"}}]}"""
        assertEquals("""{"actions":[]}""", replyContent(body))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty completion is an error`() {
        replyContent("""{"choices":[]}""")
    }

    @Test
    fun `an error body yields its message`() {
        assertEquals("No auth credentials found", errorMessage("""{"error":{"code":401,"message":"No auth credentials found"}}"""))
        assertEquals("plain text", errorMessage("plain text"))
    }

    @Test
    fun `the system prompt carries the rules, every field with its description, and examples`() {
        val prompt = systemPrompt()
        assertTrue(prompt.contains("voice command router"))
        assertTrue(prompt.contains("- fontSize: type=set_font_size"))
        assertTrue(prompt.contains("- type: one of: navigate"))
        assertTrue(prompt.contains("""{"actions":[{"type":"accept_changes"}]}"""))
    }

    @Test
    fun `the user prompt states the screen and the transcript`() {
        val text = userPrompt("open the diff", VoiceRouterContext("code", "a.kt", true, null))
        assertTrue(text.contains("view=code"))
        assertTrue(text.contains("diffFileOpen=a.kt"))
        assertTrue(text.endsWith("Transcript: \"open the diff\""))
    }

    @Test
    fun `every shipped example round-trips through the parser`() {
        for (example in OpenRouterVoiceRouterExamples.all) {
            val json = kotlinx.serialization.json.Json.encodeToString(VoiceCommandPlan.serializer(), example)
            assertEquals(example, parsePlan(json))
        }
    }
}

class AddressEditReplyTest {
    @Test
    fun `a clean reply is the new address`() {
        assertEquals("http://100.96.103.61:8000/", parseEditedAddress("""{"address":"http://100.96.103.61:8000/"}"""))
    }

    @Test
    fun `fences and prose around the object are tolerated, bare hosts too`() {
        assertEquals("192.168.1.157:3001", parseEditedAddress("Sure: ```json\n{\"address\": \"192.168.1.157:3001\"}\n```"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an address with spaces is refused`() {
        parseEditedAddress("""{"address":"http://h :8000"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a missing or empty address is refused`() {
        parseEditedAddress("""{"address":""}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a reply with no JSON is refused`() {
        parseEditedAddress("I could not tell what you meant.")
    }

    @Test
    fun `the prompt carries the 103 61 example and the symbol rules`() {
        assertTrue(ADDRESS_EDIT_PROMPT.contains("it should be 103.61 not 103.69"))
        assertTrue(ADDRESS_EDIT_PROMPT.contains("dot is \".\""))
    }

    @Test
    fun `whole addresses replace the field, corrections do not`() {
        assertTrue(replacesWholeAddress("", "add slash docs"))
        assertTrue(replacesWholeAddress("http://h", "one nine two dot one six eight dot three dot one"))
        assertTrue(replacesWholeAddress("http://h", "localhost colon three zero zero zero"))
        assertTrue(!replacesWholeAddress("http://100.96.103.69:8000", "it should be one oh three dot sixty one not one oh three dot sixty nine"))
        assertTrue(!replacesWholeAddress("http://h", "make it https"))
        // Number words need the model: the digit parser would leave "thousand" in the address.
        assertTrue(!replacesWholeAddress("http://h", "localhost colon three thousand"))
        assertTrue(!replacesWholeAddress("", "one ninety two dot one sixty eight dot one dot one fifty seven"))
    }
}
