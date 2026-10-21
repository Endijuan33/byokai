package com.byokai.ui.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.byokai.domain.repository.PromptRepository

/**
 * Manual [ViewModelProvider.Factory] for [PromptsViewModel].
 *
 * Interim until Hilt returns (see docs/ARCHITECTURE.md); replaced by
 * `@HiltViewModel` injection when KSP supports Kotlin 2.4.x.
 */
class PromptsViewModelFactory(
    private val promptRepository: PromptRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PromptsViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return PromptsViewModel(promptRepository) as T
    }
}
