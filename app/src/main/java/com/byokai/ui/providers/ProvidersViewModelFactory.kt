package com.byokai.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.byokai.domain.provider.ProviderRegistry
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore

/**
 * Manual [ViewModelProvider.Factory] for [ProvidersViewModel].
 *
 * Interim until Hilt returns (see docs/ARCHITECTURE.md); replaced by
 * `@HiltViewModel` injection when KSP supports Kotlin 2.4.x.
 */
class ProvidersViewModelFactory(
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
    private val providerRegistry: ProviderRegistry,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ProvidersViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ProvidersViewModel(configRepository, credentialStore, providerRegistry) as T
    }
}
