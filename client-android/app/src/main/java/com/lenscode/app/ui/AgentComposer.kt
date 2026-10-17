package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.MachineInfo
import com.lenscode.app.net.ProviderInfo
import com.lenscode.app.net.WorktreeInfo
import kotlinx.coroutines.delay

/** One permission mode the composer offers — [id] is the Paseo modeId, [spoken] is what to say to pick it. */
data class ComposerMode(val id: String, val label: String, val description: String, val spoken: String)

/**
 * Claude Code's own permission-mode list. "Auto" isn't offered separately:
 * Claude's CLI rejects `auto` as a mode (confirmed live, see modeIdFor's old
 * note), and the "auto-approve edits" behavior the voice flow always used is
 * exactly Accept edits — "auto mode" is just a spoken alias for it.
 */
val ComposerModes = listOf(
    ComposerMode("acceptEdits", "Accept edits", "Automatically accept all file edits", "accept edits"),
    ComposerMode("default", "Manual", "Always ask before making changes", "manual mode"),
    ComposerMode("plan", "Plan", "Create a plan before making changes", "plan mode"),
)
val ComposerBypassMode = ComposerMode("bypassPermissions", "Bypass permissions", "No approval prompts at all", "bypass permissions")

fun composerModeFor(id: String): ComposerMode =
    (ComposerModes + ComposerBypassMode).firstOrNull { it.id == id } ?: ComposerModes.first()

/**
 * Everything a new agent is created with, shared by the Code-view composer and
 * voice's ask_agent — so a mode/model/worktree picked by tap or by voice is
 * the same one either path uses, and is always visible on the chips.
 */
class ComposerState {
    var modeId by mutableStateOf("acceptEdits")
    var provider by mutableStateOf("claude")
    /** null = the provider's own default model. */
    var model by mutableStateOf<String?>(null)
    var worktree by mutableStateOf(false)
    /** TextFieldValue (not String) so dictation can insert at the pinch-dragged cursor. */
    var prompt by mutableStateOf(TextFieldValue(""))

    /** Which inline menu is open: "machine" | "branch" | "mode" | "model" | null. */
    var menu by mutableStateOf<String?>(null)
    /** Chip that just changed by voice/tap — briefly highlighted so a spoken change is visibly confirmed. */
    var flash by mutableStateOf<String?>(null)

    fun toggleMenu(name: String) {
        menu = if (menu == name) null else name
    }
}

@Composable
private fun Chip(
    label: String,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        color = if (highlighted) LensCodeColors.ActiveDock else LensCodeColors.Text,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) LensCodeColors.DockActiveGradTop else LensCodeColors.CardBg)
            .border(
                1.dp,
                if (highlighted) LensCodeColors.DockActiveBorder else LensCodeColors.CardBorder,
                RoundedCornerShape(8.dp),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    hint: String? = null,
    dotColor: Color? = null,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            StatusDot(dotColor, size = 7.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = LensCodeColors.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (subtitle != null) Text(subtitle, color = LensCodeColors.Muted, fontSize = 12.sp)
        }
        if (hint != null) Text(hint, color = LensCodeColors.Muted, fontSize = 11.sp)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text("✓", color = LensCodeColors.Blue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (onRemove != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "×",
                color = LensCodeColors.Muted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun MenuCard(speakHint: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LensCodeColors.CardBg)
            .border(1.dp, LensCodeColors.CardBorder, RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
        // Voice-first: every menu says how to drive it by voice.
        Text(
            "🎤 say “$speakHint”",
            color = LensCodeColors.Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * Claude Code–style composer for the Code view: machine / folder / branch +
 * worktree chips on top, the prompt box, and mode + model chips underneath.
 * Everything a chip can do also has a spoken command (see the Koog router's
 * set_mode / set_model / set_worktree / switch_branch actions).
 */
@Composable
fun AgentComposer(
    state: ComposerState,
    machineLabel: String,
    machineId: String,
    onSelectMachine: (MachineInfo) -> Unit,
    folderLabel: String,
    machines: List<MachineInfo>,
    branches: List<String>,
    currentBranch: String?,
    worktrees: List<WorktreeInfo>,
    providers: List<ProviderInfo>,
    isListening: Boolean,
    dictationHint: String? = null,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onSwitchBranch: (String) -> Unit,
    onOpenWorktree: (WorktreeInfo) -> Unit,
    onCreateWorktree: (String) -> Unit,
    onRemoveWorktree: (WorktreeInfo) -> Unit,
    onModeChosen: (ComposerMode) -> Unit,
    onModelChosen: (provider: String, model: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.flash) {
        if (state.flash != null) {
            delay(1800)
            state.flash = null
        }
    }
    val mode = composerModeFor(state.modeId)
    val modelLabel = state.model ?: state.provider

    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── Inline menus (above the chips, like Claude Code's popovers) ──
        when (state.menu) {
            "machine" -> MenuCard("use <machine name>") {
                // Only registered hosts — there is no built-in "local" machine.
                val hosts = machines.filter { it.id != "local" }
                if (hosts.isEmpty()) MenuRow("No machines registered", onClick = { state.menu = null })
                for (m in hosts) {
                    MenuRow(
                        m.label.ifBlank { m.id },
                        if (m.connected) "Connected" else "Tap to connect",
                        selected = m.id == machineId,
                    ) {
                        state.menu = null
                        onSelectMachine(m)
                    }
                }
            }
            "branch" -> MenuCard("switch to <branch>") {
                // A branch already checked out in a worktree can't be switched
                // to (git refuses outright) — it lives in WORKTREES below and
                // tapping it opens that checkout instead.
                val worktreeBranches = worktrees.mapNotNull { it.branch }.toSet()
                val switchable = branches.filter { it != currentBranch && it !in worktreeBranches }
                // Worktree checkouts usually share a parent dir — strip the
                // common prefix so the subtitle shows what distinguishes them.
                val sharedPrefix = worktrees.map { it.path }
                    .reduceOrNull { a, b -> a.commonPrefixWith(b) }
                    ?.substringBeforeLast('/')
                    ?: ""
                fun shortPath(path: String) =
                    if (sharedPrefix.length > 1 && path.startsWith(sharedPrefix))
                        "…${path.removePrefix(sharedPrefix)}"
                    else path
                if (currentBranch != null) {
                    // Not always the main checkout — Code can be rooted in a
                    // worktree, in which case that branch shows up here too.
                    MenuRow(currentBranch, "current checkout", selected = true, onClick = { state.menu = null })
                }
                for (b in switchable) {
                    MenuRow(b) {
                        state.menu = null
                        onSwitchBranch(b)
                    }
                }
                if (currentBranch == null && switchable.isEmpty() && worktrees.isEmpty()) {
                    MenuRow("No branches found", onClick = { state.menu = null })
                }
                Text(
                    "WORKTREES",
                    color = LensCodeColors.Eyebrow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp),
                )
                for (wt in worktrees) {
                    val open = wt.openWorkspaceId != null
                    MenuRow(
                        wt.branch ?: wt.path.substringAfterLast('/'),
                        shortPath(wt.path),
                        hint = if (open) "open" else null,
                        dotColor = if (open) LensCodeColors.Cyan else LensCodeColors.Muted,
                        onRemove = { onRemoveWorktree(wt) },
                    ) {
                        state.menu = null
                        onOpenWorktree(wt)
                    }
                }
                MenuRow(
                    "+ New worktree",
                    "check out a branch in its own worktree",
                ) { state.menu = "newworktree" }
            }
            "newworktree" -> MenuCard("create a worktree for <branch>") {
                val worktreeBranches = worktrees.mapNotNull { it.branch }.toSet()
                // The current branch is checked out in the main checkout — git
                // refuses to give it a second checkout, same as worktree'd branches.
                val candidates = branches.filter { it != currentBranch && it !in worktreeBranches }
                if (candidates.isEmpty()) {
                    MenuRow("Every other branch already has a worktree", onClick = { state.menu = "branch" })
                }
                for (b in candidates) {
                    MenuRow(b) {
                        state.menu = null
                        onCreateWorktree(b)
                    }
                }
            }
            "mode" -> MenuCard("plan mode, accept edits, manual") {
                Text(
                    "MODE",
                    color = LensCodeColors.Eyebrow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp),
                )
                for (m in ComposerModes) {
                    MenuRow(m.label, m.description, selected = m.id == state.modeId, hint = "“${m.spoken}”") {
                        state.menu = null
                        onModeChosen(m)
                    }
                }
                MenuRow(
                    ComposerBypassMode.label,
                    "Skips every approval — asks you to confirm first",
                    selected = state.modeId == ComposerBypassMode.id,
                    hint = "“${ComposerBypassMode.spoken}”",
                ) {
                    state.menu = null
                    onModeChosen(ComposerBypassMode)
                }
            }
            "model" -> MenuCard("use <model name>") {
                val usable = providers.filter { it.available }
                if (usable.isEmpty()) MenuRow("No providers loaded yet", onClick = { state.menu = null })
                for (p in usable) {
                    MenuRow(p.provider, "provider default", selected = state.provider == p.provider && state.model == null) {
                        state.menu = null
                        onModelChosen(p.provider, null)
                    }
                    for (m in p.models) {
                        MenuRow(m, selected = state.provider == p.provider && state.model == m) {
                            state.menu = null
                            onModelChosen(p.provider, m)
                        }
                    }
                }
            }
        }

        // ── Top chip row: machine · folder · branch | worktree ──────────
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("💻 $machineLabel", state.menu == "machine" || state.flash == "machine", { state.toggleMenu("machine") })
            Chip("📁 $folderLabel")
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.flash == "branch") LensCodeColors.DockActiveGradTop else LensCodeColors.CardBg)
                    .border(1.dp, if (state.flash == "branch") LensCodeColors.DockActiveBorder else LensCodeColors.CardBorder, RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⑂ ${currentBranch ?: "branch"}",
                    color = LensCodeColors.Text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable { state.toggleMenu("branch") }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Box(Modifier.width(1.dp).size(1.dp, 18.dp).background(LensCodeColors.CardBorder))
                Row(
                    Modifier
                        .clickable {
                            state.worktree = !state.worktree
                            state.flash = "branch"
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.worktree) "☑" else "☐",
                        color = if (state.worktree) LensCodeColors.Blue else LensCodeColors.Muted,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("worktree", color = LensCodeColors.Text, fontSize = 13.sp)
                }
            }
        }

        // ── Prompt box ───────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LensCodeColors.CardBg)
                .border(1.dp, LensCodeColors.CardBorder, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f).heightIn(min = 44.dp)) {
                val shown = state.prompt
                if (shown.text.isEmpty()) {
                    Text(
                        if (isListening) "Listening…" else "Speak or type a task for a new agent…",
                        color = LensCodeColors.Muted,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = shown,
                    onValueChange = { state.prompt = it },
                    textStyle = TextStyle(color = LensCodeColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(LensCodeColors.Text),
                    // The keyboard's Enter key sends (it used to just add a newline,
                    // so typing a task and hitting Enter did nothing).
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.prompt.text.isNotBlank()) onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.prompt.text.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "✕",
                    color = LensCodeColors.Muted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { state.prompt = TextFieldValue("") }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "⏎",
                color = if (state.prompt.text.isBlank()) LensCodeColors.Muted else LensCodeColors.ActiveDock,
                fontSize = 20.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = state.prompt.text.isNotBlank(), onClick = onSend)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // In-flight dictation shown under the box, not inside it — writing the
        // partial into the field would corrupt the cursor's selection indexes.
        if (isListening && !dictationHint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("…$dictationHint", color = LensCodeColors.Muted, fontSize = 12.sp, maxLines = 2)
        }

        // ── Bottom row: mic · mode ······ model ──────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The mic is the primary control (voice-first): large, and lit while
            // listening — dictation stays open until this is tapped again.
            Text(
                if (isListening) "🎙 Stop" else "🎤 Speak",
                color = if (isListening) LensCodeColors.ActiveDock else LensCodeColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isListening) LensCodeColors.DockActiveGradTop else LensCodeColors.CardBg)
                    .border(1.dp, if (isListening) LensCodeColors.DockActiveBorder else LensCodeColors.CardBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onMic)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Chip(
                mode.label,
                highlighted = state.flash == "mode" || state.menu == "mode",
                onClick = { state.toggleMenu("mode") },
            )
            Spacer(Modifier.weight(1f))
            Chip(
                modelLabel,
                highlighted = state.flash == "model" || state.menu == "model",
                onClick = { state.toggleMenu("model") },
            )
        }
    }
}
