package io.github.mangi.eta.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val MoneyBag02: ImageVector
    get() {
        if (_moneyBag02 != null) {
            return _moneyBag02!!
        }
        _moneyBag02 = ImageVector.Builder(
            name = "MoneyBag02",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF141B34)),
            fillAlpha = 1f,
            strokeAlpha = 1f,
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(20.9427f, 16.8354f)
            curveTo(20.2864f, 12.8866f, 18.2432f, 9.94613f, 16.467f, 8.219f)
            curveTo(15.9501f, 7.71642f, 15.6917f, 7.46513f, 15.1208f, 7.23257f)
            curveTo(14.5499f, 7f, 14.0592f, 7f, 13.0778f, 7f)
            horizontalLineTo(10.9222f)
            curveTo(9.94081f, 7f, 9.4501f, 7f, 8.87922f, 7.23257f)
            curveTo(8.30834f, 7.46513f, 8.04991f, 7.71642f, 7.53304f, 8.219f)
            curveTo(5.75682f, 9.94613f, 3.71361f, 12.8866f, 3.05727f, 16.8354f)
            curveTo(2.56893f, 19.7734f, 5.27927f, 22f, 8.30832f, 22f)
            horizontalLineTo(15.6917f)
            curveTo(18.7207f, 22f, 21.4311f, 19.7734f, 20.9427f, 16.8354f)
            close()
        }

        path(
            fill = null,
            stroke = SolidColor(Color(0xFF141B34)),
            fillAlpha = 1f,
            strokeAlpha = 1f,
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(7.25662f, 4.44287f)
            curveTo(7.05031f, 4.14258f, 6.75128f, 3.73499f, 7.36899f, 3.64205f)
            curveTo(8.00392f, 3.54651f, 8.66321f, 3.98114f, 9.30855f, 3.97221f)
            curveTo(9.89237f, 3.96413f, 10.1898f, 3.70519f, 10.5089f, 3.33548f)
            curveTo(10.8449f, 2.94617f, 11.3652f, 2f, 12f, 2f)
            curveTo(12.6348f, 2f, 13.1551f, 2.94617f, 13.4911f, 3.33548f)
            curveTo(13.8102f, 3.70519f, 14.1076f, 3.96413f, 14.6914f, 3.97221f)
            curveTo(15.3368f, 3.98114f, 15.9961f, 3.54651f, 16.631f, 3.64205f)
            curveTo(17.2487f, 3.73499f, 16.9497f, 4.14258f, 16.7434f, 4.44287f)
            lineTo(15.8105f, 5.80064f)
            curveTo(15.4115f, 6.38146f, 15.212f, 6.67187f, 14.7944f, 6.83594f)
            curveTo(14.3769f, 7f, 13.8373f, 7f, 12.7582f, 7f)
            horizontalLineTo(11.2418f)
            curveTo(10.1627f, 7f, 9.6231f, 7f, 9.20556f, 6.83594f)
            curveTo(8.78802f, 6.67187f, 8.5885f, 6.38146f, 8.18945f, 5.80064f)
            lineTo(7.25662f, 4.44287f)
            close()
        }

        path(
            fill = null,
            stroke = SolidColor(Color(0xFF141B34)),
            fillAlpha = 1f,
            strokeAlpha = 1f,
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(13.6267f, 12.9186f)
            curveTo(13.4105f, 12.1205f, 12.3101f, 11.4003f, 10.9892f, 11.9391f)
            curveTo(9.66829f, 12.4778f, 9.45847f, 14.2113f, 11.4565f, 14.3955f)
            curveTo(12.3595f, 14.4787f, 12.9483f, 14.2989f, 13.4873f, 14.8076f)
            curveTo(14.0264f, 15.3162f, 14.1265f, 16.7308f, 12.7485f, 17.112f)
            curveTo(11.3705f, 17.4932f, 10.006f, 16.8976f, 9.85742f, 16.0517f)
            moveTo(11.8417f, 10.9927f)
            verticalLineTo(11.7531f)
            moveTo(11.8417f, 17.2293f)
            verticalLineTo(17.9927f)
        }
        }.build()

        return _moneyBag02!!
    }

private var _moneyBag02: ImageVector? = null
