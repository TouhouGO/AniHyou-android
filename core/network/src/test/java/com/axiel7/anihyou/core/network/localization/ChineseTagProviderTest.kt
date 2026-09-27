package com.axiel7.anihyou.core.network.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChineseTagProviderTest {

    private fun providerWith(tagsJson: String): Pair<ChineseTagProvider, LocalizationBundleManager> {
        val dir = Files.createTempDirectory("chinese_tag_test").toFile()
        dir.deleteOnExit()
        File(dir, "tags_zh_cn.json").writeText(tagsJson)
        val manager = LocalizationBundleManager().apply { setStorageDirectory(dir) }
        return ChineseTagProvider(manager) to manager
    }

    @Test
    fun testGetChineseTagExactMatchAndCaseVariants() {
        val json = """
            {
                "Action": "动作",
                "Sci-Fi": "科幻",
                "Slice of Life": "日常"
            }
        """.trimIndent()
        val (provider, _) = providerWith(json)

        // Exact match
        assertEquals("动作", provider.getChineseTag("Action"))
        assertEquals("科幻", provider.getChineseTag("Sci-Fi"))
        assertEquals("日常", provider.getChineseTag("Slice of Life"))

        // Lowercase variant match
        assertEquals("动作", provider.getChineseTag("action"))
        assertEquals("科幻", provider.getChineseTag("sci-fi"))
        assertEquals("日常", provider.getChineseTag("slice of life"))

        // Uppercase / mixed-case variant match
        assertEquals("动作", provider.getChineseTag("ACTION"))
        assertEquals("科幻", provider.getChineseTag("SCI-FI"))

        // Whitespace trimming
        assertEquals("动作", provider.getChineseTag("  Action  "))
        assertEquals("科幻", provider.getChineseTag(" sci-fi "))
    }

    @Test
    fun testGetChineseTagUnmatchedReturnsOriginal() {
        val (provider, _) = providerWith("""{"Action": "动作"}""")

        assertEquals("UnknownTag", provider.getChineseTag("UnknownTag"))
        assertEquals("NonExistent", provider.getChineseTag("NonExistent"))
        assertEquals("Comedy", provider.getChineseTag("Comedy"))
    }

    @Test
    fun testGetChineseTagNullAndBlankReturnsOriginal() {
        val (provider, _) = providerWith("""{"Action": "动作"}""")

        assertNull(provider.getChineseTag(null))
        assertEquals("", provider.getChineseTag(""))
        assertEquals("   ", provider.getChineseTag("   "))
    }

    @Test
    fun testGetEnglishTagMatchedReturnsEnglishKey() {
        val json = """
            {
                "Action": "动作",
                "Sci-Fi": "科幻",
                "Slice of Life": "日常"
            }
        """.trimIndent()
        val (provider, _) = providerWith(json)

        assertEquals("Action", provider.getEnglishTag("动作"))
        assertEquals("Sci-Fi", provider.getEnglishTag("科幻"))
        assertEquals("Slice of Life", provider.getEnglishTag("日常"))

        // Whitespace trimming
        assertEquals("Action", provider.getEnglishTag(" 动作 "))
    }

    @Test
    fun testGetEnglishTagUnmatchedReturnsOriginal() {
        val (provider, _) = providerWith("""{"Action": "动作"}""")

        assertEquals("未知标签", provider.getEnglishTag("未知标签"))
        assertNull(provider.getEnglishTag(null))
        assertEquals("", provider.getEnglishTag(""))
        assertEquals("   ", provider.getEnglishTag("   "))

        // When input is already an English tag present in tagMap, return it
        assertEquals("Action", provider.getEnglishTag("Action"))
        assertEquals("action", provider.getEnglishTag("action"))

        // An unmapped English word returns original
        assertEquals("NonExistentTag", provider.getEnglishTag("NonExistentTag"))
    }

    @Test
    fun testGetChineseTagAndGetEnglishTagRoundTrip() {
        val sampleTags = mapOf(
            "Action" to "动作",
            "Sci-Fi" to "科幻",
            "Romance" to "恋爱",
            "Adventure" to "冒险",
            "Slice of Life" to "日常"
        )
        val json = "{" + sampleTags.entries.joinToString(",") { "\"${it.key}\": \"${it.value}\"" } + "}"
        val (provider, _) = providerWith(json)

        for ((en, zh) in sampleTags) {
            val translatedZh = provider.getChineseTag(en)
            assertEquals(zh, translatedZh)

            val backToEn = provider.getEnglishTag(translatedZh)
            assertEquals(en, backToEn)

            // Direct round-trips
            assertEquals(en, provider.getEnglishTag(provider.getChineseTag(en)))
            assertEquals(zh, provider.getChineseTag(provider.getEnglishTag(zh)))
        }
    }

    @Test
    fun testReloadInvalidatesCacheAndReflectsNewData() {
        val dir = Files.createTempDirectory("chinese_tag_reload").toFile()
        dir.deleteOnExit()
        val tagFile = File(dir, "tags_zh_cn.json")
        tagFile.writeText("""{"Action": "动作"}""")

        val manager = LocalizationBundleManager().apply { setStorageDirectory(dir) }
        val provider = ChineseTagProvider(manager)

        assertEquals("动作", provider.getChineseTag("Action"))
        assertEquals("Action", provider.getEnglishTag("动作"))

        // Modify file content and notify reload via manager
        tagFile.writeText("""{"Action": "格斗"}""")
        manager.notifyReload()

        assertEquals("格斗", provider.getChineseTag("Action"))
        assertEquals("Action", provider.getEnglishTag("格斗"))

        // Modify file content and call reload() directly
        tagFile.writeText("""{"Action": "战斗"}""")
        provider.reload()

        assertEquals("战斗", provider.getChineseTag("Action"))
        assertEquals("Action", provider.getEnglishTag("战斗"))
    }

    @Test
    fun testTagMapAndReverseTagMapContents() {
        val json = """
            {
                "Action": "动作",
                "Sci-Fi": "科幻"
            }
        """.trimIndent()
        val (provider, _) = providerWith(json)

        val forward = provider.tagMap
        val reverse = provider.reverseTagMap

        // Forward map contains both original and lowercased keys
        assertEquals("动作", forward["Action"])
        assertEquals("动作", forward["action"])
        assertEquals("科幻", forward["Sci-Fi"])
        assertEquals("科幻", forward["sci-fi"])

        // Reverse map contains Chinese to English mapping
        assertEquals("Action", reverse["动作"])
        assertEquals("Sci-Fi", reverse["科幻"])
    }

    @Test
    fun testCorruptOrInvalidFileDoesNotThrowAndReturnsEmptyMaps() {
        // Broken JSON syntax
        val (corruptProvider, _) = providerWith("{ invalid json content")
        assertTrue("tagMap should be empty on corrupt json", corruptProvider.tagMap.isEmpty())
        assertTrue("reverseTagMap should be empty on corrupt json", corruptProvider.reverseTagMap.isEmpty())
        assertEquals("Action", corruptProvider.getChineseTag("Action"))
        assertEquals("动作", corruptProvider.getEnglishTag("动作"))

        // JSON array instead of object
        val (arrayProvider, _) = providerWith("""["Not", "An", "Object"]""")
        assertTrue("tagMap should be empty on non-object json", arrayProvider.tagMap.isEmpty())
        assertTrue("reverseTagMap should be empty on non-object json", arrayProvider.reverseTagMap.isEmpty())
        assertEquals("Action", arrayProvider.getChineseTag("Action"))
        assertEquals("动作", arrayProvider.getEnglishTag("动作"))

        // Empty JSON object
        val (emptyProvider, _) = providerWith("{}")
        assertTrue("tagMap should be empty on empty json", emptyProvider.tagMap.isEmpty())
        assertTrue("reverseTagMap should be empty on empty json", emptyProvider.reverseTagMap.isEmpty())
        assertEquals("Action", emptyProvider.getChineseTag("Action"))
        assertEquals("动作", emptyProvider.getEnglishTag("动作"))
    }
}
