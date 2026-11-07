package com.lenscode.app.ui

import com.lenscode.app.voice.AgentModelChoice

/**
 * The "Did you mean …?" confirmation for a spoken agent or model that matched nothing exactly. Confirming
 * (tap, or saying "confirm") closes the card and then calls [onAccept] with the suggestion; cancelling only
 * closes it, so the switch or the spoken task never happens without a yes. [onClose] clears the card.
 */
fun agentModelSuggestionCard(
    heard: String,
    suggestion: AgentModelChoice,
    suffix: String,
    onAccept: (AgentModelChoice) -> Unit,
    onClose: () -> Unit,
): DeleteConfirmState = DeleteConfirmState(
    title = "Did you mean ${suggestion.label}?",
    message = "No agent or model is called \"$heard\". The closest is ${suggestion.label}. " +
        "Say “confirm” to use it, or “cancel”.$suffix",
    confirmLabel = "Use ${suggestion.model ?: suggestion.provider}",
    onConfirm = {
        onClose()
        onAccept(suggestion)
    },
    onCancel = onClose,
)
