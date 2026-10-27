package io.embodit.accesscheck.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Five `ColorScheme` roles are fixed here and injected into `PicoTheme` by [AccessCheckTheme]; the
 * other eleven inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials and the
 * solid faces (reading plates, hint bar), where adaptive colours must not land.
 */
object AcColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: blocked / can't turn / bumped
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: tight / clear-floor short
    val Passable = Color(0xFF66E08A) // design-style: fixed-figma-color passable: fits / room to turn
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color interaction: selected mode, seated eye line

    // ---- brand tokens (design `palette.brandTokens`)
    val PineGreen = Color(0xFF0E7C66) // design-style: fixed-figma-color pineGreen: icon background, report title bar
    val ChairFrame = Color(0xFFD9DDE3) // design-style: fixed-figma-color chairFrame
    val ChairDark = Color(0xFF2A2D33) // design-style: fixed-figma-color chairDark: wheels
    val ChairSeat = Color(0xFF3A3F47) // design-style: fixed-figma-color seat and backrest
    val ScuffRed = Color(0xFFE5322D) // design-style: fixed-figma-color scuffRed
    val ChildCyan = Color(0xFF4FD1E0) // design-style: fixed-figma-color childCyan: child eye line
    val ReachGreen = Color(0xFF66E08A) // design-style: fixed-figma-color reachGreen at 28 % (material opacity)
    val PlateInk = Color(0xFF121417) // design-style: fixed-figma-color plateInk: reading plates, hint bar, report root
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: tracking lost, door "to measure"
    val Ink = Color(0xFF16181C) // design-style: fixed-figma-color ink: content on semantic fills
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white: footprint frame, pin needles
}
