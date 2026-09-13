package com.axiel7.anihyou.core.resources

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPluralFormatTest {
    @Test
    fun chineseAiringNotificationFormatsEpisodeBeforeTitle() {
        val template = airingTemplate("values-zh-rCN")
        assertEquals(
            "《作品》的第 3 集已播出",
            String.format(Locale.SIMPLIFIED_CHINESE, template, 3, "作品"),
        )
    }

    @Test
    fun turkishAiringNotificationFormatsEpisodeBeforeTitle() {
        val template = airingTemplate("values-tr-rTR")
        assertEquals(
            "Anime animesinin 3. bölümü yayınlandı.",
            String.format(Locale.forLanguageTag("tr-TR"), template, 3, "Anime"),
        )
        assertEquals(
            "Anime animesinin 1. bölümü yayınlandı.",
            String.format(
                Locale.forLanguageTag("tr-TR"),
                airingTemplate("values-tr-rTR", "one"),
                1,
                "Anime",
            ),
        )
    }

    private fun airingTemplate(localeDirectory: String, quantity: String = "other"): String {
        val resource = File("src/main/res/$localeDirectory/strings.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resource)
        val plurals = document.getElementsByTagName("plurals")
        val plural = (0 until plurals.length)
            .map { plurals.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == "notification_episode_aired" }
        val items = (plural as org.w3c.dom.Element).getElementsByTagName("item")
        return (0 until items.length)
            .map { items.item(it) }
            .first { it.attributes.getNamedItem("quantity").nodeValue == quantity }
            .textContent.trim()
    }
}
