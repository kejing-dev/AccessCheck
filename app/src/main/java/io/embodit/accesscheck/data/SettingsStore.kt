package io.embodit.accesscheck.data

import android.content.Context
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.LanguagePref
import io.embodit.accesscheck.domain.model.Settings
import io.embodit.accesscheck.domain.model.UnitPref

/**
 * The preferences the app keeps between launches (design `persistence`): chair model, language, units,
 * and whether the first-run coachmark was seen. The scene (scuffs, pins, door readings) lives only for
 * one run; the report file is what stays.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): Settings =
        Settings(
            chair = ChairModel.fromTag(prefs.getString(KEY_CHAIR, null)),
            language = LanguagePref.fromTag(prefs.getString(KEY_LANG, null)),
            units = prefs.getString(KEY_UNITS, null)?.let { UnitPref.fromTag(it) },
            coachSeen = prefs.getBoolean(KEY_COACH, false),
        )

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_CHAIR, s.chair.tag)
            .putString(KEY_LANG, s.language.tag)
            .apply { if (s.units != null) putString(KEY_UNITS, s.units.tag) else remove(KEY_UNITS) }
            .putBoolean(KEY_COACH, s.coachSeen)
            .apply()
    }

    private companion object {
        const val FILE = "access_check"
        const val KEY_CHAIR = "chair"
        const val KEY_LANG = "language"
        const val KEY_UNITS = "units"
        const val KEY_COACH = "coach_seen"
    }
}
