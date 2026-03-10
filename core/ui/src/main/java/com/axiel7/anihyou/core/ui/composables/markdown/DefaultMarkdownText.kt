package com.axiel7.anihyou.core.ui.composables.markdown

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.axiel7.anihyou.core.ui.utils.MarkdownUtils.formatCompatibleMarkdown
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun DefaultMarkdownText(
    markdown: String?,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = LocalTextStyle.current.fontSize,
    lineHeight: TextUnit = LocalTextStyle.current.lineHeight,
    uriHandler: MarkdownUriHandler,
) {
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = markdown?.formatCompatibleMarkdown().orEmpty(),
            typography = markdownTypography(
                text = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                )
            ),
            modifier = modifier,
            imageTransformer = Coil3ImageTransformerImpl,
        )
    }
}

@Composable
fun SpoilerDialog(
    text: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
        text = {
            Text(text = text)
        }
    )
}

@Preview
@Composable
private fun DefaultMarkdownTextPreview() {
    AniHyouTheme {
        Surface {
            DefaultMarkdownText(
                markdown = "",
                uriHandler = MarkdownUriHandler()
            )
        }
    }
}