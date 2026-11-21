package com.timeout.app.ui

/** Balanced full rows. There are no placeholder cells in the final row. */
fun choiceRowSizes(count: Int, availableWidth: Float, minimumWidth: Float, gap: Float): List<Int> {
    require(count >= 0 && availableWidth >= 0 && minimumWidth > 0 && gap >= 0)
    if (count == 0) return emptyList()
    val columns = ((availableWidth + gap) / (minimumWidth + gap)).toInt().coerceIn(1, count)
    val rows = (count + columns - 1) / columns
    val base = count / rows
    val extra = count % rows
    return List(rows) { base + if (it < extra) 1 else 0 }
}
