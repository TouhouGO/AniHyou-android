package com.axiel7.anihyou.core.network.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChineseConverterTest {

    private fun converterWith(t2sJson: String, titlesJson: String = "{}"): ChineseConverter {
        val dir = Files.createTempDirectory("chinese_converter_test").toFile()
        dir.deleteOnExit()
        File(dir, "t2s_char_map.json").writeText(t2sJson)
        File(dir, "titles_zh_cn.json").writeText(titlesJson)
        return ChineseConverter(LocalizationBundleManager().apply { setStorageDirectory(dir) })
    }

    @Test
    fun convertsMappedCharactersAndLeavesUnmappedIntact() {
        val converter = converterWith("""{"幹": "干", "來": "来"}""")
        assertEquals("干来", converter.toSimplified("幹來"))
        assertEquals("干净的来", converter.toSimplified("干净的来"))
    }

    @Test
    fun handlesNullAndEmptyInput() {
        val converter = converterWith("""{"幹": "干"}""")
        assertNull(converter.toSimplified(null))
        assertEquals("", converter.toSimplified(""))
    }

    @Test
    fun reloadsWhenBundleChanges() {
        val dir = Files.createTempDirectory("chinese_converter_reload").toFile()
        dir.deleteOnExit()
        val t2s = File(dir, "t2s_char_map.json")
        t2s.writeText("""{"幹": "干"}""")
        File(dir, "titles_zh_cn.json").writeText("{}")

        val manager = LocalizationBundleManager().apply { setStorageDirectory(dir) }
        val converter = ChineseConverter(manager)
        assertEquals("干", converter.toSimplified("幹"))

        t2s.writeText("""{"幹": "千"}""")
        manager.notifyReload()
        assertEquals("千", converter.toSimplified("幹"))
    }

    @Test
    fun fallsBackToIdentityWhenMapMissingOrBroken() {
        // Missing file entirely: converter must not throw, just pass text through
        val converter = converterWith("not json at all")
        assertEquals("幹", converter.toSimplified("幹"))
    }
}
