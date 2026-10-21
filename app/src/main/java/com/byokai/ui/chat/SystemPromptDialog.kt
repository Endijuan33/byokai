package com.byokai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.byokai.R
import com.byokai.domain.model.PromptTemplate

/**
 * Per-conversation system-prompt picker (Milestone 4C).
 *
 * The user picks a saved template (which fills the text field for
 * further tweaking), types fully custom text, or clears the field to
 * send no system prompt. Saving persists via
 * [ChatViewModel.setSystemPrompt]; the text is sent to the provider
 * through the adapter's system-prompt channel, never as a user
 * message.
 */
@Composable
fun SystemPromptDialog(
    prompts: List<PromptTemplate>,
    currentSystemPrompt: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var text by remember(currentSystemPrompt) {
        mutableStateOf(currentSystemPrompt.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_system_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.chat_system_prompt_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompts.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(prompts, key = { it.id }) { prompt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { text = prompt.content }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(
                                    selected = text == prompt.content && text.isNotBlank(),
                                    onClick = { text = prompt.content },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = prompt.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (prompt.isBuiltin) {
                                        Text(
                                            text = stringResource(R.string.prompts_builtin_chip),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.chat_system_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.chat_system_prompt_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.ifBlank { null }) }) {
                Text(stringResource(R.string.prompts_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.prompts_cancel))
            }
        },
    )
}
