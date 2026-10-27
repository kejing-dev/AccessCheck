package io.embodit.accesscheck.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.accesscheck.platform.AcLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.accesscheck.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.accesscheck.DEBUG -p io.embodit.accesscheck --es cmd 'state S12'"
 *   adb -s <serial> shell "am broadcast -a io.embodit.accesscheck.DEBUG -p io.embodit.accesscheck --es cmd head --ef x 1.2 --ef z -0.4 --ef yaw 90"
 *
 * Extras: `cmd` = command name with its arguments after it; float extras (`--ef x 1.2`) and int extras
 * (`--ei threshold 2`) are read by name; `s` = free text.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val s = intent.getStringExtra("s")
        val extras = HashMap<String, Float>()
        intent.extras?.let { b ->
            for (key in b.keySet()) {
                if (key == "cmd" || key == "s") continue
                @Suppress("DEPRECATION")
                when (val v = b.get(key)) {
                    is Float -> extras[key] = v
                    is Int -> extras[key] = v.toFloat()
                    is Double -> extras[key] = v.toFloat()
                    is Long -> extras[key] = v.toFloat()
                    is String -> v.toFloatOrNull()?.let { extras[key] = it }
                    else -> Unit
                }
            }
        }
        AcLog.i("CMD recv '$cmd' extras=$extras s='$s'")
        DebugCommands.run(context.applicationContext, cmd, extras, s)
    }
}
