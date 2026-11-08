package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.network.SearchMediaQuery
import com.axiel7.anihyou.core.network.fragment.CommonPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchMediaPageValidationTest {
    private fun data(
        media: List<SearchMediaQuery.Medium?>? = emptyList(),
        pageInfo: SearchMediaQuery.PageInfo? = pageInfo(1, false),
        page: SearchMediaQuery.Page? = SearchMediaQuery.Page("Page", media, pageInfo),
    ) = SearchMediaQuery.Data(page)

    private fun pageInfo(currentPage: Int?, hasNextPage: Boolean?) =
        SearchMediaQuery.PageInfo(
            "PageInfo",
            CommonPage("PageInfo", currentPage = currentPage, hasNextPage = hasNextPage),
        )

    @Test fun completePageMapsOnce() {
        val result = data().completeSearchMediaPage(1)
        assertEquals(1, result?.currentPage)
        assertEquals(false, result?.hasNextPage)
        assertEquals(emptyList<SearchMediaQuery.Medium>(), result?.list)
    }

    @Test fun malformedPageFieldsAreRejected() {
        assertNull(SearchMediaQuery.Data(null).completeSearchMediaPage(1))
        assertNull(data(page = null).completeSearchMediaPage(1))
        assertNull(data(media = null).completeSearchMediaPage(1))
        assertNull(data(pageInfo = null).completeSearchMediaPage(1))
        assertNull(data(pageInfo = pageInfo(null, false)).completeSearchMediaPage(1))
        assertNull(data(pageInfo = pageInfo(1, null)).completeSearchMediaPage(1))
        assertNull(data(pageInfo = pageInfo(2, false)).completeSearchMediaPage(1))
    }
}
