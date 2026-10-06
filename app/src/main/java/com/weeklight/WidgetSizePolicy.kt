package com.weeklight

internal object WidgetSizePolicy {
    const val MIN_WIDTH = 110
    private const val GRID_MIN_WIDTH = 220
    private const val GRID_BASE_HEIGHT = 92
    private const val WEEK_HEIGHT = 48
    const val LIST_MIN_HEIGHT = 32

    fun maxWeeks(width: Int, height: Int): Int =
        if (width < GRID_MIN_WIDTH) 0
        else ((height - GRID_BASE_HEIGHT - LIST_MIN_HEIGHT) / WEEK_HEIGHT).coerceIn(0, 8)

    fun visibleWeeks(width: Int, height: Int, configuredWeeks: Int): Int =
        maxWeeks(width, height).let { if (it == 0) configuredWeeks else minOf(configuredWeeks, it) }
}