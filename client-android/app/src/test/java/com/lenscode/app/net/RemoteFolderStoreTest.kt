package com.lenscode.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteFolderStoreTest {

    private val folder = SavedRemoteFolder("maclaptop", "/Users/bread/clawd", "maclaptop · clawd")

    @Test
    fun `nothing saved loads as null`() {
        assertNull(RemoteFolderStore(FakeSharedPreferences()).load())
    }

    @Test
    fun `a machine and folder round-trip`() {
        val prefs = FakeSharedPreferences()
        RemoteFolderStore(prefs).save(folder)

        assertEquals(folder, RemoteFolderStore(prefs).load())
    }

    @Test
    fun `the latest folder wins`() {
        val prefs = FakeSharedPreferences()
        val store = RemoteFolderStore(prefs)
        store.save(folder)
        val other = SavedRemoteFolder("pastetreee", "/home/me/app", "pastetreee · app")
        store.save(other)

        assertEquals(other, store.load())
    }

    @Test
    fun `a missing label falls back to the folder path`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("machine_id", "box").putString("root", "/srv/app").apply()

        assertEquals(SavedRemoteFolder("box", "/srv/app", "/srv/app"), RemoteFolderStore(prefs).load())
    }

    @Test
    fun `a record without a machine or folder is not restored`() {
        val noRoot = FakeSharedPreferences().apply { edit().putString("machine_id", "box").apply() }
        val noMachine = FakeSharedPreferences().apply { edit().putString("root", "/srv/app").apply() }

        assertNull(RemoteFolderStore(noRoot).load())
        assertNull(RemoteFolderStore(noMachine).load())
    }
}
