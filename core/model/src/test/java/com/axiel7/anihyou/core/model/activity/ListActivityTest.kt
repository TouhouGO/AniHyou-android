package com.axiel7.anihyou.core.model.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class ListActivityTest {
    @Test fun unknownStatusRetainsProgressAndExplicitTitle() {
        assertEquals("Sample 4 of 中文", unknownActivityText("Sample", "4", "中文"))
        assertEquals("Sample 中文", unknownActivityText("Sample", "0", "中文"))
        assertEquals("null 原名", unknownActivityText(null, null, "原名"))
    }

}
