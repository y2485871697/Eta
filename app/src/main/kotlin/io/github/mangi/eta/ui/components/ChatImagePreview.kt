package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

internal val LocalOpenChatImagePreview = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
internal fun ChatImagePreviewHost(content: @Composable () -> Unit) {
    var source by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalOpenChatImagePreview provides { source = it }) {
        content()
    }
    val current = source
    if (current != null) {
        ChatImagePreviewDialog(
            source = current,
            onDismiss = { source = null },
        )
    }
}

@Composable
internal fun ChatClickableImage(
    source: String,
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val openPreview = LocalOpenChatImagePreview.current
    val view = LocalView.current
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clickable {
            TouchHaptics.click(view)
            openPreview(source)
        },
    )
}

@Composable
internal fun ChatMarkdownImage(
    content: String,
    node: ASTNode,
    modifier: Modifier = Modifier,
    sourceOverride: String? = null,
    fillPlaceholder: Boolean = false,
) {
    val source = remember(content, node.startOffset, node.endOffset, sourceOverride) {
        resolveChatImageSource(content, node, sourceOverride)
    } ?: return
    val imageModifier = if (fillPlaceholder) {
        modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(14.dp))
    }
    DisableSelection {
        ChatRemoteClickableImage(
            source = source,
            modifier = imageModifier,
            contentScale = if (fillPlaceholder) ContentScale.Crop else ContentScale.FillWidth,
            compactLoading = fillPlaceholder,
        )
    }
}

@Composable
private fun ChatRemoteClickableImage(
    source: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    compactLoading: Boolean = false,
) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        val loaded = withContext(Dispatchers.IO) { ChatImageBytes.load(context, source) }
        bitmap = loaded?.bitmap
        failed = loaded == null
    }
    val image = bitmap
    when {
        image != null -> ChatClickableImage(
            source = source,
            bitmap = image,
            contentDescription = stringResource(R.string.chat_image_preview),
            modifier = modifier,
            contentScale = contentScale,
        )
        failed -> Unit
        else -> Box(
            modifier = if (compactLoading) {
                modifier.background(MiuixTheme.colorScheme.surfaceContainer)
            } else {
                modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainer)
            },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ChatImagePreviewDialog(
    source: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var loaded by remember(source) { mutableStateOf<LoadedChatImage?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var scale by remember(source) { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(source) {
        val result = withContext(Dispatchers.IO) { ChatImageBytes.load(context, source) }
        loaded = result
        failed = result == null
    }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val image = loaded
            when {
                image != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(source) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    scale = nextScale
                                    offset = if (nextScale == MIN_ZOOM) {
                                        Offset.Zero
                                    } else {
                                        offset + pan
                                    }
                                }
                            },
                    ) {
                        Image(
                            bitmap = image.bitmap,
                            contentDescription = stringResource(R.string.chat_image_preview),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y,
                                ),
                        )
                    }
                }
                failed -> {
                    Text(
                        text = stringResource(R.string.chat_image_load_failed),
                        color = Color.White,
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        size = 28.dp,
                        strokeWidth = 2.5.dp,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.chat_image_preview),
                    color = Color.White,
                    style = MiuixTheme.textStyles.title3,
                )
                IconButton(
                    enabled = image != null && !saving,
                    onClick = {
                        val payload = loaded ?: return@IconButton
                        TouchHaptics.click(view)
                        saving = true
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                ChatImageGallery.save(context, payload.bytes, payload.mimeType)
                            }
                            saving = false
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (uri != null) {
                                        R.string.chat_image_saved
                                    } else {
                                        R.string.chat_image_save_failed
                                    },
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.action_save),
                        tint = Color.White,
                    )
                }
            }
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                    size = 20.dp,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
