package io.embodit.accesscheck.ui.access.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.ui.theme.AcColors

/** Scene-lifetime materials, one per appearance. All unlit, tone mapping off so the design colours are exact. */
internal class SceneMaterials(private val shared: SharedResources) {
    // the wheelchair (design `primitiveRecipes`)
    val chairFrame: Material = unlit(AcColors.ChairFrame)
    val chairDark: Material = unlit(AcColors.ChairDark)
    val chairSeat: Material = unlit(AcColors.ChairSeat)
    val chairRed: Material = unlit(AcColors.Error)
    val chairStale: Material = unlit(AcColors.StaleGrey)

    // footprint frame and face
    val footWhite: Material = unlit(AcColors.White, twoSided = true)
    val footRed: Material = unlit(AcColors.Error, twoSided = true)
    val footStale: Material = unlit(AcColors.StaleGrey, twoSided = true)
    val footFaceRed: Material = sheer(AcColors.Error, 0.35f)

    // scuffs: three darkening levels
    val scuff1: Material = sheer(AcColors.ScuffRed, 0.60f)
    val scuff2: Material = sheer(AcColors.ScuffRed, 0.78f)
    val scuff3: Material = sheer(AcColors.ScuffRed, 0.95f)

    // turning ring, clear floor, snap frame
    val ringGreen: Material = sheer(AcColors.Passable, 0.90f, depthTest = false)
    val ringRed: Material = sheer(AcColors.Error, 0.95f, depthTest = false)
    val arrowRed: Material = sheer(AcColors.Error, 0.95f, depthTest = false)
    val clearWhite: Material = sheer(AcColors.White, 0.70f, depthTest = false)
    val clearOrange: Material = sheer(AcColors.Alert, 0.90f, depthTest = false)
    val snapDash: Material = sheer(AcColors.Interaction, 0.85f, depthTest = false)

    // door gates and threshold line
    val gateGreen: Material = unlit(AcColors.Passable, twoSided = true)
    val gateOrange: Material = unlit(AcColors.Alert, twoSided = true)
    val gateRed: Material = unlit(AcColors.Error, twoSided = true)
    val gateGrey: Material = unlit(AcColors.StaleGrey, twoSided = true)
    val thresholdRed: Material = sheer(AcColors.Error, 0.90f, depthTest = false)

    // reach band, eye lines
    val reachBand: Material = sheer(AcColors.ReachGreen, 0.28f)
    val eyeYellow: Material = unlit(AcColors.Interaction, twoSided = true)
    val eyeCyan: Material = unlit(AcColors.ChildCyan, twoSided = true)

    // pins
    val pinOrange: Material = unlit(AcColors.Alert)
    val pinYellow: Material = unlit(AcColors.Interaction)
    val pinRed: Material = unlit(AcColors.Error)
    val pinWhite: Material = unlit(AcColors.White)

    fun gate(v: Verdict): Material =
        when (v) {
            Verdict.PASS -> gateGreen
            Verdict.TIGHT -> gateOrange
            Verdict.FAIL -> gateRed
            Verdict.TO_MEASURE -> gateGrey
        }

    fun scuff(level: Int): Material =
        when {
            level >= 3 -> scuff3
            level == 2 -> scuff2
            else -> scuff1
        }

    private fun unlit(color: Color, twoSided: Boolean = false, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
                if (!depthTest) {
                    setDepthTest(false)
                    setDepthWrite(false)
                }
            }
        )

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                if (!depthTest) setDepthTest(false)
            }
        )
}
