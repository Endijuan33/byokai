package com.byokai.data.local.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import com.byokai.core.common.Result
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [SettingsDataStore] against a real DataStore
 * backed by a temporary file. Runs on the JVM — no device needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDataStoreTest {

    private lateinit var testFile: File
    private lateinit var dataStore: SettingsDataStore

    @Before
    fun setUp() {
        testFile = File.createTempFile("byokai-settings-test", ".preferences_pb")
        testFile.delete()
        // PreferencesFileSerializer is the classic file-based Serializer
        // explicitly documented for manual DataStore creation in tests.
        dataStore = SettingsDataStore(
            DataStoreFactory.create(
                serializer = PreferencesFileSerializer,
                produceFile = { testFile },
            ),
        )
    }

    @After
    fun tearDown() {
        testFile.delete()
    }

    @Test
    fun `reads return documented defaults on an empty store`() = runTest {
        assertEquals(ThemeMode.SYSTEM, dataStore.themeMode.first())
        assertEquals(true, dataStore.dynamicColor.first())
        assertEquals(AppLanguage.ENGLISH, dataStore.language.first())
    }

    @Test
    fun `writes round-trip through the real preferences file`() = runTest {
        assertTrue(dataStore.setThemeMode(ThemeMode.DARK) is Result.Success)
        assertTrue(dataStore.setDynamicColor(false) is Result.Success)
        assertTrue(dataStore.setLanguage(AppLanguage.INDONESIAN) is Result.Success)

        assertEquals(ThemeMode.DARK, dataStore.themeMode.first())
        assertEquals(false, dataStore.dynamicColor.first())
        assertEquals(AppLanguage.INDONESIAN, dataStore.language.first())
    }

}
