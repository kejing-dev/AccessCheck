package io.embodit.accesscheck.platform

import android.content.Context
import io.embodit.accesscheck.data.SettingsStore
import io.embodit.accesscheck.domain.usecase.ObstacleField
import io.embodit.accesscheck.platform.input.ControllerInput
import io.embodit.accesscheck.platform.input.HandInput
import io.embodit.accesscheck.platform.sense.MeshTracker
import io.embodit.accesscheck.platform.sense.PlaneTracker
import io.embodit.accesscheck.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `AccessScene.attach` / `detach`); the ViewModel sees the obstacle
 * field and the settings store only.
 */
object AccessGraph {
    val planes = PlaneTracker()
    val mesh = MeshTracker()
    val head = HeadTracker()
    val controller = ControllerInput()
    val hands = HandInput()

    /** The floor-plan obstacles, fed by the scene, read by the scene and the ViewModel (main thread). */
    val field = ObstacleField()

    @Volatile var appContext: Context? = null

    @Volatile private var settings: SettingsStore? = null

    fun settings(context: Context): SettingsStore =
        settings ?: synchronized(this) { settings ?: SettingsStore(context).also { settings = it } }
}
