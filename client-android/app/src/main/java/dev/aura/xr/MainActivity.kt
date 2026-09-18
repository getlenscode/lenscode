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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.xr.compose.platform.requestFullSpace
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.FollowingSubspace
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.subspace.FollowBehavior
import androidx.xr.compose.subspace.FollowTarget
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
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
import dev.aura.xr.ui.AuraCorners
import dev.aura.xr.ui.DiffPanelContent
import dev.aura.xr.ui.DockButtonContent
import dev.aura.xr.ui.FilesPanelContent
import dev.aura.xr.ui.GitPanelContent
import dev.aura.xr.ui.PermissionsPanelContent
import dev.aura.xr.ui.RemoteBrowseOverlay
import dev.aura.xr.ui.RemotePanelContent
import dev.aura.xr.ui.PrimaryPanelContent
import dev.aura.xr.ui.SnipCropOverlay
import dev.aura.xr.ui.SnipRegion
import dev.aura.xr.ui.TerminalPanelContent
import dev.aura.xr.ui.VoiceOverlay
import dev.aura.xr.ui.rememberSnipCaptureController
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
        // BuildConfig.BRIDGE_URL defaults to 10.0.2.2 (emulator host-loopback
        // alias); pass -PbridgeUrl=ws://<lan-ip>:8137 to gradle for a real device.
        bridge = BridgeClient(
            lifecycleScope,
            BuildConfig.BRIDGE_URL,
            deviceId,
            dev.aura.xr.net.PairingStore(applicationContext),
        )
        bridge.connect()

        setContent {
            AuraWorkspace(
                bridge,
                // Confirmed live: entering PiP does change the Activity's own
                // window (dumpsys shows mIsInPictureInPictureMode=true), but
                // has no visible effect — every visible panel renders through
                // SceneCore's spatial compositor, not this transparent 2D
                // Activity window, so shrinking it changes nothing on screen.
                onEnterPip = {
                    enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
                },
            )
        }

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
 * Holds one panel's live layout state — separated from the modifier itself so
 * callers that want pin/depth controls (currently just the primary panel) can
 * read/mutate it without re-deriving the SubspaceModifier chain by hand.
 */
class PanelLayoutState(
    private val layoutStore: dev.aura.xr.layout.LayoutStore,
    private val id: String,
    offset: dev.aura.xr.layout.SavedOffset,
    size: dev.aura.xr.layout.SavedSize,
    pinned: Boolean,
) {
    var offset by mutableStateOf(offset)
        private set
    var size by mutableStateOf(size)
        private set
    var pinned by mutableStateOf(pinned)
        private set

    fun onMoveEnd(t: dev.aura.xr.layout.SavedOffset) {
        offset = t
        layoutStore.saveOffset(id, t)
    }

    fun onResizeEnd(s: dev.aura.xr.layout.SavedSize) {
        size = s
        layoutStore.saveSize(id, s)
    }

    fun togglePinned() {
        pinned = !pinned
        layoutStore.savePinned(id, pinned)
    }

    /** Bring-closer / push-farther (spec §52) — nudges depth (Z) by a fixed step. */
    fun nudgeDepth(deltaZ: Float) {
        val next = offset.copy(z = offset.z + deltaZ)
        offset = next
        layoutStore.saveOffset(id, next)
    }

    fun toModifier(padding: androidx.compose.ui.unit.Dp): SubspaceModifier = SubspaceModifier
        .width(size.width.dp)
        .height(size.height.dp)
        .padding(padding)
        .offset(offset.x.dp, offset.y.dp, offset.z.dp)
        .movable(enabled = !pinned, onMoveEnd = { event ->
            val t = event.pose.translation
            onMoveEnd(dev.aura.xr.layout.SavedOffset(t.x, t.y, t.z))
        })
        .resizable(enabled = !pinned, onResizeEnd = { newSize ->
            onResizeEnd(dev.aura.xr.layout.SavedSize(newSize.width.toFloat(), newSize.height.toFloat()))
            true
        })
}

@Composable
private fun rememberPanelLayoutState(
    layoutStore: dev.aura.xr.layout.LayoutStore,
    id: String,
    defaultWidth: Float,
    defaultHeight: Float,
    generation: Int = 0,
): PanelLayoutState = remember(id, generation) {
    PanelLayoutState(
        layoutStore,
        id,
        // Always launch centered at a readable size. Transforms remain live
        // for this session, but old scattered layouts are not restored.
        dev.aura.xr.layout.SavedOffset(0f, 0f, 0f),
        dev.aura.xr.layout.SavedSize(defaultWidth, defaultHeight),
        layoutStore.loadPinned(id),
    )
}

/**
 * Spatial layout mirroring the mockup (spec §71): agent strip on top, one
 * dominant primary panel, quick dock underneath.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
fun AuraWorkspace(bridge: BridgeClient, onEnterPip: () -> Unit = {}) {
    val state by bridge.state.collectAsState()
    val terminals by bridge.terminals.collectAsState()
    val terminalOutput by bridge.terminalOutput.collectAsState()
    val dirPath by bridge.dirPath.collectAsState()
    val dirEntries by bridge.dirEntries.collectAsState()
    val filesRootLabel by bridge.filesRootLabel.collectAsState()
    val openFile by bridge.openFile.collectAsState()
    val agents by bridge.agents.collectAsState()
    val agentHistory by bridge.agentHistory.collectAsState()
    val agentHistoryHasMore by bridge.agentHistoryHasMore.collectAsState()
    val agentTimeline by bridge.agentTimeline.collectAsState()
    val permissions by bridge.permissions.collectAsState()
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
        layoutGeneration++ // re-center the main panel and its attached rows
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
    var diffDiscardConfirm by remember { mutableStateOf<dev.aura.xr.ui.DeleteConfirmState?>(null) }
    var stagedHunks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var fileSearchQuery by remember { mutableStateOf("") }
    val fileSearchResults by bridge.searchResults.collectAsState()
    val context = LocalContext.current

    // ── Snip tool (screenshot → crop → attach to the tmux voice agent) ────
    val snipController = rememberSnipCaptureController()
    var snipBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var snipUploading by remember { mutableStateOf(false) }
    // Set once the crop is uploaded, while the user is being asked which of
    // several active agents to attach it to — cleared on pick or dismiss.
    var snipPendingMessage by remember { mutableStateOf<String?>(null) }
    var snipPickerAgents by remember { mutableStateOf<List<dev.aura.xr.net.AgentInfo>>(emptyList()) }
    val snipScope = rememberCoroutineScope()
    fun showToast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    // ── Browser dock button ──────────────────────────────────────────────
    // No embedded live preview in Code (docs/remote-setup-and-limits.md), so
    // this is the app's only way to see a change actually running — it opens
    // Chrome as a separate Android activity rather than anything drawn inside
    // this app's own panel. In XR that surfaces as its own spatial window in
    // Home Space; positioning it beside this panel is the user's/XR shell's
    // job, not something startActivity lets the launching app dictate.
    val browserUrlStore = remember { dev.aura.xr.net.BrowserUrlStore(context) }
    var browserOverlayOpen by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf(browserUrlStore.load() ?: "") }

    // A bare "192.168.1.x:3000" is the common case (a LAN dev server, almost
    // never with a cert for its raw IP) — default those to http. Everything
    // else (a real hostname like "youtube.com") defaults to https instead:
    // the app's network_security_config.xml only allow-lists cleartext for a
    // couple of dev hosts, so http to anything else is silently blocked
    // (ERR_CLEARTEXT_NOT_PERMITTED) before any connection is even attempted —
    // confirmed live, this is why "youtube.com" looked like "no internet".
    fun normalizeBrowserUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) return trimmed
        val host = trimmed.substringBefore('/').substringBefore(':')
        val looksLikeLan = host == "localhost" || host.endsWith(".local") ||
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
        return if (looksLikeLan) "http://$trimmed" else "https://$trimmed"
    }

    // Renders in this app's own window (BrowserPanelContent's WebView) rather
    // than launching Chrome as a separate Android activity — a separate
    // Chrome window occludes/hides this app's other panels (Terminal in
    // particular) in Home Space instead of sitting beside them.
    fun openInAppBrowser(rawUrl: String) {
        val normalized = normalizeBrowserUrl(rawUrl) ?: return
        browserUrlStore.save(normalized)
        browserUrl = normalized
        browserOverlayOpen = false
        activeView = "browser"
    }

    fun openInChrome(rawUrl: String) {
        val normalized = normalizeBrowserUrl(rawUrl) ?: return
        browserUrlStore.save(normalized)
        browserUrl = normalized
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(normalized))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // Right-half launch bounds — the standard Android freeform-windowing
        // hint (setLaunchBounds), same mechanism Chromebooks/large-screen
        // Android use for "open this app docked to a side". Home Space's
        // multi-window layer honors it the same way any freeform host does;
        // it's a request the launched activity/window manager can override
        // (e.g. Chrome restoring its last size), not a placement guarantee —
        // there's no Android API that gives the launcher a hard guarantee
        // over another app's window.
        val metrics = context.resources.displayMetrics
        val bounds = android.graphics.Rect(metrics.widthPixels / 2, 0, metrics.widthPixels, metrics.heightPixels)
        val options = android.app.ActivityOptions.makeBasic().apply { launchBounds = bounds }
        try {
            context.startActivity(intent.setPackage("com.android.chrome"), options.toBundle())
        } catch (e: android.content.ActivityNotFoundException) {
            // Chrome specifically isn't installed — fall back to whatever the
            // system's default handler for http(s) is, rather than failing.
            try {
                context.startActivity(intent.setPackage(null), options.toBundle())
            } catch (e2: android.content.ActivityNotFoundException) {
                android.util.Log.w("AuraBridge", "openInChrome: no app can handle $normalized", e2)
            }
        }
        browserOverlayOpen = false
    }

    // "Follow me" — Jetpack XR's app-side equivalent of the pin-to-headset
    // button a Home Space window gets from the OS; this Full Space app renders
    // its own panels instead of a system-managed window, so it never gets
    // that automatically. Off by default: it re-poses the *entire* subspace
    // root as one rigid block on every head move, fighting manual
    // reposition/pin/resize — an opt-in for walking, not the default.
    var followMeEnabled by remember { mutableStateOf(false) }
    var agentDetail by remember { mutableStateOf<dev.aura.xr.ui.AgentDetailState?>(null) }
    var agentPromptText by remember { mutableStateOf("") }
    // The one agent voice's "ask_agent" action talks to — like one running
    // chat with Claude/Codex in a terminal, not a fresh agent per utterance.
    // Set the first time ask_agent has to create an agent; every ask_agent
    // after that (with no agent card explicitly open) sends into this same
    // agent instead of spinning up another one.
    var voiceAgentId by remember { mutableStateOf<String?>(null) }
    // The tmux-bridged terminal pane ask_agent's server-under-test runs
    // through (docs/paseo-migration-plan.md follow-up) — created once
    // alongside the first voice agent, then reused. See AskAgent's handler
    // for why: without this, agent Bash tool calls are invisible outside
    // querying Paseo directly, and interrupting one kills the process
    // outright rather than leaving it running the way a real terminal would.
    var voiceAgentTerminalId by remember { mutableStateOf<String?>(null) }
    // "auto" | "bypass" | "manual" — set via the dropdown in the Agents view
    // (VoiceAgentModeSelector). Maps to the Paseo modeId a new voice agent is
    // created with; see modeIdFor.
    var voiceAgentModeId by remember { mutableStateOf("auto") }
    fun modeIdFor(choice: String): String? = when (choice) {
        // Claude Code's --permission-mode only accepts acceptEdits,
        // bypassPermissions, default, plan (confirmed live — "auto" made every
        // agent's first turn fail with "argument 'auto' is invalid"). acceptEdits
        // is the closest real mode to the "auto-approve low-risk" copy this
        // choice shows in VoiceAgentModeSelector.
        "auto" -> "acceptEdits"
        "bypass" -> "bypassPermissions"
        else -> null // "manual" — Paseo's own default (ask every time)
    }

    // ── Device pairing (spec §55) ────────────────────────────────────────
    val pairingState by bridge.pairingState.collectAsState()
    var pairingDeviceName by remember { mutableStateOf("Aura XR Glasses") }
    var pairingCode by remember { mutableStateOf("") }

    fun openAgentDetail(agent: dev.aura.xr.net.AgentInfo) {
        agentDetail = dev.aura.xr.ui.AgentDetailState(agent)
        agentPromptText = ""
    }

    /**
     * Opens the agent's own directory in Files + Terminal (docs/paseo-migration-plan.md,
     * Phase 3). Paseo agents aren't tied to a terminal pane the way Herdr's
     * were (see AgentInfo's doc), so this opens where the agent actually
     * works — its workspaceId if it has one, otherwise a fresh terminal at
     * its cwd — rather than a specific pane that may not exist.
     */
    fun openAgentWorkspace(agent: dev.aura.xr.net.AgentInfo) {
        val workspaceId = agent.workspaceId
        if (workspaceId != null) {
            bridge.openWorkspaceInFiles(workspaceId, agent.label, machineId = "local")
        }
        bridge.switchTerminalsMachine("local")
        activeView = if (workspaceId != null) "code" else "terminal"
        if (workspaceId == null) bridge.createTerminal(name = agent.label, cwd = agent.cwd)
        agentDetail = null
    }

    // ── Voice input (spec §13, §63) — shared recognizer, two destinations:
    // file search (global overlay) and, once an agent card is open, filling
    // its prompt box directly instead. Never auto-submits what it hears —
    // file search still goes through candidates/confidence, and a voice
    // agent-prompt lands in the text field for you to review and hit Send,
    // never fires straight into agent.prompt on its own.
    val voiceRecognizer = remember { VoiceRecognizer(context) }
    var isListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var voiceMode by remember { mutableStateOf("fileSearch") }
    var pendingVoiceMode by remember { mutableStateOf("fileSearch") }
    // Final transcript from the global voice-command mic, awaiting Submit/Cancel
    // in VoiceCommandOverlay — see dispatchVoiceCommand below for what Submit does.
    var voiceCommandReview by remember { mutableStateOf<String?>(null) }

    // Debug-only text injection — types a transcript into the same review
    // popup a real recognized phrase would land in (Submit/Cancel still
    // required; nothing is bypassed), for testing without speaking out loud
    // in a public place. Only registered in debug builds. Trigger with:
    //   adb shell am broadcast -a dev.aura.xr.INJECT_VOICE_COMMAND --es text "open diff"
    if (BuildConfig.DEBUG) {
        DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    val text = intent?.getStringExtra("text") ?: return
                    android.util.Log.d("AuraVoice", "injected transcript: \"$text\"")
                    voiceMode = "command"
                    voiceCommandReview = text
                }
            }
            val filter = android.content.IntentFilter("dev.aura.xr.INJECT_VOICE_COMMAND")
            // RECEIVER_EXPORTED, not _NOT_EXPORTED: `adb shell am broadcast` runs
            // as the shell UID, not system, and RECEIVER_NOT_EXPORTED silently
            // drops broadcasts from anything but the app itself or the system —
            // confirmed live, it blocked adb entirely with no error. Acceptable
            // for a debug-only feature that only ever populates the review card
            // (still gated behind the user's own Submit tap, nothing auto-fires).
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    fun startListening(mode: String) {
        voiceMode = mode
        voiceError = null
        voicePartial = ""
        isListening = true
        if (mode == "fileSearch") bridge.clearVoiceResult()
        if (mode == "command") voiceCommandReview = null
        voiceRecognizer.listen(
            onPartial = { text ->
                voicePartial = text
                if (mode == "agentPrompt") agentPromptText = text // live feedback right in the field being filled
                if (mode == "browserUrl") browserUrl = dev.aura.xr.voice.normalizeSpokenAddress(text)
            },
            onResult = { text ->
                android.util.Log.d("AuraVoice", "recognized ($mode): \"$text\"")
                isListening = false
                when (mode) {
                    "fileSearch" -> bridge.voiceSearch(text)
                    "agentPrompt" -> agentPromptText = text
                    // An address, not a sentence — "one nine two dot one six
                    // eight..." needs the digit/symbol-word collapsing, not
                    // the raw transcript.
                    "browserUrl" -> browserUrl = dev.aura.xr.voice.normalizeSpokenAddress(text)
                    // Never dispatches straight from speech — always lands in
                    // the review card first so a misheard word can't fire a
                    // destructive command (spec §57's confirmation discipline,
                    // applied one step earlier than usual: before the command
                    // is even classified).
                    "command" -> voiceCommandReview = text
                }
            },
            onError = { message ->
                android.util.Log.d("AuraVoice", "recognizer error ($mode): $message")
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
    fun requestVoiceCommand() = requestVoice("command")

    /**
     * Executes one classified action (docs/paseo-migration-plan.md follow-up
     * — the agentic Koog/Gemini rewrite of the original regex router).
     * Navigation, file open, and search act immediately — they're not
     * destructive. Everything else reuses the app's existing confirmation-card
     * types (StopConfirmState/DeleteConfirmState) rather than acting directly,
     * so a voice command carries exactly the same safety guarantee as its
     * equivalent tap (spec §57) — the transcript-review step answers "did it
     * hear me right", these cards answer "are you sure". The LLM only ever
     * classifies; it never bypasses this confirmation layer.
     */
    /**
     * Shared by ask_agent (with a task) and new_agent (without one) — both end up
     * creating the same tmux-backed agent, just with a different first prompt.
     */
    fun startVoiceAgent(instruction: String?) {
        activeView = "agents"
        // A tmux session both the agent and the Terminal view share
        // (docs/paseo-migration-plan.md follow-up) — created once,
        // then just told about in every later prompt. See the
        // instruction text below for exactly what this buys.
        fun createAgentInTmux(terminalId: String) {
            bridge.inputTerminal(terminalId, "tmux new -A -s aura-agent\n")
            val prompt = """
                You have a tmux session named "aura-agent" available, mirrored live
                in this workspace's own Terminal view. Always run shell commands
                through it instead of your default tool execution — that's what
                makes output visible there, and what lets a long-running process
                (a dev server, a watcher, …) keep running independently of this
                conversation instead of dying with whatever tool call started it:
                  tmux send-keys -t aura-agent "<command>" Enter
                  tmux capture-pane -t aura-agent -p     # to read output back
                To stop something, send a real interrupt inside the session
                (tmux send-keys -t aura-agent C-c, or kill its pid) rather than
                canceling your own tool call.

                ${if (instruction != null) "Task: $instruction" else "No task yet — say a brief hello and wait for the next instruction."}
            """.trimIndent()
            bridge.createAgent(
                provider = "claude",
                cwd = ".",
                prompt = prompt,
                modeId = modeIdFor(voiceAgentModeId),
                onCreated = { created ->
                    voiceAgentId = created.agentId
                    android.util.Log.d("AuraVoice", "started agent ${created.agentId} (tmux)${instruction?.let { " for \"$it\"" } ?: ""}")
                },
                onError = { android.util.Log.w("AuraVoice", "agent.create failed") },
            )
        }
        val terminalId = voiceAgentTerminalId
        if (terminalId != null) {
            createAgentInTmux(terminalId)
        } else {
            bridge.createTerminal(
                name = "aura-agent",
                onCreated = { newTerminalId ->
                    voiceAgentTerminalId = newTerminalId
                    createAgentInTmux(newTerminalId)
                },
                onError = { error ->
                    val message = error.optString("message", "Couldn't start the agent's terminal.")
                    android.util.Log.w("AuraVoice", "terminal.create failed: $message (${error.optString("detail")})")
                    voiceError = message
                },
            )
        }
    }

    // ── Snip tool ────────────────────────────────────────────────────────
    // "Snip" dock button: flatten the workspace into a screenshot, let the
    // user crop it (two-hand pinch on the corner handles, or one hand on
    // either), then hand it to whichever agent voice's ask_agent would talk
    // to — same "one running conversation" target, so a snip is just another
    // turn in that chat.
    fun startSnip() {
        snipScope.launch {
            val bitmap = snipController.captureComposite()
            if (bitmap == null) showToast("Nothing on screen to capture yet.") else snipBitmap = bitmap
        }
    }

    // Opens an existing agent's card with the screenshot path sitting in the
    // prompt box, unsent — same "review before it goes anywhere" rule every
    // other voice-filled prompt in this app already follows, rather than
    // firing a bare "look at this" turn with no actual instruction attached.
    fun attachSnipToAgent(agent: dev.aura.xr.net.AgentInfo, message: String) {
        voiceAgentId = agent.agentId
        openAgentDetail(agent)
        agentPromptText = message
        activeView = "agents"
    }

    fun confirmSnip(cropRect: androidx.compose.ui.geometry.Rect) {
        val source = snipBitmap ?: return
        val left = cropRect.left.toInt().coerceIn(0, source.width - 1)
        val top = cropRect.top.toInt().coerceIn(0, source.height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, source.width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, source.height)
        val cropped = android.graphics.Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        snipBitmap = null
        snipUploading = true
        val stream = java.io.ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        bridge.uploadSnip(
            base64,
            onResult = { path ->
                snipUploading = false
                val message = "Screenshot attached: $path"
                // Never guess which of several running conversations a snip
                // belongs to (spec §13's "never silently guess" discipline) —
                // only auto-pick when there's exactly one candidate.
                val active = agents.filter { it.status != "closed" }
                when {
                    active.isEmpty() -> startVoiceAgent(instruction = message)
                    active.size == 1 -> attachSnipToAgent(active[0], message)
                    else -> {
                        snipPendingMessage = message
                        snipPickerAgents = active
                    }
                }
            },
            onError = {
                snipUploading = false
                showToast("Could not upload that screenshot — check the bridge connection.")
            },
        )
    }

    fun executeVoiceAction(action: dev.aura.xr.voice.VoiceAction) {
        android.util.Log.d("AuraVoice", "executing: $action")
        when (action) {
            is dev.aura.xr.voice.VoiceAction.Navigate -> activeView = action.view

            is dev.aura.xr.voice.VoiceAction.OpenFile -> {
                // Reuses the existing fuzzy-resolve + confidence flow
                // (bridge.voiceSearch → LaunchedEffect(voiceResult) below
                // opens a confident hit and switches to Code automatically).
                // `action.line` isn't wired to anything yet — the file viewer
                // has no scroll-to-line capability — so it's logged and
                // otherwise dropped rather than silently pretending to honor it.
                if (action.line != null) {
                    android.util.Log.d("AuraVoice", "open_file requested line ${action.line} — not supported yet, ignoring")
                }
                bridge.voiceSearch(action.path)
            }

            dev.aura.xr.voice.VoiceAction.AcceptChanges -> {
                val path = gitDiffPath
                val text = gitDiffText
                activeView = "diff"
                if (path != null && text != null) {
                    val (header, hunks) = dev.aura.xr.ui.parseDiffHunks(text)
                    for (hunk in hunks) {
                        bridge.applyHunk(path, dev.aura.xr.ui.hunkPatch(header, hunk), "stage", onDone = {}, onError = {})
                    }
                }
            }

            dev.aura.xr.voice.VoiceAction.DiscardChanges -> {
                val path = gitDiffPath
                val text = gitDiffText
                activeView = "diff"
                if (path != null && text != null) {
                    val (header, hunks) = dev.aura.xr.ui.parseDiffHunks(text)
                    diffDiscardConfirm = dev.aura.xr.ui.DeleteConfirmState(
                        title = "Discard all changes in $path?",
                        message = "This reverts every uncommitted hunk in this file back to HEAD.",
                        onConfirm = {
                            for (hunk in hunks) {
                                bridge.applyHunk(path, dev.aura.xr.ui.hunkPatch(header, hunk), "discard", onDone = {}, onError = {})
                            }
                            diffDiscardConfirm = null
                            bridge.loadGitDiff(path)
                        },
                        onCancel = { diffDiscardConfirm = null },
                    )
                }
            }

            dev.aura.xr.voice.VoiceAction.NewTerminal -> {
                activeView = "terminal"
                bridge.createTerminal()
            }

            dev.aura.xr.voice.VoiceAction.KillTerminal -> {
                activeView = "terminal"
                val pane = selectedPane
                if (pane != null) {
                    stopConfirm = dev.aura.xr.ui.StopConfirmState(
                        pane = pane,
                        processLabel = "the running process",
                        onConfirm = {
                            bridge.interruptTerminal(pane.terminalId, pane.machineId)
                            stopConfirm = null
                        },
                        onCancel = { stopConfirm = null },
                    )
                }
            }

            dev.aura.xr.voice.VoiceAction.ArchiveAgent -> {
                val target = agentDetail?.agent
                if (target != null) {
                    deleteConfirm = dev.aura.xr.ui.DeleteConfirmState(
                        title = "Archive this agent?",
                        message = "This closes ${target.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
                        onConfirm = {
                            bridge.archiveAgent(target.agentId)
                            if (voiceAgentId == target.agentId) voiceAgentId = null
                            deleteConfirm = null
                            agentDetail = null
                        },
                        onCancel = { deleteConfirm = null },
                    )
                }
            }

            dev.aura.xr.voice.VoiceAction.NewAgent -> {
                // Only actually spawn a fresh one when there's nothing already
                // running — same "one voice agent conversation" model as
                // ask_agent below; otherwise just surface the existing one.
                val openTarget = agentDetail?.agent?.agentId
                val reuseTarget = voiceAgentId?.takeIf { id -> agents.any { it.agentId == id && it.status != "closed" } }
                val target = openTarget ?: reuseTarget
                if (target != null) {
                    activeView = "agents"
                } else {
                    startVoiceAgent(instruction = null)
                }
            }

            is dev.aura.xr.voice.VoiceAction.Search -> bridge.voiceSearch(action.query)

            is dev.aura.xr.voice.VoiceAction.RespondPermission -> {
                // Prefer the pending permission for whichever agent's card is
                // open (matches what you're actually looking at); otherwise
                // the oldest pending one globally — same "first in line"
                // behavior a human tapping through the Permissions queue
                // top-to-bottom would get.
                val target = permissions.firstOrNull { it.agentId == agentDetail?.agent?.agentId }
                    ?: permissions.firstOrNull()
                if (target != null) {
                    bridge.respondToPermission(target, action.behavior)
                } else {
                    android.util.Log.w("AuraVoice", "respond_permission: nothing pending to answer")
                }
            }

            is dev.aura.xr.voice.VoiceAction.AskAgent -> {
                // Priority: an explicitly open agent card wins (the user is
                // clearly looking at that one) — otherwise this is one running
                // conversation with a single agent across every voice call, the
                // same as typing turn after turn into one Claude/Codex session,
                // not a fresh agent per utterance. Reuse voiceAgentId as long as
                // that agent is still alive; only create a new one the first
                // time, or after it's gone (archived/closed elsewhere).
                val openTarget = agentDetail?.agent?.agentId
                val reuseTarget = voiceAgentId?.takeIf { id -> agents.any { it.agentId == id && it.status != "closed" } }
                val target = openTarget ?: reuseTarget
                if (target != null) {
                    bridge.sendAgentMessage(target, action.instruction)
                } else {
                    startVoiceAgent(instruction = action.instruction)
                }
            }
        }
    }

    /** Runs a whole plan's actions in order — see [executeVoiceAction] for what each does and why it's safe to just run them. */
    fun executeVoicePlan(plan: dev.aura.xr.voice.VoiceCommandPlan) {
        for (action in plan.toActions()) executeVoiceAction(action)
    }

    /** Maps the old deterministic single-intent router's output onto the new action grammar, for the no-key/offline fallback path. */
    fun dev.aura.xr.voice.VoiceCommand.toAction(): dev.aura.xr.voice.VoiceAction = when (this) {
        is dev.aura.xr.voice.VoiceCommand.Navigate -> dev.aura.xr.voice.VoiceAction.Navigate(view)
        dev.aura.xr.voice.VoiceCommand.AcceptChanges -> dev.aura.xr.voice.VoiceAction.AcceptChanges
        dev.aura.xr.voice.VoiceCommand.DiscardChanges -> dev.aura.xr.voice.VoiceAction.DiscardChanges
        dev.aura.xr.voice.VoiceCommand.NewTerminal -> dev.aura.xr.voice.VoiceAction.NewTerminal
        dev.aura.xr.voice.VoiceCommand.KillTerminal -> dev.aura.xr.voice.VoiceAction.KillTerminal
        is dev.aura.xr.voice.VoiceCommand.ArchiveAgent -> dev.aura.xr.voice.VoiceAction.ArchiveAgent(target)
        is dev.aura.xr.voice.VoiceCommand.Search -> dev.aura.xr.voice.VoiceAction.Search(query)
    }

    // Bumped by the Settings panel's Reload button to force geminiConfigResult
    // (and koogVoiceRouter below) to re-read aura-config.json — see
    // net/GeminiConfigStore.kt and ui/SettingsPanelContent.kt.
    var geminiConfigReloadKey by remember { mutableStateOf(0) }
    val geminiConfigResult = remember(geminiConfigReloadKey) {
        dev.aura.xr.net.GeminiConfigStore.load(context)
    }

    /**
     * Koog/Gemini router — null (and every call skipped) when no API key is
     * configured, so an un-keyed build/device degrades straight to the
     * deterministic local router rather than making a doomed network call.
     * A key can come from either place, config file wins:
     *   - on-device: Settings panel → aura-config.json (no rebuild needed)
     *   - build-time: ./gradlew :app:assembleDebug -PgeminiApiKey=AIza...
     */
    val koogVoiceRouter = remember(geminiConfigReloadKey) {
        val fileConfig = (geminiConfigResult as? dev.aura.xr.net.GeminiConfigResult.Loaded)?.config
        val apiKey = fileConfig?.apiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
        val model = fileConfig?.model?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_MODEL
        if (apiKey.isBlank()) null else dev.aura.xr.voice.KoogVoiceRouter(apiKey, model)
    }
    var isRoutingVoiceCommand by remember { mutableStateOf(false) }
    val voiceScope = rememberCoroutineScope()

    /**
     * Submit handler for the voice-command review card. Tries the agentic
     * Koog/Gemini router first; on any failure (no key, network, malformed
     * response) falls back to the deterministic local router rather than
     * leaving the command unhandled — the fallback always succeeds (worst
     * case it resolves to a plain file search of the raw transcript).
     */
    fun submitVoiceCommand(transcript: String) {
        val router = koogVoiceRouter
        if (router == null) {
            android.util.Log.d("AuraVoice", "no Gemini key configured — using local router")
            executeVoiceAction(dev.aura.xr.voice.parseVoiceCommand(transcript).toAction())
            voiceCommandReview = null
            return
        }
        val context = dev.aura.xr.voice.VoiceRouterContext(
            activeView = activeView,
            diffFileOpen = gitDiffPath,
            terminalSelected = selectedPane != null,
            agentCardOpen = agentDetail?.agent?.label,
            hasPendingPermission = permissions.isNotEmpty(),
        )
        // voiceCommandReview stays set (not nulled here) so the overlay keeps
        // showing the transcript through the "Thinking…" state below.
        isRoutingVoiceCommand = true
        voiceScope.launch {
            // Confirmed live: router.route()'s Result<VoiceCommandPlan> does
            // NOT actually guarantee every failure is caught — a raw network
            // exception (DNS resolution failure, no connectivity) escaped
            // Koog's own Result-wrapping and crashed the whole app via this
            // otherwise-unhandled coroutine. A wrapping try/catch is required
            // for the "always falls back, never crashes" guarantee this
            // function's own doc comment already promised but didn't deliver.
            val result = try {
                router.route(transcript, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // structured concurrency — never swallow, let it propagate
            } catch (e: Exception) {
                Result.failure(e)
            }
            isRoutingVoiceCommand = false
            voiceCommandReview = null
            result.onSuccess { plan ->
                android.util.Log.d("AuraVoice", "Gemini plan: $plan")
                executeVoicePlan(plan)
            }.onFailure { e ->
                android.util.Log.w("AuraVoice", "Gemini routing failed, falling back to local router", e)
                executeVoiceAction(dev.aura.xr.voice.parseVoiceCommand(transcript).toAction())
            }
        }
    }

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

    // Keeps bridge.agentTimeline live for whichever agent's detail card is
    // open, and stops (clearing it) the moment it closes — one place to get
    // this right instead of a trackAgent() call at every site that opens or
    // nulls agentDetail (openAgentDetail, dismiss, archive, send-and-close…).
    LaunchedEffect(agentDetail?.agent?.agentId) {
        bridge.trackAgent(agentDetail?.agent?.agentId)
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
            "permissions" -> {
                bridge.refreshPermissions()
                bridge.refreshAgents()
            }
            "git" -> {
                bridge.refreshWorktrees()
                bridge.refreshBranches()
            }
            "diff" -> bridge.refreshGitStatus()
            "remote" -> bridge.refreshMachines()
        }
    }

    val primaryLayout = rememberPanelLayoutState(layoutStore, "primary", 900f, 520f, layoutGeneration)
    val panelOffset = primaryLayout.offset
    val panelSize = primaryLayout.size

    val xrSession = LocalSession.current
    // FollowTarget.ArDevice needs Config.DeviceTrackingMode.SPATIAL enabled on
    // the session — DISABLED by default (confirmed live: constructing it threw
    // IllegalStateException before this). SPATIAL also needs the HEAD_TRACKING
    // runtime permission on immersive devices (developer.android.com/develop/
    // xr/jetpack-xr-sdk/arcore/device-pose) — request that first, then
    // configure once permission is granted. FollowingSubspace below only ever
    // fires once this has actually succeeded, so there's no race with the toggle.
    var deviceTrackingReady by remember { mutableStateOf(false) }
    // Bumped after a permission grant to retry configure() — LaunchedEffect
    // below is keyed on this too, since granting permission doesn't otherwise
    // change xrSession's identity and so wouldn't trigger a re-run on its own.
    var headTrackingPermissionAttempt by remember { mutableStateOf(0) }
    val headTrackingPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            headTrackingPermissionAttempt++
        } else {
            android.util.Log.w("AuraFollowMe", "HEAD_TRACKING permission denied — Follow Me can't work")
        }
    }
    LaunchedEffect(xrSession, headTrackingPermissionAttempt) {
        if (xrSession == null) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, "android.permission.HEAD_TRACKING") ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            headTrackingPermission.launch("android.permission.HEAD_TRACKING")
            return@LaunchedEffect
        }
        val result = xrSession.configure(
            androidx.xr.runtime.Config.Builder(xrSession.config)
                .setDeviceTracking(androidx.xr.runtime.DeviceTrackingMode.SPATIAL)
                .build(),
        )
        deviceTrackingReady = result is androidx.xr.runtime.SessionConfigureSuccess
        if (!deviceTrackingReady) {
            android.util.Log.w("AuraFollowMe", "device tracking configure failed: $result")
        }
    }

    // Factored out so both the plain Subspace and the "Follow me" FollowingSubspace
    // below can render identical content — only the root wrapper differs.
    @Composable
    fun androidx.xr.compose.subspace.SpatialBoxScope.spatialContent() {
            // ── Agent strip — real Paseo agent.list, honestly empty if none ──
            // All three rows share the main panel's horizontal/depth anchor.
            // A 12 dp gap separates each accessory row from its visible edge.
            SpatialRow(SubspaceModifier.offset(
                x = panelOffset.x.dp,
                y = (panelOffset.y + panelSize.height / 2f + 12f + 55f).dp,
                z = panelOffset.z.dp,
            )) {
                if (agents.isEmpty()) {
                    SpatialPanel(
                        SubspaceModifier.width(460.dp).height(110.dp).padding(horizontal = 8.dp),
                        shape = SpatialRoundedCornerShape(CornerSize(AuraCorners.Card)),
                    ) {
                        SnipRegion(snipController, "agents", "empty") {
                            AgentChipContent("—", "No agents running", AuraColors.Idle)
                        }
                    }
                } else {
                    for (agent in agents) {
                        androidx.compose.runtime.key(agent.agentId) {
                            SpatialPanel(
                                SubspaceModifier.width(220.dp).height(110.dp).padding(horizontal = 8.dp),
                                shape = SpatialRoundedCornerShape(CornerSize(AuraCorners.Card)),
                            ) {
                                SnipRegion(snipController, "agents", agent.agentId, Modifier.fillMaxSize().clickable { openAgentDetail(agent) }) {
                                    AgentChipContent(agent.label, agent.status, AuraColors.agentStatusColor(agent.status), agent.provider)
                                }
                            }
                        }
                    }
                }
            }

            // ── Primary panel ──────────────────────────────────────────────
            SpatialPanel(primaryLayout.toModifier(0.dp), shape = SpatialRoundedCornerShape(CornerSize(AuraCorners.Panel))) {
                SnipRegion(snipController, "primary", "main", Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                when (activeView) {
                    "terminal" -> TerminalPanelContent(
                        terminals = terminals,
                        machineId = terminalsMachineId,
                        selected = selectedPane,
                        output = terminalOutput,
                        onSelect = { pane ->
                            selectedPane = pane
                            bridge.readTerminal(pane.terminalId, pane.machineId)
                        },
                        onNew = { bridge.createTerminal() },
                        onClose = { pane ->
                            bridge.closeTerminal(pane.terminalId, pane.machineId) {
                                if (selectedPane?.terminalId == pane.terminalId) selectedPane = null
                            }
                        },
                        onRequestStop = { pane ->
                            // Paseo has no per-terminal "what's running" RPC
                            // (docs/paseo-migration-plan.md, Phase 3) — the card
                            // can only confirm the action, not name the process.
                            stopConfirm = dev.aura.xr.ui.StopConfirmState(
                                pane = pane,
                                processLabel = "the running process",
                                onConfirm = {
                                    bridge.interruptTerminal(pane.terminalId, pane.machineId)
                                    stopConfirm = null
                                },
                                onCancel = { stopConfirm = null },
                            )
                        },
                        stopConfirm = stopConfirm,
                    )
                    "code" -> FilesPanelContent(
                        rootLabel = filesRootLabel,
                        path = dirPath,
                        entries = dirEntries,
                        file = openFile,
                        onOpenDir = { bridge.listDir(it) },
                        onOpenFile = {
                            fileSearchQuery = ""
                            bridge.clearSearchResults()
                            bridge.openFile(it)
                        },
                        onUp = {
                            val parent = dirPath.substringBeforeLast('/', ".")
                            bridge.listDir(parent)
                        },
                        searchQuery = fileSearchQuery,
                        onSearchQueryChange = { q ->
                            fileSearchQuery = q
                            bridge.searchFiles(q)
                        },
                        searchResults = fileSearchResults,
                    )
                    "agents" -> AgentsPanelContent(
                        agents,
                        onSelect = { openAgentDetail(it) },
                        onArchive = { agent ->
                            bridge.archiveAgent(agent.agentId)
                            if (voiceAgentId == agent.agentId) voiceAgentId = null
                            if (agentDetail?.agent?.agentId == agent.agentId) agentDetail = null
                        },
                        voiceAgentModeId = voiceAgentModeId,
                        onVoiceAgentModeChange = { voiceAgentModeId = it },
                        history = agentHistory,
                        historyHasMore = agentHistoryHasMore,
                        onShowHistory = { bridge.refreshAgentHistory() },
                        onLoadMoreHistory = { bridge.loadMoreAgentHistory() },
                        onSelectHistory = { entry -> openAgentDetail(entry.toAgentInfo()) },
                    )
                    "permissions" -> PermissionsPanelContent(
                        permissions = permissions,
                        agents = agents,
                        onRespond = { permission, behavior, selectedActionId ->
                            bridge.respondToPermission(permission, behavior, selectedActionId)
                        },
                    )
                    "settings" -> dev.aura.xr.ui.SettingsPanelContent(
                        geminiResult = geminiConfigResult,
                        buildKeyPresent = BuildConfig.GEMINI_API_KEY.isNotBlank(),
                        configPath = dev.aura.xr.net.GeminiConfigStore.configFile(context).absolutePath,
                        onReload = { geminiConfigReloadKey++ },
                    )
                    "diff" -> DiffPanelContent(
                        status = gitStatus,
                        diffPath = gitDiffPath,
                        diffText = gitDiffText,
                        onSelectFile = { path ->
                            stagedHunks = emptySet()
                            bridge.loadGitDiff(path)
                        },
                        onStageHunk = { path, patch ->
                            bridge.applyHunk(path, patch, "stage", onDone = {
                                // The Diff view always shows working-tree-vs-HEAD
                                // regardless of index state (see git.ts), so a
                                // successful stage genuinely doesn't change what's
                                // rendered — only this local badge reflects it.
                                stagedHunks = stagedHunks + patch.hashCode()
                            }, onError = {})
                        },
                        onDiscardHunk = { path, patch ->
                            diffDiscardConfirm = dev.aura.xr.ui.DeleteConfirmState(
                                title = "Discard this hunk?",
                                message = "This reverts those lines in $path back to HEAD. Uncommitted changes in this hunk are lost.",
                                onConfirm = {
                                    bridge.applyHunk(path, patch, "discard", onDone = {
                                        diffDiscardConfirm = null
                                        bridge.loadGitDiff(path) // the hunk should be gone now
                                    }, onError = { diffDiscardConfirm = null })
                                },
                                onCancel = { diffDiscardConfirm = null },
                            )
                        },
                        discardConfirm = diffDiscardConfirm,
                        stagedHunks = stagedHunks,
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
                    "browser" -> dev.aura.xr.ui.BrowserPanelContent(
                        url = browserUrl,
                        onChangeUrl = { browserOverlayOpen = true },
                        onOpenInChrome = { openInChrome(browserUrl) },
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
                    else -> PrimaryPanelContent(
                        state,
                        onResetLayout = { resetLayout() },
                        pinned = primaryLayout.pinned,
                        onTogglePin = { primaryLayout.togglePinned() },
                        onBringCloser = { primaryLayout.nudgeDepth(-40f) },
                        onPushFarther = { primaryLayout.nudgeDepth(40f) },
                        followMeEnabled = followMeEnabled,
                        onToggleFollowMe = { followMeEnabled = !followMeEnabled },
                        onEnterPip = onEnterPip,
                        pairingState = pairingState,
                        deviceName = pairingDeviceName,
                        onDeviceNameChange = { pairingDeviceName = it },
                        onBeginPairing = { bridge.beginPairing(pairingDeviceName) },
                        pairingCode = pairingCode,
                        onPairingCodeChange = { pairingCode = it },
                        onSubmitPairingCode = {
                            bridge.completePairing(pairingDeviceName, pairingCode)
                        },
                        onRetryPairing = {
                            pairingCode = ""
                            bridge.beginPairing(pairingDeviceName)
                        },
                    )
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
                // Global voice command mic (dock 🎤 / walking mode) — record,
                // review the transcript, then Submit routes it through
                // dispatchVoiceCommand. An unrecognized command falls back to
                // voiceSearch(), which is what populates the VoiceOverlay
                // above with candidates, same as before this router existed.
                dev.aura.xr.ui.VoiceCommandOverlay(
                    isListening = isListening && voiceMode == "command",
                    partial = voicePartial,
                    error = if (voiceMode == "command") voiceError else null,
                    reviewText = voiceCommandReview,
                    isRouting = isRoutingVoiceCommand,
                    onSubmit = {
                        // Don't null voiceCommandReview here — submitVoiceCommand
                        // keeps it set through routing so the "Thinking…" state
                        // (isRoutingVoiceCommand) has a transcript to show.
                        val text = voiceCommandReview
                        if (!text.isNullOrBlank()) submitVoiceCommand(text) else voiceCommandReview = null
                    },
                    onCancel = { voiceCommandReview = null },
                    onDismissError = {
                        isListening = false
                        voiceError = null
                    },
                )
                // Agent chips (strip above, or the Agents view) expand here
                // rather than navigating away — see openAgentDetail's doc.
                dev.aura.xr.ui.AgentDetailOverlay(
                    state = agentDetail,
                    timeline = agentTimeline,
                    promptText = agentPromptText,
                    onPromptChange = { agentPromptText = it },
                    isListeningForPrompt = isListening && voiceMode == "agentPrompt",
                    onVoiceInput = { requestVoice("agentPrompt") },
                    onSendPrompt = {
                        val target = agentDetail?.agent
                        if (target != null && agentPromptText.isNotBlank()) {
                            bridge.sendAgentMessage(
                                target.agentId,
                                agentPromptText,
                                onDone = {
                                    agentPromptText = ""
                                    agentDetail = null
                                },
                                onError = {
                                    agentDetail = agentDetail?.copy(
                                        promptError = "Couldn't send — try again in a moment.",
                                    )
                                },
                            )
                        }
                    },
                    onArchive = {
                        val target = agentDetail?.agent
                        if (target != null) {
                            deleteConfirm = dev.aura.xr.ui.DeleteConfirmState(
                                title = "Archive this agent?",
                                message = "This closes ${target.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
                                onConfirm = {
                                    bridge.archiveAgent(target.agentId)
                                    deleteConfirm = null
                                    agentDetail = null
                                },
                                onCancel = { deleteConfirm = null },
                            )
                        }
                    },
                    onOpenWorkspace = {
                        agentDetail?.agent?.let { openAgentWorkspace(it) }
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
                dev.aura.xr.ui.BrowserLaunchOverlay(
                    visible = browserOverlayOpen,
                    url = browserUrl,
                    onUrlChange = { browserUrl = it },
                    onOpen = { openInAppBrowser(browserUrl) },
                    onDismiss = { browserOverlayOpen = false },
                    isListeningForUrl = isListening && voiceMode == "browserUrl",
                    onVoiceInput = { requestVoice("browserUrl") },
                )
                snipBitmap?.let { bitmap ->
                    SnipCropOverlay(
                        bitmap = bitmap,
                        uploading = snipUploading,
                        onConfirm = { rect -> confirmSnip(rect) },
                        onCancel = { snipBitmap = null },
                    )
                }
                snipPendingMessage?.let { message ->
                    dev.aura.xr.ui.SnipAgentPickerOverlay(
                        agents = snipPickerAgents,
                        onSelect = { agent ->
                            attachSnipToAgent(agent, message)
                            snipPendingMessage = null
                            snipPickerAgents = emptyList()
                        },
                        onDismiss = {
                            snipPendingMessage = null
                            snipPickerAgents = emptyList()
                            showToast("Screenshot discarded — no agent selected.")
                        },
                    )
                }
                }
                }
            }

            // ── Quick dock ────────────────────────────────────────────────
            run {
                // Follow the main panel's bottom edge, including live moves
                // and resizes, rather than anchoring to a separate world pose.
                SpatialPanel(
                    SubspaceModifier.width(panelSize.width.dp).height(76.dp)
                        .offset(
                            x = panelOffset.x.dp,
                            y = (panelOffset.y - panelSize.height / 2f - 12f - 38f).dp,
                            z = panelOffset.z.dp,
                        ),
                    shape = SpatialRoundedCornerShape(CornerSize(AuraCorners.Control)),
                ) {
                    SnipRegion(snipController, "dock", "main", Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in listOf(
                            // Only way back to the primary panel once you've
                            // navigated elsewhere — that's where Follow Me,
                            // Walking Mode, Pin, and pairing status all live,
                            // and there was previously no dock entry back to it.
                            "home" to "Home",
                            "code" to "Code",
                            "terminal" to "Terminal",
                            "diff" to "Diff",
                            "git" to "Git",
                            "agents" to "Agents",
                            "permissions" to "Permissions",
                            "remote" to "Remote",
                            "voice" to "Voice",
                            "snip" to "Snip",
                            "browser" to "Browser",
                            "settings" to "Settings",
                        )) {
                            androidx.compose.runtime.key(id) {
                                Box(Modifier.weight(1f)) {
                                    DockButtonContent(id, label, active = activeView == id) {
                                        when (id) {
                                            "voice" -> requestVoiceCommand()
                                            "snip" -> startSnip()
                                            "browser" -> browserOverlayOpen = true
                                            else -> activeView = id
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
    }

    if (followMeEnabled && xrSession != null && deviceTrackingReady) {
        // Positional args, not named — androidx.xr.compose's public parameter
        // names for this experimental alpha API aren't available offline.
        // TrackedDimensions.All (full 6 DOF) explicitly, not the default —
        // confirmed live that a translation-only default followed you walking
        // forward but never re-faced you after a turn.
        FollowingSubspace(
            FollowTarget.ArDevice(xrSession),
            FollowBehavior.Tight,
            SubspaceModifier,
            androidx.xr.compose.subspace.TrackedDimensions.All,
        ) {
            spatialContent()
        }
    } else {
        Subspace {
            SpatialBox {
                spatialContent()
            }
        }
    }
}
