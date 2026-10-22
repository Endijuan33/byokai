package com.byokai.ui.chat

import com.byokai.core.common.UiText
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.repository.AttachmentStorage
import com.byokai.ui.providers.FakeCredentialStore
import com.byokai.ui.providers.InMemoryConfigRepo
import java.io.File
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the Phase 9B ViewModel logic that is pure state.
 *
 * Honestly NOT covered here (device-tested only, see class KDocs):
 * - [ChatViewModel.onImagePicked] / [ChatViewModel.onCameraCaptured]:
 *   need a real [com.byokai.data.local.ImageProcessor], which requires
 *   an Android Context.
 * - [VoiceInputController]: wraps [android.speech.SpeechRecognizer].
 * - Camera / gallery intents and thumbnail rendering: Compose +
 *   activity results, on-device only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatAttachmentsVoiceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun config() = ProviderConfig(
        id = "cfg-1",
        providerId = ProviderId.OPENAI,
        displayName = "Test",
        baseUrl = "https://api.openai.com",
        modelIds = listOf("m-1"),
        defaultModelId = "m-1",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private suspend fun TestScope.viewModel(
        storage: AttachmentStorage? = null,
    ): ChatViewModel {
        val repo = InMemoryConfigRepo(listOf(config()))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-key")
        val vm = ChatViewModel(
            FakeChatRepository(),
            repo,
            store,
            attachmentStorage = storage,
        )
        advanceUntilIdle()
        return vm
    }

    // --- Voice state machine (pure; controller callbacks only). ---

    @Test
    fun `voice result appends to blank input`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onVoiceResult("hello")
        assertEquals("hello", vm.uiState.value.input)
        assertEquals(VoiceState.Idle, vm.uiState.value.voiceState)
    }

    @Test
    fun `voice result appends with separating space`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.updateInput("hi")
        vm.onVoiceResult("hello")
        assertEquals("hi hello", vm.uiState.value.input)
        assertEquals(VoiceState.Idle, vm.uiState.value.voiceState)
    }

    @Test
    fun `voice result does not double-space after trailing whitespace`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.updateInput("hi ")
            vm.onVoiceResult("hello")
            assertEquals("hi hello", vm.uiState.value.input)
        }

    @Test
    fun `empty voice result only resets state`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.updateInput("hi")
        vm.onVoiceResult("   ")
        assertEquals("hi", vm.uiState.value.input)
        assertEquals(VoiceState.Idle, vm.uiState.value.voiceState)
    }

    @Test
    fun `voice error surfaces error state`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onVoiceError("boom")
        val state = vm.uiState.value.voiceState
        assertTrue(state is VoiceState.Error)
        assertEquals(UiText.Raw("boom"), (state as VoiceState.Error).message)
    }

    @Test
    fun `dismissVoiceError resets to idle`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onVoiceError("boom")
        vm.dismissVoiceError()
        assertEquals(VoiceState.Idle, vm.uiState.value.voiceState)
    }

    @Test
    fun `dismissVoiceError is a no-op when idle`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.dismissVoiceError()
        assertEquals(VoiceState.Idle, vm.uiState.value.voiceState)
    }

    @Test
    fun `stopVoiceInput with no controller is a safe no-op`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.stopVoiceInput()
            assertEquals(VoiceState.Idle, vm.uiState.value.voiceState)
        }

    // --- Attachment list ops that do not need ImageProcessor. ---

    @Test
    fun `removePendingAttachment with unknown id is a no-op`() =
        runTest(testDispatcher) {
            val storage = FakeAttachmentStorage()
            val vm = viewModel(storage)
            vm.removePendingAttachment("nope")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.pendingAttachments.isEmpty())
            assertTrue(storage.deleted.isEmpty())
        }

    @Test
    fun `prepareCameraFile without storage fails fast`() = runTest(testDispatcher) {
        val vm = viewModel(storage = null)
        var thrown: Exception? = null
        try {
            vm.prepareCameraFile()
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `send with blank input and no attachments is rejected`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.updateInput("   ")
            assertEquals(null, vm.send())
        }
}

/** In-memory [AttachmentStorage] for ViewModel tests. */
class FakeAttachmentStorage : AttachmentStorage {
    val files = mutableMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()

    override fun fileFor(fileName: String): File =
        File("/tmp/byokai-test-attachments", fileName)

    override suspend fun readBytes(attachment: ImageAttachment): ByteArray =
        files[attachment.fileName] ?: throw NoSuchElementException(attachment.fileName)

    override suspend fun delete(fileName: String) {
        files.remove(fileName)
        deleted.add(fileName)
    }
}
