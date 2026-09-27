package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lenscode.app.net.AgentInfo

/**
 * Generic "which agent?" picker for whenever an action has several
 * candidate agents and no single obvious target — a snip crop confirmed with
 * more than one agent active, or a voice archive_agent whose named target
 * fuzzy-matches more than one. Tapping an agent runs [onSelect]; tapping the
 * dimmed background runs [onDismiss] instead of guessing — same "never
 * silently guess" discipline voice search already follows (spec §13).
 */
@Composable
fun AgentPickerOverlay(
    title: String,
    agents: List<AgentInfo>,
    onSelect: (AgentInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E24))
                // Disabled (not omitted) clickable still consumes the touch,
                // so a tap on the card itself doesn't fall through to the
                // background's onDismiss above.
                .clickable(enabled = false) {}
                .padding(20.dp)
                .widthIn(min = 320.dp, max = 480.dp),
        ) {
            Column {
                Text(title, color = Color.White)
                Spacer(Modifier.height(12.dp))
                // Capped height + LazyColumn rather than a plain Column — with
                // three or more agents (easy once ask_agent/new_agent voice
                // flows are in play) an uncapped list ran off the card with
                // no way to reach the rest.
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(agents, key = { it.agentId }) { agent ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(agent) }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                        ) {
                            Column {
                                Text(agent.label, color = Color.White)
                                Text(agent.status, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
