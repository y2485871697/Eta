package io.github.mangi.eta.agent.device

import org.json.JSONObject

/** 越界或非法坐标系导致的坐标拒绝；携带稳定的错误码，供工具层如实上报。 */
internal class VirtualDisplayCoordinateRejection(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

/** 已解析到副屏真实像素空间的坐标点。 */
internal data class VirtualDisplayPoint(val x: Int, val y: Int)

/**
 * 虚拟屏截图坐标合约。
 *
 * 发送给模型的截图会按既有视觉预算缩放，因此截图尺寸与副屏真实像素尺寸可能不同：
 * - `screenshot`：最近一次 observe_screen 附图的像素坐标，按比例映射到副屏像素；
 * - `screen`：副屏真实像素坐标，显式直通。
 *
 * 默认按 `screenshot` 映射；未知坐标系与越界坐标一律拒绝。
 * 本合约只声明本进程造成的缩放（screen / screenshot）；供应商或后端可能追加的渲染缩放
 * 是本进程不可知的，不做任何假设。
 */
internal class VirtualDisplayCoordinateContract(
    val screenWidth: Int,
    val screenHeight: Int,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
) {
    init {
        require(screenWidth > 0 && screenHeight > 0) { "screen size must be positive" }
        require(screenshotWidth > 0 && screenshotHeight > 0) { "screenshot size must be positive" }
    }

    /** 解析一个坐标；[space] 为空表示默认的 `screenshot` 空间。 */
    fun resolve(space: String?, x: Int, y: Int): VirtualDisplayPoint {
        val normalized = space?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        return when {
            normalized.isEmpty() || normalized == SPACE_SCREENSHOT -> fromScreenshot(x, y)
            normalized == SPACE_SCREEN -> fromScreen(x, y)
            else -> throw VirtualDisplayCoordinateRejection(
                "UNKNOWN_COORDINATE_SPACE",
                "unsupported coordinate_space: ${space?.trim()}",
            )
        }
    }

    /** tap_area：两个端点都必须有效，中心取映射到屏幕后两端点的中点。 */
    fun resolveArea(space: String?, x1: Int, y1: Int, x2: Int, y2: Int): VirtualDisplayPoint {
        val first = resolve(space, x1, y1)
        val second = resolve(space, x2, y2)
        return VirtualDisplayPoint(
            ((first.x.toLong() + second.x) / 2).toInt(),
            ((first.y.toLong() + second.y) / 2).toInt(),
        )
    }

    /** `screenshot` 坐标：越界拒绝，按比例映射到副屏像素（比例可为非整数）。 */
    fun fromScreenshot(x: Int, y: Int): VirtualDisplayPoint {
        if (x < 0 || x >= screenshotWidth || y < 0 || y >= screenshotHeight) {
            throw VirtualDisplayCoordinateRejection(
                "COORDINATE_OUT_OF_BOUNDS",
                "screenshot coordinates out of bounds: ($x,$y) not in ${screenshotWidth}x$screenshotHeight",
            )
        }
        return VirtualDisplayPoint(
            x = (x.toLong() * screenWidth / screenshotWidth).toInt(),
            y = (y.toLong() * screenHeight / screenshotHeight).toInt(),
        )
    }

    /** `screen` 坐标：越界拒绝，副屏真实像素直通。 */
    fun fromScreen(x: Int, y: Int): VirtualDisplayPoint {
        if (x < 0 || x >= screenWidth || y < 0 || y >= screenHeight) {
            throw VirtualDisplayCoordinateRejection(
                "COORDINATE_OUT_OF_BOUNDS",
                "screen coordinates out of bounds: ($x,$y) not in ${screenWidth}x$screenHeight",
            )
        }
        return VirtualDisplayPoint(x, y)
    }

    fun toContractJson(): JSONObject = JSONObject()
        .put("default_coordinate_space", SPACE_SCREENSHOT)
        .put("screenshot", JSONObject().put("width", screenshotWidth).put("height", screenshotHeight))
        .put("screen", JSONObject().put("width", screenWidth).put("height", screenHeight))
        .put(
            "scale_to_screen",
            JSONObject()
                .put("x", screenWidth.toDouble() / screenshotWidth)
                .put("y", screenHeight.toDouble() / screenshotHeight),
        )
        .put(
            "note",
            "tap、tap_area、long_press、swipe 默认接收截图像素坐标，按 scale_to_screen 映射到副屏像素；" +
                "coordinate_space=screen 时按副屏真实像素直通",
        )

    companion object {
        const val SPACE_SCREENSHOT = "screenshot"
        const val SPACE_SCREEN = "screen"
    }
}

/**
 * 最近一次虚拟屏观察的坐标基础。
 *
 * 观察失败会整体作废（[invalidate]），不会保留旧的可用坐标；帧尺寸变化后，后续输入一律
 * 按新一帧的边界与比例校验；输入前需另行比对 owner 当前尺寸，不能推断模型坐标的来源。
 */
internal class VirtualDisplayObservation {
    var contract: VirtualDisplayCoordinateContract? = null
        private set

    val observed: Boolean get() = contract != null

    fun record(next: VirtualDisplayCoordinateContract) {
        contract = next
    }

    fun invalidate() {
        contract = null
    }

    fun validateFrame(width: Int, height: Int) {
        val previous = require()
        if (width != previous.screenWidth || height != previous.screenHeight) {
            invalidate()
            throw VirtualDisplayCoordinateRejection("VIRTUAL_FRAME_CHANGED", "observe the resized virtual display again")
        }
    }


    fun require(): VirtualDisplayCoordinateContract = contract
        ?: throw VirtualDisplayCoordinateRejection(
            "NO_VIRTUAL_OBSERVATION",
            "observe current virtual screen first",
        )

    fun resolve(space: String?, x: Int, y: Int): VirtualDisplayPoint =
        require().resolve(space, x, y)

    fun resolveArea(space: String?, x1: Int, y1: Int, x2: Int, y2: Int): VirtualDisplayPoint =
        require().resolveArea(space, x1, y1, x2, y2)
}
