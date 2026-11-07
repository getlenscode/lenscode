package com.lenscode.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLoadTrackerTest {
    @Test
    fun `the first address loads once, and recomposing with it does nothing`() {
        val t = BrowserLoadTracker()
        assertTrue(t.needsLoad("https://100.96.103.69:8000/"))
        assertFalse(t.needsLoad("https://100.96.103.69:8000/"))
        assertFalse(t.needsLoad("https://100.96.103.69:8000/"))
    }

    @Test
    fun `switching http and https navigates each time, and back again`() {
        val t = BrowserLoadTracker()
        t.needsLoad("https://100.96.103.69:8000/")
        assertTrue(t.needsLoad("http://100.96.103.69:8000/"))
        assertFalse(t.needsLoad("http://100.96.103.69:8000/"))
        assertTrue(t.needsLoad("https://100.96.103.69:8000/"))
    }

    @Test
    fun `a new port navigates too`() {
        val t = BrowserLoadTracker()
        t.needsLoad("http://192.168.1.157:3000")
        assertTrue(t.needsLoad("http://192.168.1.157:3001"))
    }

    @Test
    fun `reload fires once per press and not on later recompositions`() {
        val t = BrowserLoadTracker()
        assertFalse(t.needsReload(0))
        assertTrue(t.needsReload(1))
        assertFalse(t.needsReload(1))
        assertFalse(t.needsReload(1))
        assertTrue(t.needsReload(2))
    }
}
