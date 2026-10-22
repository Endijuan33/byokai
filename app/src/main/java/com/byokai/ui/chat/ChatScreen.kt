package com.byokai.ui.chat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byokai.ByokaiApplication
import com.byokai.R
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.ProviderConfig
import java.io.File

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
 * - Conversation history (Milestone 3E): [conversationId] opens an
 *   existing conversation; null starts a new one. Turns are persisted
 *   per conversation and the top bar shows its title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String?,
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
            container.conversationRepository,
            container.promptRepository,
            // Phase 9B integration: the coordinator must expose these on
            // AppContainer (Worker A provides the implementations).
            container.attachmentStorage,
            container.imageProcessor,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedConfig = uiState.configs.firstOrNull { it.id == uiState.selectedConfigId }
    val anyKeyReady = uiState.configs.any { uiState.keyReady[it.id] == true }
    val defaultTitle = stringResource(R.string.conversations_new)
    /** The user message currently being edited, or null. */
    var editingMessage by remember { mutableStateOf<ChatUiMessage?>(null) }

    // --- Phase 9B: voice input controller (screen-owned lifecycle). ---
    val voiceController = remember { VoiceInputController(context) }
    DisposableEffect(voiceController) {
        onDispose { voiceController.destroy() }
    }

    // --- Phase 9B: runtime permissions. ---
    // Rationale vs permanently-denied states drive one small dialog per
    // permission (see the dialog block near the end of this function).
    val activity = context as? Activity
    var audioRationale by remember { mutableStateOf(false) }
    var audioDenied by remember { mutableStateOf(false) }
    var cameraRationale by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    fun shouldShowRationale(permission: String): Boolean =
        activity?.shouldShowRequestPermissionRationale(permission) == true

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceInput(voiceController)
        } else if (shouldShowRationale(Manifest.permission.RECORD_AUDIO)) {
            audioRationale = true
        } else {
            audioDenied = true
        }
    }

    fun onMicClick() {
        if (uiState.voiceState is VoiceState.Listening) {
            viewModel.stopVoiceInput()
            return
        }
        viewModel.dismissVoiceError()
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED ->
                viewModel.startVoiceInput(voiceController)
            shouldShowRationale(Manifest.permission.RECORD_AUDIO) -> audioRationale = true
            else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- Phase 9B: image attachment launchers. ---
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    // TakePicture needs its destination file BEFORE launch; the URI and
    // file are held here until the result arrives.
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraPhotoUri
        val file = cameraTempFile
        cameraPhotoUri = null
        cameraTempFile = null
        if (success && uri != null && file != null) {
            viewModel.onCameraCaptured(uri, file)
        }
    }

    fun launchCamera() {
        val file = viewModel.prepareCameraFile()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        cameraPhotoUri = uri
        cameraTempFile = file
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCamera()
        } else if (shouldShowRationale(Manifest.permission.CAMERA)) {
            cameraRationale = true
        } else {
            cameraDenied = true
        }
    }

    fun onCameraClick() {
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED -> launchCamera()
            shouldShowRationale(Manifest.permission.CAMERA) -> cameraRationale = true
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(conversationId) {
        if (conversationId != null) {
            viewModel.loadConversation(conversationId, defaultTitle)
        } else {
            viewModel.newConversation(defaultTitle)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(uiState.conversationTitle ?: stringResource(R.string.chat_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Drops the conversation row when the user never
                            // sent anything; kept conversations are untouched.
                            viewModel.discardIfEmpty()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Per-conversation system prompt (Milestone 4C).
                    IconButton(onClick = viewModel::openSystemPromptDialog) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.chat_system_prompt),
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
                        onRegenerate = viewModel::regenerate,
                        onEdit = { editingMessage = it },
                        thumbnailFileFor = viewModel::thumbnailFileFor,
                        modifier = Modifier.weight(1f),
                    )

                    // Subtle heuristic context estimate (Milestone 4D):
                    // "est." labeled, never an official provider count.
                    val plan by viewModel.contextPlan.collectAsStateWithLifecycle()
                    ContextEstimateLabel(plan = plan)

                    // Phase 9B: voice status line above the input row.
                    when (val voiceState = uiState.voiceState) {
                        is VoiceState.Listening -> Text(
                            text = stringResource(R.string.voice_listening),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )

                        is VoiceState.Error -> Text(
                            text = voiceState.message.asString(context),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.dismissVoiceError() }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )

                        VoiceState.Idle -> Unit
                    }

                    // Phase 9B: staged image attachments, above the input row.
                    if (uiState.pendingAttachments.isNotEmpty()) {
                        AttachmentStrip(
                            attachments = uiState.pendingAttachments,
                            thumbnailFileFor = viewModel::thumbnailFileFor,
                            onRemove = viewModel::removePendingAttachment,
                        )
                    }

                    InputRow(
                        input = uiState.input,
                        canSend = uiState.canSend,
                        generating = uiState.generating,
                        voiceState = uiState.voiceState,
                        onInputChange = viewModel::updateInput,
                        onSend = { viewModel.send() },
                        onStop = viewModel::stop,
                        onGalleryClick = { galleryLauncher.launch("image/*") },
                        onCameraClick = ::onCameraClick,
                        onMicClick = ::onMicClick,
                    )
                }
            }
        }
    }

    if (uiState.systemPromptDialogOpen) {
        SystemPromptDialog(
            prompts = uiState.prompts,
            currentSystemPrompt = uiState.systemPrompt,
            onDismiss = viewModel::closeSystemPromptDialog,
            onSave = viewModel::setSystemPrompt,
        )
    }

    val messageToEdit = editingMessage
    if (messageToEdit != null) {
        EditMessageDialog(
            initialText = messageToEdit.text,
            onDismiss = { editingMessage = null },
            onSave = { newText ->
                viewModel.editAndResend(messageToEdit, newText)
                editingMessage = null
            },
        )
    }

    // --- Phase 9B: permission rationale / permanently-denied notices. ---
    if (audioRationale) {
        PermissionNoticeDialog(
            body = stringResource(R.string.voice_permission_rationale),
            allowLabel = stringResource(R.string.voice_allow),
            onAllow = {
                audioRationale = false
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { audioRationale = false },
        )
    }
    if (audioDenied) {
        PermissionNoticeDialog(
            body = stringResource(R.string.voice_permission_denied),
            allowLabel = null,
            onAllow = {},
            onDismiss = { audioDenied = false },
        )
    }
    if (cameraRationale) {
        PermissionNoticeDialog(
            body = stringResource(R.string.camera_permission_rationale),
            allowLabel = stringResource(R.string.attach_allow),
            onAllow = {
                cameraRationale = false
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onDismiss = { cameraRationale = false },
        )
    }
    if (cameraDenied) {
        PermissionNoticeDialog(
            body = stringResource(R.string.camera_permission_denied),
            allowLabel = null,
            onAllow = {},
            onDismiss = { cameraDenied = false },
        )
    }
}

/**
 * Small permission dialog (Phase 9B). With [allowLabel] it is a
 * rationale: Allow re-triggers the system request, Cancel drops it.
 * Without it, it is a permanently-denied notice pointing at Settings
 * with a single Dismiss button (no auto-open, per scope).
 */
@Composable
private fun PermissionNoticeDialog(
    body: String,
    allowLabel: String?,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(body) },
        confirmButton = {
            if (allowLabel != null) {
                TextButton(onClick = onAllow) { Text(allowLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (allowLabel != null) {
                            R.string.conversations_cancel
                        } else {
                            R.string.chat_dismiss_error
                        },
                    ),
                )
            }
        },
    )
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
    onRegenerate: (ChatUiMessage) -> Unit,
    onEdit: (ChatUiMessage) -> Unit,
    thumbnailFileFor: (ImageAttachment) -> File,
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
                isLastMessage = index == messages.lastIndex,
                actionsEnabled = !generating,
                onRegenerate = { onRegenerate(message) },
                onEdit = { onEdit(message) },
                thumbnailFileFor = thumbnailFileFor,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    showGenerating: Boolean,
    isLastMessage: Boolean,
    actionsEnabled: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    thumbnailFileFor: (ImageAttachment) -> File,
) {
    val isUser = message.role == ChatRole.USER
    val clipboardManager = LocalClipboardManager.current
    var menuExpanded by remember { mutableStateOf(false) }
    // Phase 9B: attachment opened in the full-screen viewer, or null.
    var viewerAttachment by remember { mutableStateOf<ImageAttachment?>(null) }
    // Long-press actions, mirroring flagship chat apps. Regenerate is
    // only offered on the trailing assistant turn (the only one it can
    // meaningfully replace); edit only on user turns. While a stream
    // is running only Copy stays available.
    val showCopy = message.text.isNotBlank()
    val showRegenerate = !isUser && isLastMessage && actionsEnabled
    val showEdit = isUser && actionsEnabled
    val hasActions = showCopy || showRegenerate || showEdit
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClickLabel = stringResource(R.string.chat_message_actions),
                    onLongClick = { if (hasActions) menuExpanded = true },
                ),
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
                    // Phase 9B: image attachments render as a thumbnail
                    // row above the text; tap opens the full-screen
                    // viewer (no pinch-zoom, per scope).
                    if (message.attachments.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(
                                bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp,
                            ),
                        ) {
                            message.attachments.forEach { attachment ->
                                val bitmap = rememberAttachmentBitmap(
                                    thumbnailFileFor(attachment),
                                )
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = stringResource(
                                            R.string.attach_thumbnail,
                                        ),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { viewerAttachment = attachment },
                                    )
                                }
                            }
                        }
                    }
                    if (message.text.isNotEmpty()) {
                        if (isUser) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            // Read the ambient (platform) handler OUTSIDE the
                            // provider override below: AssistantMarkdownText
                            // replaces LocalUriHandler internally with the
                            // safe variant, which calls back here only after
                            // the URL passed LinkSafety.
                            val platformUriHandler = LocalUriHandler.current
                            AssistantMarkdownText(
                                text = message.text,
                                onLinkClick = { url -> platformUriHandler.openUri(url) },
                            )
                        }
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
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (showCopy) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_copy)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                        menuExpanded = false
                    },
                )
            }
            if (showRegenerate) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_regenerate)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onRegenerate()
                        menuExpanded = false
                    },
                )
            }
            if (showEdit) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_edit)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onEdit()
                        menuExpanded = false
                    },
                )
            }
        }
        // Phase 9B: full-screen attachment viewer.
        val openAttachment = viewerAttachment
        if (openAttachment != null) {
            ImageViewerDialog(
                attachment = openAttachment,
                thumbnailFileFor = thumbnailFileFor,
                onDismiss = { viewerAttachment = null },
            )
        }
    }
}

/**
 * Decodes [file] into an [ImageBitmap], remembered by path. Returns
 * null when the file cannot be decoded (missing/corrupt) — callers
 * render nothing rather than crashing.
 *
 * Runs on the calling thread; attachment files are downscaled JPEGs
 * (ImageProcessor caps at 1568px), so main-thread decode stays cheap.
 */
@Composable
private fun rememberAttachmentBitmap(file: File): ImageBitmap? {
    return remember(file.absolutePath) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
            .getOrNull()
    }
}

/**
 * Horizontal strip of staged attachment thumbnails above the input row
 * (Phase 9B). Each tile carries a remove (X) button.
 */
@Composable
private fun AttachmentStrip(
    attachments: List<ImageAttachment>,
    thumbnailFileFor: (ImageAttachment) -> File,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Box(modifier = Modifier.size(72.dp)) {
                val bitmap = rememberAttachmentBitmap(thumbnailFileFor(attachment))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.attach_thumbnail),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                IconButton(
                    onClick = { onRemove(attachment.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.attach_remove),
                        tint = Color.White,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Full-screen image viewer (Phase 9B). Minimal by scope: the image
 * fitted on black, X or back dismisses — no pinch-zoom.
 */
@Composable
private fun ImageViewerDialog(
    attachment: ImageAttachment,
    thumbnailFileFor: (ImageAttachment) -> File,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val bitmap = rememberAttachmentBitmap(thumbnailFileFor(attachment))
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.attach_thumbnail),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.attach_close),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Dialog for editing a user message. Saving truncates the message and
 * everything after it, then resends the new text (see
 * [ChatViewModel.editAndResend]).
 */
@Composable
private fun EditMessageDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_message_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.conversations_cancel))
            }
        },
    )
}

@Composable
private fun InputRow(
    input: String,
    canSend: Boolean,
    generating: Boolean,
    voiceState: VoiceState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onGalleryClick: () -> Unit,
    onCameraClick: () -> Unit,
    onMicClick: () -> Unit,
) {
    var attachMenuExpanded by remember { mutableStateOf(false) }
    val listening = voiceState is VoiceState.Listening
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Phase 9B: image attachment entry point (gallery / camera chooser).
        Box {
            IconButton(
                onClick = { attachMenuExpanded = true },
                enabled = !generating,
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = stringResource(R.string.attach_add),
                )
            }
            DropdownMenu(
                expanded = attachMenuExpanded,
                onDismissRequest = { attachMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.attach_gallery)) },
                    onClick = {
                        attachMenuExpanded = false
                        onGalleryClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.attach_camera)) },
                    onClick = {
                        attachMenuExpanded = false
                        onCameraClick()
                    },
                )
            }
        }
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
        // Phase 9B: voice input. While listening the mic becomes Stop.
        IconButton(onClick = onMicClick, enabled = !generating) {
            Icon(
                imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = stringResource(
                    if (listening) R.string.voice_stop else R.string.voice_start,
                ),
                tint = if (listening) {
                    MaterialTheme.colorScheme.error
                } else {
                    LocalContentColor.current
                },
            )
        }
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
