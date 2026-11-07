package com.lenscode.app.voice

import com.lenscode.app.net.TaskCard
import com.lenscode.app.net.TaskPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRefTest {
    private val tasks = listOf(
        TaskCard(key = "ABC-7", title = "Fix login redirect"),
        TaskCard(key = "ABC-12", title = "Add dark mode"),
        TaskCard(key = "WEB-12", title = "Fix login page copy"),
    )

    @Test
    fun `spoken statuses map to wire statuses`() {
        assertEquals("in_progress", parseTaskStatus("In progress"))
        assertEquals("in_review", parseTaskStatus("review"))
        assertEquals("canceled", parseTaskStatus("cancelled"))
        assertEquals("done", parseTaskStatus("Done!"))
        assertNull(parseTaskStatus("someday"))
    }

    @Test
    fun `a key is matched however it is spoken`() {
        assertEquals(TaskMatch.Found(tasks[1]), resolveTask("a b c 12", tasks))
        assertEquals(TaskMatch.Found(tasks[0]), resolveTask("task ABC dash 7", tasks))
        assertEquals(TaskMatch.Found(tasks[0]), resolveTask("abc-7", tasks))
    }

    @Test
    fun `a bare number needs to be unique`() {
        assertEquals(TaskMatch.Found(tasks[0]), resolveTask("7", tasks))
        assertTrue(resolveTask("12", tasks) is TaskMatch.Ambiguous)
    }

    @Test
    fun `numbers spelled out by speech-to-text still match`() {
        assertEquals(TaskMatch.Found(tasks[0]), resolveTask("task seven", tasks))
        assertEquals(TaskMatch.Found(tasks[1]), resolveTask("a b c twelve", tasks))
        val many = listOf(TaskCard(key = "LC-21", title = "x"), TaskCard(key = "LC-20", title = "y"))
        assertEquals(TaskMatch.Found(many[0]), resolveTask("twenty one", many))
        assertEquals(TaskMatch.Found(many[1]), resolveTask("task twenty", many))
    }

    @Test
    fun `title words find a task, and shared words are ambiguous`() {
        assertEquals(TaskMatch.Found(tasks[1]), resolveTask("the dark mode task", tasks))
        assertTrue(resolveTask("fix login", tasks) is TaskMatch.Ambiguous)
        assertEquals(TaskMatch.NotFound, resolveTask("deploy pipeline", tasks))
    }

    @Test
    fun `an agent name finds its preset`() {
        val presets = listOf(TaskPreset("Claude Code", "claude", "m"), TaskPreset("Codex", "codex", "m"))
        assertEquals("Codex", resolvePreset("codex", presets)?.name)
        assertEquals("Claude Code", resolvePreset("claude", presets)?.name)
        assertNull(resolvePreset("pi", presets))
    }

    @Test
    fun `a key the model picked wins over word matching`() {
        val m = resolveTaskChoice("log in thing", "abc-12", emptyList(), tasks)
        assertEquals(TaskMatch.Found(tasks[1]), m)
    }

    @Test
    fun `an unknown key falls back to the words, then to the model's alternatives`() {
        assertEquals(TaskMatch.Found(tasks[1]), resolveTaskChoice("dark mode", "ZZZ-1", emptyList(), tasks))
        val offered = resolveTaskChoice("blorp", null, listOf("ABC-7", "nope", "ABC-12"), tasks)
        assertEquals(TaskMatch.Ambiguous(listOf(tasks[0], tasks[1])), offered)
        assertEquals(TaskMatch.NotFound, resolveTaskChoice("blorp", null, emptyList(), tasks))
    }

    @Test
    fun `move_task and run_task steps carry the model's key and alternatives`() {
        val move = VoiceStep(type = "move_task", taskRef = "log in", taskStatus = "done", taskKey = " ABC-7 ", taskAlternatives = "ABC-12, WEB-12")
            .toVoiceAction()
        assertEquals(VoiceAction.MoveTask("log in", "done", "ABC-7", listOf("ABC-12", "WEB-12")), move)
        val run = VoiceStep(type = "run_task", taskRef = "7", taskAgent = "claude").toVoiceAction()
        assertEquals(VoiceAction.RunTask("7", "claude", null, emptyList()), run)
    }
}
