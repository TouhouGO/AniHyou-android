package com.axiel7.anihyou.feature.mediadetails

import androidx.compose.ui.text.AnnotatedString
import com.axiel7.anihyou.core.domain.localization.DescriptionState

internal sealed interface SynopsisText {
    data class Supplemental(val text: String) : SynopsisText
    data class Original(val html: String?) : SynopsisText
}

internal fun selectSynopsis(state: DescriptionState, original: String?): SynopsisText =
    if (state is DescriptionState.Available) SynopsisText.Supplemental(state.summary)
    else SynopsisText.Original(original)

internal fun SynopsisText.render(decodeHtml: (String) -> AnnotatedString): AnnotatedString? =
    when (this) {
        is SynopsisText.Supplemental -> AnnotatedString(text)
        is SynopsisText.Original -> html?.takeUnless { it.isBlank() }?.let(decodeHtml)
    }

internal fun SynopsisText.shouldOfferTranslation(isCurrentLanguageEn: Boolean): Boolean =
    !isCurrentLanguageEn || this is SynopsisText.Supplemental
