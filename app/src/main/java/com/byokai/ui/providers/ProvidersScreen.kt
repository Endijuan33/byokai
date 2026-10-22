package com.byokai.ui.providers

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byokai.ByokaiApplication
import com.byokai.R
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.AiProvider
import com.byokai.domain.provider.CatalogEntry

/**
 * Provider configuration list with an editor bottom sheet.
 *
 * - Each row shows the display name, adapter family name, base URL, a
 *   key-status badge ("key saved" / "no key" — the key value is never
 *   shown), and the model count. Tap edits; the trash icon asks for
 *   confirmation before deleting the config AND its stored key.
 * - The editor never displays a saved key's value: the key field shows a
 *   placeholder only, and blank keeps the existing key.
 * - Connection tests and model refreshes are explicit buttons — nothing
 *   here probes the network on open.
 * - Non-loopback cleartext HTTP is blocked by validation at save time;
 *   a loopback `http://` URL additionally shows a warning in the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as ByokaiApplication
    val container = application.appContainer
    val viewModel: ProvidersViewModel = viewModel(
        factory = ProvidersViewModelFactory(
            container.providerConfigRepository,
            container.credentialStore,
            container.providerRegistry,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { uiText ->
            snackbarHostState.showSnackbar(uiText.asString(context))
        }
    }

    val pendingDelete = uiState.pendingDeleteId?.let { id ->
        uiState.configs.firstOrNull { it.id == id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_title)) },
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
            FloatingActionButton(onClick = { viewModel.openCatalogPicker() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.providers_add),
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

            uiState.configs.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.providers_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.providers_empty_body),
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
                items(uiState.configs, key = { it.id }) { config ->
                    ProviderRow(
                        config = config,
                        adapterName = uiState.providers
                            .firstOrNull { it.providerId == config.providerId }
                            ?.displayName ?: config.providerId.name,
                        hasKey = uiState.hasKey[config.id] == true,
                        testState = uiState.testResult[config.id] ?: TestState.Idle,
                        onEdit = { viewModel.edit(config) },
                        onDelete = { viewModel.requestDelete(config.id) },
                    )
                }
            }
        }
    }

    val editing = uiState.editing
    if (uiState.catalogOpen) {
        ProviderCatalogSheet(
            query = uiState.catalogQuery,
            entries = viewModel.filteredCatalog(),
            onQueryChange = viewModel::updateCatalogQuery,
            onSelectEntry = viewModel::selectCatalogEntry,
            onSelectCustom = viewModel::selectCustomProvider,
            onDismiss = viewModel::closeCatalogPicker,
        )
    }
    if (editing != null) {
        ProviderEditorSheet(
            draft = editing,
            adapters = uiState.providers,
            testState = uiState.testResult[editing.id] ?: TestState.Idle,
            onDraftChange = viewModel::updateDraft,
            onProviderFamilySelected = viewModel::selectProviderFamily,
            onDismiss = viewModel::cancelEdit,
            onSave = { draft, apiKey -> viewModel.saveDraft(draft, apiKey) },
            onTest = { draft, apiKey -> viewModel.testDraft(draft, apiKey) },
            onRefresh = { viewModel.refreshModels(editing.id) },
        )
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.providers_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.providers_delete_body, pendingDelete.displayName),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteConfig(pendingDelete.id) }) {
                    Text(
                        stringResource(R.string.providers_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.providers_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderRow(
    config: ProviderConfig,
    adapterName: String,
    hasKey: Boolean,
    testState: TestState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                KeyBadge(hasKey = hasKey)
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.providers_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = adapterName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = config.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.providers_models_count,
                        config.modelIds.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (testState is TestState.Ok) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.providers_test_ok),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.providers_test_ok),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (testState is TestState.Failed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Key-status pill. Shows only WHETHER a key exists — never the value.
 */
@Composable
private fun KeyBadge(hasKey: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (hasKey) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Text(
            text = stringResource(
                if (hasKey) R.string.providers_key_saved else R.string.providers_no_key,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (hasKey) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorSheet(
    draft: ProviderConfigDraft,
    adapters: List<AiProvider>,
    testState: TestState,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    onProviderFamilySelected: (AiProvider) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ProviderConfigDraft, String) -> Unit,
    onTest: (ProviderConfigDraft, String) -> Unit,
    onRefresh: () -> Unit,
) {
    // Local to the sheet: never stored in the ViewModel, cleared on close.
    var apiKey by remember(draft.id) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val adapter = adapters.firstOrNull { it.providerId == draft.providerId }
    val canRefresh = adapter?.capabilities?.modelListing == true && !draft.isNew
    val nonBlankModels = draft.modelIds.map { it.trim() }.filter { it.isNotEmpty() }
    val canSave = draft.displayName.isNotBlank() &&
        draft.baseUrl.isNotBlank() &&
        nonBlankModels.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (draft.isNew) R.string.providers_add else R.string.providers_edit_title,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            ProviderFamilyDropdown(
                adapters = adapters,
                selected = adapter,
                onSelect = onProviderFamilySelected,
            )

            OutlinedTextField(
                value = draft.displayName,
                onValueChange = { onDraftChange(draft.copy(displayName = it)) },
                label = { Text(stringResource(R.string.providers_field_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.providers_field_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.baseUrl.trim().lowercase().startsWith("http://")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.providers_http_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.providers_field_api_key)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (draft.hasSavedKey) {
                                R.string.providers_api_key_hint_existing
                            } else {
                                R.string.providers_api_key_hint_new
                            },
                        ),
                    )
                },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                ),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = stringResource(R.string.providers_api_key_toggle),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.hasSavedKey) {
                Text(
                    text = stringResource(R.string.providers_api_key_keep_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.providers_field_models),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            draft.modelIds.forEachIndexed { index, modelId ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { value ->
                            onDraftChange(
                                draft.copy(
                                    modelIds = draft.modelIds.toMutableList()
                                        .also { it[index] = value },
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.providers_model_id_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            onDraftChange(
                                draft.copy(
                                    modelIds = draft.modelIds.filterIndexed { i, _ -> i != index },
                                    defaultModelId = draft.defaultModelId
                                        ?.takeIf { it != draft.modelIds.getOrNull(index)?.trim() },
                                ),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.providers_remove_model),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { onDraftChange(draft.copy(modelIds = draft.modelIds + "")) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.providers_add_model))
            }

            val distinctModels = nonBlankModels.distinct()
            DefaultModelDropdown(
                options = distinctModels,
                selected = draft.defaultModelId?.takeIf { it in distinctModels },
                onSelect = { onDraftChange(draft.copy(defaultModelId = it)) },
            )

            TestStatusRow(testState = testState)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onTest(draft, apiKey) },
                    enabled = testState != TestState.Running,
                ) {
                    if (testState == TestState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.providers_test_connection))
                }
                if (canRefresh) {
                    OutlinedButton(onClick = onRefresh) {
                        Text(stringResource(R.string.providers_refresh_models))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.providers_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onSave(draft, apiKey) },
                    enabled = canSave,
                ) {
                    Text(stringResource(R.string.providers_save))
                }
            }
        }
    }
}

@Composable
private fun TestStatusRow(testState: TestState) {
    val context = LocalContext.current
    when (testState) {
        is TestState.Idle -> Unit
        is TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.providers_test_running),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is TestState.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.providers_test_ok),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is TestState.Failed -> Text(
            text = testState.message.asString(context),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderFamilyDropdown(
    adapters: List<AiProvider>,
    selected: AiProvider?,
    onSelect: (AiProvider) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.displayName.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.providers_field_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            adapters.forEach { adapter ->
                DropdownMenuItem(
                    text = { Text(adapter.displayName) },
                    onClick = {
                        onSelect(adapter)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultModelDropdown(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = options.isNotEmpty() && it },
    ) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = options.isNotEmpty(),
            label = { Text(stringResource(R.string.providers_field_default_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
