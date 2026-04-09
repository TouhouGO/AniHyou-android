package com.axiel7.anihyou.core.ui.common.navigation

import androidx.navigation3.runtime.NavKey

interface INavigator {
    fun navigate(route: NavKey)
    fun goBack()
}