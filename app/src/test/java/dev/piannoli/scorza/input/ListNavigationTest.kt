package dev.piannoli.scorza.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ListNavigationTest {
    @Test fun `held moves reach either end and stay there until a fresh press`() {
        var index = 1
        ListNavigation.withRepeat(true) {
            repeat(10) { index = ListNavigation.move(index, 1, 4) }
        }
        assertEquals(3, index)
        index = ListNavigation.move(index, 1, 4)
        assertEquals(0, index)
        ListNavigation.withRepeat(true) {
            repeat(10) { index = ListNavigation.move(index, -1, 4) }
        }
        assertEquals(0, index)
        assertEquals(3, ListNavigation.move(index, -1, 4))
        assertEquals(0, ListNavigation.move(3, 1, 4))
    }

    @Test fun `multi item moves clamp even without repeat context`() {
        assertEquals(3, ListNavigation.move(2, 10, 4))
        assertEquals(0, ListNavigation.move(1, -10, 4))
        assertEquals(3, ListNavigation.move(3, 2, 4))
        assertEquals(0, ListNavigation.move(0, -2, 4))
        assertEquals(3, ListNavigation.move(3, Int.MAX_VALUE, 4))
        assertEquals(0, ListNavigation.move(0, Int.MIN_VALUE, 4))
    }

    @Test fun `empty and single item lists remain safe`() {
        for (delta in listOf(-2, -1, 0, 1, 2)) {
            assertEquals(0, ListNavigation.move(0, delta, 0))
            assertEquals(0, ListNavigation.move(0, delta, 1))
            ListNavigation.withRepeat(true) {
                assertEquals(0, ListNavigation.move(0, delta, 1))
            }
        }
    }

    @Test fun `repeat scope is restored after nested dispatch and failures`() {
        ListNavigation.withRepeat(true) {
            assertEquals(2, ListNavigation.move(2, 1, 3))
            ListNavigation.withRepeat(false) {
                assertEquals(0, ListNavigation.move(2, 1, 3))
            }
            assertEquals(2, ListNavigation.move(2, 1, 3))
        }
        runCatching { ListNavigation.withRepeat(true) { error("dispatch failed") } }
        assertEquals(0, ListNavigation.move(2, 1, 3))
    }
}
