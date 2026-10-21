package com.byokai.ui.settings

import com.byokai.R
import com.byokai.core.common.UiText
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel] using [FakeSettingsRepository].
 * `viewModelScope` is redirected to a [StandardTestDispatcher] so the
 * tests are deterministic on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects repository values and clears loading`() =
        runTest(testDispatcher) {
            val viewModel = SettingsViewModel(
                FakeSettingsRepository(
                    themeMode = ThemeMode.DARK,
                    dynamicColor = false,
                    language = AppLanguage.INDONESIAN,
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(ThemeMode.DARK, state.themeMode)
            assertEquals(false, state.dynamicColor)
            assertEquals(AppLanguage.INDONESIAN, state.language)
            assertFalse(state.isLoading)
        }

    @Test
    fun `setThemeMode updates uiState`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(FakeSettingsRepository())
        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.DARK).join()
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `setDynamicColor updates uiState`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(FakeSettingsRepository())
        advanceUntilIdle()

        viewModel.setDynamicColor(false).join()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.dynamicColor)
    }

    @Test
    fun `setLanguage updates uiState`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(FakeSettingsRepository())
        advanceUntilIdle()

        viewModel.setLanguage(AppLanguage.INDONESIAN).join()
        advanceUntilIdle()

        assertEquals(AppLanguage.INDONESIAN, viewModel.uiState.value.language)
    }

    @Test
    fun `write failure is forwarded to the error channel`() =
        runTest(testDispatcher) {
            val viewModel = SettingsViewModel(
                FakeSettingsRepository().apply { failWrites = true },
            )
            advanceUntilIdle()

            viewModel.setThemeMode(ThemeMode.LIGHT).join()

            assertEquals(
                UiText.Res(R.string.settings_save_error),
                viewModel.errors.first(),
            )
        }

    @Test
    fun `successful write emits no error`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(FakeSettingsRepository())
        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.LIGHT).join()
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
        assertNull(withTimeoutOrNull(100) { viewModel.errors.first() })
    }
}
