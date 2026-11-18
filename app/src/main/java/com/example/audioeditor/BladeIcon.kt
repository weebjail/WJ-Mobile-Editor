package com.example.audioeditor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A razor blade, tilted like Resolve's blade tool: the split-clip-at-playhead icon.
 * A double-edged blade with a notch at each end and a slot through the middle (the slot is a hole,
 * drawn with the even-odd fill rule). The colour is replaced by the Icon's tint.
 */
val BladeIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Blade", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            group(rotate = -45f, pivotX = 12f, pivotY = 12f) {
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                    // Body, with a round notch cut into each short end.
                    moveTo(5f, 8f)
                    horizontalLineTo(19f)
                    quadTo(20f, 8f, 20f, 9f)
                    verticalLineTo(10.5f)
                    arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 13.5f)
                    verticalLineTo(15f)
                    quadTo(20f, 16f, 19f, 16f)
                    horizontalLineTo(5f)
                    quadTo(4f, 16f, 4f, 15f)
                    verticalLineTo(13.5f)
                    arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 4f, y1 = 10.5f)
                    verticalLineTo(9f)
                    quadTo(4f, 8f, 5f, 8f)
                    close()
                    // The slot through the middle.
                    moveTo(8f, 11.25f)
                    horizontalLineTo(16f)
                    verticalLineTo(12.75f)
                    horizontalLineTo(8f)
                    close()
                }
            }
        }
        .build()
}
