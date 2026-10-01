package com.lenscode.app.ui

import com.lenscode.app.net.AgentHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkspaceLogicTest {

    // ── parseDiffHunks / hunkPatch ───────────────────────────────────────

    private val twoHunkDiff = """
        diff --git a/foo.kt b/foo.kt
        index 1111111..2222222 100644
        --- a/foo.kt
        +++ b/foo.kt
        @@ -1,3 +1,3 @@
         context
        -old one
        +new one
        @@ -10,3 +10,4 @@
         context
        +added
         tail
    """.trimIndent()

    @Test
    fun `diff splits into header plus hunks`() {
        val (header, hunks) = parseDiffHunks(twoHunkDiff)
        assertEquals(4, header.size)
        assertTrue(header.first().startsWith("diff --git"))
        assertEquals(2, hunks.size)
        assertTrue(hunks[0].lines.first().startsWith("@@ -1,3"))
        assertTrue(hunks[1].lines.first().startsWith("@@ -10,3"))
        // A hunk ends right before the next one starts.
        assertTrue(hunks[0].lines.none { it == "+added" })
        assertTrue(hunks[1].lines.contains("+added"))
    }

    @Test
    fun `hunkPatch reconstructs header plus one hunk`() {
        val (header, hunks) = parseDiffHunks(twoHunkDiff)
        val patch = hunkPatch(header, hunks[1])
        assertTrue(patch.startsWith("diff --git"))
        assertTrue(patch.contains("@@ -10,3"))
        assertTrue(patch.contains("+added"))
        assertTrue(!patch.contains("@@ -1,3"))
        assertTrue(patch.endsWith("\n"))
    }

    @Test
    fun `diff with no hunks is all header`() {
        val (header, hunks) = parseDiffHunks("diff --git a/x b/x\nnew file mode 100644")
        assertEquals(2, header.size)
        assertTrue(hunks.isEmpty())
    }

    // ── formatHistoryDateLabel / buildHistoryRows ────────────────────────

    private fun isoOn(date: LocalDate) =
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toString()

    @Test
    fun `unparseable date label is Unknown`() {
        assertEquals("Unknown date", formatHistoryDateLabel("not-a-date"))
        assertEquals("Unknown date", formatHistoryDateLabel(""))
    }

    @Test
    fun `today and yesterday get relative labels`() {
        assertEquals("Today", formatHistoryDateLabel(isoOn(LocalDate.now())))
        assertEquals("Yesterday", formatHistoryDateLabel(isoOn(LocalDate.now().minusDays(1))))
    }

    @Test
    fun `older dates get an absolute label`() {
        assertEquals("January 15, 2020", formatHistoryDateLabel("2020-01-15T12:00:00Z"))
    }

    private fun historyEntry(id: String, createdAt: String) = AgentHistoryEntry(
        agentId = id,
        provider = "claude",
        model = null,
        title = null,
        status = "closed",
        cwd = "/tmp",
        createdAt = createdAt,
        archivedAt = null,
    )

    @Test
    fun `history rows insert one header per distinct day`() {
        val today = isoOn(LocalDate.now())
        val yesterday = isoOn(LocalDate.now().minusDays(1))
        val rows = buildHistoryRows(
            listOf(
                historyEntry("a1", today),
                historyEntry("a2", today),
                historyEntry("a3", yesterday),
            ),
        )
        // Today header, two entries, Yesterday header, one entry.
        assertEquals(5, rows.size)
        assertEquals("header-Today", rows[0].rowKey)
        assertEquals("a1", rows[1].rowKey)
        assertEquals("a2", rows[2].rowKey)
        assertEquals("header-Yesterday", rows[3].rowKey)
        assertEquals("a3", rows[4].rowKey)
    }

    // ── symbolBodyRange ──────────────────────────────────────────────────

    @Test
    fun `kotlin outline extracts the top-level shapes`() {
        val code = """
            package com.example

            class Foo {
                private fun helper() {}
            }
            data class Bar(val x: Int)
            interface Baz
            enum class Mode { A, B }
            object Singleton
            typealias Name = String
            fun String.shout() {}
            suspend fun load() {}
        """.trimIndent()
        val symbols = extractSymbols(code, "kotlin")
        val byName = symbols.associate { it.name to it.kind }
        assertEquals(
            mapOf(
                "Foo" to "class",
                "helper" to "function",
                "Bar" to "class",
                "Baz" to "interface",
                "Mode" to "enum",
                "Singleton" to "class",
                "Name" to "type",
                "shout" to "function",
                "load" to "function",
            ),
            byName,
        )
    }

    @Test
    fun `unsupported language yields no symbols`() {
        assertTrue(extractSymbols("fun f() {}", "cobol").isEmpty())
        assertTrue(extractSymbols("fun f() {}", null).isEmpty())
    }

    @Test
    fun `brace language range covers the whole body`() {
        val lines = listOf(
            "fun main() {",      // 0 — symbol line
            "    println(1)",    // 1
            "}",                 // 2 — body ends
            "fun other() {",     // 3 — must not be included
            "}",
        )
        val range = symbolBodyRange(lines, CodeSymbol("main", "function", 0), "kotlin")
        assertEquals(0..2, range)
    }

    @Test
    fun `python range follows indentation`() {
        val lines = listOf(
            "def f():",          // 0 — symbol line
            "    x = 1",         // 1
            "",                  // 2 — blanks count as inside
            "    return x",      // 3
            "def g():",          // 4 — dedent ends the block
            "    pass",
        )
        val range = symbolBodyRange(lines, CodeSymbol("f", "function", 0), "python")
        assertEquals(0..3, range)
    }

    @Test
    fun `out-of-range or unknown language falls back to the symbol line`() {
        assertEquals(9..9, symbolBodyRange(listOf("a"), CodeSymbol("x", "function", 9), "kotlin"))
        assertEquals(0..0, symbolBodyRange(listOf("x = 1"), CodeSymbol("x", "function", 0), null))
    }
}
