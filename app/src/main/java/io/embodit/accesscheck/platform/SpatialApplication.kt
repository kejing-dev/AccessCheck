package io.embodit.accesscheck.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.accesscheck.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AccessGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
