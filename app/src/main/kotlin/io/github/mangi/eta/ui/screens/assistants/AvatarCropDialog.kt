package io.github.mangi.eta.ui.screens.assistants

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val MAX_DECODE_EDGE = 2048

@Composable
internal fun AvatarCropDialog(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    BackHandler(onBack = onCancel)
    var viewportSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap) { mutableStateOf(MIN_ZOOM) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    fun updateZoom(nextZoom: Float) {
        val bounded = nextZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = bounded / zoom
        zoom = bounded
        pan = clampPan(bitmap, viewportSize, bounded, Offset(pan.x * factor, pan.y * factor))
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            val nextZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val zoomFactor = nextZoom / zoom
                            zoom = nextZoom
                            pan = clampPan(
                                bitmap = bitmap,
                                viewport = viewportSize,
                                zoom = nextZoom,
                                requested = Offset(
                                    (pan.x + panChange.x) * zoomFactor,
                                    (pan.y + panChange.y) * zoomFactor,
                                ),
                            )
                        }
                    },
            ) {
                if (viewportSize.width <= 0 || viewportSize.height <= 0) return@Canvas
                val diameter = cropDiameter(viewportSize)
                val scale = coverScale(bitmap, diameter) * zoom
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val left = size.width / 2f + pan.x - drawWidth / 2f
                val top = size.height / 2f + pan.y - drawHeight / 2f
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1)),
                )
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = diameter / 2f
                val overlay = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center.x - radius,
                            center.y - radius,
                            center.x + radius,
                            center.y + radius,
                        ),
                    )
                }
                drawPath(overlay, Color.Black.copy(alpha = 0.58f))
                val cropPath = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center.x - radius,
                            center.y - radius,
                            center.x + radius,
                            center.y + radius,
                        ),
                    )
                }
                clipPath(cropPath) {
                    val grid = Color.White.copy(alpha = 0.4f)
                    for (fraction in listOf(1f / 3f, 2f / 3f)) {
                        val x = center.x - radius + diameter * fraction
                        val y = center.y - radius + diameter * fraction
                        drawLine(grid, Offset(x, center.y - radius), Offset(x, center.y + radius), 1.dp.toPx())
                        drawLine(grid, Offset(center.x - radius, y), Offset(center.x + radius, y), 1.dp.toPx())
                    }
                }
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.assistant_crop_title),
                    color = Color.White,
                    style = MiuixTheme.textStyles.title3,
                )
                IconButton(
                    onClick = {
                        createAvatarCrop(bitmap, viewportSize, zoom, pan)?.let(onConfirm)
                    },
                    enabled = viewportSize.width > 0 && viewportSize.height > 0,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.action_save),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

internal fun decodeAvatarBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_EDGE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null
    val orientation = resolver.openInputStream(uri)?.use { stream ->
        runCatching {
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    applyExifOrientation(decoded, orientation)
}.getOrNull()

private fun cropDiameter(viewport: IntSize): Float {
    if (viewport.width <= 0 || viewport.height <= 0) return 0f
    return min(viewport.width * 0.86f, viewport.height * 0.72f).coerceAtLeast(1f)
}

private fun coverScale(bitmap: Bitmap, diameter: Float): Float = max(
    diameter / bitmap.width.coerceAtLeast(1),
    diameter / bitmap.height.coerceAtLeast(1),
)

private fun clampPan(
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
    requested: Offset,
): Offset {
    val diameter = cropDiameter(viewport)
    if (diameter <= 0f) return Offset.Zero
    val scale = coverScale(bitmap, diameter) * zoom
    val maxX = ((bitmap.width * scale - diameter) / 2f).coerceAtLeast(0f)
    val maxY = ((bitmap.height * scale - diameter) / 2f).coerceAtLeast(0f)
    return Offset(requested.x.coerceIn(-maxX, maxX), requested.y.coerceIn(-maxY, maxY))
}

private fun createAvatarCrop(
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
    pan: Offset,
): Bitmap? = runCatching {
    val diameter = cropDiameter(viewport)
    if (diameter <= 0f) return@runCatching null
    val scale = coverScale(bitmap, diameter) * zoom
    val sourceSide = (diameter / scale).roundToInt().coerceIn(1, min(bitmap.width, bitmap.height))
    val scaledWidth = bitmap.width * scale
    val scaledHeight = bitmap.height * scale
    val imageLeft = viewport.width / 2f + pan.x - scaledWidth / 2f
    val imageTop = viewport.height / 2f + pan.y - scaledHeight / 2f
    val cropLeft = (viewport.width - diameter) / 2f
    val cropTop = (viewport.height - diameter) / 2f
    val sourceLeft = ((cropLeft - imageLeft) / scale).roundToInt().coerceIn(0, bitmap.width - sourceSide)
    val sourceTop = ((cropTop - imageTop) / scale).roundToInt().coerceIn(0, bitmap.height - sourceSide)
    Bitmap.createBitmap(bitmap, sourceLeft, sourceTop, sourceSide, sourceSide)
}.getOrNull()

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    val transformed = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { matrix.setScale(-1f, 1f); true }
        ExifInterface.ORIENTATION_ROTATE_180 -> { matrix.setRotate(180f); true }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setScale(1f, -1f); true }
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f); true }
        ExifInterface.ORIENTATION_ROTATE_90 -> { matrix.setRotate(90f); true }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f); true }
        ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.setRotate(-90f); true }
        else -> false
    }
    if (!transformed) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
