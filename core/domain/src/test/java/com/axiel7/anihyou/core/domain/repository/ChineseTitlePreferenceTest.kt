package com.axiel7.anihyou.core.domain.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChineseTitlePreferenceTest {
    @Test fun defaultAndStoredChoiceSurviveReopeningDataStore() = runBlocking {
        val directory = Files.createTempDirectory("title-preference").toFile()
        val file = directory.resolve("settings.preferences_pb")
        var job = Job()
        try {
            val first = DefaultPreferencesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            assertTrue(first.chineseDescriptions.first())
            assertTrue(first.chineseTitles.first())
            assertTrue(first.chinesePeople.first())
            assertTrue(first.chineseTags.first())
            first.setChineseDescriptions(false)
            first.setChineseTitles(false)
            first.setChinesePeople(false)
            job.cancelAndJoin()

            job = Job()
            val reopened = DefaultPreferencesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            assertFalse(reopened.chineseDescriptions.first())
            assertFalse(reopened.chineseTitles.first())
            assertFalse(reopened.chinesePeople.first())
            assertTrue(reopened.chineseTags.first())
            reopened.setChineseTags(false)
            reopened.setChinesePeople(true)
            assertFalse(reopened.chineseTags.first())
            assertTrue(reopened.chinesePeople.first())
            reopened.setChineseTitles(true)
            assertTrue(reopened.chineseTitles.first())
            assertFalse(reopened.chineseDescriptions.first())
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
