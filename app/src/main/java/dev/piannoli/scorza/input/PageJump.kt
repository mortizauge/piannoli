package dev.piannoli.scorza.input

import androidx.compose.foundation.lazy.LazyListState

object PageJump {

    fun compute(direction: Int, itemCount: Int, selectedIndex: Int, listState: LazyListState?): Int {
        if (itemCount == 0) return selectedIndex
        val lastIndex = itemCount - 1
        val viewport = readViewport(listState, lastIndex)
        val page = (viewport.last - viewport.first + 1).coerceAtLeast(1)

        return if (direction > 0) {
            when {
                selectedIndex < viewport.last -> viewport.last.coerceAtMost(lastIndex)
                selectedIndex >= lastIndex -> selectedIndex
                else -> (selectedIndex + page).coerceAtMost(lastIndex)
            }
        } else {
            when {
                selectedIndex > viewport.first -> viewport.first.coerceAtLeast(0)
                selectedIndex <= 0 -> selectedIndex
                else -> (selectedIndex - page).coerceAtLeast(0)
            }
        }
    }

    private data class Viewport(val first: Int, val last: Int)

    private fun readViewport(listState: LazyListState?, lastIndex: Int): Viewport {
        if (listState == null) return Viewport(0, lastIndex.coerceAtLeast(0))
        val info = listState.layoutInfo
        val viewportEnd = info.viewportEndOffset
        val fully = info.visibleItemsInfo.filter {
            it.offset >= 0 && it.offset + it.size <= viewportEnd
        }
        if (fully.isEmpty()) {
            val first = listState.firstVisibleItemIndex
            return Viewport(first, first)
        }
        return Viewport(fully.first().index, fully.last().index)
    }
}
