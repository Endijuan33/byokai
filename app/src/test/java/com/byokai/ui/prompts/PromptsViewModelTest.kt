package com.byokai.ui.prompts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PromptsViewModel] with an in-memory
 * [FakePromptRepository].
 *
 * `viewModelScope` is redirected to a [StandardTestDispatcher] so the
 * tests are deterministic on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        repo: FakePromptRepository = FakePromptRepository(),
    ): PromptsViewModel {
        val vm = PromptsViewModel(repo)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `init seeds builtins when table is empty`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        val vm = viewModel(repo)

        assertEquals(1, repo.seedCount)
        assertEquals(4, vm.uiState.value.prompts.size)
        assertTrue(vm.uiState.value.prompts.all { it.isBuiltin })
    }

    @Test
    fun `init does not reseed when prompts exist`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        repo.create("Mine", "custom content")
        val vm = viewModel(repo)

        assertEquals(0, repo.seedCount)
        assertEquals(1, vm.uiState.value.prompts.size)
    }

    @Test
    fun `create adds a user prompt`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        val vm = viewModel(repo)
        vm.openEditor(null)
        vm.onEditorTitleChange("My prompt")
        vm.onEditorContentChange("Do the thing.")
        vm.saveEditor()
        advanceUntilIdle()

        val prompts = vm.uiState.value.prompts
        assertEquals(5, prompts.size)
        val created = prompts.first { !it.isBuiltin }
        assertEquals("My prompt", created.title)
        assertEquals("Do the thing.", created.content)
        assertNull(vm.uiState.value.editor)
    }

    @Test
    fun `save with blank fields keeps editor open`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        val vm = viewModel(repo)
        vm.openEditor(null)
        vm.onEditorTitleChange("   ")
        vm.onEditorContentChange("content")
        vm.saveEditor()
        advanceUntilIdle()

        assertEquals(0, repo.createCount)
        assertNotNull(vm.uiState.value.editor)
    }

    @Test
    fun `edit updates title and content`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        val vm = viewModel(repo)
        val builtin = vm.uiState.value.prompts.first()
        vm.openEditor(builtin)
        vm.onEditorTitleChange("Renamed")
        vm.onEditorContentChange("New content.")
        vm.saveEditor()
        advanceUntilIdle()

        val updated = vm.uiState.value.prompts.first { it.id == builtin.id }
        assertEquals("Renamed", updated.title)
        assertEquals("New content.", updated.content)
    }

    @Test
    fun `delete removes the prompt after confirmation`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        val vm = viewModel(repo)
        val target = vm.uiState.value.prompts.first()

        vm.requestDelete(target.id)
        assertEquals(target.id, vm.uiState.value.pendingDeleteId)
        // Not deleted yet — confirmation required.
        assertEquals(4, vm.uiState.value.prompts.size)

        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(1, repo.deleteCount)
        assertEquals(3, vm.uiState.value.prompts.size)
        assertNull(vm.uiState.value.pendingDeleteId)
    }

    @Test
    fun `cancel delete keeps the prompt`() = runTest(testDispatcher) {
        val repo = FakePromptRepository()
        val vm = viewModel(repo)
        val target = vm.uiState.value.prompts.first()

        vm.requestDelete(target.id)
        vm.cancelDelete()
        advanceUntilIdle()

        assertEquals(0, repo.deleteCount)
        assertEquals(4, vm.uiState.value.prompts.size)
    }
}
