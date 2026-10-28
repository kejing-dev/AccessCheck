package io.embodit.accesscheck.ui.access.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val CONSOLE = "console"
    const val CHAIR_PLATE = "chairPlate"
    const val DOOR_A = "doorPlateA"
    const val DOOR_B = "doorPlateB"
    const val DOOR_CARD = "doorCard"
    const val PIN_TAG = "pinTag"
    const val REPORT = "report"
    const val MENU = "menu"
    const val HINT = "hint"
    const val DIALOG = "dialog"
    const val COACH = "coach"

    /** Width / height in dp (the console's height is its tallest state; its glass hugs the content). */
    val CONSOLE_SIZE = 400f to 496f
    val CHAIR_PLATE_SIZE = 280f to 112f
    val DOOR_PLATE_SIZE = 300f to 112f
    val DOOR_CARD_SIZE = 300f to 320f
    val PIN_TAG_SIZE = 360f to 72f
    val REPORT_SIZE = 440f to 560f
    val MENU_SIZE = 320f to 464f
    val HINT_SIZE = 480f to 64f
    val DIALOG_SIZE = 440f to 240f
    val COACH_SIZE = 360f to 96f

    /** The console family is shown at 0.75 (1 dp = 0.6 mm). */
    const val MAIN_SCALE = 0.75f

    /** Plates in the room: 1 dp = 0.8 mm. */
    const val PLATE_SCALE = 1.0f

    /** Dialog: 1.0 m from the head. */
    const val DIALOG_DISTANCE_M = 1.0f

    val pool: List<String> = listOf(CONSOLE, CHAIR_PLATE, DOOR_A, DOOR_B, DOOR_CARD, PIN_TAG, REPORT, MENU, HINT, DIALOG, COACH)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CONSOLE -> CONSOLE_SIZE
            CHAIR_PLATE -> CHAIR_PLATE_SIZE
            DOOR_A, DOOR_B -> DOOR_PLATE_SIZE
            DOOR_CARD -> DOOR_CARD_SIZE
            PIN_TAG -> PIN_TAG_SIZE
            REPORT -> REPORT_SIZE
            MENU -> MENU_SIZE
            HINT -> HINT_SIZE
            DIALOG -> DIALOG_SIZE
            else -> COACH_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
