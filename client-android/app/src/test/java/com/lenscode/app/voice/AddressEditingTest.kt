package com.lenscode.app.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressEditingTest {
    private class FakeEditor(private val answer: () -> Result<String>) : AddressEditor {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun editAddress(current: String, spoken: String): Result<String> {
            calls += current to spoken
            return answer()
        }
    }

    private val current = "http://100.96.103.69:8000/"
    private val correction = "it should be 103.61 not 103.69"

    @Test
    fun `a correction is sent to the model with the current address and the words`() = runBlocking {
        val editor = FakeEditor { Result.success("http://100.96.103.61:8000/") }
        val outcome = resolveSpokenAddress(current, correction, editor)
        assertEquals(AddressEditOutcome.Set("http://100.96.103.61:8000/"), outcome)
        assertEquals(listOf(current to correction), editor.calls)
    }

    @Test
    fun `a whole new address replaces the field without asking the model`() = runBlocking {
        val editor = FakeEditor { error("must not be called") }
        val outcome = resolveSpokenAddress(
            current, "one nine two dot one six eight dot one dot one five seven colon three zero zero zero", editor,
        )
        assertEquals(AddressEditOutcome.Set("192.168.1.157:3000"), outcome)
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun `number words the parser cannot turn into digits go to the model instead of becoming garbage`() = runBlocking {
        val editor = FakeEditor { Result.success("192.168.1.157:3000") }
        val outcome = resolveSpokenAddress(
            current, "one nine two dot one six eight dot one dot one five seven colon three thousand", editor,
        )
        assertEquals(AddressEditOutcome.Set("192.168.1.157:3000"), outcome)
        assertEquals(1, editor.calls.size)
        // And with no key it asks for one, rather than putting "3thousand" in the field.
        assertEquals(
            AddressEditOutcome.NeedsKey,
            resolveSpokenAddress(current, "localhost colon three thousand", null),
        )
    }

    @Test
    fun `an empty field takes what was said, even with no key`() = runBlocking {
        assertEquals(AddressEditOutcome.Set("192.168.1.157:3000"),
            resolveSpokenAddress("", "one nine two dot one six eight dot one dot one five seven colon three zero zero zero", null))
    }

    @Test
    fun `a correction with no key needs one, and never guesses`() = runBlocking {
        assertEquals(AddressEditOutcome.NeedsKey, resolveSpokenAddress(current, correction, null))
        assertEquals(AddressEditOutcome.NeedsKey, resolveSpokenAddress(current, "make it https", null))
    }

    @Test
    fun `a model failure is reported, not turned into a guess`() = runBlocking {
        val boom = IllegalStateException("OpenRouter HTTP 500")
        val outcome = resolveSpokenAddress(current, correction, FakeEditor { Result.failure(boom) })
        assertTrue(outcome is AddressEditOutcome.Failed)
        assertEquals(boom, (outcome as AddressEditOutcome.Failed).error)
    }

    @Test
    fun `an exception from the editor is reported as a failure too`() = runBlocking {
        val outcome = resolveSpokenAddress(current, correction, FakeEditor { throw java.io.IOException("offline") })
        assertTrue(outcome is AddressEditOutcome.Failed)
        assertEquals("offline", (outcome as AddressEditOutcome.Failed).error.message)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never swallowed`() {
        runBlocking { resolveSpokenAddress(current, correction, FakeEditor { throw CancellationException("stop") }) }
    }
}
