package dev.aura.xr.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class PaneInfo(
    val paneId: String,
    val tabId: String,
    val workspaceId: String,
    val cwd: String,
    val title: String,
    val focused: Boolean,
    /** "local" or a connected remote machine id (spec §37) — carried per-pane so
     * pane-scoped actions (read/close/interrupt) always target the right
     * machine, regardless of which machine's terminal list is on screen. */
    val machineId: String = "local",
)

data class DirEntry(val name: String, val isDir: Boolean, val language: String?)

data class FileContent(val path: String, val content: String, val language: String?, val truncated: Boolean)

data class AgentInfo(val id: String, val label: String, val status: String, val kind: String, val paneId: String)

data class WorktreeInfo(
    val path: String,
    val branch: String?,
    val isLinked: Boolean,
    val openWorkspaceId: String?,
)

data class GitFileStatus(val path: String, val oldPath: String?, val status: String)

data class VoiceCandidate(val path: String, val score: Double, val preview: String?)

data class VoiceResolution(val confident: Boolean, val resolvedPath: String?, val candidates: List<VoiceCandidate>)

data class GitStatus(val branch: String?, val files: List<GitFileStatus>)

data class MachineInfo(val id: String, val label: String, val connected: Boolean = false)

/**
 * WebSocket client for aura-bridge speaking XR protocol v1 (see ../protocol).
 * Runs the initial-sync sequence (spec §7): hello → snapshot → subscribe, and
 * resyncs on every reconnect because backend state wins (spec §58–59).
 *
 * Requests are correlated by id (a callback per pending request) rather than
 * routed by guessing the result's shape, since several request types now
 * share overlapping field names.
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val url: String,
    private val deviceId: String,
) {
    data class UiState(
        val bridgeConnected: Boolean = false,
        val herdrConnected: Boolean = false,
        val workspaces: Int = 0,
        val panes: Int = 0,
        val agents: Int = 0,
        val lastEvent: String = "—",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _terminals = MutableStateFlow<List<PaneInfo>>(emptyList())
    val terminals: StateFlow<List<PaneInfo>> = _terminals

    private val _terminalOutput = MutableStateFlow<String?>(null)
    val terminalOutput: StateFlow<String?> = _terminalOutput

    private val _dirPath = MutableStateFlow(".")
    val dirPath: StateFlow<String> = _dirPath

    private val _dirEntries = MutableStateFlow<List<DirEntry>>(emptyList())
    val dirEntries: StateFlow<List<DirEntry>> = _dirEntries

    private val _openFile = MutableStateFlow<FileContent?>(null)
    val openFile: StateFlow<FileContent?> = _openFile

    private val _voiceResult = MutableStateFlow<VoiceResolution?>(null)
    val voiceResult: StateFlow<VoiceResolution?> = _voiceResult

    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents

    private val _worktrees = MutableStateFlow<List<WorktreeInfo>>(emptyList())
    val worktrees: StateFlow<List<WorktreeInfo>> = _worktrees

    private val _machines = MutableStateFlow<List<MachineInfo>>(emptyList())
    val machines: StateFlow<List<MachineInfo>> = _machines

    private val _gitStatus = MutableStateFlow<GitStatus?>(null)
    val gitStatus: StateFlow<GitStatus?> = _gitStatus

    private val _gitDiffPath = MutableStateFlow<String?>(null)
    val gitDiffPath: StateFlow<String?> = _gitDiffPath

    private val _gitDiffText = MutableStateFlow<String?>(null)
    val gitDiffText: StateFlow<String?> = _gitDiffText

    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private val nextId = AtomicLong(1)
    private var closedByUs = false
    private val callbacks = mutableMapOf<String, (JSONObject) -> Unit>()
    private val errorCallbacks = mutableMapOf<String, (JSONObject) -> Unit>()

    fun connect() {
        closedByUs = false
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, Listener())
    }

    fun close() {
        closedByUs = true
        ws?.close(1000, "bye")
    }

    private fun send(
        type: String,
        fields: JSONObject = JSONObject(),
        onResult: ((JSONObject) -> Unit)? = null,
        onError: ((JSONObject) -> Unit)? = null,
    ) {
        val id = "a${nextId.getAndIncrement()}"
        fields.put("id", id)
        fields.put("type", type)
        if (onResult != null) callbacks[id] = onResult
        if (onError != null) errorCallbacks[id] = onError
        ws?.send(fields.toString())
    }

    // ── Terminals ────────────────────────────────────────────────────────
    // "local" or a connected remote machine id (spec §37) — every call takes
    // it explicitly rather than trailing a stored "current machine", since
    // the agent detail card shares several of these functions and its
    // targets are always local regardless of which machine the Terminal
    // view happens to be showing.

    private val _terminalsMachineId = MutableStateFlow("local")
    val terminalsMachineId: StateFlow<String> = _terminalsMachineId

    /** Switches which machine's terminal list the Terminal view shows, and reloads it. */
    fun switchTerminalsMachine(machineId: String) {
        _terminalsMachineId.value = machineId
        _terminals.value = emptyList()
        refreshTerminals(machineId)
    }

    fun refreshTerminals(machineId: String = _terminalsMachineId.value, onDone: () -> Unit = {}) {
        send(
            "terminal.list",
            JSONObject().put("machineId", machineId),
            onResult = { result ->
                val arr = result.optJSONArray("terminals") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    PaneInfo(
                        paneId = t.optString("paneId"),
                        tabId = t.optString("tabId"),
                        workspaceId = t.optString("workspaceId"),
                        cwd = t.optString("cwd", ""),
                        title = t.optString("name", t.optString("paneId")),
                        focused = t.optBoolean("focused", false),
                        machineId = machineId,
                    )
                }
                _terminals.value = list
                onDone()
            },
        )
    }

    fun readTerminal(paneId: String, machineId: String = "local") {
        send(
            "terminal.read",
            JSONObject().put("paneId", paneId).put("machineId", machineId).put("source", "visible").put("format", "text"),
            onResult = { result ->
                _terminalOutput.value = result.optString("content", "")
            },
        )
    }

    /**
     * One-off read that doesn't touch [terminalOutput] — used by the agent
     * detail card's preview, which must not clobber whatever the Terminal
     * view currently has selected.
     */
    fun peekTerminal(paneId: String, machineId: String = "local", onResult: (String) -> Unit) {
        send(
            "terminal.read",
            JSONObject().put("paneId", paneId).put("machineId", machineId).put("source", "visible").put("format", "text"),
            onResult = { result -> onResult(result.optString("content", "")) },
        )
    }

    fun createTerminal(name: String? = null, machineId: String = _terminalsMachineId.value, cwd: String? = null) {
        val fields = JSONObject().put("machineId", machineId)
        if (name != null) fields.put("name", name)
        if (cwd != null) fields.put("cwd", cwd)
        send("terminal.create", fields, onResult = {
            // Only actually switch (which clears the list first) if this created
            // a pane on a different machine than the Terminal view is showing —
            // the common "+ New" case targets the current one and should just refresh.
            if (_terminalsMachineId.value != machineId) switchTerminalsMachine(machineId) else refreshTerminals(machineId)
        })
    }

    fun closeTerminal(paneId: String, machineId: String = "local", onDone: () -> Unit = {}) {
        send("terminal.close", JSONObject().put("paneId", paneId).put("machineId", machineId), onResult = {
            refreshTerminals(machineId)
            onDone()
        })
    }

    /**
     * "Stop process?" card content (spec §20) — what's actually running right
     * now. Resolves to the foreground process's command line, e.g. "npm run
     * dev", or a generic fallback if Herdr can't identify one.
     */
    fun fetchProcessInfo(paneId: String, machineId: String = "local", onResult: (String) -> Unit) {
        send(
            "terminal.process_info",
            JSONObject().put("paneId", paneId).put("machineId", machineId),
            onResult = { result ->
                val processInfo = result.optJSONObject("process")?.optJSONObject("process_info")
                val foreground = processInfo?.optJSONArray("foreground_processes")
                val top = if (foreground != null && foreground.length() > 0) foreground.getJSONObject(0) else null
                val label = top?.optString("cmdline")?.takeIf { it.isNotBlank() }
                    ?: top?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: "the running process"
                onResult(label)
            },
        )
    }

    /** Ctrl+C — the protocol requires `confirmed:true`, so only call this after the XR confirmation card. */
    fun interruptTerminal(paneId: String, machineId: String = "local", onDone: () -> Unit = {}) {
        send(
            "terminal.interrupt",
            JSONObject().put("paneId", paneId).put("machineId", machineId).put("confirmed", true),
            onResult = {
                readTerminal(paneId, machineId)
                onDone()
            },
        )
    }

    // ── Files ────────────────────────────────────────────────────────────

    /**
     * Which Herdr workspace the file browser is rooted at. "local" resolves
     * bridge-side to aura-bridge's own project root; any real Herdr
     * workspace_id resolves to that workspace's pane cwd (see aura-bridge's
     * `filesFor`) — which is exactly a worktree's checkout path, so jumping
     * the Code view into a worktree is just switching this id.
     */
    private var filesWorkspaceId = "local"
    /** "local" or a connected remote machine id — which machine `filesWorkspaceId` is interpreted against. */
    private var filesMachineId = "local"

    private val _filesRootLabel = MutableStateFlow("local")
    val filesRootLabel: StateFlow<String> = _filesRootLabel

    /** Re-roots the file browser at a worktree (or any Herdr workspace) and opens it in Code. Defaults to local. */
    fun openWorkspaceInFiles(workspaceId: String, label: String, machineId: String = "local") {
        filesMachineId = machineId
        filesWorkspaceId = workspaceId
        _filesRootLabel.value = label
        listDir(".")
    }

    /**
     * Re-roots the file browser at an absolute folder on a remote machine
     * (spec §37) — e.g. a folder picked in the remote browse dialog. Unlike
     * [openWorkspaceInFiles], `root` isn't a Herdr workspace id to resolve;
     * it's used as the literal filesystem root, matching how the bridge's
     * `RemoteFiles` treats it (see aura-bridge's resolveFiles).
     */
    fun openRemoteFolderInFiles(machineId: String, root: String, label: String) {
        filesMachineId = machineId
        filesWorkspaceId = root
        _filesRootLabel.value = label
        listDir(".")
    }

    fun listDir(path: String) {
        send(
            "dir.list",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("path", path),
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    DirEntry(
                        name = e.optString("name"),
                        isDir = e.optString("kind") == "dir",
                        language = if (e.isNull("language")) null else e.optString("language"),
                    )
                }
                _dirPath.value = path
                _dirEntries.value = list
                _openFile.value = null
            },
        )
    }

    fun openFile(path: String) {
        send(
            "file.open",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("path", path),
            onResult = { result ->
                _openFile.value = FileContent(
                    path = result.optString("path", path),
                    content = result.optString("content", ""),
                    language = if (result.isNull("language")) null else result.optString("language"),
                    truncated = result.optBoolean("truncated", false),
                )
            },
        )
    }

    // ── Voice file search ────────────────────────────────────────────────
    // Never silently guesses (spec §13, §63): a confident hit still comes
    // back with candidates attached, and an unconfident one carries no
    // resolvedPath at all — the UI decides what "confident enough" means to
    // show, but it can never open a file the bridge itself wasn't sure about.

    fun voiceSearch(query: String) {
        _voiceResult.value = null
        send(
            "voice.search",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("query", query),
            onResult = { result ->
                val arr = result.optJSONArray("candidates") ?: JSONArray()
                val candidates = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    VoiceCandidate(
                        path = c.optString("path"),
                        score = c.optDouble("score", 0.0),
                        preview = if (c.isNull("preview")) null else c.optString("preview"),
                    )
                }
                _voiceResult.value = VoiceResolution(
                    confident = result.optBoolean("confident", false),
                    resolvedPath = if (result.isNull("resolvedPath")) null else result.optString("resolvedPath"),
                    candidates = candidates,
                )
            },
        )
    }

    fun clearVoiceResult() {
        _voiceResult.value = null
    }

    // ── Git diff ─────────────────────────────────────────────────────────
    // Always scoped to whatever workspace the Code view is currently rooted
    // at (filesWorkspaceId) — "what changed" should track "what I'm browsing".

    fun refreshGitStatus() {
        send(
            "git.status",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId),
            onResult = { result ->
                val arr = result.optJSONArray("files") ?: JSONArray()
                val files = (0 until arr.length()).map { i ->
                    val f = arr.getJSONObject(i)
                    GitFileStatus(
                        path = f.optString("path"),
                        oldPath = if (f.isNull("oldPath")) null else f.optString("oldPath"),
                        status = f.optString("status"),
                    )
                }
                _gitStatus.value = GitStatus(
                    branch = if (result.isNull("branch")) null else result.optString("branch"),
                    files = files,
                )
                _gitDiffPath.value = null
                _gitDiffText.value = null
            },
        )
    }

    // ── Branch switcher ──────────────────────────────────────────────────

    private val _branches = MutableStateFlow<List<String>>(emptyList())
    val branches: StateFlow<List<String>> = _branches

    private val _currentBranch = MutableStateFlow<String?>(null)
    val currentBranch: StateFlow<String?> = _currentBranch

    fun refreshBranches() {
        send(
            "git.branches",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId),
            onResult = { result ->
                val arr = result.optJSONArray("branches") ?: JSONArray()
                _branches.value = (0 until arr.length()).map { arr.getString(it) }
                _currentBranch.value = if (result.isNull("current")) null else result.optString("current")
            },
        )
    }

    /**
     * Switches to [target] in the currently-browsed workspace. [onDirty] is
     * omitted on the first attempt; if the tree has uncommitted changes the
     * bridge refuses with a structured "dirty" conflict rather than picking a
     * resolution itself (spec §27) — [onConflict] gets called so the UI can
     * ask the user to choose, then this is called again with their answer.
     */
    fun switchBranch(
        target: String,
        onDirty: String? = null,
        onSwitched: () -> Unit,
        onConflict: () -> Unit,
        /** (workspaceId, machineId) — the machine is whatever Code was scoped to when the switch was requested. */
        onWorktreeCreated: (String?, String) -> Unit,
    ) {
        val machineId = filesMachineId
        val fields = JSONObject()
            .put("machineId", machineId)
            .put("workspaceId", filesWorkspaceId)
            .put("target", target)
        if (onDirty != null) fields.put("onDirty", onDirty)
        send(
            "git.branch.switch",
            fields,
            onResult = { result ->
                when {
                    result.optBoolean("worktreeCreated", false) -> {
                        val wsId = if (result.isNull("workspaceId")) null else result.optString("workspaceId")
                        onWorktreeCreated(wsId, machineId)
                    }
                    result.optBoolean("switched", false) -> {
                        refreshBranches()
                        refreshGitStatus()
                        onSwitched()
                    }
                    else -> Unit // aborted — nothing changed, nothing to refresh
                }
            },
            onError = { error ->
                if (error.optString("detail") == "dirty") onConflict() else Unit
            },
        )
    }

    fun loadGitDiff(path: String) {
        _gitDiffPath.value = path
        _gitDiffText.value = null
        send(
            "git.diff",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("path", path),
            onResult = { result ->
                if (_gitDiffPath.value == path) _gitDiffText.value = result.optString("diff", "")
            },
        )
    }

    /**
     * Transparent proxy to a Herdr method (spec §70's `herdr.request`, allowlisted
     * bridge-side to workspace./tab./pane./agent./worktree./layout./notification.).
     * Unwraps the bridge's `{response: {id, result}}` envelope down to Herdr's own
     * result object. Two independent failure modes both route to [onError]: a
     * bridge-level error (unauthorized method, Herdr unreachable — arrives as the
     * message's own `error`), and a Herdr-level error from the proxied call itself
     * (arrives as `response.error` alongside a message-level `result`).
     */
    private fun herdrRequest(
        method: String,
        params: JSONObject = JSONObject(),
        machineId: String = "local",
        onError: (() -> Unit)? = null,
        onResult: (JSONObject) -> Unit,
    ) {
        send(
            "herdr.request",
            JSONObject()
                .put("machineId", machineId)
                .put(
                    "request",
                    JSONObject().put("id", "hr${nextId.getAndIncrement()}").put("method", method).put("params", params),
                ),
            onResult = { result ->
                val response = result.optJSONObject("response")
                val inner = response?.optJSONObject("result")
                if (inner != null) onResult(inner) else onError?.invoke()
            },
            onError = { onError?.invoke() },
        )
    }

    // ── Agents ───────────────────────────────────────────────────────────

    fun refreshAgents() {
        herdrRequest("agent.list") { inner ->
            val arr = inner.optJSONArray("agents") ?: JSONArray()
            val list = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val kind = a.optString("agent", "agent")
                AgentInfo(
                    id = a.optString("pane_id", "agent$i"),
                    label = a.optString("name").takeIf { it.isNotBlank() } ?: kind,
                    status = a.optString("agent_status", "unknown"),
                    kind = kind,
                    paneId = a.optString("pane_id"),
                )
            }
            _agents.value = list
        }
    }

    /**
     * Sends a chat-style turn to a running agent. Herdr rejects this outright
     * (`agent_blocked`) if the agent is sitting at a raw prompt like a
     * "trust this folder?" choice — that needs real keystrokes via the
     * terminal instead, which is why the detail card only offers this input
     * when the agent isn't blocked.
     */
    fun sendAgentPrompt(paneId: String, text: String, onDone: () -> Unit = {}, onError: (() -> Unit)? = null) {
        herdrRequest(
            "agent.prompt",
            JSONObject().put("target", paneId).put("text", text),
            onError = onError,
        ) {
            refreshAgents()
            onDone()
        }
    }

    // ── Remote machines (spec §36, registry only — no tunnel yet) ─────────

    fun refreshMachines() {
        send("machine.list", onResult = { result ->
            val arr = result.optJSONArray("machines") ?: JSONArray()
            _machines.value = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                MachineInfo(
                    id = m.optString("id"),
                    label = m.optString("label"),
                    connected = m.optBoolean("connected", false),
                )
            }
        })
    }

    private val _machineConnectError = MutableStateFlow<String?>(null)
    val machineConnectError: StateFlow<String?> = _machineConnectError

    private val _connectingMachineId = MutableStateFlow<String?>(null)
    val connectingMachineId: StateFlow<String?> = _connectingMachineId

    /**
     * Opens the SSH tunnel + remote Herdr connection (spec §37) — can take a
     * few seconds. On success, opens the remote folder browser right away
     * (starting at the machine's real home directory) so connecting always
     * leads somewhere, rather than landing on an otherwise-empty machine row.
     */
    fun connectMachine(machineId: String, label: String) {
        _machineConnectError.value = null
        _connectingMachineId.value = machineId
        send(
            "machine.connect",
            JSONObject().put("machineId", machineId),
            onResult = { result ->
                _connectingMachineId.value = null
                refreshMachines()
                val homeDir = if (result.isNull("homeDir")) null else result.optString("homeDir")
                startRemoteBrowse(machineId, label, homeDir?.trimEnd('/')?.ifBlank { "/" } ?: "/")
            },
            onError = { error ->
                _connectingMachineId.value = null
                _machineConnectError.value = error.optString("message", "Could not connect to $machineId.")
            },
        )
    }

    // ── Remote folder browser (spec §37) — picks the folder that "Connect" opens into Code + Terminal ──

    data class RemoteBrowseEntry(val name: String, val isDir: Boolean)

    data class RemoteBrowseState(
        val machineId: String,
        val machineLabel: String,
        val path: String,
        val entries: List<RemoteBrowseEntry>,
        val loading: Boolean,
    )

    private val _remoteBrowse = MutableStateFlow<RemoteBrowseState?>(null)
    val remoteBrowse: StateFlow<RemoteBrowseState?> = _remoteBrowse

    fun startRemoteBrowse(machineId: String, machineLabel: String, startPath: String) {
        _remoteBrowse.value = RemoteBrowseState(machineId, machineLabel, startPath, emptyList(), loading = true)
        fetchRemoteBrowseDir(machineId, startPath)
    }

    fun browseRemoteDir(path: String) {
        val current = _remoteBrowse.value ?: return
        _remoteBrowse.value = current.copy(loading = true)
        fetchRemoteBrowseDir(current.machineId, path)
    }

    fun browseRemoteUp() {
        val current = _remoteBrowse.value ?: return
        val parent = current.path.trimEnd('/').substringBeforeLast('/', "/").ifBlank { "/" }
        browseRemoteDir(parent)
    }

    private fun fetchRemoteBrowseDir(machineId: String, path: String) {
        // "." as the browsed path against `path` as the workspace root — same
        // convention resolveFiles uses server-side: workspaceId IS the absolute
        // root for a remote machine, not a Herdr workspace id to resolve.
        send(
            "dir.list",
            JSONObject().put("machineId", machineId).put("workspaceId", path).put("path", "."),
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    RemoteBrowseEntry(name = e.optString("name"), isDir = e.optString("kind") == "dir")
                }
                val current = _remoteBrowse.value
                if (current != null && current.machineId == machineId) {
                    _remoteBrowse.value = current.copy(path = path, entries = entries, loading = false)
                }
            },
        )
    }

    fun closeRemoteBrowse() {
        _remoteBrowse.value = null
    }

    /** Opens the currently-browsed folder in both Code (this machine+path) and a fresh Terminal rooted there. */
    fun openRemoteBrowsedFolder() {
        val current = _remoteBrowse.value ?: return
        val folderName = current.path.trimEnd('/').substringAfterLast('/').ifBlank { current.path }
        openRemoteFolderInFiles(current.machineId, current.path, "${current.machineLabel} · $folderName")
        createTerminal(name = folderName, machineId = current.machineId, cwd = current.path)
        _remoteBrowse.value = null
    }

    fun disconnectMachine(machineId: String) {
        send(
            "machine.disconnect",
            JSONObject().put("machineId", machineId),
            onResult = { refreshMachines() },
        )
    }

    fun clearMachineConnectError() {
        _machineConnectError.value = null
    }

    // ── Git worktrees & branches ─────────────────────────────────────────
    // Scoped to whichever machine the Code view is currently rooted at
    // (filesMachineId) — worktrees, like Diff and the branch switcher,
    // follow Code rather than tracking their own separate machine context.

    /** Once a workspace_id is confirmed to resolve inside a Git work tree, reuse it — but only for the same machine. */
    private var worktreeWorkspaceId: String? = null
    private var worktreeMachineId: String? = null

    fun refreshWorktrees() {
        val machineId = filesMachineId
        if (worktreeMachineId == machineId && worktreeWorkspaceId != null) {
            fetchWorktrees(machineId, worktreeWorkspaceId!!)
            return
        }
        worktreeWorkspaceId = null
        // worktree.list requires a workspace whose cwd sits inside a Git work
        // tree, and the client has no fixed answer for which one that is on
        // this machine — try every pane's workspace on it until one resolves.
        fetchPaneWorkspaceIds(machineId) { ids -> tryWorktreeCandidates(machineId, ids) }
    }

    /** Distinct workspace ids from this machine's live panes, without touching the shared Terminal-view list. */
    private fun fetchPaneWorkspaceIds(machineId: String, onResult: (List<String>) -> Unit) {
        send(
            "terminal.list",
            JSONObject().put("machineId", machineId),
            onResult = { result ->
                val arr = result.optJSONArray("terminals") ?: JSONArray()
                val ids = (0 until arr.length()).map { arr.getJSONObject(it).optString("workspaceId") }.distinct()
                onResult(ids)
            },
        )
    }

    private fun tryWorktreeCandidates(machineId: String, remaining: List<String>) {
        val id = remaining.firstOrNull() ?: run {
            _worktrees.value = emptyList()
            return
        }
        herdrRequest(
            "worktree.list",
            JSONObject().put("workspace_id", id),
            machineId = machineId,
            onError = { tryWorktreeCandidates(machineId, remaining.drop(1)) },
        ) { inner ->
            worktreeWorkspaceId = id
            worktreeMachineId = machineId
            applyWorktrees(inner)
        }
    }

    private fun fetchWorktrees(machineId: String, workspaceId: String) {
        herdrRequest(
            "worktree.list",
            JSONObject().put("workspace_id", workspaceId),
            machineId = machineId,
            onError = {
                // Cached workspace stopped resolving (e.g. it was closed) —
                // drop it and re-search from scratch next time.
                worktreeWorkspaceId = null
                _worktrees.value = emptyList()
            },
        ) { inner -> applyWorktrees(inner) }
    }

    /**
     * Opens a worktree's checkout in the Code view. Most worktrees we create
     * already have a live Herdr workspace (`openWorkspaceId`) — reuse it
     * directly. If one doesn't (e.g. after a Herdr restart dropped it),
     * `worktree.open` re-attaches a workspace to that same checkout path
     * before browsing it. Always on whatever machine the Git view is
     * currently scoped to (filesMachineId).
     */
    fun openWorktreeInCode(worktree: WorktreeInfo, onOpened: () -> Unit) {
        val label = worktree.branch ?: worktree.path.substringAfterLast('/')
        val existing = worktree.openWorkspaceId
        if (existing != null) {
            openWorkspaceInFiles(existing, label, filesMachineId)
            onOpened()
            return
        }
        herdrRequest(
            "worktree.open",
            JSONObject().put("path", worktree.path).put("focus", false),
            machineId = filesMachineId,
            onError = { onOpened() },
        ) { inner ->
            val workspaceId = inner.optJSONObject("workspace")?.optString("workspace_id")
            if (workspaceId != null) openWorkspaceInFiles(workspaceId, label, filesMachineId)
            onOpened()
        }
    }

    /**
     * Removes a worktree's checkout (spec §57 — destructive, caller must have
     * already shown a confirmation card). Only works for a worktree that's
     * currently open (has a live workspace_id) — matches what the Git view
     * can already show/act on.
     */
    fun removeWorktree(worktree: WorktreeInfo, onDone: () -> Unit, onError: (String) -> Unit) {
        val workspaceId = worktree.openWorkspaceId
        if (workspaceId == null) {
            onError("That worktree isn't currently open.")
            return
        }
        herdrRequest(
            "worktree.remove",
            JSONObject().put("workspace_id", workspaceId).put("force", true),
            machineId = filesMachineId,
            onError = { onError("Could not remove that worktree.") },
        ) {
            refreshWorktrees()
            onDone()
        }
    }

    /** Destructive (spec §57) — only ever called after the caller's own confirmation card. */
    fun deleteBranch(branch: String, force: Boolean = false, onDone: () -> Unit, onError: (String) -> Unit) {
        send(
            "git.branch.delete",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("branch", branch).put("force", force),
            onResult = {
                refreshBranches()
                onDone()
            },
            onError = { error -> onError(error.optString("message", "Could not delete $branch.")) },
        )
    }

    private fun applyWorktrees(inner: JSONObject) {
        val arr = inner.optJSONArray("worktrees") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            WorktreeInfo(
                path = w.optString("path"),
                branch = if (w.isNull("branch")) null else w.optString("branch"),
                isLinked = w.optBoolean("is_linked_worktree", false),
                openWorkspaceId = if (w.isNull("open_workspace_id")) null else w.optString("open_workspace_id"),
            )
        }
        _worktrees.value = list
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("AuraBridge", "onOpen: socket open, sending hello")
            send(
                "hello",
                JSONObject()
                    .put("protocolVersion", 1)
                    .put("deviceId", deviceId)
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("app", "aura-xr-android")
                            .put("version", "0.1.0")
                            .put("platform", "android-xr"),
                    ),
                onResult = { helloResult ->
                    Log.d("AuraBridge", "hello result received: $helloResult")
                    _state.value = _state.value.copy(
                        bridgeConnected = true,
                        herdrConnected = helloResult.optBoolean("herdrConnected", false),
                    )
                    send("snapshot.request", onResult = { snapshotResult -> applySnapshot(snapshotResult) })
                    send("events.subscribe", JSONObject().put("topics", JSONArray().put("herdr")))
                    refreshTerminals()
                    refreshAgents()
                },
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            if (msg.optString("type") == "event") {
                handleEvent(msg)
                return
            }
            val id = if (msg.isNull("id")) null else msg.optString("id")
            if (id == null) return
            val result = msg.optJSONObject("result")
            try {
                if (result != null) {
                    errorCallbacks.remove(id)
                    callbacks.remove(id)?.invoke(result)
                } else {
                    callbacks.remove(id)
                    errorCallbacks.remove(id)?.invoke(msg.optJSONObject("error") ?: JSONObject())
                }
            } catch (e: Exception) {
                // A malformed/unexpected response must never take down the reader
                // loop — that would silently kill the whole connection.
                Log.e("AuraBridge", "callback for $id threw", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("AuraBridge", "WebSocket failure: ${t.javaClass.simpleName}: ${t.message}", t)
            _state.value = _state.value.copy(bridgeConnected = false)
            if (closedByUs) return
            // Spec §59: never assume backend processes stopped; retry and resync.
            scope.launch {
                delay(3000)
                connect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("AuraBridge", "onClosed: code=$code reason=$reason")
            _state.value = _state.value.copy(bridgeConnected = false)
        }
    }

    private fun applySnapshot(result: JSONObject) {
        val snapshot = result.optJSONObject("herdr")?.optJSONObject("snapshot")
        _state.value = _state.value.copy(
            workspaces = snapshot?.optJSONArray("workspaces")?.length() ?: 0,
            panes = snapshot?.optJSONArray("panes")?.length() ?: 0,
            agents = snapshot?.optJSONArray("agents")?.length() ?: 0,
        )
    }

    private fun handleEvent(msg: JSONObject) {
        if (msg.optString("topic") != "herdr") return
        _state.value = _state.value.copy(lastEvent = msg.optString("event", "—"))
        send("snapshot.request", onResult = { snapshotResult -> applySnapshot(snapshotResult) })
        refreshTerminals()
    }
}
