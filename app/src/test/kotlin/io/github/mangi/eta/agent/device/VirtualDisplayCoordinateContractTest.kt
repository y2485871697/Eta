package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayCoordinateContractTest {
    private fun rejection(code: String, block: () -> Unit): VirtualDisplayCoordinateRejection {
        val error = assertThrows(VirtualDisplayCoordinateRejection::class.java) { block() }
        assertEquals(code, error.code)
        return error
    }

    @Test
    fun screenshotSpaceMapsWithNonIntegerRatioAndRejectsUnknownOrOutOfBounds() {
        // 3x3 截图映射到 100x100 屏幕：比例是非整数 33.33…
        val contract = VirtualDisplayCoordinateContract(
            screenWidth = 100, screenHeight = 100,
            screenshotWidth = 3, screenshotHeight = 3,
        )
        assertEquals(VirtualDisplayPoint(0, 0), contract.resolve("screenshot", 0, 0))
        assertEquals(VirtualDisplayPoint(33, 33), contract.resolve("screenshot", 1, 1))
        assertEquals(VirtualDisplayPoint(33, 66), contract.resolve("screenshot", 1, 2))
        assertEquals(VirtualDisplayPoint(66, 66), contract.resolve("screenshot", 2, 2)) // 最大合法端点

        // 缺省（null / 空字符串）落到 screenshot 空间。
        assertEquals(VirtualDisplayPoint(33, 33), contract.resolve(null, 1, 1))
        assertEquals(VirtualDisplayPoint(33, 33), contract.resolve("", 1, 1))

        // 越界与负坐标拒绝（端点 3 == 宽度）。
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolve("screenshot", 3, 1) }
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolve("screenshot", 1, 3) }
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolve("screenshot", -1, 0) }

        // 未知坐标系拒绝。
        rejection("UNKNOWN_COORDINATE_SPACE") { contract.resolve("device", 1, 1) }
        rejection("UNKNOWN_COORDINATE_SPACE") { contract.resolve("screens", 1, 1) }
    }

    @Test
    fun screenSpacePassesThroughAndValidatesAgainstScreenBounds() {
        val contract = VirtualDisplayCoordinateContract(
            screenWidth = 100, screenHeight = 100,
            screenshotWidth = 3, screenshotHeight = 3,
        )
        // 显式直通：不做缩放，只校验副屏边界（截图只有 3x3 也不影响）。
        assertEquals(VirtualDisplayPoint(0, 0), contract.resolve("screen", 0, 0))
        assertEquals(VirtualDisplayPoint(99, 99), contract.resolve("screen", 99, 99))
        assertEquals(VirtualDisplayPoint(50, 42), contract.resolve("screen", 50, 42))
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolve("screen", 100, 0) }
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolve("screen", 0, -1) }
    }

    @Test
    fun tapAreaValidatesBothEndpointsAndUsesTheirCenter() {
        val contract = VirtualDisplayCoordinateContract(
            screenWidth = 100, screenHeight = 100,
            screenshotWidth = 100, screenshotHeight = 100,
        )
        assertEquals(VirtualDisplayPoint(15, 20), contract.resolveArea(null, 10, 10, 20, 30))
        // 反向端点同样取中点。
        assertEquals(VirtualDisplayPoint(15, 20), contract.resolveArea(null, 20, 30, 10, 10))
        // 任一端点越界都必须拒绝（只校验中点会漏掉这种情况）。
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolveArea(null, 10, 10, 100, 30) }
        rejection("COORDINATE_OUT_OF_BOUNDS") { contract.resolveArea(null, 10, 10, 20, 100) }
    }

    @Test
    fun contractPublishesActualImageDimensionsAndScale() {
        val contract = VirtualDisplayCoordinateContract(
            screenWidth = 1216, screenHeight = 2640,
            screenshotWidth = 736, screenshotHeight = 1600,
        )
        val json = contract.toContractJson()
        assertEquals("screenshot", json.getString("default_coordinate_space"))
        assertEquals(736, json.getJSONObject("screenshot").getInt("width"))
        assertEquals(1600, json.getJSONObject("screenshot").getInt("height"))
        assertEquals(1216, json.getJSONObject("screen").getInt("width"))
        assertEquals(2640, json.getJSONObject("screen").getInt("height"))
        assertEquals(1216.0 / 736, json.getJSONObject("scale_to_screen").getDouble("x"), 1e-9)
        assertEquals(2640.0 / 1600, json.getJSONObject("scale_to_screen").getDouble("y"), 1e-9)
    }
}

class VirtualDisplayObservationTest {
    @Test
    fun failedObservationClearsPreviouslyValidCoordinates() {
        val observation = VirtualDisplayObservation()
        observation.record(VirtualDisplayCoordinateContract(100, 100, 10, 10))
        assertTrue(observation.observed)
        assertEquals(VirtualDisplayPoint(50, 50), observation.resolve("screenshot", 5, 5))

        observation.invalidate()
        assertFalse(observation.observed)
        val error = assertThrows(VirtualDisplayCoordinateRejection::class.java) {
            observation.resolve("screenshot", 5, 5)
        }
        assertEquals("NO_VIRTUAL_OBSERVATION", error.code)
        assertThrows(VirtualDisplayCoordinateRejection::class.java) {
            observation.resolveArea("screenshot", 1, 1, 2, 2)
        }
    }

    @Test
    fun frameSizeChangeRejectsStaleCoordinatesFromThePreviousFrame() {
        val observation = VirtualDisplayObservation()
        observation.record(VirtualDisplayCoordinateContract(1000, 1000, 100, 100))
        assertEquals(VirtualDisplayPoint(990, 990), observation.resolve("screenshot", 99, 99))

        // 新一帧截图变小：上一帧的坐标基础作废，必须按新边界校验。
        observation.record(VirtualDisplayCoordinateContract(1000, 1000, 50, 50))
        val error = assertThrows(VirtualDisplayCoordinateRejection::class.java) {
            observation.resolve("screenshot", 99, 99)
        }
        assertEquals("COORDINATE_OUT_OF_BOUNDS", error.code)
        assertEquals(VirtualDisplayPoint(40, 40), observation.resolve("screenshot", 2, 2))
    }

    @org.junit.Test fun changedLiveFrameInvalidatesObservation() {
        val observation = VirtualDisplayObservation()
        observation.record(VirtualDisplayCoordinateContract(1216, 2640, 737, 1600))
        org.junit.Assert.assertThrows(VirtualDisplayCoordinateRejection::class.java) {
            observation.validateFrame(2640, 1216)
        }
        org.junit.Assert.assertFalse(observation.observed)
    }
}
