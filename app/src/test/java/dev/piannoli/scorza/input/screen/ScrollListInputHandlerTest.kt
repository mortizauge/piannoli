package dev.piannoli.scorza.input.screen

import dev.piannoli.scorza.input.ListNavigation
import dev.piannoli.scorza.navigation.NavigationController
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollListInputHandlerTest {
    @Test fun `shared launcher and RomM list navigation clamps holds and pages but wraps fresh steps`() {
        var index = 1
        var moves = 0
        val handler = ScrollListInputHandler(
            NavigationController(), { 4 }, { index }, { index = it; moves++ },
            {}, {}, null, null, null, null, null, null, null,
        )
        ListNavigation.withRepeat(true) {
            repeat(10) { handler.onDown() }
            assertEquals(3, index)
            assertEquals(2, moves)
            handler.onRight()
            assertEquals(3, index)
        }
        handler.onDown()
        assertEquals(0, index)
        ListNavigation.withRepeat(true) {
            repeat(10) { handler.onUp() }
        }
        assertEquals(0, index)
        handler.onLeft()
        assertEquals(0, index)
        handler.onUp()
        assertEquals(3, index)
        handler.onLeft()
        assertEquals(0, index)
        handler.onRight()
        assertEquals(3, index)
    }
}
