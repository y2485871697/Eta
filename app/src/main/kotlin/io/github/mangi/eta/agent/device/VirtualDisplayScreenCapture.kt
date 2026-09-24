package io.github.mangi.eta.agent.device

import android.graphics.BitmapFactory
import android.util.Base64
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient

/**
 * 把副屏 snapshot 的原始 PNG 转成实际发给模型的截图，并据此发布坐标合约。
 *
 * 复用既有屏幕观察策略（[AgentImageCodec.fromScreenBytes] → 视觉预算内的 JPEG），因此
 * 发布出去的 screenshot 尺寸就是实际附件尺寸，而不是只把元数据改小。screen 尺寸取自
 * owner 报告的原始帧尺寸；两者不一致时，`scale_to_screen` 就是唯一的映射依据。
 */
internal object VirtualDisplayScreenCapture {
    data class Capture(
        val image: AgentModelClient.ModelImage,
        val contract: VirtualDisplayCoordinateContract,
    )

    fun capture(
        encodedPngBase64: String,
        frameWidth: Int,
        frameHeight: Int,
        source: String = "virtual_display",
    ): Capture {
        require(frameWidth > 0 && frameHeight > 0) { "invalid frame size" }
        val png = Base64.decode(encodedPngBase64, Base64.DEFAULT)
        require(png.isNotEmpty()) { "empty frame" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        require(bounds.outWidth == frameWidth && bounds.outHeight == frameHeight) {
            "frame metadata does not match encoded pixels"
        }

        // 走既有屏幕缩放：返回的 ModelImage 尺寸即实际发送尺寸。
        val image = AgentImageCodec.fromScreenBytes(png, source)
        val width = image.width ?: error("encoded frame has no width")
        val height = image.height ?: error("encoded frame has no height")
        return Capture(
            image = image,
            contract = VirtualDisplayCoordinateContract(
                screenWidth = frameWidth,
                screenHeight = frameHeight,
                screenshotWidth = width,
                screenshotHeight = height,
            ),
        )
    }
}
