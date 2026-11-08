package com.axiel7.anihyou.feature.mediadetails

import androidx.compose.ui.text.AnnotatedString
import com.axiel7.anihyou.core.domain.localization.DescriptionState
import com.axiel7.anihyou.core.network.localization.DescriptionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynopsisTextTest {
    @Test fun supplementalTextIsLiteralAndFallbackPreservesOriginal() {
        val literal = "<b>literal</b> &amp; [link](https://example.org)\nnext"
        val original = "<i>original</i>"
        assertEquals(SynopsisText.Supplemental(literal), selectSynopsis(DescriptionState.Available(literal, 2), original))
        listOf(DescriptionState.Disabled, DescriptionState.Unmapped, DescriptionState.Loading,
            DescriptionState.NoContent, DescriptionState.Failed(DescriptionFailure.NETWORK, 0)).forEach {
            assertEquals(SynopsisText.Original(original), selectSynopsis(it, original))
        }
    }
    @Test fun translationVisibilityIncludesSupplementalInEnglish() {
        val supplemental = SynopsisText.Supplemental("summary")
        val original = SynopsisText.Original("original")
        assertTrue(supplemental.shouldOfferTranslation(true))
        assertTrue(supplemental.shouldOfferTranslation(false))
        assertFalse(original.shouldOfferTranslation(true))
        assertTrue(original.shouldOfferTranslation(false))
    }

    @Test fun renderedTextIsSharedWithTranslationAndSupplementalIsLiteral() {
        val html = "A &amp; B<br>C"
        val original = SynopsisText.Original(html)
        val rendered = original.render { input ->
            assertEquals(html, input)
            AnnotatedString("A & B\nC")
        }!!
        assertEquals("A & B\nC", rendered.text)
        val literal = "<b>literal</b> &amp;\nnext"
        val supplemental = SynopsisText.Supplemental(literal).render { error("must remain literal") }!!
        assertEquals(literal, supplemental.text)
    }
}
