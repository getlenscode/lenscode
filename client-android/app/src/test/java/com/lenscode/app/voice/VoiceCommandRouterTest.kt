package com.lenscode.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandRouterTest {

    // ── Navigation ───────────────────────────────────────────────────────

    @Test
    fun `bare view name navigates`() {
        assertEquals(VoiceCommand.Navigate("diff"), parseVoiceCommand("diff"))
        assertEquals(VoiceCommand.Navigate("terminal"), parseVoiceCommand("terminal"))
    }

    @Test
    fun `nav verb plus synonym navigates`() {
        assertEquals(VoiceCommand.Navigate("code"), parseVoiceCommand("click code"))
        assertEquals(VoiceCommand.Navigate("code"), parseVoiceCommand("open files"))
        assertEquals(VoiceCommand.Navigate("diff"), parseVoiceCommand("view changes"))
        assertEquals(VoiceCommand.Navigate("terminal"), parseVoiceCommand("open terminals"))
        assertEquals(VoiceCommand.Navigate("git"), parseVoiceCommand("go to branches"))
        assertEquals(VoiceCommand.Navigate("agents"), parseVoiceCommand("show agents"))
        assertEquals(VoiceCommand.Navigate("permissions"), parseVoiceCommand("open permissions"))
        assertEquals(VoiceCommand.Navigate("remote"), parseVoiceCommand("switch to remote"))
        assertEquals(VoiceCommand.Navigate("home"), parseVoiceCommand("go to home"))
    }

    @Test
    fun `synonym without a verb and not standalone is a search, not navigation`() {
        // "the auth code file" mentions "code" but isn't a nav command.
        val result = parseVoiceCommand("the auth code file")
        assertTrue(result is VoiceCommand.Search)
    }

    // ── Diff actions ─────────────────────────────────────────────────────

    @Test
    fun `accept changes phrasings`() {
        assertEquals(VoiceCommand.AcceptChanges, parseVoiceCommand("accept changes"))
        assertEquals(VoiceCommand.AcceptChanges, parseVoiceCommand("approve the changes"))
        assertEquals(VoiceCommand.AcceptChanges, parseVoiceCommand("stage changes"))
    }

    @Test
    fun `discard changes phrasings`() {
        assertEquals(VoiceCommand.DiscardChanges, parseVoiceCommand("discard changes"))
        assertEquals(VoiceCommand.DiscardChanges, parseVoiceCommand("reject changes"))
        assertEquals(VoiceCommand.DiscardChanges, parseVoiceCommand("undo the changes"))
    }

    @Test
    fun `discard wins over accept when both appear`() {
        // Ordering matters: destructive intent is checked first.
        assertEquals(VoiceCommand.DiscardChanges, parseVoiceCommand("discard and reject these changes"))
    }

    // ── Terminals ────────────────────────────────────────────────────────

    @Test
    fun `kill server or terminal phrasings`() {
        assertEquals(VoiceCommand.KillTerminal, parseVoiceCommand("kill server"))
        assertEquals(VoiceCommand.KillTerminal, parseVoiceCommand("stop the terminal"))
        assertEquals(VoiceCommand.KillTerminal, parseVoiceCommand("kill this process"))
    }

    @Test
    fun `new terminal phrasings`() {
        assertEquals(VoiceCommand.NewTerminal, parseVoiceCommand("new terminal"))
        assertEquals(VoiceCommand.NewTerminal, parseVoiceCommand("create a terminal"))
    }

    // ── Agents ───────────────────────────────────────────────────────────

    @Test
    fun `archive agent phrasings`() {
        assertEquals(VoiceCommand.ArchiveAgent("current"), parseVoiceCommand("archive agent"))
        assertEquals(VoiceCommand.ArchiveAgent("current"), parseVoiceCommand("close this agent"))
    }

    @Test
    fun `archive agent target extraction`() {
        assertEquals(VoiceCommand.ArchiveAgent("all"), parseVoiceCommand("kill all agents"))
        assertEquals(VoiceCommand.ArchiveAgent("all"), parseVoiceCommand("stop all the agents"))
        assertEquals(VoiceCommand.ArchiveAgent("terminal test"), parseVoiceCommand("kill the terminal test agent"))
        // parseVoiceCommand lowercases the transcript before matching.
        assertEquals(VoiceCommand.ArchiveAgent("b"), parseVoiceCommand("archive agent B"))
    }

    @Test
    fun `close terminal phrasings are distinct from kill terminal`() {
        // The bug this locks in: "close all my open terminals" used to have no
        // matching action at all and silently fell through to plain search.
        assertEquals(VoiceCommand.CloseTerminal("all"), parseVoiceCommand("close all my open terminals"))
        assertEquals(VoiceCommand.CloseTerminal("current"), parseVoiceCommand("close this terminal"))
        assertEquals(VoiceCommand.KillTerminal, parseVoiceCommand("kill the server"))
        assertEquals(VoiceCommand.KillTerminal, parseVoiceCommand("stop the running process"))
    }

    // ── Fallback ─────────────────────────────────────────────────────────

    @Test
    fun `unrecognized text falls back to search with the original casing`() {
        val result = parseVoiceCommand("Find the authentication middleware")
        assertEquals(VoiceCommand.Search("Find the authentication middleware"), result)
    }

    @Test
    fun `blank input falls back to an empty search rather than crashing`() {
        assertEquals(VoiceCommand.Search(""), parseVoiceCommand("   "))
    }

    // ── Filenames are files, not views ───────────────────────────────────

    @Test
    fun `open main dot ts searches for the file instead of navigating home`() {
        // The bug this locks in: "home" has the synonym "main", which matched
        // inside "main.ts", so "open main.ts" jumped to Home. And even as a
        // search, the bridge got "open main.ts" and found nothing.
        assertEquals(VoiceCommand.Search("main.ts"), parseVoiceCommand("open main.ts"))
        assertEquals(VoiceCommand.Search("main.ts"), parseVoiceCommand("Open main.ts."))
        assertEquals(VoiceCommand.Search("main dot ts"), parseVoiceCommand("open main dot ts"))
    }

    @Test
    fun `command words are stripped from a file query`() {
        assertEquals(VoiceCommand.Search("src/server.ts"), parseVoiceCommand("show me src/server.ts"))
        assertEquals(VoiceCommand.Search("package.json"), parseVoiceCommand("go to the file package.json"))
        assertEquals(VoiceCommand.Search("main.ts"), parseVoiceCommand("open main.ts in the code editor"))
        assertEquals(
            VoiceCommand.Search("src slash main dot ts"),
            parseVoiceCommand("open src slash main dot ts"),
        )
    }

    @Test
    fun `plain view names still navigate`() {
        assertEquals(VoiceCommand.Navigate("home"), parseVoiceCommand("go to main"))
        assertEquals(VoiceCommand.Navigate("home"), parseVoiceCommand("open home"))
        assertEquals(VoiceCommand.Navigate("diff"), parseVoiceCommand("open the diff."))
        assertEquals(VoiceCommand.Navigate("code"), parseVoiceCommand("open the code view"))
    }

    @Test
    fun `fileSearchQuery ignores text without a filename`() {
        assertEquals(null, fileSearchQuery("open the diff view"))
        assertEquals(null, fileSearchQuery("version 1.5 please"))
        assertEquals("main.ts", fileSearchQuery("open main.ts"))
    }

    // ── Editor font size ─────────────────────────────────────────────────

    @Test
    fun `a spoken font size sets that size`() {
        assertEquals(VoiceCommand.SetFontSize(size = 16), parseVoiceCommand("set font size to 16"))
        assertEquals(VoiceCommand.SetFontSize(size = 20), parseVoiceCommand("text size 20"))
        assertEquals(VoiceCommand.SetFontSize(size = 14), parseVoiceCommand("Set the font size to fourteen."))
        assertEquals(VoiceCommand.SetFontSize(size = 24), parseVoiceCommand("font size twenty four"))
    }

    @Test
    fun `bigger and smaller are relative changes`() {
        assertEquals(VoiceCommand.SetFontSize(change = "larger"), parseVoiceCommand("make the font bigger"))
        assertEquals(VoiceCommand.SetFontSize(change = "larger"), parseVoiceCommand("increase the font size"))
        assertEquals(VoiceCommand.SetFontSize(change = "smaller"), parseVoiceCommand("make the font smaller"))
        assertEquals(VoiceCommand.SetFontSize(change = "reset"), parseVoiceCommand("reset the font size"))
    }

    @Test
    fun `text searches that merely mention text are not font commands`() {
        assertEquals(VoiceCommand.Search("search text for auth"), parseVoiceCommand("search text for auth"))
        assertEquals(VoiceCommand.Search("the font loader file"), parseVoiceCommand("the font loader file"))
    }
}
