package dev.cannoli.ui

enum class MenuAction {
    CONFIRM, BACK, WEST, NORTH, START, SELECT, MENU, L1, R1, L2, R2, L3, R3;

    companion object {
        val defaults: Map<MenuAction, MenuAction> = entries.associateWith { it }
        val remappable = listOf(CONFIRM, BACK, NORTH, WEST, START, SELECT)

        fun restrict(bindings: Map<MenuAction, MenuAction>): Map<MenuAction, MenuAction> {
            if (bindings.isEmpty()) return bindings
            var result = defaults
            remappable.forEach { action ->
                val button = bindings[action] ?: action
                if (button in remappable) result = swap(result, action, button)
            }
            return result
        }

        fun swap(bindings: Map<MenuAction, MenuAction>, action: MenuAction, button: MenuAction): Map<MenuAction, MenuAction> {
            val current = defaults + bindings
            val other = current.entries.first { it.value == button }.key
            return current + (other to current.getValue(action)) + (action to button)
        }
    }
}
