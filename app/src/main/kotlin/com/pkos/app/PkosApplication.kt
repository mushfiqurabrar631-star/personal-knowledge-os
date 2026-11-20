package com.pkos.app

import android.app.Application
import com.pkos.app.di.AppContainer

class PkosApplication : Application() {
    val container: AppContainer by lazy { AppContainer.create(this) }
}
