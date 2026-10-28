package io.embodit.accesscheck.data

import android.content.Context
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.LanguagePref
import io.embodit.accesscheck.domain.model.Settings
import io.embodit.accesscheck.domain.model.UnitPref

/**
 * The preferences the app keeps between launches (design `persistence`): chair preset and size (v0.1.2), language, units,
 * whether the first-run coachmark was seen, and the mute switch. The scene (scuffs, pins, door readings) lives only for
 * one run; the report file is what stays.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): Settings =
        Settings(
            chair =
                ChairModel.fromTag(prefs.getString(KEY_CHAIR, null)).let {
                    it.sized(prefs.getInt(KEY_CHAIR_W, it.widthCm), prefs.getInt(KEY_CHAIR_L, it.lengthCm), prefs.getInt(KEY_CHAIR_H, it.heightCm))
                },
            language = LanguagePref.fromTag(prefs.getString(KEY_LANG, null)),
            units = prefs.getString(KEY_UNITS, null)?.let { UnitPref.fromTag(it) },
            coachSeen = prefs.getBoolean(KEY_COACH, false),
            muted = prefs.getBoolean(KEY_MUTED, false),
        )

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_CHAIR, s.chair.tag)
            .putInt(KEY_CHAIR_W, s.chair.widthCm)
            .putInt(KEY_CHAIR_L, s.chair.lengthCm)
            .putInt(KEY_CHAIR_H, s.chair.heightCm)
            .putString(KEY_LANG, s.language.tag)
            .apply { if (s.units != null) putString(KEY_UNITS, s.units.tag) else remove(KEY_UNITS) }
            .putBoolean(KEY_COACH, s.coachSeen)
            .putBoolean(KEY_MUTED, s.muted)
            .apply()
    }

    private companion object {
        const val FILE = "access_check"
        const val KEY_CHAIR = "chair"
        const val KEY_CHAIR_W = "chair_width_cm"
        const val KEY_CHAIR_L = "chair_length_cm"
        const val KEY_CHAIR_H = "chair_height_cm"
        const val KEY_LANG = "language"
        const val KEY_UNITS = "units"
        const val KEY_COACH = "coach_seen"
        const val KEY_MUTED = "muted"
    }
}
