package com.lenscode.app.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCommandsTest {

    // ── what may reach a shell or an intent ──────────────────────────────

    @Test
    fun `tmux session names with shell characters are refused`() {
        assertTrue(isSafeTmuxSession("lc-ab12"))
        assertTrue(isSafeTmuxSession("web.dev_2"))
        for (bad in listOf("", "a b", "x;rm -rf ~", "\$(id)", "a`b`", "a\nb", "x".repeat(65))) {
            assertFalse(bad, isSafeTmuxSession(bad))
        }
    }

    @Test
    fun `only http and https pages may be opened`() {
        assertTrue(isWebUrl("https://example.com"))
        assertTrue(isWebUrl("http://192.168.1.5:3000/x"))
        assertTrue(isWebUrl("HTTPS://EXAMPLE.COM"))
        for (bad in listOf("javascript://x", "file:///etc/passwd", "intent://scan#Intent;end", "content://x", "example.com")) {
            assertFalse(bad, isWebUrl(bad))
        }
    }

    @Test
    fun `the view list matches the app's dock`() {
        assertEquals(
            setOf("home", "code", "terminal", "agents", "permissions", "diff", "remote", "tasks", "browser", "settings"),
            CONTROL_VIEWS,
        )
    }

    // ── opening a file ───────────────────────────────────────────────────

    @Test
    fun `a file under the open folder on the same machine opens directly`() {
        assertEquals(
            OpenFilePlan.Direct("src/a.kt"),
            planOpenFile("maclaptop", "/Users/me/app", null, "/Users/me/app/src/a.kt"),
        )
        assertEquals(
            OpenFilePlan.Direct("src/a.kt"),
            planOpenFile("maclaptop", "/Users/me/app/", "maclaptop", "/Users/me/app/src/a.kt"),
        )
    }

    @Test
    fun `a relative path opens against the open folder`() {
        assertEquals(OpenFilePlan.Direct("src/a.kt"), planOpenFile("box", "/srv/app", null, "./src/a.kt"))
        assertEquals(OpenFilePlan.Direct("README.md"), planOpenFile("box", null, null, "README.md"))
    }

    @Test
    fun `a file outside the open folder re-roots Code at its folder`() {
        assertEquals(
            OpenFilePlan.Reroot("maclaptop", "/Users/me/other", "b.kt"),
            planOpenFile("maclaptop", "/Users/me/app", null, "/Users/me/other/b.kt"),
        )
    }

    @Test
    fun `a sibling folder sharing the root's prefix is not mistaken for inside it`() {
        assertEquals(
            OpenFilePlan.Reroot("box", "/srv/app-old", "x.kt"),
            planOpenFile("box", "/srv/app", null, "/srv/app-old/x.kt"),
        )
    }

    @Test
    fun `a file on another machine re-roots Code on that machine`() {
        assertEquals(
            OpenFilePlan.Reroot("pastetreee", "/home/me/app", "main.py"),
            planOpenFile("maclaptop", "/Users/me/app", "pastetreee", "/home/me/app/main.py"),
        )
    }

    @Test
    fun `a file at the filesystem root re-roots at slash`() {
        assertEquals(OpenFilePlan.Reroot("box", "/", "notes.txt"), planOpenFile("box", "/srv/app", null, "/notes.txt"))
    }

    @Test
    fun `without a machine, or a relative path for another machine, it says why`() {
        assertTrue(planOpenFile("local", null, null, "/x/y.kt") is OpenFilePlan.Unresolvable)
        assertTrue(planOpenFile("maclaptop", "/a", "pastetreee", "src/y.kt") is OpenFilePlan.Unresolvable)
    }

    // ── the project label ────────────────────────────────────────────────

    @Test
    fun `a project label is the machine and the folder name`() {
        assertEquals("doraemon_arch · app", projectLabel("doraemon_arch", "/home/me/Documents/app"))
        assertEquals("box · app", projectLabel("box", "/srv/app/"))
        assertEquals("box · /", projectLabel("box", "/"))
    }
}

