package com.byokai.ui.chat

import com.byokai.R
import com.byokai.core.common.UiText
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
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
}
