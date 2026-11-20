package com.pkos.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pkos.app.di.AppContainer
import com.pkos.app.di.LocalAppContainer

/** Creates a screen ViewModel from the app container (manual DI). */
@Composable
inline fun <reified VM : ViewModel> pkosViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
