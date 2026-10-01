package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.AgentInfo

@Composable
fun AgentChipContent(name: String, stateLabel: String, dotColor: Color, kind: String = name) {
    CardSurface {
        Column(
            Modifier.fillMaxSize().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // .logo circular badge: bg #0b1119, border #2c3b4a, per-agent accent color.
            // Accent is looked up by kind (e.g. "claude"), not the user's custom
            // display name — those rarely match one of the known providers.
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(DockCorner))
                    .background(LensCodeColors.LogoBg)
                    .border(1.dp, LensCodeColors.LogoBorder, RoundedCornerShape(DockCorner)),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1), color = LensCodeColors.agentAccent(kind), fontWeight = FontWeight.Black, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(name, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dotColor, size = 6.dp)
                Spacer(Modifier.width(4.dp))
                Text(stateLabel, color = LensCodeColors.Muted, fontSize = 11.sp)
            }
        }
    }
}

/** One row in the grouped agent-history list — a date divider or an entry under it. */
internal sealed class HistoryRow(val rowKey: String) {
    class DateHeader(val label: String) : HistoryRow("header-$label")
    class Entry(val entry: com.lenscode.app.net.AgentHistoryEntry) : HistoryRow(entry.agentId)
}

/** "Today" / "Yesterday" / "September 18, 2026" — the session's local creation date. */
internal fun formatHistoryDateLabel(createdAtIso: String): String {
    return try {
        val date = java.time.Instant.parse(createdAtIso).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val today = java.time.LocalDate.now()
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        }
    } catch (e: Exception) {
        "Unknown date"
    }
}

/**
 * Groups already-sorted history entries under a date header per distinct day
 * (by creation date), inserting a new header only when the date changes from
 * the entry immediately before it — matches how the backend already orders
 * the list rather than re-sorting client-side.
 */
internal fun buildHistoryRows(history: List<com.lenscode.app.net.AgentHistoryEntry>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    var lastLabel: String? = null
    for (entry in history) {
        val label = formatHistoryDateLabel(entry.createdAt)
        if (label != lastLabel) {
            rows.add(HistoryRow.DateHeader(label))
            lastLabel = label
        }
        rows.add(HistoryRow.Entry(entry))
    }
    return rows
}

/** Agents view: real Paseo agent.list — honestly empty until an agent is started. */
@Composable
fun AgentsPanelContent(
    agents: List<AgentInfo>,
    onSelect: (AgentInfo) -> Unit,
    onArchive: (AgentInfo) -> Unit = {},
    // Archived/closed agents (docs/snip-and-agent-management-plan.md) — Paseo
    // still has their full record even after agent.list drops them; this is
    // LensCode's equivalent of Paseo Desktop's own History view.
    history: List<com.lenscode.app.net.AgentHistoryEntry> = emptyList(),
    historyHasMore: Boolean = false,
    onShowHistory: () -> Unit = {},
    onLoadMoreHistory: () -> Unit = {},
    onSelectHistory: (com.lenscode.app.net.AgentHistoryEntry) -> Unit = {},
) {
    // Local, not lifted to the caller — this list's own close button is the
    // only thing that can open it, same as TerminalPanelContent's stopConfirm.
    var archiveTarget by remember { mutableStateOf<AgentInfo?>(null) }
    var showingHistory by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Eyebrow(if (showingHistory) "AGENT HISTORY" else "AGENTS")
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, LensCodeColors.Cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable {
                            showingHistory = !showingHistory
                            if (showingHistory) onShowHistory()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (showingHistory) "Live" else "History",
                        color = LensCodeColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (showingHistory) {
                Text(
                    "Archived and closed agents — read only.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (history.isEmpty()) {
                    Text("No agent history yet.", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else {
                    val rows = remember(history) { buildHistoryRows(history) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        itemsIndexed(rows, key = { index, row -> "$index-${row.rowKey}" }) { _, row ->
                            when (row) {
                                is HistoryRow.DateHeader -> Text(
                                    row.label,
                                    color = LensCodeColors.Amber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                )
                                is HistoryRow.Entry -> {
                                    val entry = row.entry
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(3.dp))
                                            .clickable { onSelectHistory(entry) }
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StatusDot(LensCodeColors.Muted, size = 8.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(entry.label, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Text("(${entry.provider})", color = LensCodeColors.Eyebrow, fontSize = 12.sp)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            entry.archivedAt?.let { "archived" } ?: entry.status,
                                            color = LensCodeColors.Muted,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                        if (historyHasMore) {
                            item {
                                Text(
                                    "Load more",
                                    color = LensCodeColors.Cyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onLoadMoreHistory)
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                return@Column
            }
            if (agents.isNotEmpty()) {
                Text("Tap an agent to see status and respond.", color = LensCodeColors.Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            if (agents.isEmpty()) {
                Text(
                    "No agents running.",
                    color = LensCodeColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paseo reports zero active agents right now — start one\nwith `paseo run` to see it appear here.",
                    color = LensCodeColors.Muted,
                    fontSize = 13.sp,
                )
            } else {
                LazyColumn {
                    items(agents) { agent ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { onSelect(agent) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(LensCodeColors.agentStatusColor(agent.status), size = 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(agent.label, color = LensCodeColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("(${agent.provider})", color = LensCodeColors.Eyebrow, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(agent.status, color = LensCodeColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "×",
                                color = LensCodeColors.Muted,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { archiveTarget = agent }.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    // Closes the agent's runtime — its history stays in Paseo, it just can't
    // take new messages, same guarantee as the detail card's Archive action
    // (spec §57: destructive actions always confirm first).
    if (archiveTarget != null) {
        val target = archiveTarget!!
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(360.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text("Archive this agent?", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This closes ${target.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
                        color = LensCodeColors.Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { archiveTarget = null }.padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Archive",
                            color = LensCodeColors.Amber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                onArchive(target)
                                archiveTarget = null
                            }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Permissions queue (docs/paseo-migration-plan.md, Phase 3) — the spatial
 * "approve this tool call" card. This is the single highest-value new
 * affordance the Paseo migration adds: heads-up approval from a glasses card
 * beats reaching for a phone. Every pending request across every local agent,
 * flattened into one list (`permission.list` already spans all agents).
 */
@Composable
fun PermissionsPanelContent(
    permissions: List<com.lenscode.app.net.PermissionInfo>,
    agents: List<AgentInfo>,
    onRespond: (com.lenscode.app.net.PermissionInfo, String, String?) -> Unit,
) {
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Eyebrow("PERMISSIONS")
            Spacer(Modifier.height(4.dp))
            if (permissions.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Nothing waiting on you.", color = LensCodeColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tool calls and other approvals an agent needs from you show up here.",
                    color = LensCodeColors.Muted,
                    fontSize = 13.sp,
                )
            } else {
                Text("Tap Allow or Deny to answer — never sent without your say.", color = LensCodeColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                LazyColumn {
                    items(permissions, key = { it.permissionRequestId }) { permission ->
                        val agentLabel = agents.find { it.agentId == permission.agentId }?.label ?: permission.agentId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(LensCodeColors.CardBg)
                                .border(1.dp, LensCodeColors.CardBorder, RoundedCornerShape(3.dp))
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(agentLabel, color = LensCodeColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text("(${permission.kind})", color = LensCodeColors.Eyebrow, fontSize = 11.sp)
                            }
                            if (permission.title != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(permission.title, color = LensCodeColors.Text, fontSize = 13.sp)
                            }
                            if (permission.description != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(permission.description, color = LensCodeColors.Muted, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row {
                                if (permission.actions.isEmpty()) {
                                    Text(
                                        "Allow",
                                        color = LensCodeColors.Green,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onRespond(permission, "allow", null) }.padding(end = 20.dp),
                                    )
                                    Text(
                                        "Deny",
                                        color = LensCodeColors.Red,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onRespond(permission, "deny", null) },
                                    )
                                } else {
                                    for (action in permission.actions) {
                                        Text(
                                            action.label,
                                            color = if (action.behavior == "allow") LensCodeColors.Green else LensCodeColors.Red,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { onRespond(permission, action.behavior, action.id) }
                                                .padding(end = 20.dp),
                                        )
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

/** What the agent detail card shows (docs/paseo-migration-plan.md, Phase 3). */
data class AgentDetailState(val agent: AgentInfo, val promptError: String? = null)

/**
 * Agent detail card (spec §10 — a chip "expands to a detail card" rather than
 * navigating away). Paseo agents are first-class objects, not a scraped
 * terminal pane (see AgentInfo's doc), so unlike the Herdr-era version this
 * shows real agent metadata (model, cwd, cost, last error) instead of a raw
 * terminal snippet, and offers "Open Workspace" (the agent's own cwd/
 * workspace in Files+Terminal) rather than a fake "its terminal". A prompt
 * box only makes sense once the agent isn't closed — Paseo's own attention/
 * permission flow (see the Permissions queue) is the sanctioned way to
 * unblock one that's waiting on something, not a raw terminal keystroke.
 */
/**
 * What the agent is actually doing — every message and tool call, oldest
 * first (docs/paseo-migration-plan.md follow-up). Before this existed the
 * only way to see an agent's shell commands/output was querying the Paseo
 * daemon directly from outside the app.
 */
@Composable
private fun AgentActivityFeed(timeline: List<com.lenscode.app.net.AgentTimelineEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.size - 1)
    }
    Column {
        Text("ACTIVITY", color = LensCodeColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 220.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(VsCodeColors.EditorBg)
                .padding(10.dp),
        ) {
            if (timeline.isEmpty()) {
                Text("No activity yet.", color = LensCodeColors.Muted, fontSize = 12.sp)
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(timeline) { entry -> AgentActivityRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun AgentActivityRow(entry: com.lenscode.app.net.AgentTimelineEntry) {
    when (entry.kind) {
        "user_message" -> Text(
            "you: ${entry.text ?: ""}",
            color = LensCodeColors.Text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        "assistant_message" -> Text(
            entry.text ?: "",
            color = LensCodeColors.Cyan,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        "tool_call" -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (entry.status) {
                    "completed" -> LensCodeColors.Green
                    "error" -> LensCodeColors.Red
                    "running" -> LensCodeColors.Amber
                    else -> LensCodeColors.Muted
                }
                StatusDot(statusColor, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.toolName ?: "tool",
                    color = LensCodeColors.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (entry.command != null) {
                Text(
                    "$ ${entry.command}",
                    color = LensCodeColors.Text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (entry.output != null) {
                Text(
                    entry.output.take(500),
                    color = LensCodeColors.Muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 8,
                )
            }
        }
        else -> Text(
            entry.text ?: entry.kind,
            color = LensCodeColors.Muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
fun AgentDetailOverlay(
    state: AgentDetailState?,
    timeline: List<com.lenscode.app.net.AgentTimelineEntry> = emptyList(),
    promptText: String,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onArchive: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onDismiss: () -> Unit,
    isListeningForPrompt: Boolean = false,
    onVoiceInput: () -> Unit = {},
) {
    if (state == null) return
    val agent = state.agent
    val canPrompt = agent.status != "closed"

    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(440.dp)
                .glassFinish(PanelCorner)
                .clickable(enabled = false) {} // swallow taps so the card itself doesn't dismiss
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(DockCorner)).background(LensCodeColors.LogoBg)
                            .border(1.dp, LensCodeColors.LogoBorder, RoundedCornerShape(DockCorner)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            agent.label.take(1),
                            color = LensCodeColors.agentAccent(agent.provider),
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(agent.label, color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(LensCodeColors.agentStatusColor(agent.status), size = 6.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("${agent.provider} · ${agent.status}", color = LensCodeColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(VsCodeColors.EditorBg)
                        .padding(10.dp),
                ) {
                    Column {
                        Text(agent.cwd, color = VsCodeColors.Plain, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        if (agent.model != null) {
                            Text("model ${agent.model}", color = LensCodeColors.Muted, fontSize = 11.sp)
                        }
                        if (agent.costUsd != null) {
                            Text("cost \$${"%.4f".format(agent.costUsd)}", color = LensCodeColors.Muted, fontSize = 11.sp)
                        }
                        if (agent.pendingPermissionCount > 0) {
                            Text(
                                "⚠ ${agent.pendingPermissionCount} permission request(s) pending — see Permissions",
                                color = LensCodeColors.Amber,
                                fontSize = 11.sp,
                            )
                        }
                        if (agent.lastError != null) {
                            Text(agent.lastError, color = LensCodeColors.Red, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                AgentActivityFeed(timeline)
                Spacer(Modifier.height(14.dp))
                if (canPrompt) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(VsCodeColors.EditorBg)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            if (promptText.isEmpty() && !isListeningForPrompt) {
                                Text("Send a message…", color = LensCodeColors.Muted, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = promptText,
                                onValueChange = onPromptChange,
                                textStyle = TextStyle(color = LensCodeColors.Text, fontSize = 13.sp),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(LensCodeColors.Text),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { onSendPrompt() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // Voice fills the field above rather than sending on its
                        // own — always something to review before Send actually
                        // reaches the agent.
                        if (isListeningForPrompt) {
                            StatusDot(LensCodeColors.Red, size = 8.dp)
                        } else {
                            Text(
                                "🎤",
                                fontSize = 15.sp,
                                modifier = Modifier.clickable(onClick = onVoiceInput).padding(8.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Send",
                            color = LensCodeColors.Blue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onSendPrompt).padding(8.dp),
                        )
                    }
                    if (state.promptError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(state.promptError, color = LensCodeColors.Amber, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        "This agent is closed — open its workspace to start a new one there.",
                        color = LensCodeColors.Amber,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Open Workspace",
                        color = LensCodeColors.Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onOpenWorkspace),
                    )
                    Text(
                        "Archive",
                        color = LensCodeColors.Amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onArchive),
                    )
                    Text(
                        "Close",
                        color = LensCodeColors.Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                }
            }
        }
    }
}
