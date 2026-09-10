package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AssistantAvatar(
    assistant: AssistantProfile?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(assistant?.id, assistant?.avatarFileName) {
        AssistantRepository.avatarBitmap(assistant?.avatarFileName)
    }
    AssistantAvatar(bitmap = bitmap, size = size, modifier = modifier)
}

@Composable
internal fun AssistantAvatar(
    bitmap: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
