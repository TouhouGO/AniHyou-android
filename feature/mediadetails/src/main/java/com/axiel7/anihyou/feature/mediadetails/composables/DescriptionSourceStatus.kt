package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.domain.localization.DescriptionState
import com.axiel7.anihyou.core.resources.R
import kotlinx.coroutines.delay

@Composable
internal fun DescriptionSourceStatus(state: DescriptionState, onRetry: (() -> Unit)?) {
    val uriHandler = LocalUriHandler.current
    when (state) {
        is DescriptionState.Available -> TextButton(
            onClick = { uriHandler.openUri("https://bgm.tv/subject/${state.subjectId}") },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(stringResource(R.string.description_source)) }
        DescriptionState.Loading, DescriptionState.Unmapped, DescriptionState.NoContent -> Text(
            text = stringResource(if (state == DescriptionState.Loading) R.string.description_loading else R.string.description_unavailable),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        is DescriptionState.Failed -> {
            var now by remember(state.retryAt) { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.retryAt) {
                while (now < state.retryAt) {
                    delay(1000)
                    now = System.currentTimeMillis()
                }
            }
            val seconds = ((state.retryAt - now).coerceAtLeast(0L) + 999L) / 1000L
            Row(modifier = Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.description_failed), style = MaterialTheme.typography.bodySmall)
                if (seconds > 0) Text(
                    stringResource(R.string.description_retry_after, seconds),
                    modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                ) else TextButton(onClick = { onRetry?.invoke() }, enabled = onRetry != null) {
                    Text(stringResource(R.string.description_retry))
                }
            }
        }
        DescriptionState.Disabled -> Unit
    }
}

