package com.axiel7.anihyou.feature.genrestags

import androidx.compose.runtime.mutableStateListOf
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.CatalogTag
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.model.genre.SelectableGenre
import com.axiel7.anihyou.core.ui.localization.LocalizationDisplayContext
import org.junit.Assert.assertEquals
import org.junit.Test

class GenresTagsUiStateTest {

    @Test
    fun localizedQueryPreservesDistinctRawOptionsAndSelections() {
        val source = CatalogSource("tags", "revision", "https://example.org", "CC0", "a".repeat(64))
        val snapshot = LocalizationSnapshot.create(
            version = "v1",
            sources = listOf(source),
            records = emptyList(),
            tags = listOf(
                CatalogTag("Action", "动作", source.id),
                CatalogTag("Adventure", "动作", source.id),
            ),
        )
        val state = GenresTagsUiState(
            filter = "动作",
            tags = mutableStateListOf(
                SelectableGenre("Action"),
                SelectableGenre("Adventure"),
                SelectableGenre("Drama"),
            ),
        )
        val enabled = LocalizationDisplayContext(snapshot, false, false, true)

        assertEquals(
            listOf("Action", "Adventure"),
            state.filteredTags(enabled::tagMatches).map(SelectableGenre::name),
        )

        state.updateTag(SelectableGenre("Action", SelectableGenre.State.SELECTED))
        state.updateTag(SelectableGenre("Adventure", SelectableGenre.State.EXCLUDED))
        assertEquals(setOf("Action"), state.genresAndTagsForSearch().tagIn)
        assertEquals(setOf("Adventure"), state.genresAndTagsForSearch().tagNot)

        val disabled = LocalizationDisplayContext(snapshot, false, false, false)
        assertEquals(emptyList<SelectableGenre>(), state.filteredTags(disabled::tagMatches))
        assertEquals(
            listOf("Action"),
            state.copy(filter = "action").filteredTags(disabled::tagMatches).map(SelectableGenre::name),
        )
        assertEquals(
            listOf("Action", "Adventure", "Drama"),
            state.copy(filter = "").filteredTags(disabled::tagMatches).map(SelectableGenre::name),
        )
    }
}
