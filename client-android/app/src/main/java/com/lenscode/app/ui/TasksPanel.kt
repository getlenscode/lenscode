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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.AgentInfo
import com.lenscode.app.net.MachineInfo
import com.lenscode.app.net.TaskCard
import com.lenscode.app.net.TaskPreset
import com.lenscode.app.net.TasksBoard

private const val UNASSIGNED = "\u0000unassigned"
private val COLUMN_WIDTH = 270.dp
private val STATUS_ORDER = listOf("backlog", "todo", "in_progress", "in_review", "done", "canceled")

/** Wire status -> the label on a column header. */
fun taskStatusLabel(status: String): String = when (status) {
    "backlog" -> "Backlog"
    "todo" -> "Todo"
    "in_progress" -> "In Progress"
    "in_review" -> "In Review"
    "done" -> "Done"
    "canceled" -> "Canceled"
    else -> status
}

/**
 * The agent tags on a task: the providers working on it ("claude", "codex", …). Two sources, since the
 * plugin's own session attach only works from its UI: sessions it recorded on the task (their id is a
 * Paseo agent id, so the Agents list gives the provider; a session no longer listed falls back to its
 * preset's name), and agents whose title starts with the task key, which is how the app names the ones
 * it starts for a task.
 */
fun taskAgentTags(task: TaskCard, agents: List<AgentInfo>): List<String> {
    val provider = agents.associate { it.agentId to it.provider }
    val fromThreads = task.threads.map { provider[it.threadId] ?: it.presetName.lowercase().substringBefore(' ') }
    val fromTitles = agents.filter { it.title.orEmpty().startsWith("${task.key}:") }.map { it.provider }
    return (fromThreads + fromTitles).filter { it.isNotBlank() }.distinct()
}

private fun priorityColor(priority: String): Color? = when (priority) {
    "urgent", "high" -> LensCodeColors.Red
    "medium" -> LensCodeColors.Amber
    "low" -> LensCodeColors.Muted
    else -> null
}

/**
 * Kanban view of the Paseo Tasks plugin: one column per status, filterable by the agent working on
 * a task. Tap a card to move it or hand it to an agent; new tasks come from the voice command.
 */
@Composable
fun TasksPanelContent(
    board: TasksBoard?,
    error: String?,
    agents: List<AgentInfo>,
    machines: List<MachineInfo>,
    machineId: String,
    onMachine: (String) -> Unit,
    onRefresh: () -> Unit,
    onMove: (key: String, status: String) -> Unit,
    onRun: (task: TaskCard, preset: TaskPreset) -> Unit,
) {
    var filter by remember { mutableStateOf<String?>(null) }
    var project by remember { mutableStateOf<String?>(null) }
    var openKey by remember { mutableStateOf<String?>(null) }
    GlassSurface {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("TASKS")
                Spacer(Modifier.weight(1f))
                if (board != null && board.available) {
                    Text("${board.tasks.size} tasks", color = LensCodeColors.Muted, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    "Refresh",
                    color = LensCodeColors.Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onRefresh).padding(4.dp),
                )
            }
            val connected = machines.filter { it.connected }
            if (connected.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("This computer", null, LensCodeColors.Cyan, machineId == "local") { onMachine("local") }
                    for (m in connected) {
                        FilterChip(m.label.ifBlank { m.id }, null, LensCodeColors.Cyan, machineId == m.id) { onMachine(m.id) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            when {
                error != null -> Notice(error)
                board == null -> Notice("Loading tasks…")
                !board.available -> Notice(board.reason ?: "The Paseo Tasks plugin isn't available.")
                else -> BoardBody(
                    board, agents, filter, project, openKey,
                    onFilter = { filter = it },
                    onProject = { project = it },
                    onOpen = { openKey = if (openKey == it) null else it },
                    onMove = onMove,
                    onRun = onRun,
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LensCodeColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun BoardBody(
    board: TasksBoard,
    agents: List<AgentInfo>,
    filter: String?,
    project: String?,
    openKey: String?,
    onFilter: (String?) -> Unit,
    onProject: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onRun: (TaskCard, TaskPreset) -> Unit,
) {
    val tags = board.tasks.associate { it.key to taskAgentTags(it, agents) }
    val agents = tags.values.flatten().distinct()
    val untagged = board.tasks.count { tags[it.key].isNullOrEmpty() }
    val visible = board.tasks.filter { t ->
        (project == null || t.key.substringBefore('-') == project) &&
            when (filter) {
                null -> true
                UNASSIGNED -> tags[t.key].isNullOrEmpty()
                else -> filter in tags[t.key].orEmpty()
            }
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip("All", board.tasks.size, LensCodeColors.Text, filter == null) { onFilter(null) }
        for (a in agents) {
            val count = tags.values.count { a in it }
            FilterChip("@$a", count, LensCodeColors.agentAccent(a), filter == a) { onFilter(if (filter == a) null else a) }
        }
        if (agents.isNotEmpty() && untagged > 0) {
            FilterChip("No agent", untagged, LensCodeColors.Muted, filter == UNASSIGNED) {
                onFilter(if (filter == UNASSIGNED) null else UNASSIGNED)
            }
        }
        if (board.projects.size > 1) {
            Spacer(Modifier.width(10.dp))
            for (p in board.projects) {
                FilterChip(p.prefix, board.tasks.count { it.key.substringBefore('-') == p.prefix }, LensCodeColors.Blue, project == p.prefix) {
                    onProject(if (project == p.prefix) null else p.prefix)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    val columns = board.statuses.ifEmpty { STATUS_ORDER }
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (status in columns) {
            val cards = visible.filter { it.status == status }
            // Canceled is clutter unless something is in it.
            if (status == "canceled" && cards.isEmpty()) continue
            Column(
                Modifier
                    .width(COLUMN_WIDTH)
                    .fillMaxHeight()
                    .clip(lensShape(10.dp))
                    .background(LensCodeColors.CardBg)
                    .border(1.dp, LensCodeColors.CardBorder, lensShape(10.dp))
                    .padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor(status), size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(taskStatusLabel(status), color = LensCodeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${cards.size}", color = LensCodeColors.Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cards, key = { it.key }) { task ->
                        TaskCardView(
                            task = task,
                            agentTags = tags[task.key].orEmpty(),
                            expanded = openKey == task.key,
                            presets = board.presets,
                            onClick = { onOpen(task.key) },
                            onMove = { onMove(task.key, it) },
                            onRun = { onRun(task, it) },
                        )
                    }
                }
            }
        }
    }
}

private fun statusColor(status: String): Color = when (status) {
    "backlog" -> LensCodeColors.Muted
    "todo" -> LensCodeColors.Blue
    "in_progress" -> LensCodeColors.Cyan
    "in_review" -> LensCodeColors.Amber
    "done" -> LensCodeColors.Green
    else -> LensCodeColors.Red
}

@Composable
private fun FilterChip(label: String, count: Int?, accent: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = lensShape(DockCorner)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else LensCodeColors.DockBg)
            .border(1.dp, if (selected) accent else LensCodeColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) accent else LensCodeColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = LensCodeColors.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TaskCardView(
    task: TaskCard,
    agentTags: List<String>,
    expanded: Boolean,
    presets: List<TaskPreset>,
    onClick: () -> Unit,
    onMove: (String) -> Unit,
    onRun: (TaskPreset) -> Unit,
) {
    val shape = lensShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LensCodeColors.DockBg)
            .border(1.dp, if (expanded) LensCodeColors.Cyan else LensCodeColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.key, color = LensCodeColors.Muted, fontSize = 11.sp)
            if (task.agentsWorking > 0) {
                Spacer(Modifier.width(6.dp))
                StatusDot(LensCodeColors.Green, size = 6.dp)
                Text(" ${task.agentsWorking} working", color = LensCodeColors.Green, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            priorityColor(task.priority)?.let { c ->
                Text(task.priority.uppercase(), color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(task.title, color = LensCodeColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (task.summary.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(task.summary, color = LensCodeColors.Muted, fontSize = 12.sp, maxLines = if (expanded) 8 else 3)
        }
        if (agentTags.isNotEmpty() || task.labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (a in agentTags) Tag("@$a", LensCodeColors.agentAccent(a), filled = true)
                for (l in task.labels) Tag(l, LensCodeColors.Muted, filled = false)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            ActionLabel("MOVE TO")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in STATUS_ORDER.filter { it != task.status && it != "canceled" }) {
                    Action(taskStatusLabel(s), statusColor(s)) { onMove(s) }
                }
            }
            if (presets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ActionLabel("RUN WITH")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in presets) Action(p.name, LensCodeColors.agentAccent(p.providerId)) { onRun(p) }
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(text: String) {
    Text(text, color = LensCodeColors.Eyebrow, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    val shape = lensShape(4.dp)
    Text(
        label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Tag(text: String, color: Color, filled: Boolean) {
    val shape = lensShape(4.dp)
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
