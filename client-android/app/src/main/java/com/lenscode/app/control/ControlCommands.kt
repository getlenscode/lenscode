package com.lenscode.app.control

/**
 * A command sent from a tool on the user's computer (Claude Code / Claude Desktop through
 * `lenscode-bridge mcp`), relayed by the bridge as a `control` event. The bridge validates these
 * already; the app checks the parts that reach a shell or an intent again rather than trust them.
 */
data class ControlCommand(val name: String, val args: Map<String, String>)

/** Views a control command may switch to. */
val CONTROL_VIEWS = setOf("home", "code", "terminal", "agents", "permissions", "diff", "remote", "tasks", "browser", "settings")

private val TMUX_SESSION = Regex("^[A-Za-z0-9._-]{1,64}$")

/** A tmux session name that is safe to type into a shell as `tmux new -A -s <name>`. */
fun isSafeTmuxSession(name: String): Boolean = TMUX_SESSION.matches(name)

/** Only http(s) pages may be opened from outside — never javascript:, file:, intent: and the like. */
fun isWebUrl(url: String): Boolean {
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(url)?.groupValues?.get(1)?.lowercase()
    return scheme == "http" || scheme == "https"
}

/** How to show a file the user's computer asked for. */
sealed class OpenFilePlan {
    /** Already under the folder Code shows: open it by its path relative to that folder. */
    data class Direct(val relativePath: String) : OpenFilePlan()

    /** Elsewhere: open its folder in Code on [machineId], then the file by name. */
    data class Reroot(val machineId: String, val folder: String, val fileName: String) : OpenFilePlan()

    /** Not enough to go on (a relative path for a machine Code isn't on, or no machine at all). */
    data class Unresolvable(val reason: String) : OpenFilePlan()
}

/**
 * Where [path] on [machineId] (null = the machine Code is on) lands, given Code is on
 * [currentMachine] showing [root].
 */
fun planOpenFile(currentMachine: String, root: String?, machineId: String?, path: String): OpenFilePlan {
    val machine = machineId ?: currentMachine
    if (machine == "local") return OpenFilePlan.Unresolvable("Pick a machine in Code first.")
    val sameMachine = machine == currentMachine
    if (!path.startsWith("/")) {
        return if (sameMachine) {
            OpenFilePlan.Direct(path.removePrefix("./"))
        } else {
            OpenFilePlan.Unresolvable("A relative path needs Code to be on $machine; give an absolute path.")
        }
    }
    val base = root?.trimEnd('/')
    if (sameMachine && !base.isNullOrEmpty() && path.startsWith("$base/")) {
        return OpenFilePlan.Direct(path.removePrefix("$base/"))
    }
    val folder = path.substringBeforeLast('/').ifEmpty { "/" }
    return OpenFilePlan.Reroot(machine, folder, path.substringAfterLast('/'))
}

/** "doraemon_arch · app" — the label Code shows for a remote folder, as the folder browser makes it. */
fun projectLabel(machineLabel: String, path: String): String =
    "$machineLabel · ${path.trimEnd('/').substringAfterLast('/').ifEmpty { path }}"
