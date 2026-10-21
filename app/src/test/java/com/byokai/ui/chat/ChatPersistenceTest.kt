package com.byokai.ui.chat

import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.ui.conversations.FakeConversationRepository
import com.byokai.ui.providers.FakeCredentialStore
import com.byokai.ui.providers.InMemoryConfigRepo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence-integration tests for [ChatViewModel] with an in-memory
 * [FakeConversationRepository].
 *
 * The real `SqliteConversationRepository` is Android-only and cannot be
 * instantiated on the JVM; the mapping and write-timing logic below is
 * what these tests pin down, while the SQL itself is verified on-device
 * / by CI instrumentation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPersistenceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun config(id: String = "cfg-1") = ProviderConfig(
        id = id,
        providerId = ProviderId.OPENAI,
        displayName = "Test",
        baseUrl = "https://api.openai.com",
        modelIds = listOf("m-1"),
        defaultModelId = "m-1",
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** ViewModel with one keyed config and a fake conversation repo; settled past init. */
    private suspend fun TestScope.readyViewModel(
        chatRepo: FakeChatRepository = FakeChatRepository(),
        conversations: FakeConversationRepository = FakeConversationRepository(),
    ): ChatViewModel {
        val repo = InMemoryConfigRepo(listOf(config()))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-key")
        val vm = ChatViewModel(chatRepo, repo, store, conversations)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `newConversation creates a row with the default title`() =
        runTest(testDispatcher) {
            val conversations = FakeConversationRepository()
            val vm = readyViewModel(conversations = conversations)

            vm.newConversation("New conversation")
            advanceUntilIdle()

            assertEquals(1, conversations.createCount)
            val id = vm.uiState.value.conversationId
            assertNotNull(id)
            assertEquals("New conversation", vm.uiState.value.conversationTitle)
            assertEquals("New conversation", conversations.get(id!!)?.title)
        }

    @Test
    fun `send persists user turn, renames from first message, persists Done`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(
                events = listOf(ChatEvent.Done("Hello world")),
            )
            val conversations = FakeConversationRepository()
            val vm = readyViewModel(chatRepo, conversations)
            vm.newConversation("New conversation")
            advanceUntilIdle()
            val id = vm.uiState.value.conversationId!!

            vm.updateInput("Hi there, this is a longer first message for the title")
            vm.send()!!.join()
            advanceUntilIdle()

            val stored = conversations.appendedMessages
            assertEquals(2, stored.size)
            assertEquals(ChatRole.USER, stored[0].role)
            assertEquals(
                "Hi there, this is a longer first message for the title",
                stored[0].content,
            )
            assertFalse(stored[0].isPartial)
            assertEquals(ChatRole.ASSISTANT, stored[1].role)
            assertEquals("Hello world", stored[1].content)
            assertFalse(stored[1].isPartial)

            // Title renamed to the first 40 chars, once.
            assertEquals(
                "Hi there, this is a longer first message",
                vm.uiState.value.conversationTitle,
            )
            assertEquals(1, conversations.renameCalls.size)
            assertEquals(id to "Hi there, this is a longer first message", conversations.renameCalls[0])
            assertEquals(
                "Hi there, this is a longer first message",
                conversations.get(id)?.title,
            )

            // A second send does not rename again.
            vm.updateInput("second question")
            vm.send()!!.join()
            advanceUntilIdle()
            assertEquals(1, conversations.renameCalls.size)
            assertEquals(4, conversations.appendedMessages.size)
        }

    @Test
    fun `Error event persists the partial assistant message as partial`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(
                events = listOf(
                    ChatEvent.Delta("partial"),
                    ChatEvent.Error(ProviderError.Timeout),
                ),
            )
            val conversations = FakeConversationRepository()
            val vm = readyViewModel(chatRepo, conversations)
            vm.newConversation("New conversation")
            advanceUntilIdle()

            vm.updateInput("Hi")
            vm.send()!!.join()
            advanceUntilIdle()

            val stored = conversations.appendedMessages
            assertEquals(2, stored.size)
            assertEquals(ChatRole.USER, stored[0].role)
            val partial = stored[1]
            assertEquals(ChatRole.ASSISTANT, partial.role)
            assertEquals("partial", partial.content)
            assertTrue(partial.isPartial)

            // The UI still shows it pending for retry.
            assertTrue(vm.uiState.value.messages[1].isPending)
        }

    @Test
    fun `stop persists the partial text`() = runTest(testDispatcher) {
        // No terminal event: the stream "hangs" after one delta, so stop()
        // is what persists the partial.
        val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Delta("hel")))
        val conversations = FakeConversationRepository()
        val vm = readyViewModel(chatRepo, conversations)
        vm.newConversation("New conversation")
        advanceUntilIdle()

        vm.updateInput("Hi")
        vm.send()
        advanceUntilIdle()

        vm.stop()
        advanceUntilIdle()

        val partials = conversations.appendedMessages
            .filter { it.role == ChatRole.ASSISTANT }
        assertEquals(1, partials.size)
        assertEquals("hel", partials[0].content)
        assertTrue(partials[0].isPartial)
    }

    @Test
    fun `failed user persistence aborts the send`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("x")))
        val conversations = FakeConversationRepository()
        val vm = readyViewModel(chatRepo, conversations)
        vm.newConversation("New conversation")
        advanceUntilIdle()

        conversations.failWrites = true
        vm.updateInput("Hi")
        vm.send()!!.join()
        advanceUntilIdle()

        // No stream started, nothing shown that is not stored.
        assertEquals(0, chatRepo.callCount)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals(
            UiText.Res(R.string.conversations_save_error),
            vm.uiState.value.error,
        )
    }

    @Test
    fun `loadConversation populates history and does not rename`() =
        runTest(testDispatcher) {
            val conversations = FakeConversationRepository()
            val id = (conversations.create("Old title") as Result.Success).data.id
            conversations.appendMessage(id, ChatRole.USER, "past question", false)
            conversations.appendMessage(id, ChatRole.ASSISTANT, "past answer", false)

            val chatRepo = FakeChatRepository(
                events = listOf(ChatEvent.Done("new answer")),
            )
            val vm = readyViewModel(chatRepo, conversations)

            vm.loadConversation(id, "New conversation")
            advanceUntilIdle()

            val messages = vm.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals(ChatRole.USER, messages[0].role)
            assertEquals("past question", messages[0].text)
            assertFalse(messages[0].isPending)
            assertEquals("past answer", messages[1].text)
            assertEquals(id, vm.uiState.value.conversationId)
            assertEquals("Old title", vm.uiState.value.conversationTitle)

            // History already has a user message: no rename on the next send.
            vm.updateInput("follow-up")
            vm.send()!!.join()
            advanceUntilIdle()
            assertEquals(0, conversations.renameCalls.size)
            assertEquals("Old title", vm.uiState.value.conversationTitle)
        }

    @Test
    fun `loadConversation with unknown id shows not-found`() =
        runTest(testDispatcher) {
            val vm = readyViewModel()
            vm.loadConversation("nope", "New conversation")
            advanceUntilIdle()

            assertNull(vm.uiState.value.conversationId)
            assertEquals(
                UiText.Res(R.string.conversations_not_found),
                vm.uiState.value.error,
            )
        }

    @Test
    fun `discardIfEmpty deletes an untouched new conversation`() =
        runTest(testDispatcher) {
            val conversations = FakeConversationRepository()
            val vm = readyViewModel(conversations = conversations)
            vm.newConversation("New conversation")
            advanceUntilIdle()
            val id = vm.uiState.value.conversationId!!

            vm.discardIfEmpty()
            advanceUntilIdle()

            assertNull(conversations.get(id))
            assertNull(vm.uiState.value.conversationId)
        }

    @Test
    fun `discardIfEmpty keeps a conversation with messages`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(
                events = listOf(ChatEvent.Done("answer")),
            )
            val conversations = FakeConversationRepository()
            val vm = readyViewModel(chatRepo, conversations)
            vm.newConversation("New conversation")
            advanceUntilIdle()
            val id = vm.uiState.value.conversationId!!

            vm.updateInput("Hi")
            vm.send()!!.join()
            advanceUntilIdle()

            vm.discardIfEmpty()
            advanceUntilIdle()

            assertNotNull(conversations.get(id))
            assertEquals(0, conversations.deleteCount)
            assertEquals(id, vm.uiState.value.conversationId)
        }

    @Test
    fun `stop then send keeps database turn order`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Delta("partial")))
        val conversations = FakeConversationRepository()
        val vm = readyViewModel(chatRepo, conversations)
        vm.newConversation("New conversation")
        advanceUntilIdle()

        vm.updateInput("first")
        vm.send()!!.join()
        advanceUntilIdle()
        // The stream ended after one delta with no terminal event;
        // stop() persists the partial text.
        vm.stop()

        chatRepo.events = listOf(ChatEvent.Done("second reply"))
        vm.updateInput("second")
        vm.send()!!.join()
        advanceUntilIdle()

        val ordered = conversations.appendedMessages.map { it.role to it.content }
        assertEquals(
            listOf(
                ChatRole.USER to "first",
                ChatRole.ASSISTANT to "partial",
                ChatRole.USER to "second",
                ChatRole.ASSISTANT to "second reply",
            ),
            ordered,
        )
        // The persisted partial stays marked partial; the completed
        // turn does not.
        val partials = conversations.appendedMessages.filter { it.isPartial }
        assertEquals(1, partials.size)
        assertEquals("partial", partials.single().content)
    }
}
