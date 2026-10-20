package com.lenscode.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorFontSizeTest {
    @Test
    fun `an absolute size is used as given`() {
        assertEquals(16, EditorFontSize.resolve(current = 12, size = 16, change = null))
    }

    @Test
    fun `an absolute size wins over a relative change`() {
        assertEquals(20, EditorFontSize.resolve(current = 12, size = 20, change = "smaller"))
    }

    @Test
    fun `larger and smaller step from the current size`() {
        assertEquals(14, EditorFontSize.resolve(12, null, "larger"))
        assertEquals(10, EditorFontSize.resolve(12, null, "smaller"))
    }

    @Test
    fun `reset returns to the default`() {
        assertEquals(EditorFontSize.DEFAULT, EditorFontSize.resolve(28, null, "reset"))
    }

    @Test
    fun `sizes are clamped so a misheard number cannot break the editor`() {
        assertEquals(EditorFontSize.MAX, EditorFontSize.resolve(12, 160, null))
        assertEquals(EditorFontSize.MIN, EditorFontSize.resolve(12, 2, null))
        assertEquals(EditorFontSize.MAX, EditorFontSize.resolve(EditorFontSize.MAX, null, "larger"))
        assertEquals(EditorFontSize.MIN, EditorFontSize.resolve(EditorFontSize.MIN, null, "smaller"))
    }

    @Test
    fun `an unknown change leaves the size alone`() {
        assertEquals(12, EditorFontSize.resolve(12, null, "sideways"))
        assertEquals(12, EditorFontSize.resolve(12, null, null))
    }
}
