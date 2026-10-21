package com.byokai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byokai.ByokaiApplication
import com.byokai.R
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig

/**
 * Streaming chat screen.
 *
 * - The top selectors choose the provider config and model. Only
 *   configs with a saved API key are selectable; the rest are disabled
 *   with a "no key" hint.
 * - Messages render as bubbles (user right, assistant left). Assistant
 *   text is appended live from the stream; a generating indicator shows
 *   while the first chunk is awaited. A failed or stopped stream keeps
 *   its partial text, marked as partial.
 * - Send is enabled only when a keyed config, a model, and non-blank
 *   input are present ([ChatUiState.canSend]). While generating, Send
 *   becomes Stop.
 * - Errors appear as a banner with Retry (resends the last user
 *   message, dropping the failed partial) and dismiss actions.
 * - Honest empty states: no provider configured, or no key saved yet —
 *   both link to the providers screen. No fake conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    onProvidersClick: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as ByokaiApplication
    val container = application.appContainer
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(
            container.chatRepository,
            container.providerConfigRepository,
            container.credentialStore,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedConfig = uiState.configs.firstOrNull { it.id == uiState.selectedConfigId }
    val anyKeyReady = uiState.configs.any { uiState.keyReady[it.id] == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                uiState.configs.isEmpty() -> EmptyChatState(
                    title = stringResource(R.string.chat_no_providers_title),
                    body = stringResource(R.string.chat_no_providers_body),
                    actionLabel = stringResource(R.string.chat_open_providers),
                    onAction = onProvidersClick,
                )

                !anyKeyReady -> EmptyChatState(
                    title = stringResource(R.string.chat_no_key_title),
                    body = stringResource(R.string.chat_no_key_body),
                    actionLabel = stringResource(R.string.chat_open_providers),
                    onAction = onProvidersClick,
                )

                else -> {
                    SelectorsRow(
                        configs = uiState.configs,
                        keyReady = uiState.keyReady,
                        selectedConfig = selectedConfig,
                        selectedModelId = uiState.selectedModelId,
                        onConfigSelect = viewModel::selectConfig,
                        onModelSelect = viewModel::selectModel,
                    )
                    HorizontalDivider()

                    val error = uiState.error
                    if (error != null) {
                        ErrorBanner(
                            message = error.asString(context),
                            onRetry = { viewModel.retry() },
                            onDismiss = viewModel::dismissError,
                        )
                    }

                    MessageList(
                        messages = uiState.messages,
                        generating = uiState.generating,
                        modifier = Modifier.weight(1f),
                    )

                    InputRow(
                        input = uiState.input,
                        canSend = uiState.canSend,
                        generating = uiState.generating,
                        onInputChange = viewModel::updateInput,
                        onSend = { viewModel.send() },
                        onStop = viewModel::stop,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorsRow(
    configs: List<ProviderConfig>,
    keyReady: Map<String, Boolean>,
    selectedConfig: ProviderConfig?,
    selectedModelId: String?,
    onConfigSelect: (String) -> Unit,
    onModelSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConfigDropdown(
            configs = configs,
            keyReady = keyReady,
            selected = selectedConfig,
            onSelect = onConfigSelect,
            modifier = Modifier.weight(1f),
        )
        ModelDropdown(
            models = selectedConfig?.modelIds.orEmpty(),
            selected = selectedModelId,
            onSelect = onModelSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigDropdown(
    configs: List<ProviderConfig>,
    keyReady: Map<String, Boolean>,
    selected: ProviderConfig?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected?.displayName.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.chat_select_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            configs.forEach { config ->
                val ready = keyReady[config.id] == true
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(config.displayName)
                            Text(
                                if (ready) {
                                    config.baseUrl
                                } else {
                                    stringResource(R.string.providers_no_key)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    enabled = ready,
                    onClick = {
                        onSelect(config.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = models.isNotEmpty() && it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = models.isNotEmpty(),
            label = { Text(stringResource(R.string.chat_select_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.chat_retry))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_dismiss_error),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatUiMessage>,
    generating: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages.size, key = { index -> index }) { index ->
            val message = messages[index]
            MessageBubble(
                message = message,
                showGenerating = message.isPending && generating,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    showGenerating: Boolean,
) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            tonalElevation = 1.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (showGenerating && message.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.chat_generating),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (message.isPending && !showGenerating &&
                    message.role == ChatRole.ASSISTANT && message.text.isNotEmpty()
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.chat_partial),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InputRow(
    input: String,
    canSend: Boolean,
    generating: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(stringResource(R.string.chat_message_hint)) },
            enabled = !generating,
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (generating) {
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.chat_stop),
                )
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                )
            }
        }
    }
}
