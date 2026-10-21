package com.byokai.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byokai.R
import com.byokai.core.chat.ContextPolicy

/**
 * Subtle context estimate label above the chat input (Milestone 4D).
 *
 * Shows the heuristic token estimate for the next request's context
 * ("est." — never an official provider count) and, when history was
 * truncated request-side, how many of the oldest messages were left
 * out. Persisted history is unaffected; this only describes what the
 * provider will receive.
 */
@Composable
fun ContextEstimateLabel(plan: ContextPolicy.Plan?) {
    if (plan == null) return
    val text = buildString {
        append(stringResource(R.string.chat_context_estimate, formatTokens(plan.estimatedTokens)))
        if (plan.droppedCount > 0) {
            append(" · ")
            append(stringResource(R.string.chat_context_truncated, plan.droppedCount))
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** Compact token rendering: 999 → "999", 1500 → "~2k". */
private fun formatTokens(tokens: Int): String =
    if (tokens < 1000) tokens.toString() else "~${tokens / 1000}k"
