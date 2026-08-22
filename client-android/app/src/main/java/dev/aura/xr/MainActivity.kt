package dev.aura.xr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.xr.compose.platform.requestFullSpace
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.padding
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import dev.aura.xr.net.BridgeClient
import dev.aura.xr.net.PaneInfo
import dev.aura.xr.ui.AgentChipContent
import dev.aura.xr.ui.AgentsPanelContent
import dev.aura.xr.ui.AuraColors
import dev.aura.xr.ui.DiffPanelContent
import dev.aura.xr.ui.DockButtonContent
import dev.aura.xr.ui.FilesPanelContent
import dev.aura.xr.ui.GitPanelContent
import dev.aura.xr.ui.RemoteBrowseOverlay
import dev.aura.xr.ui.RemotePanelContent
import dev.aura.xr.ui.PrimaryPanelContent
import dev.aura.xr.ui.TerminalPanelContent
import dev.aura.xr.ui.VoiceOverlay
import dev.aura.xr.voice.VoiceRecognizer

class MainActivity : ComponentActivity() {

    private lateinit var bridge: BridgeClient

    override fun onCreate(savedInstanceState: Bundle?) {
        // All UI lives inside Subspace, which SceneCore renders through its own
        // spatial compositor rather than the Activity's normal window content
        // view — so the system's default splash icon never gets a "first
        // frame drawn" signal to dismiss itself, and sits on screen forever.
        // Dismiss it immediately; there is no real loading gate to wait on.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)

        // The Activity's own 2D window is separate from Subspace content and
        // is opaque black by default — without this it renders as a solid
        // black panel with the launcher icon behind the spatial panels.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(AndroidColor.TRANSPARENT))

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-xr-dev"
        // 10.0.2.2 = host loopback from the Android emulator (aura-bridge).
        bridge = BridgeClient(lifecycleScope, "ws://10.0.2.2:8137", deviceId)
        bridge.connect()

        setContent { AuraWorkspace(bridge) }

        // The manifest's FULL_SPACE_MANAGED start mode only sets the default
        // launch mode — actually entering full space (so SpatialPanel content
        // attaches to a real scene node instead of the system Home
        // environment staying HOME_VISIBLE) requires this explicit call.
        lifecycleScope.launch { requestFullSpace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.close()
    }
}

/**
 * Builds the width/height/offset/movable/resizable chain for one panel,
 * loading its saved position/size (if any) and persisting on every move/
 * resize end (spec §52) — shared by every panel so "move it, it stays moved"
 * isn't just the primary panel's behavior.
 */
@Composable
private fun rememberPanelModifier(
    layoutStore: dev.aura.xr.layout.LayoutStore,
    id: String,
    defaultWidth: Float,
    defaultHeight: Float,
    padding: androidx.compose.ui.unit.Dp,
    /** Bumped by "Reset Layout" to force every panel to re-read (now-cleared) saved state. */
    generation: Int = 0,
): SubspaceModifier {
    var offset by remember(id, generation) {
        mutableStateOf(layoutStore.loadOffset(id) ?: dev.aura.xr.layout.SavedOffset(0f, 0f, 0f))
    }
    var size by remember(id, generation) {
        mutableStateOf(layoutStore.loadSize(id) ?: dev.aura.xr.layout.SavedSize(defaultWidth, defaultHeight))
    }
    return SubspaceModifier
        .width(size.width.dp)
        .height(size.height.dp)
        .padding(padding)
        .offset(offset.x.dp, offset.y.dp, offset.z.dp)
        .movable(onMoveEnd = { event ->
            val t = event.pose.translation
            offset = dev.aura.xr.layout.SavedOffset(t.x, t.y, t.z)
            layoutStore.saveOffset(id, offset)
        })
        .resizable(onResizeEnd = { newSize ->
            size = dev.aura.xr.layout.SavedSize(newSize.width.toFloat(), newSize.height.toFloat())
            layoutStore.saveSize(id, size)
            true
        })
}

/**
 * Spatial layout mirroring the mockup (spec §71): agent strip on top, one
 * dominant primary panel, quick dock underneath.
 */
@Composable
fun AuraWorkspace(bridge: BridgeClient) {
    val state by bridge.state.collectAsState()
    val terminals by bridge.terminals.collectAsState()
    val terminalOutput by bridge.terminalOutput.collectAsState()
    val dirPath by bridge.dirPath.collectAsState()
    val dirEntries by bridge.dirEntries.collectAsState()
    val filesRootLabel by bridge.filesRootLabel.collectAsState()
    val openFile by bridge.openFile.collectAsState()
    val agents by bridge.agents.collectAsState()
    val worktrees by bridge.worktrees.collectAsState()
    val machines by bridge.machines.collectAsState()
    val connectingMachineId by bridge.connectingMachineId.collectAsState()
    val machineConnectError by bridge.machineConnectError.collectAsState()
    val terminalsMachineId by bridge.terminalsMachineId.collectAsState()
    val remoteBrowse by bridge.remoteBrowse.collectAsState()

    // ── Panel layout persistence (spec §52) ─────────────────────────────
    val layoutStore = dev.aura.xr.layout.rememberLayoutStore()
    var layoutGeneration by remember { mutableStateOf(0) }
    fun resetLayout() {
        layoutStore.resetAll()
        layoutGeneration++ // forces every rememberPanelModifier call to re-read (now default) state
    }
    val gitStatus by bridge.gitStatus.collectAsState()
    val gitDiffPath by bridge.gitDiffPath.collectAsState()
    val gitDiffText by bridge.gitDiffText.collectAsState()
    val branches by bridge.branches.collectAsState()
    val currentBranch by bridge.currentBranch.collectAsState()
    val voiceResult by bridge.voiceResult.collectAsState()
    var activeView by remember { mutableStateOf("home") }
    var selectedPane by remember { mutableStateOf<PaneInfo?>(null) }
    var stopConfirm by remember { mutableStateOf<dev.aura.xr.ui.StopConfirmState?>(null) }
    var branchConflict by remember { mutableStateOf<dev.aura.xr.ui.BranchConflictState?>(null) }
    var deleteConfirm by remember { mutableStateOf<dev.aura.xr.ui.DeleteConfirmState?>(null) }
    var agentDetail by remember { mutableStateOf<dev.aura.xr.ui.AgentDetailState?>(null) }
    var agentPromptText by remember { mutableStateOf("") }

    fun openAgentDetail(agent: dev.aura.xr.net.AgentInfo) {
        agentDetail = dev.aura.xr.ui.AgentDetailState(agent, preview = null)
        agentPromptText = ""
        bridge.peekTerminal(agent.paneId) { text ->
            // Guard against a stale response landing after the card was
            // dismissed or reopened on a different agent in the meantime.
            if (agentDetail?.agent?.paneId == agent.paneId) {
                agentDetail = agentDetail?.copy(preview = text)
            }
        }
    }

    fun openAgentTerminal(agent: dev.aura.xr.net.AgentInfo) {
        // Agents are always local — force the Terminal view back to "local"
        // explicitly rather than trusting whatever machine it last showed
        // (it may currently be pointed at a connected remote).
        val pane = terminals.find { it.paneId == agent.paneId && it.machineId == "local" }
            ?: PaneInfo(agent.paneId, "", "", "", agent.label, focused = false, machineId = "local")
        selectedPane = pane
        bridge.readTerminal(agent.paneId, machineId = "local")
        bridge.switchTerminalsMachine("local") // also updates the Terminal view's machine context/badge
        activeView = "terminal"
        agentDetail = null
    }

    // ── Voice input (spec §13, §63) — shared recognizer, two destinations:
    // file search (global overlay) and, once an agent card is open, filling
    // its prompt box directly instead. Never auto-submits what it hears —
    // file search still goes through candidates/confidence, and a voice
    // agent-prompt lands in the text field for you to review and hit Send,
    // never fires straight into agent.prompt on its own.
    val context = LocalContext.current
    val voiceRecognizer = remember { VoiceRecognizer(context) }
    var isListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var voiceMode by remember { mutableStateOf("fileSearch") }
    var pendingVoiceMode by remember { mutableStateOf("fileSearch") }

    fun startListening(mode: String) {
        voiceMode = mode
        voiceError = null
        voicePartial = ""
        isListening = true
        if (mode == "fileSearch") bridge.clearVoiceResult()
        voiceRecognizer.listen(
            onPartial = { text ->
                voicePartial = text
                if (mode == "agentPrompt") agentPromptText = text // live feedback right in the field being filled
            },
            onResult = { text ->
                isListening = false
                when (mode) {
                    "fileSearch" -> bridge.voiceSearch(text)
                    "agentPrompt" -> agentPromptText = text
                }
            },
            onError = { message ->
                isListening = false
                voiceError = message
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(pendingVoiceMode) else voiceError = "Microphone permission is required for voice input."
    }

    fun requestVoice(mode: String) {
        if (isListening) return // already listening — a second tap shouldn't restart the session mid-capture
        pendingVoiceMode = mode
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening(mode) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun requestVoiceSearch() = requestVoice("fileSearch")

    // A confident hit opens straight away; anything else surfaces candidates
    // (or "no match") through the overlay instead of ever guessing (spec §13).
    LaunchedEffect(voiceResult) {
        val result = voiceResult ?: return@LaunchedEffect
        if (result.confident && result.resolvedPath != null) {
            bridge.openFile(result.resolvedPath)
            activeView = "code"
            bridge.clearVoiceResult()
        }
    }

    // Refresh the data a view needs the moment it becomes active, rather than
    // only at connect time — dock switches are the user's "load this" signal.
    // Code is skipped when we already have a listing: jumping here from a
    // worktree click already fired its own dir.list at the new root, and
    // re-requesting the (stale, pre-switch) dirPath here would race it.
    LaunchedEffect(activeView) {
        when (activeView) {
            "terminal" -> bridge.refreshTerminals()
            "code" -> if (dirEntries.isEmpty()) bridge.listDir(dirPath)
            "agents" -> bridge.refreshAgents()
            "git" -> {
                bridge.refreshWorktrees()
                bridge.refreshBranches()
            }
            "diff" -> bridge.refreshGitStatus()
            "remote" -> bridge.refreshMachines()
        }
    }

    Subspace {
        SpatialColumn {
            // ── Agent strip — real herdr agent.list, honestly empty if none ──
            SpatialRow {
                if (agents.isEmpty()) {
                    SpatialPanel(rememberPanelModifier(layoutStore, "agents.empty", 460f, 90f, 8.dp, layoutGeneration)) {
                        AgentChipContent("—", "No agents running", AuraColors.Idle)
                    }
                } else {
                    for (agent in agents) {
                        androidx.compose.runtime.key(agent.paneId) {
                            SpatialPanel(
                                rememberPanelModifier(layoutStore, "agent.${agent.paneId}", 220f, 110f, 8.dp, layoutGeneration),
                            ) {
                                Box(Modifier.fillMaxSize().clickable { openAgentDetail(agent) }) {
                                    AgentChipContent(agent.label, agent.status, AuraColors.agentStatusColor(agent.status), agent.kind)
                                }
                            }
                        }
                    }
                }
            }

            // ── Primary panel ──────────────────────────────────────────────
            SpatialPanel(
                rememberPanelModifier(layoutStore, "primary", 900f, 520f, 12.dp, layoutGeneration),
            ) {
                Box(Modifier.fillMaxSize()) {
                when (activeView) {
                    "terminal" -> TerminalPanelContent(
                        terminals = terminals,
                        machineId = terminalsMachineId,
                        selected = selectedPane,
                        output = terminalOutput,
                        onSelect = { pane ->
                            selectedPane = pane
                            bridge.readTerminal(pane.paneId, pane.machineId)
                        },
                        onNew = { bridge.createTerminal() },
                        onClose = { pane ->
                            bridge.closeTerminal(pane.paneId, pane.machineId) {
                                if (selectedPane?.paneId == pane.paneId) selectedPane = null
                            }
                        },
                        onRequestStop = { pane ->
                            // Fetch what's actually running before showing the
                            // card — the confirmation is meaningless if it
                            // can't say what it's about to interrupt.
                            bridge.fetchProcessInfo(pane.paneId, pane.machineId) { label ->
                                stopConfirm = dev.aura.xr.ui.StopConfirmState(
                                    pane = pane,
                                    processLabel = label,
                                    onConfirm = {
                                        bridge.interruptTerminal(pane.paneId, pane.machineId)
                                        stopConfirm = null
                                    },
                                    onCancel = { stopConfirm = null },
                                )
                            }
                        },
                        stopConfirm = stopConfirm,
                    )
                    "code" -> FilesPanelContent(
                        rootLabel = filesRootLabel,
                        path = dirPath,
                        entries = dirEntries,
                        file = openFile,
                        onOpenDir = { bridge.listDir(it) },
                        onOpenFile = { bridge.openFile(it) },
                        onUp = {
                            val parent = dirPath.substringBeforeLast('/', ".")
                            bridge.listDir(parent)
                        },
                    )
                    "agents" -> AgentsPanelContent(agents, onSelect = { openAgentDetail(it) })
                    "diff" -> DiffPanelContent(
                        status = gitStatus,
                        diffPath = gitDiffPath,
                        diffText = gitDiffText,
                        onSelectFile = { path -> bridge.loadGitDiff(path) },
                    )
                    "git" -> GitPanelContent(
                        worktrees = worktrees,
                        branches = branches,
                        currentBranch = currentBranch,
                        onOpenInCode = { wt ->
                            bridge.openWorktreeInCode(wt) { activeView = "code" }
                        },
                        onSwitchBranch = { target ->
                            bridge.switchBranch(
                                target = target,
                                onSwitched = { branchConflict = null },
                                onWorktreeCreated = { workspaceId, machineId ->
                                    branchConflict = null
                                    if (workspaceId != null) {
                                        bridge.openWorkspaceInFiles(workspaceId, target, machineId)
                                        activeView = "code"
                                    }
                                },
                                onConflict = {
                                    branchConflict = dev.aura.xr.ui.BranchConflictState(
                                        target = target,
                                        onStash = {
                                            bridge.switchBranch(
                                                target = target,
                                                onDirty = "stash",
                                                onSwitched = { branchConflict = null },
                                                onWorktreeCreated = { workspaceId, machineId ->
                                                    branchConflict = null
                                                    if (workspaceId != null) {
                                                        bridge.openWorkspaceInFiles(workspaceId, target, machineId)
                                                        activeView = "code"
                                                    }
                                                },
                                                onConflict = { branchConflict = null },
                                            )
                                        },
                                        onWorktree = {
                                            bridge.switchBranch(
                                                target = target,
                                                onDirty = "worktree",
                                                onSwitched = { branchConflict = null },
                                                onWorktreeCreated = { workspaceId, machineId ->
                                                    branchConflict = null
                                                    if (workspaceId != null) {
                                                        bridge.openWorkspaceInFiles(workspaceId, target, machineId)
                                                        activeView = "code"
                                                    }
                                                },
                                                onConflict = { branchConflict = null },
                                            )
                                        },
                                        onCancel = {
                                            bridge.switchBranch(
                                                target = target,
                                                onDirty = "abort",
                                                onSwitched = { branchConflict = null },
                                                onWorktreeCreated = { _, _ -> branchConflict = null },
                                                onConflict = { branchConflict = null },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                        onDeleteBranch = { branch ->
                            deleteConfirm = dev.aura.xr.ui.DeleteConfirmState(
                                title = "Delete branch?",
                                message = "This deletes $branch. It can't be recovered if unmerged changes only exist there.",
                                onConfirm = {
                                    bridge.deleteBranch(
                                        branch,
                                        onDone = { deleteConfirm = null },
                                        onError = { message ->
                                            deleteConfirm = dev.aura.xr.ui.DeleteConfirmState(
                                                title = "Couldn't delete $branch",
                                                message = message,
                                                onConfirm = {
                                                    // Not fully merged is the common reason git -d refuses — offer -D.
                                                    bridge.deleteBranch(branch, force = true, onDone = { deleteConfirm = null }, onError = { deleteConfirm = null })
                                                },
                                                onCancel = { deleteConfirm = null },
                                            )
                                        },
                                    )
                                },
                                onCancel = { deleteConfirm = null },
                            )
                        },
                        onRemoveWorktree = { wt ->
                            deleteConfirm = dev.aura.xr.ui.DeleteConfirmState(
                                title = "Remove worktree?",
                                message = "This deletes the checkout at ${wt.path}. Any uncommitted changes there are lost.",
                                onConfirm = {
                                    bridge.removeWorktree(
                                        wt,
                                        onDone = { deleteConfirm = null },
                                        onError = { deleteConfirm = null },
                                    )
                                },
                                onCancel = { deleteConfirm = null },
                            )
                        },
                        conflict = branchConflict,
                        deleteConfirm = deleteConfirm,
                    )
                    "remote" -> RemotePanelContent(
                        machines = machines,
                        connectingMachineId = connectingMachineId,
                        connectError = machineConnectError,
                        onConnect = { machineId ->
                            val label = machines.find { it.id == machineId }?.label ?: machineId
                            bridge.connectMachine(machineId, label)
                        },
                        onDisconnect = { machineId ->
                            bridge.disconnectMachine(machineId)
                            // If the Terminal view was showing this machine, fall back
                            // to local rather than silently pointing at a dead tunnel.
                            if (bridge.terminalsMachineId.value == machineId) {
                                bridge.switchTerminalsMachine("local")
                            }
                        },
                        onViewTerminals = { machineId ->
                            bridge.switchTerminalsMachine(machineId)
                            activeView = "terminal"
                        },
                        onBrowse = { machineId ->
                            val label = machines.find { it.id == machineId }?.label ?: machineId
                            bridge.startRemoteBrowse(machineId, label, "/")
                        },
                    )
                    else -> PrimaryPanelContent(state, onResetLayout = { resetLayout() })
                }
                // Voice search is reachable from any view (dock mic button
                // below), so its overlay lives here rather than inside one
                // specific view's content.
                VoiceOverlay(
                    isListening = isListening && voiceMode == "fileSearch",
                    partial = voicePartial,
                    error = if (voiceMode == "fileSearch") voiceError else null,
                    result = voiceResult,
                    onSelectCandidate = { path ->
                        bridge.openFile(path)
                        activeView = "code"
                        bridge.clearVoiceResult()
                    },
                    onDismiss = {
                        isListening = false
                        voiceError = null
                        bridge.clearVoiceResult()
                    },
                )
                // Agent chips (strip above, or the Agents view) expand here
                // rather than navigating away — see openAgentDetail's doc.
                dev.aura.xr.ui.AgentDetailOverlay(
                    state = agentDetail,
                    promptText = agentPromptText,
                    onPromptChange = { agentPromptText = it },
                    isListeningForPrompt = isListening && voiceMode == "agentPrompt",
                    onVoiceInput = { requestVoice("agentPrompt") },
                    onSendPrompt = {
                        val target = agentDetail?.agent
                        if (target != null && agentPromptText.isNotBlank()) {
                            bridge.sendAgentPrompt(
                                target.paneId,
                                agentPromptText,
                                onDone = {
                                    agentPromptText = ""
                                    agentDetail = null
                                },
                                onError = {
                                    // Most likely the agent went blocked between opening
                                    // the card and hitting Send — surface that instead of
                                    // failing silently.
                                    agentDetail = agentDetail?.copy(
                                        promptError = "Couldn't send — the agent may need a direct response now.",
                                    )
                                },
                            )
                        }
                    },
                    onInterrupt = {
                        val target = agentDetail?.agent
                        if (target != null) {
                            bridge.fetchProcessInfo(target.paneId) { label ->
                                stopConfirm = dev.aura.xr.ui.StopConfirmState(
                                    pane = PaneInfo(target.paneId, "", "", "", target.label, focused = false),
                                    processLabel = label,
                                    onConfirm = {
                                        bridge.interruptTerminal(target.paneId)
                                        stopConfirm = null
                                        agentDetail = null
                                    },
                                    onCancel = { stopConfirm = null },
                                )
                            }
                        }
                    },
                    onOpenTerminal = {
                        agentDetail?.agent?.let { openAgentTerminal(it) }
                    },
                    onDismiss = { agentDetail = null },
                )
                // Shown right after a successful Connect (spec §37) — picking a
                // folder here re-roots both Code and a fresh Terminal at it.
                RemoteBrowseOverlay(
                    state = remoteBrowse,
                    onNavigate = { path -> bridge.browseRemoteDir(path) },
                    onUp = { bridge.browseRemoteUp() },
                    onOpen = {
                        bridge.openRemoteBrowsedFolder()
                        activeView = "code"
                    },
                    onDismiss = { bridge.closeRemoteBrowse() },
                )
                }
            }

            // ── Quick dock (icons match the mockup's dock glyphs) ───────────
            SpatialRow {
                for (
                dock in listOf(
                    Triple("▣", "Code", "code"),
                    Triple("▤", "Terminal", "terminal"),
                    Triple("±", "Diff", "diff"),
                    Triple("⑂", "Git", "git"),
                    Triple("◎", "Agents", "agents"),
                    Triple("▥", "Remote", "remote"),
                    Triple("🎤", "Voice", "voice"),
                )
                ) {
                    val (icon, label, id) = dock
                    androidx.compose.runtime.key(id) {
                        SpatialPanel(rememberPanelModifier(layoutStore, "dock.$id", 150f, 70f, 6.dp, layoutGeneration)) {
                            DockButtonContent(icon, label, active = activeView == id) {
                                if (id == "voice") requestVoiceSearch() else activeView = id
                            }
                        }
                    }
                }
            }
        }
    }
}
