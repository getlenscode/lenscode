package com.lenscode.app.ui

import kotlin.random.Random

/**
 * The tmux session one agent runs its shell commands in. Every agent gets its own, so several
 * agents working in parallel never type into the same shell.
 */
data class AgentTmux(val session: String, val machineId: String, val cwd: String)

private const val SESSION_PREFIX = "lc-"
private const val SESSION_ID_LENGTH = 4
private const val SESSION_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/**
 * A short unique tmux session name ("lc-k3f9"), different from every one in [taken]. The id is what
 * ties the agent, its Terminal pane and its tmux session together.
 */
fun newTmuxSession(taken: Set<String>, random: Random = Random.Default): String {
    while (true) {
        val id = buildString { repeat(SESSION_ID_LENGTH) { append(SESSION_ID_CHARS[random.nextInt(SESSION_ID_CHARS.length)]) } }
        val name = SESSION_PREFIX + id
        if (name !in taken) return name
    }
}

/**
 * Given to an agent once. It runs shell commands through its own tmux [session], which is mirrored
 * in the Terminal pane; extra sessions are allowed, named after it so they stay traceable.
 */
fun tmuxPreamble(session: String): String = """
    You have a tmux session named "$session" available, mirrored live
    in this workspace's own Terminal view. Always run shell commands
    through it instead of your default tool execution — that's what
    makes output visible there, and what lets a long-running process
    (a dev server, a watcher, …) keep running independently of this
    conversation instead of dying with whatever tool call started it:
      tmux send-keys -t $session "<command>" Enter
      tmux capture-pane -t $session -p     # to read output back
    To stop something, send a real interrupt inside the session
    (tmux send-keys -t $session C-c, or kill its pid) rather than
    canceling your own tool call.

    This session is yours alone; other agents use their own. If you need a separate
    long-running session (a second server, say), or the user asks for one by name, create
    it detached as "$session-<purpose>" (tmux new-session -d -s $session-<purpose>), use it
    the same way, and tell the user its name.
""".trimIndent()
