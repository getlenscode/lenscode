package com.lenscode.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerPrefsStoreTest {

    @Test
    fun `nothing saved loads as null so the default agent applies`() {
        assertNull(ComposerPrefsStore(FakeSharedPreferences()).load())
    }

    @Test
    fun `an agent and model round-trip`() {
        val prefs = FakeSharedPreferences()
        ComposerPrefsStore(prefs).save(AgentChoice("codex", "gpt-5"))

        assertEquals(AgentChoice("codex", "gpt-5"), ComposerPrefsStore(prefs).load())
    }

    @Test
    fun `an agent on its default model keeps a null model`() {
        val prefs = FakeSharedPreferences()
        ComposerPrefsStore(prefs).save(AgentChoice("pi", null))

        assertEquals(AgentChoice("pi", null), ComposerPrefsStore(prefs).load())
    }

    @Test
    fun `saving a new choice replaces the old one including a model that is now default`() {
        val prefs = FakeSharedPreferences()
        val store = ComposerPrefsStore(prefs)
        store.save(AgentChoice("claude", "claude-opus"))
        store.save(AgentChoice("omp", null))

        assertEquals(AgentChoice("omp", null), store.load())
    }

    @Test
    fun `a blank saved provider is ignored`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("provider", "  ").putString("model", "x").apply()

        assertNull(ComposerPrefsStore(prefs).load())
    }

    @Test
    fun `the open-files checkbox starts off and remembers being turned on and off`() {
        val prefs = FakeSharedPreferences()
        assertEquals(false, ComposerPrefsStore(prefs).loadAttachOpenFiles())

        ComposerPrefsStore(prefs).saveAttachOpenFiles(true)
        assertEquals(true, ComposerPrefsStore(prefs).loadAttachOpenFiles())

        ComposerPrefsStore(prefs).saveAttachOpenFiles(false)
        assertEquals(false, ComposerPrefsStore(prefs).loadAttachOpenFiles())
    }

    @Test
    fun `the checkbox and the agent choice are saved independently`() {
        val prefs = FakeSharedPreferences()
        val store = ComposerPrefsStore(prefs)
        store.saveAttachOpenFiles(true)
        store.save(AgentChoice("pi", null))

        assertEquals(true, store.loadAttachOpenFiles())
        assertEquals(AgentChoice("pi", null), store.load())
    }
}
