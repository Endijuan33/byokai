package com.byokai.ui.conversations

import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.model.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConversationsViewModel] with an in-memory
 * [FakeConversationRepository].
 *
 * `viewModelScope` is redirected to a [StandardTestDispatcher] so the
 * tests are deterministic on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

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
        repo: FakeConversationRepository = FakeConversationRepository(),
    ): ConversationsViewModel {
        val vm = ConversationsViewModel(repo)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `init collects conversations most-recent first`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        repo.create("First")
        repo.create("Second")
        val vm = viewModel(repo)

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(
            listOf("Second", "First"),
            vm.uiState.value.conversations.map { it.title },
        )
    }

    @Test
    fun `create emits the new id for navigation`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val vm = viewModel(repo)

        vm.createConversation("New conversation")
        advanceUntilIdle()

        // The channel is BUFFERED so trySend cannot lose the event; first()
        // picks it up deterministically without a background collector.
        val openedId = vm.openConversation.first()
        assertEquals("New conversation", repo.get(openedId)?.title)
        // The list flow picks the new row up too.
        assertTrue(vm.uiState.value.conversations.any { it.id == openedId })
    }

    @Test
    fun `create failure surfaces an error`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository().also { it.failWrites = true }
        val vm = viewModel(repo)

        vm.createConversation("New conversation")
        advanceUntilIdle()

        assertEquals(
            UiText.Res(R.string.conversations_create_error),
            vm.errors.first(),
        )
    }

    @Test
    fun `delete confirm flow removes the conversation`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val id = (repo.create("Doomed") as Result.Success).data.id
        val vm = viewModel(repo)
        assertEquals(1, vm.uiState.value.conversations.size)

        vm.requestDelete(id)
        assertEquals(id, vm.uiState.value.pendingDeleteId)

        // Cancel keeps the conversation.
        vm.cancelDelete()
        assertNull(vm.uiState.value.pendingDeleteId)
        assertNotNull(repo.get(id))

        // Confirm deletes it (messages cascade in the real repo).
        vm.requestDelete(id)
        vm.confirmDelete()
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingDeleteId)
        assertNull(repo.get(id))
        assertTrue(vm.uiState.value.conversations.isEmpty())
    }

    @Test
    fun `delete failure surfaces an error and closes the dialog`() =
        runTest(testDispatcher) {
            val repo = FakeConversationRepository()
            val id = (repo.create("Doomed") as Result.Success).data.id
            val vm = viewModel(repo)

            repo.failWrites = true
            vm.requestDelete(id)
            vm.confirmDelete()
            advanceUntilIdle()

            assertNull(vm.uiState.value.pendingDeleteId)
            assertEquals(
                UiText.Res(R.string.conversations_delete_error),
                vm.errors.first(),
            )
            assertNotNull(repo.get(id))
        }

    @Test
    fun `confirmDelete without a pending id is a no-op`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val vm = viewModel(repo)

        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(0, repo.deleteCount)
    }

    @Test
    fun `search filters by title case-insensitively`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        repo.create("Shopping list")
        repo.create("Work notes")
        val vm = viewModel(repo)

        vm.updateSearchQuery("SHOP")
        advanceUntilIdle()

        assertEquals("SHOP", vm.uiState.value.searchQuery)
        assertEquals(
            listOf("Shopping list"),
            vm.uiState.value.conversations.map { it.title },
        )
    }

    @Test
    fun `search matches message content`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val id = (repo.create("Ideas") as Result.Success).data.id
        repo.appendMessage(id, ChatRole.USER, "buy milk tomorrow", false)
        repo.create("Other")
        val vm = viewModel(repo)

        vm.updateSearchQuery("milk")
        advanceUntilIdle()

        assertEquals(
            listOf("Ideas"),
            vm.uiState.value.conversations.map { it.title },
        )
    }

    @Test
    fun `blank search shows all conversations`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        repo.create("Alpha")
        repo.create("Beta")
        val vm = viewModel(repo)

        vm.updateSearchQuery("no-such-thing")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.conversations.isEmpty())

        vm.updateSearchQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.conversations.size)
    }

    @Test
    fun `rename confirm flow updates the title`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val id = (repo.create("Old") as Result.Success).data.id
        val vm = viewModel(repo)

        vm.requestRename(id)
        assertEquals(id, vm.uiState.value.pendingRenameId)

        // Cancel keeps the title.
        vm.cancelRename()
        assertNull(vm.uiState.value.pendingRenameId)
        assertEquals("Old", repo.get(id)?.title)

        // Confirm renames it.
        vm.requestRename(id)
        vm.confirmRename("New title")
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingRenameId)
        assertEquals("New title", repo.get(id)?.title)
        assertEquals("New title", vm.uiState.value.conversations.first().title)
    }

    @Test
    fun `rename with blank title keeps the dialog open`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val id = (repo.create("Old") as Result.Success).data.id
        val vm = viewModel(repo)

        vm.requestRename(id)
        vm.confirmRename("   ")
        advanceUntilIdle()

        assertEquals(id, vm.uiState.value.pendingRenameId)
        assertEquals("Old", repo.get(id)?.title)
    }

    @Test
    fun `rename failure surfaces an error and closes the dialog`() =
        runTest(testDispatcher) {
            val repo = FakeConversationRepository()
            val id = (repo.create("Old") as Result.Success).data.id
            val vm = viewModel(repo)

            repo.failWrites = true
            vm.requestRename(id)
            vm.confirmRename("New")
            advanceUntilIdle()

            assertNull(vm.uiState.value.pendingRenameId)
            assertEquals(
                UiText.Res(R.string.conversations_rename_error),
                vm.errors.first(),
            )
            assertEquals("Old", repo.get(id)?.title)
        }

    @Test
    fun `deleteMessagesFrom removes the target and later messages`() =
        runTest(testDispatcher) {
            val repo = FakeConversationRepository()
            val id = (repo.create("C") as Result.Success).data.id
            repo.appendMessage(id, ChatRole.USER, "u1", false)
            val target =
                (repo.appendMessage(id, ChatRole.ASSISTANT, "a1", false) as Result.Success).data
            repo.appendMessage(id, ChatRole.USER, "u2", false)

            repo.deleteMessagesFrom(id, target.id)

            assertEquals(
                listOf("u1"),
                repo.messages(id).first().map { it.content },
            )
        }

    @Test
    fun `deleteMessagesFrom with unknown id is a no-op`() = runTest(testDispatcher) {
        val repo = FakeConversationRepository()
        val id = (repo.create("C") as Result.Success).data.id
        repo.appendMessage(id, ChatRole.USER, "u1", false)

        repo.deleteMessagesFrom(id, "no-such-id")

        assertEquals(1, repo.messages(id).first().size)
    }
}
