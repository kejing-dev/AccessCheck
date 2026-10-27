package io.embodit.accesscheck.ui.access

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.accesscheck.platform.AcLog
import io.embodit.accesscheck.platform.AccessGraph
import io.embodit.accesscheck.ui.access.components.AccessStage
import io.embodit.accesscheck.ui.access.components.LocalAcResources
import io.embodit.accesscheck.ui.access.components.localizedResources
import io.embodit.accesscheck.ui.access.debug.AcProbe
import io.embodit.accesscheck.ui.access.scene.AccessFrameSystem
import io.embodit.accesscheck.ui.access.scene.AccessScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the
 * ViewModel holds the state, the [AccessScene] turns state into entities and reports taps, bumps and
 * the head, and [AccessStage] declares the panels.
 */
@Composable
fun AccessScreen() {
    val context = LocalContext.current
    AccessGraph.appContext = context.applicationContext
    val viewModel: AccessViewModel = viewModel(factory = AccessViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        AcProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { AccessScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<AccessFrameSystem>()
        AcProbe.host =
            object : AcProbe.Host {
                override fun dispatch(event: AccessEvent) = viewModel.onEvent(event)

                override fun uiState(): AccessUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): AcProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = AcProbe.click(name)
            }
        AcLog.i("STAGE enter")
        onDispose {
            AcProbe.host = null
            unregisterSystem<AccessFrameSystem>()
            scene.detach()
            AcLog.i("STAGE disposed")
        }
    }

    // background / foreground: a pushed chair stops by itself
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(AccessEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(AccessEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: AccessUiEffect ->
            when (effect) {
                AccessUiEffect.ExitApp -> {
                    AcLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { AcLog.e("STAGE close failed: $it", it) }
                }
                is AccessUiEffect.Sound ->
                    runCatching {
                        when (effect.kind) {
                            SoundKind.PLACE -> audio.playSystem(SpatialSoundEffect.OpClick)
                            SoundKind.BUMP -> audio.playSystem(SpatialSoundEffect.StateFailure)
                            SoundKind.PASS -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.FAIL -> audio.playSystem(SpatialSoundEffect.StateFailure)
                            SoundKind.PIN -> audio.playSystem(SpatialSoundEffect.OpDragEnd)
                            SoundKind.SNAP -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.SAVE -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.CLICK -> audio.playSystem(SpatialSoundEffect.OpClick)
                        }
                    }.onFailure { AcLog.w("SOUND failed: $it") }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalAcResources provides resources) {
        AccessStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
