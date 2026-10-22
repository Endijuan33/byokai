package com.byokai.ui.chat

import com.byokai.R
import com.byokai.core.common.UiText
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.ui.conversations.FakeConversationRepository
import com.byokai.ui.providers.FakeCredentialStore
import com.byokai.ui.providers.InMemoryConfigRepo
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
 * Unit tests for [ChatViewModel] using in-memory fakes.
 * `viewModelScope` is redirected to a [StandardTestDispatcher] so the
 * tests are deterministic on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

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
        modelIds = listOf("m-1", "m-2"),
        defaultModelId = "m-2",
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** ViewModel with one keyed config; settled past init. */
    private suspend fun TestScope.readyViewModel(
        chatRepo: FakeChatRepository = FakeChatRepository(),
        withKey: Boolean = true,
    ): ChatViewModel {
        val repo = InMemoryConfigRepo(listOf(config()))
        val store = FakeCredentialStore()
        if (withKey) store.save("cfg-1", "sk-key")
        val vm = ChatViewModel(chatRepo, repo, store)
        advanceUntilIdle()
        return vm
    }

    /**
     * ViewModel with a persisted conversation; returns the ViewModel,
     * the fake repository, and the conversation id.
     */
    private suspend fun TestScope.persistentViewModel(
        chatRepo: FakeChatRepository = FakeChatRepository(),
    ): Triple<ChatViewModel, FakeConversationRepository, String> {
        val repo = InMemoryConfigRepo(listOf(config()))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-key")
        val convRepo = FakeConversationRepository()
        val vm = ChatViewModel(chatRepo, repo, store, convRepo)
        advanceUntilIdle()
        vm.newConversation("Test")
        advanceUntilIdle()
        val convId = vm.uiState.value.conversationId!!
        return Triple(vm, convRepo, convId)
    }

    /** Sends [text] and settles; returns the persisted conversation id. */
    private suspend fun TestScope.sendText(vm: ChatViewModel, text: String) {
        vm.updateInput(text)
        vm.send()!!.join()
        advanceUntilIdle()
    }

    @Test
    fun `init auto-selects first keyed config with its default model`() =
        runTest(testDispatcher) {
            val vm = readyViewModel()
            assertEquals("cfg-1", vm.uiState.value.selectedConfigId)
            assertEquals("m-2", vm.uiState.value.selectedModelId)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `canSend requires key, model, and non-blank input`() = runTest(testDispatcher) {
        val vm = readyViewModel(withKey = false)
        vm.updateInput("hello")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSend)
        assertNull(vm.send())
    }

    @Test
    fun `send streams deltas and finalizes on Done`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(
            events = listOf(
                ChatEvent.Delta("Hello"),
                ChatEvent.Delta(" world"),
                ChatEvent.Done("Hello world"),
            ),
        )
        val vm = readyViewModel(chatRepo)

        vm.updateInput("Hi")
        assertTrue(vm.uiState.value.canSend)
        vm.send()!!.join()
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("Hi", messages[0].text)
        assertFalse(messages[0].isPending)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
        assertEquals("Hello world", messages[1].text)
        assertFalse(messages[1].isPending)
        assertFalse(vm.uiState.value.generating)
        assertEquals("", vm.uiState.value.input)

        val request = chatRepo.lastRequest
        assertNotNull(request)
        assertEquals("cfg-1", request!!.providerConfigId)
        assertEquals("m-2", request.modelId)
        assertEquals(listOf(ChatMessage(ChatRole.USER, "Hi")), request.messages)
    }

    @Test
    fun `second send while generating is ignored`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val vm = readyViewModel(chatRepo)

        vm.updateInput("one")
        val job = vm.send()
        assertNotNull(job)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.generating)

        vm.updateInput("two")
        assertNull(vm.send())
        assertEquals(1, chatRepo.callCount)
        assertEquals(2, vm.uiState.value.messages.size)

        vm.stop()
        assertFalse(vm.uiState.value.generating)
        job!!.join()
    }

    @Test
    fun `Error event sets banner and keeps partial text pending`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(
                events = listOf(
                    ChatEvent.Delta("partial"),
                    ChatEvent.Error(ProviderError.Auth("bad key")),
                ),
            )
            val vm = readyViewModel(chatRepo)

            vm.updateInput("Hi")
            vm.send()!!.join()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.generating)
            assertEquals(
                UiText.Res(R.string.error_provider_auth),
                vm.uiState.value.error,
            )
            val messages = vm.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals("partial", messages[1].text)
            assertTrue(messages[1].isPending)
        }

    @Test
    fun `stop cancels the stream and keeps partial text`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val vm = readyViewModel(chatRepo)

        vm.updateInput("Hi")
        val job = vm.send()!!
        advanceUntilIdle()
        assertTrue(vm.uiState.value.generating)

        vm.stop()
        job.join()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.generating)
        assertNull(vm.uiState.value.error)
        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertTrue(messages[1].isPending)
    }

    @Test
    fun `retry drops the failed assistant and resends`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(
            events = listOf(
                ChatEvent.Delta("partial"),
                ChatEvent.Error(ProviderError.Timeout),
            ),
        )
        val vm = readyViewModel(chatRepo)

        vm.updateInput("Hi")
        vm.send()!!.join()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)

        chatRepo.events = listOf(ChatEvent.Done("fresh answer"))
        vm.retry()!!.join()
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("Hi", messages[0].text)
        assertEquals("fresh answer", messages[1].text)
        assertFalse(messages[1].isPending)
        assertNull(vm.uiState.value.error)

        // The failed partial is not part of the resent history.
        assertEquals(
            listOf(ChatMessage(ChatRole.USER, "Hi")),
            chatRepo.lastRequest!!.messages,
        )
    }

    @Test
    fun `retry is a no-op while generating`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val vm = readyViewModel(chatRepo)

        vm.updateInput("Hi")
        val job = vm.send()!!
        advanceUntilIdle()

        assertNull(vm.retry())
        assertEquals(1, chatRepo.callCount)

        vm.stop()
        job.join()
    }

    @Test
    fun `selectConfig resets the model to the config default`() =
        runTest(testDispatcher) {
            val repo = InMemoryConfigRepo(
                listOf(
                    config("cfg-1"),
                    config("cfg-2").copy(
                        displayName = "Other",
                        modelIds = listOf("x-1"),
                        defaultModelId = null,
                    ),
                ),
            )
            val store = FakeCredentialStore()
            store.save("cfg-1", "sk-1")
            store.save("cfg-2", "sk-2")
            val vm = ChatViewModel(FakeChatRepository(), repo, store)
            advanceUntilIdle()

            vm.selectModel("m-1")
            assertEquals("m-1", vm.uiState.value.selectedModelId)

            vm.selectConfig("cfg-2")
            assertEquals("cfg-2", vm.uiState.value.selectedConfigId)
            assertEquals("x-1", vm.uiState.value.selectedModelId)
        }

    @Test
    fun `rapid double send is claimed once synchronously`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(
                events = listOf(ChatEvent.Done("Hi there")),
            )
            val vm = readyViewModel(chatRepo)

            vm.updateInput("one")
            val first = vm.send()
            // No dispatcher advance: the second call must be rejected by
            // the synchronous claim, before any coroutine runs.
            val second = vm.send()
            assertNotNull(first)
            assertNull(second)
            first!!.join()
            advanceUntilIdle()

            assertEquals(1, chatRepo.callCount)
            assertEquals(
                1,
                vm.uiState.value.messages.count { it.role == ChatRole.USER },
            )
            assertFalse(vm.uiState.value.generating)
        }

    @Test
    fun `selectConfig stops an in-flight stream`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val repo = InMemoryConfigRepo(listOf(config("cfg-1"), config("cfg-2")))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-1")
        store.save("cfg-2", "sk-2")
        val vm = ChatViewModel(chatRepo, repo, store)
        advanceUntilIdle()

        vm.updateInput("one")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.generating)

        vm.selectConfig("cfg-2")

        assertFalse(vm.uiState.value.generating)
        assertEquals("cfg-2", vm.uiState.value.selectedConfigId)
        // The partial turn is kept, still pending.
        assertEquals(1, vm.uiState.value.messages.count { it.isPending })
    }

    @Test
    fun `selectModel stops an in-flight stream`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val vm = readyViewModel(chatRepo)

        vm.updateInput("one")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.generating)

        vm.selectModel("m-1")

        assertFalse(vm.uiState.value.generating)
        assertEquals("m-1", vm.uiState.value.selectedModelId)
    }

    @Test
    fun `dismissError clears the banner`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(
            events = listOf(ChatEvent.Error(ProviderError.Timeout)),
        )
        val vm = readyViewModel(chatRepo)

        vm.updateInput("Hi")
        vm.send()!!.join()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)

        vm.dismissError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `send assigns stable ids to persisted turns`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("answer")))
        val (vm, convRepo, convId) = persistentViewModel(chatRepo)

        sendText(vm, "Hi")

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertNotNull(messages[0].id)
        assertNotNull(messages[1].id)
        val stored = convRepo.messages(convId).first()
        assertEquals(messages.map { it.id }, stored.map { it.id })
    }

    @Test
    fun `regenerate drops the last assistant message and streams a fresh one`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("first answer")))
            val (vm, convRepo, convId) = persistentViewModel(chatRepo)
            sendText(vm, "Hi")
            assertEquals(2, vm.uiState.value.messages.size)

            chatRepo.events = listOf(ChatEvent.Done("second answer"))
            val last = vm.uiState.value.messages.last()
            assertEquals(ChatRole.ASSISTANT, last.role)
            vm.regenerate(last)!!.join()
            advanceUntilIdle()

            val messages = vm.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals("Hi", messages[0].text)
            assertEquals("second answer", messages[1].text)
            assertFalse(messages[1].isPending)
            // The user turn is not duplicated in the resent history.
            assertEquals(
                listOf(ChatMessage(ChatRole.USER, "Hi")),
                chatRepo.lastRequest!!.messages,
            )
            assertEquals(2, chatRepo.callCount)
            // The database holds exactly the two current rows.
            val stored = convRepo.messages(convId).first()
            assertEquals(2, stored.size)
            assertEquals("Hi", stored[0].content)
            assertEquals("second answer", stored[1].content)
            assertEquals(messages.map { it.id }, stored.map { it.id })
        }

    @Test
    fun `regenerate is a no-op while generating`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val (vm, _, _) = persistentViewModel(chatRepo)
        vm.updateInput("Hi")
        vm.send()
        advanceUntilIdle()

        assertNull(vm.regenerate(vm.uiState.value.messages.last()))
        assertEquals(1, chatRepo.callCount)
    }

    @Test
    fun `regenerate refuses a non-trailing message`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("a1")))
        val (vm, _, _) = persistentViewModel(chatRepo)
        // Empty conversation: nothing to regenerate.
        assertNull(vm.regenerate(ChatUiMessage(ChatRole.USER, "x", false)))
        sendText(vm, "Hi")
        val firstUser = vm.uiState.value.messages.first()
        // The user turn is not the trailing message: refused.
        assertNull(vm.regenerate(firstUser))
        assertEquals(1, chatRepo.callCount)
    }

    @Test
    fun `editAndResend truncates from the edited message and sends new text`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("a1")))
            val (vm, convRepo, convId) = persistentViewModel(chatRepo)
            sendText(vm, "first")
            chatRepo.events = listOf(ChatEvent.Done("a2"))
            sendText(vm, "second")
            assertEquals(4, vm.uiState.value.messages.size)

            chatRepo.events = listOf(ChatEvent.Done("a-new"))
            val firstUser = vm.uiState.value.messages[0]
            vm.editAndResend(firstUser, "edited")!!.join()
            advanceUntilIdle()

            val messages = vm.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals("edited", messages[0].text)
            assertEquals("a-new", messages[1].text)
            assertFalse(messages[1].isPending)
            // The resent history contains only the edited turn.
            assertEquals(
                listOf(ChatMessage(ChatRole.USER, "edited")),
                chatRepo.lastRequest!!.messages,
            )
            assertEquals(3, chatRepo.callCount)
            // The database was truncated transactionally: exactly the
            // two new rows remain.
            val stored = convRepo.messages(convId).first()
            assertEquals(2, stored.size)
            assertEquals("edited", stored[0].content)
            assertEquals("a-new", stored[1].content)
            assertEquals(messages.map { it.id }, stored.map { it.id })
        }

    @Test
    fun `editAndResend with blank text is a no-op`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("a1")))
        val (vm, _, _) = persistentViewModel(chatRepo)
        sendText(vm, "first")
        val before = vm.uiState.value.messages

        assertNull(vm.editAndResend(before[0], "   "))
        advanceUntilIdle()

        assertEquals(before, vm.uiState.value.messages)
        assertEquals(1, chatRepo.callCount)
    }

    @Test
    fun `editAndResend is a no-op while generating`() = runTest(testDispatcher) {
        val chatRepo = FakeChatRepository(hangForever = true)
        val (vm, _, _) = persistentViewModel(chatRepo)
        vm.updateInput("Hi")
        vm.send()
        advanceUntilIdle()

        val userMessage = vm.uiState.value.messages.first { it.role == ChatRole.USER }
        assertNull(vm.editAndResend(userMessage, "edited"))
        assertEquals(1, chatRepo.callCount)
    }

    @Test
    fun `regenerate works in in-memory mode without persistence`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("one")))
            val vm = readyViewModel(chatRepo)
            sendText(vm, "Hi")

            chatRepo.events = listOf(ChatEvent.Done("two"))
            vm.regenerate(vm.uiState.value.messages.last())!!.join()
            advanceUntilIdle()

            assertEquals(
                listOf("Hi", "two"),
                vm.uiState.value.messages.map { it.text },
            )
            assertEquals(2, chatRepo.callCount)
        }

    @Test
    fun `editAndResend works in in-memory mode without persistence`() =
        runTest(testDispatcher) {
            val chatRepo = FakeChatRepository(events = listOf(ChatEvent.Done("one")))
            val vm = readyViewModel(chatRepo)
            sendText(vm, "Hi")

            chatRepo.events = listOf(ChatEvent.Done("uno"))
            vm.editAndResend(vm.uiState.value.messages.first(), "Hello")!!.join()
            advanceUntilIdle()

            assertEquals(
                listOf("Hello", "uno"),
                vm.uiState.value.messages.map { it.text },
            )
            assertEquals(2, chatRepo.callCount)
        }
}
