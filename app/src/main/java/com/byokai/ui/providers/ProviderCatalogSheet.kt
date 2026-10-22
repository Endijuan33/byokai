package com.byokai.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byokai.R
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.CatalogEntry

/**
 * Searchable provider-catalog picker.
 *
 * The search field filters the bundled catalog by name
 * (case-insensitive). Tapping an entry opens the provider editor
 * prefilled from that entry; the "Custom provider" section covers
 * endpoints that are not listed, with an explicit note that the API key
 * will be sent to the chosen endpoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderCatalogSheet(
    query: String,
    entries: List<CatalogEntry>,
    onQueryChange: (String) -> Unit,
    onSelectEntry: (CatalogEntry) -> Unit,
    onSelectCustom: (ProviderId) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.providers_catalog_title),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.providers_catalog_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    CatalogEntryRow(
                        entry = entry,
                        onClick = { onSelectEntry(entry) },
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.providers_catalog_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.providers_catalog_custom_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.providers_catalog_custom_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onSelectCustom(ProviderId.OPENAI_COMPATIBLE) },
                ) {
                    Text(stringResource(R.string.providers_catalog_openai_compatible))
                }
                OutlinedButton(
                    onClick = { onSelectCustom(ProviderId.ANTHROPIC_COMPATIBLE) },
                ) {
                    Text(stringResource(R.string.providers_catalog_anthropic_compatible))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * One catalog row: provider name, base URL, and the suggested-model
 * count (labeled as suggestions, never as verified).
 */
@Composable
private fun CatalogEntryRow(
    entry: CatalogEntry,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.defaultBaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.suggestedModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.providers_catalog_suggested,
                            entry.suggestedModels.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
