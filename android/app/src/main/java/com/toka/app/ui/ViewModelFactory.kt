package com.toka.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ViewModel con el ciclo de vida correcto (atado a la entrada de navegación, se limpia al
 * salir). Antes se creaban con `remember { ... }`: nunca recibían `onCleared()`, sus
 * colectores de Room seguían vivos y el estado se perdía al rotar.
 */
@Composable
inline fun <reified VM : ViewModel> tokaViewModel(crossinline build: () -> VM): VM =
    viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
    })
