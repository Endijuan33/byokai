package com.byokai.ui.prompts

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byokai.ByokaiApplication
import com.byokai.R
import com.byokai.core.common.UiText
import com.byokai.domain.model.PromptTemplate
import kotlinx.coroutines.launch

/**
 * Prompt template management screen (Milestone 4C).
 *
 * - The list shows every saved template: builtins first, then user
 *   prompts by recency. Rows show the title, a "built-in" chip for
 *   seeded templates, and a content preview.
 * - The FAB opens the editor dialog for a new prompt; the edit icon
 *   opens it pre-filled. Blank title/content is rejected with a
 *   snackbar and the dialog stays open.
 * - The trash icon asks for delete confirmation first. Builtins can be
 *   deleted too — deletion is permanent, and seeding never resurrects
 *   them (the seed runs only on an empty table).
 * - Honest empty state: no fake prompts are rendered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as ByokaiApplication
    val viewModel: PromptsViewModel = viewModel(
        factory = PromptsViewModelFactory(
            application.appContainer.promptRepository,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        launch {
            viewModel.errors.collect { uiText: UiText ->
                snackbarHostState.showSnackbar(uiText.asString(context))
            }
        }
    }

    val pendingDelete = uiState.pendingDeleteId?.let { id ->
        uiState.prompts.firstOrNull { it.id == id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prompts_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openEditor(null) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.prompts_add),
                )
            }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            uiState.prompts.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.prompts_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.prompts_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.prompts, key = { it.id }) { prompt ->
                    PromptRow(
                        prompt = prompt,
                        onEdit = { viewModel.openEditor(prompt) },
                        onDelete = { viewModel.requestDelete(prompt.id) },
                    )
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.prompts_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.prompts_delete_body,
                        pendingDelete.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(
                        stringResource(R.string.prompts_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.prompts_cancel))
                }
            },
        )
    }

    val editor = uiState.editor
    if (editor != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeEditor,
            title = {
                Text(
                    stringResource(
                        if (editor.promptId == null) R.string.prompts_create_title
                        else R.string.prompts_edit_title,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editor.title,
                        onValueChange = viewModel::onEditorTitleChange,
                        label = { Text(stringResource(R.string.prompts_editor_title_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.content,
                        onValueChange = viewModel::onEditorContentChange,
                        label = { Text(stringResource(R.string.prompts_editor_content_label)) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveEditor) {
                    Text(stringResource(R.string.prompts_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeEditor) {
                    Text(stringResource(R.string.prompts_cancel))
                }
            },
        )
    }
}

@Composable
private fun PromptRow(
    prompt: PromptTemplate,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = prompt.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (prompt.isBuiltin) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.prompts_builtin_chip)) },
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.prompts_edit),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.prompts_delete),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prompt.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
