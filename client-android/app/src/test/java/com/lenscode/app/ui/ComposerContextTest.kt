package com.lenscode.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerContextTest {

    // ── the Context block ────────────────────────────────────────────────

    @Test
    fun `nothing attached leaves the prompt exactly as typed`() {
        assertEquals("fix the bug", buildPromptWithContext("fix the bug", emptyList(), emptyList()))
    }

    @Test
    fun `open editor files are listed under Context`() {
        val out = buildPromptWithContext("fix it", emptyList(), listOf("/p/a.kt", "/p/b.kt"))

        assertEquals("fix it\n\nContext:\n- Open in the editor: /p/a.kt, /p/b.kt", out)
    }

    @Test
    fun `an attached file is listed on its own line`() {
        val out = buildPromptWithContext("review", listOf(ComposerAttachment.File("/p/Foo.kt")), emptyList())

        assertEquals("review\n\nContext:\n- Attached file: /p/Foo.kt", out)
    }

    @Test
    fun `a file that is both open and attached is only listed as attached`() {
        val out = buildPromptWithContext(
            "go",
            listOf(ComposerAttachment.File("/p/a.kt")),
            listOf("/p/a.kt", "/p/b.kt"),
        )

        assertEquals("go\n\nContext:\n- Open in the editor: /p/b.kt\n- Attached file: /p/a.kt", out)
    }

    @Test
    fun `an attached tmux session says how to read and drive it`() {
        val out = buildPromptWithContext("why did it fail", listOf(ComposerAttachment.Tmux("web")), emptyList())

        assertEquals(
            "why did it fail\n\nContext:\n- Attached tmux session: web — read it with " +
                "`tmux capture-pane -t web -p`, and type into it with `tmux send-keys -t web \"<command>\" Enter`",
            out,
        )
    }

    @Test
    fun `duplicates are listed once and trailing whitespace in the prompt is dropped`() {
        val out = buildPromptWithContext(
            "do it  \n",
            listOf(ComposerAttachment.File("/p/a.kt"), ComposerAttachment.File("/p/a.kt"), ComposerAttachment.Tmux("web"), ComposerAttachment.Tmux("web")),
            emptyList(),
        )

        assertEquals(1, Regex("Attached file").findAll(out).count())
        assertEquals(1, Regex("Attached tmux session").findAll(out).count())
        assertEquals(true, out.startsWith("do it\n\nContext:\n"))
    }

    // ── chip labels ──────────────────────────────────────────────────────

    @Test
    fun `a file chip shows the file name and a tmux chip the session name`() {
        assertEquals("Foo.kt", ComposerAttachment.File("/p/src/Foo.kt").label)
        assertEquals("/", ComposerAttachment.File("/").label)
        assertEquals("web", ComposerAttachment.Tmux("web").label)
    }

    // ── clipboard paste ──────────────────────────────────────────────────

    @Test
    fun `pasted text goes in at the cursor and leaves the cursor after it`() {
        val out = insertAtCursor(TextFieldValue("hello world", TextRange(5)), " big")

        assertEquals("hello big world", out.text)
        assertEquals(TextRange(9), out.selection)
    }

    @Test
    fun `pasted text replaces a selection`() {
        val out = insertAtCursor(TextFieldValue("fix THIS now", TextRange(4, 8)), "that")

        assertEquals("fix that now", out.text)
        assertEquals(TextRange(8), out.selection)
    }

    @Test
    fun `pasting into an empty prompt just sets it`() {
        val out = insertAtCursor(TextFieldValue(""), "stack trace")

        assertEquals("stack trace", out.text)
        assertEquals(TextRange(11), out.selection)
    }

    // ── paths ────────────────────────────────────────────────────────────

    @Test
    fun `a project-relative path becomes absolute under the root`() {
        assertEquals("/Users/me/app/src/a.kt", absoluteProjectPath("/Users/me/app", "src/a.kt"))
        assertEquals("/Users/me/app/src/a.kt", absoluteProjectPath("/Users/me/app/", "src/a.kt"))
        assertEquals("/Users/me/app/./src/a.kt", absoluteProjectPath("/Users/me/app", "./src/a.kt"))
    }

    @Test
    fun `an absolute path or an unknown root is left alone`() {
        assertEquals("/etc/hosts", absoluteProjectPath("/Users/me/app", "/etc/hosts"))
        assertEquals("src/a.kt", absoluteProjectPath(null, "src/a.kt"))
        assertEquals("src/a.kt", absoluteProjectPath("  ", "src/a.kt"))
    }
}
