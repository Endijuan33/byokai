package com.byokai.ui.providers

import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import com.byokai.domain.provider.ProviderRegistry
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProvidersViewModel] using in-memory fakes.
 * `viewModelScope` is redirected to a [StandardTestDispatcher] so the
 * tests are deterministic on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repo: InMemoryConfigRepo = InMemoryConfigRepo(),
        store: FakeCredentialStore = FakeCredentialStore(),
        adapter: FakeAdapter = FakeAdapter(),
    ): ProvidersViewModel {
        val registry = ProviderRegistry().apply { register(adapter) }
        return ProvidersViewModel(repo, store, registry)
    }

    private fun validDraft(id: String = "cfg-1") = ProviderConfigDraft(
        id = id,
        providerId = ProviderId.OPENAI,
        displayName = "Work",
        baseUrl = "https://api.openai.com",
        modelIds = listOf("gpt-4o"),
        defaultModelId = "gpt-4o",
        createdAt = 0L,
        isNew = true,
        hasSavedKey = false,
    )

    private fun savedConfig(id: String = "cfg-1") = ProviderConfig(
        id = id,
        providerId = ProviderId.OPENAI,
        displayName = "Work",
        baseUrl = "https://api.openai.com",
        modelIds = listOf("gpt-4o"),
        defaultModelId = "gpt-4o",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `addNew creates a draft with adapter defaults`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.addNew()

        val draft = vm.uiState.value.editing
        assertNotNull(draft)
        assertEquals(ProviderId.OPENAI, draft!!.providerId)
        assertEquals("https://fake.example.com", draft.baseUrl)
        assertEquals(listOf("gpt-4o"), draft.modelIds)
        assertTrue(draft.isNew)
        assertFalse(draft.hasSavedKey)
    }

    @Test
    fun `save valid draft persists config and stores key`() = runTest(testDispatcher) {
        val repo = InMemoryConfigRepo()
        val store = FakeCredentialStore()
        val vm = viewModel(repo, store)
        advanceUntilIdle()

        vm.saveDraft(validDraft(), "sk-test-key").join()
        advanceUntilIdle()

        val saved = repo.get("cfg-1")
        assertNotNull(saved)
        assertEquals("Work", saved!!.displayName)
        assertEquals(Result.Success("sk-test-key"), store.load("cfg-1"))
        assertNull(vm.uiState.value.editing)
        assertEquals(true, vm.uiState.value.hasKey["cfg-1"])
    }

    @Test
    fun `save draft with blank key keeps any existing key`() = runTest(testDispatcher) {
        val repo = InMemoryConfigRepo()
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-original")
        val vm = viewModel(repo, store)
        advanceUntilIdle()

        vm.saveDraft(validDraft(), "   ").join()
        advanceUntilIdle()

        assertEquals(Result.Success("sk-original"), store.load("cfg-1"))
        assertNotNull(repo.get("cfg-1"))
    }

    @Test
    fun `invalid draft saves nothing and reports validation error`() =
        runTest(testDispatcher) {
            val repo = InMemoryConfigRepo()
            val store = FakeCredentialStore()
            val vm = viewModel(repo, store)
            advanceUntilIdle()

            vm.saveDraft(validDraft().copy(displayName = "   "), "sk-key").join()

            assertNull(repo.get("cfg-1"))
            assertEquals(Result.Success(null), store.load("cfg-1"))
            val error = withTimeoutOrNull(1_000) { vm.errors.first() }
            assertEquals(
                UiText.Res(R.string.providers_validation_failed, listOf("displayName must not be blank")),
                error,
            )
        }

    @Test
    fun `repository save failure keeps the editor open`() = runTest(testDispatcher) {
        val repo = InMemoryConfigRepo().apply { failSaves = true }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.addNew()
        val draft = vm.uiState.value.editing!!.copy(displayName = "Work")
        vm.saveDraft(draft, "").join()

        val error = withTimeoutOrNull(1_000) { vm.errors.first() }
        assertEquals(UiText.Res(R.string.providers_save_error), error)
        assertNotNull(vm.uiState.value.editing)
    }

    @Test
    fun `testConnection without saved key reports Failed`() = runTest(testDispatcher) {
        val repo = InMemoryConfigRepo(listOf(savedConfig()))
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.testConnection("cfg-1").join()
        advanceUntilIdle()

        val state = vm.uiState.value.testResult["cfg-1"]
        assertTrue(state is TestState.Failed)
        assertEquals(
            UiText.Res(R.string.providers_test_no_key),
            (state as TestState.Failed).message,
        )
    }

    @Test
    fun `testConnection with saved key reports Ok`() = runTest(testDispatcher) {
        val repo = InMemoryConfigRepo(listOf(savedConfig()))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-key")
        val vm = viewModel(repo, store)
        advanceUntilIdle()

        vm.testConnection("cfg-1").join()
        advanceUntilIdle()

        assertEquals(TestState.Ok, vm.uiState.value.testResult["cfg-1"])
    }

    @Test
    fun `testConnection failure maps provider error to message`() =
        runTest(testDispatcher) {
            val repo = InMemoryConfigRepo(listOf(savedConfig()))
            val store = FakeCredentialStore()
            store.save("cfg-1", "sk-key")
            val adapter = FakeAdapter().apply {
                testResult = Result.Error(ProviderException(ProviderError.Auth("bad")))
            }
            val vm = viewModel(repo, store, adapter)
            advanceUntilIdle()

            vm.testConnection("cfg-1").join()
            advanceUntilIdle()

            val state = vm.uiState.value.testResult["cfg-1"]
            assertTrue(state is TestState.Failed)
            assertEquals(
                UiText.Res(R.string.error_provider_auth),
                (state as TestState.Failed).message,
            )
        }

    @Test
    fun `delete removes config and key`() = runTest(testDispatcher) {
        val repo = InMemoryConfigRepo(listOf(savedConfig()))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-key")
        val vm = viewModel(repo, store)
        advanceUntilIdle()

        vm.requestDelete("cfg-1")
        assertEquals("cfg-1", vm.uiState.value.pendingDeleteId)

        vm.deleteConfig("cfg-1").join()
        advanceUntilIdle()

        assertNull(repo.get("cfg-1"))
        assertEquals(Result.Success(null), store.load("cfg-1"))
        assertNull(vm.uiState.value.pendingDeleteId)
        assertTrue(vm.uiState.value.configs.isEmpty())
    }

    @Test
    fun `refreshModels replaces model ids and fixes orphaned default`() =
        runTest(testDispatcher) {
            val stale = savedConfig().copy(
                modelIds = listOf("old-model"),
                defaultModelId = "old-model",
            )
            val repo = InMemoryConfigRepo(listOf(stale))
            val store = FakeCredentialStore()
            store.save("cfg-1", "sk-key")
            val adapter = FakeAdapter().apply {
                modelList = listOf(ModelInfo("m-1", "m-1"), ModelInfo("m-2", "m-2"))
            }
            val vm = viewModel(repo, store, adapter)
            advanceUntilIdle()

            vm.refreshModels("cfg-1").join()
            advanceUntilIdle()

            val updated = repo.get("cfg-1")
            assertNotNull(updated)
            assertEquals(listOf("m-1", "m-2"), updated!!.modelIds)
            assertEquals("m-1", updated.defaultModelId)
        }

    @Test
    fun `refreshModels keeps a default that is still listed`() = runTest(testDispatcher) {
        val config = savedConfig().copy(
            modelIds = listOf("m-1"),
            defaultModelId = "m-1",
        )
        val repo = InMemoryConfigRepo(listOf(config))
        val store = FakeCredentialStore()
        store.save("cfg-1", "sk-key")
        val adapter = FakeAdapter().apply {
            modelList = listOf(ModelInfo("m-1", "m-1"), ModelInfo("m-2", "m-2"))
        }
        val vm = viewModel(repo, store, adapter)
        advanceUntilIdle()

        vm.refreshModels("cfg-1").join()
        advanceUntilIdle()

        assertEquals("m-1", repo.get("cfg-1")!!.defaultModelId)
    }

    private fun twoFamilyViewModel(): Triple<ProvidersViewModel, FakeAdapter, FakeAdapter> {
        val openai = FakeAdapter(
            providerId = ProviderId.OPENAI,
            displayName = "FakeOpenAI",
            defaultBaseUrl = "https://openai.example.com",
        )
        val anthropic = FakeAdapter(
            providerId = ProviderId.ANTHROPIC,
            displayName = "FakeAnthropic",
            defaultBaseUrl = "https://anthropic.example.com",
        )
        val registry = ProviderRegistry().apply {
            register(openai)
            register(anthropic)
        }
        val vm = ProvidersViewModel(InMemoryConfigRepo(), FakeCredentialStore(), registry)
        return Triple(vm, openai, anthropic)
    }

    @Test
    fun `selectProviderFamily replaces pristine base URL with new family default`() =
        runTest(testDispatcher) {
            val (vm, _, anthropic) = twoFamilyViewModel()
            advanceUntilIdle()

            vm.addNew() // pristine draft: baseUrl = openai default
            vm.selectProviderFamily(anthropic)

            val draft = vm.uiState.value.editing
            assertNotNull(draft)
            assertEquals(ProviderId.ANTHROPIC, draft!!.providerId)
            assertEquals("https://anthropic.example.com", draft.baseUrl)
        }

    @Test
    fun `selectProviderFamily preserves a hand-typed base URL`() =
        runTest(testDispatcher) {
            val (vm, _, anthropic) = twoFamilyViewModel()
            advanceUntilIdle()

            vm.addNew()
            val typed = vm.uiState.value.editing!!.copy(baseUrl = "https://proxy.example.com/v1")
            vm.updateDraft(typed)
            vm.selectProviderFamily(anthropic)

            val draft = vm.uiState.value.editing
            assertNotNull(draft)
            assertEquals(ProviderId.ANTHROPIC, draft!!.providerId)
            assertEquals("https://proxy.example.com/v1", draft.baseUrl)
        }

    @Test
    fun `selectProviderFamily fills a blank base URL with new family default`() =
        runTest(testDispatcher) {
            val (vm, _, anthropic) = twoFamilyViewModel()
            advanceUntilIdle()

            vm.addNew()
            val blanked = vm.uiState.value.editing!!.copy(baseUrl = "   ")
            vm.updateDraft(blanked)
            vm.selectProviderFamily(anthropic)

            val draft = vm.uiState.value.editing
            assertNotNull(draft)
            assertEquals("https://anthropic.example.com", draft!!.baseUrl)
        }

    @Test
    fun `selectProviderFamily is a no-op for the same family`() =
        runTest(testDispatcher) {
            val (vm, openai, _) = twoFamilyViewModel()
            advanceUntilIdle()

            vm.addNew()
            val before = vm.uiState.value.editing!!
            vm.selectProviderFamily(openai)

            assertEquals(before, vm.uiState.value.editing)
        }

    private fun catalogViewModel(): ProvidersViewModel {
        val registry = ProviderRegistry().apply {
            register(FakeAdapter(providerId = ProviderId.OPENAI_COMPATIBLE))
            register(FakeAdapter(providerId = ProviderId.ANTHROPIC_COMPATIBLE))
        }
        return ProvidersViewModel(InMemoryConfigRepo(), FakeCredentialStore(), registry)
    }

    @Test
    fun `catalog picker opens and filters by query`() = runTest(testDispatcher) {
        val vm = catalogViewModel()
        advanceUntilIdle()

        vm.openCatalogPicker()
        assertTrue(vm.uiState.value.catalogOpen)
        assertEquals(
            com.byokai.domain.provider.ProviderCatalog.entries,
            vm.filteredCatalog(),
        )

        vm.updateCatalogQuery("deep")
        assertEquals("deep", vm.uiState.value.catalogQuery)
        assertEquals(listOf("deepseek"), vm.filteredCatalog().map { it.id })

        vm.closeCatalogPicker()
        assertFalse(vm.uiState.value.catalogOpen)
        assertEquals("", vm.uiState.value.catalogQuery)
    }

    @Test
    fun `selectCatalogEntry prefills draft from the entry`() = runTest(testDispatcher) {
        val vm = catalogViewModel()
        advanceUntilIdle()

        vm.openCatalogPicker()
        val entry = com.byokai.domain.provider.ProviderCatalog.entries
            .first { it.id == "deepseek" }
        vm.selectCatalogEntry(entry)

        val draft = vm.uiState.value.editing
        assertNotNull(draft)
        assertEquals(ProviderId.OPENAI_COMPATIBLE, draft!!.providerId)
        assertEquals("DeepSeek", draft.displayName)
        assertEquals("https://api.deepseek.com", draft.baseUrl)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), draft.modelIds)
        assertEquals("deepseek-chat", draft.defaultModelId)
        assertTrue(draft.isNew)
        assertFalse(vm.uiState.value.catalogOpen)
    }

    @Test
    fun `selectCatalogEntry with no suggested models prefills a blank model field`() =
        runTest(testDispatcher) {
            val vm = catalogViewModel()
            advanceUntilIdle()

            val entry = com.byokai.domain.provider.ProviderCatalog.entries
                .first { it.id == "lmstudio" }
            vm.selectCatalogEntry(entry)

            val draft = vm.uiState.value.editing
            assertNotNull(draft)
            assertEquals(listOf(""), draft!!.modelIds)
            assertNull(draft.defaultModelId)
        }

    @Test
    fun `selectCustomProvider opens a blank draft for compatible families`() =
        runTest(testDispatcher) {
            val vm = catalogViewModel()
            advanceUntilIdle()

            vm.openCatalogPicker()
            vm.selectCustomProvider(ProviderId.ANTHROPIC_COMPATIBLE)

            val draft = vm.uiState.value.editing
            assertNotNull(draft)
            assertEquals(ProviderId.ANTHROPIC_COMPATIBLE, draft!!.providerId)
            assertEquals("", draft.displayName)
            assertEquals("", draft.baseUrl)
            assertEquals(listOf(""), draft.modelIds)
            assertFalse(vm.uiState.value.catalogOpen)
        }

    @Test
    fun `selectCustomProvider ignores first-party families`() = runTest(testDispatcher) {
        val vm = catalogViewModel()
        advanceUntilIdle()

        vm.selectCustomProvider(ProviderId.OPENAI)

        assertNull(vm.uiState.value.editing)
        assertFalse(vm.uiState.value.catalogOpen)
    }
}
