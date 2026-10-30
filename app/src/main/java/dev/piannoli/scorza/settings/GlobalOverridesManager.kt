package dev.piannoli.scorza.settings

import dev.piannoli.igm.ShortcutAction
import dev.piannoli.core.IniParser
import dev.piannoli.core.IniWriter
import java.io.File

class GlobalOverridesManager(private val sdCardRoot: () -> String) {

    private fun iniFile() = File(sdCardRoot(), "Config/Overrides/global.ini")

    fun readShortcuts(): Map<ShortcutAction, Set<Int>> {
        val ini = IniParser.parse(iniFile())
        val map = mutableMapOf<ShortcutAction, Set<Int>>()
        for ((key, value) in ini.getSection("shortcuts")) {
            val action = try { ShortcutAction.valueOf(key) } catch (_: IllegalArgumentException) { continue }
            val chord = if (value.isEmpty()) emptySet()
            else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            map[action] = chord
        }
        return map
    }

    fun saveShortcuts(shortcuts: Map<ShortcutAction, Set<Int>>) {
        IniWriter.mergeWrite(
            iniFile(), "shortcuts",
            shortcuts.mapKeys { it.key.name }.mapValues { it.value.joinToString(",") }
        )
    }
}
