package dev.limit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapsuleGeometryTest {
    private fun place(
        camera: CapsuleBounds? = CapsuleBounds(190, 0, 210, 24),
        width: Int = 400,
        height: Int = 800,
        textWidth: Int = 40,
        textHeight: Int = 16,
        density: Float = 1f,
    ) = CameraCapsuleGeometry.place(width, height, 28, camera, textWidth, textHeight, density)

    @Test fun `centred camera leaves minutes on left clear of right status icons`() {
        val result = place()
        assertEquals(result.camera.left - 4, result.window.right)
        assertEquals(52, result.window.width)
        assertEquals(20, result.window.height)
        assertEquals(40, result.text.width)
        assertEquals(14, (result.window.top + result.window.bottom) / 2)
        assertContained(result, 400, 800)
    }

    @Test fun `no exposed cutout uses centred estimated camera at status bar height`() {
        val result = place(camera = null)
        assertEquals(CapsuleBounds(190, 4, 210, 24), result.camera)
        assertEquals(14, (result.window.top + result.window.bottom) / 2)
        assertTrue(result.window.right < 190)
        assertContained(result, 400, 800)
    }

    @Test fun `font scale and long minute strings stay readable without crossing camera`() {
        val short = place()
        val long = place(textWidth = 110, textHeight = 36, density = 2f)
        assertTrue(long.window.width > short.window.width)
        assertTrue(long.window.height >= long.text.height + 8)
        assertEquals(110, long.text.width)
        assertEquals(long.camera.left - 8, long.window.right)
        assertContained(long, 400, 800)
    }

    @Test fun `rotated right edge camera keeps minutes to left`() {
        val result = place(CapsuleBounds(776, 186, 800, 214), width = 800, height = 400)
        assertEquals(result.camera.left - 4, result.window.right)
        assertEquals(200, (result.window.top + result.window.bottom) / 2)
        assertContained(result, 800, 400)
    }

    @Test fun `rotated left edge camera places minutes to right`() {
        val result = place(CapsuleBounds(0, 186, 24, 214), width = 800, height = 400)
        assertEquals(result.camera.right + 4, result.window.left)
        assertEquals(200, (result.window.top + result.window.bottom) / 2)
        assertContained(result, 800, 400)
    }

    @Test fun `extreme text width clips to available side without covering camera`() {
        val result = place(textWidth = 1_000)
        assertTrue(result.text.width < 1_000)
        assertTrue(result.window.right <= result.camera.left - 4)
        assertContained(result, 400, 800)
    }

    @Test fun `stale rotated cutout bounds fall back when outside current display`() {
        val result = place(CapsuleBounds(776, 186, 800, 214))
        assertEquals(CapsuleBounds(190, 4, 210, 24), result.camera)
        assertContained(result, 400, 800)
    }

    @Test fun `degenerate display dimensions remain bounded`() {
        val result = place(camera = null, width = 0, height = 0)
        assertContained(result, 1, 1)
    }

    @Test fun `Redmi tall cutout safety rectangle cannot inflate glass pill or cover network icons`() {
        val result = CameraCapsuleGeometry.place(1220, 2712, 80,
            CapsuleBounds(580, 0, 640, 144), 88, 36, 3f)
        assertEquals(60, result.window.height)
        assertEquals(124, result.window.width)
        assertEquals(40, (result.window.top + result.window.bottom) / 2)
        assertTrue(result.window.top > 0)
        assertTrue(result.window.bottom <= 80)
        assertTrue(result.window.right < 580)
        assertContained(result, 1220, 2712)
    }

    @Test fun `top safety rectangle height does not move or resize readout`() {
        val shallow = place(CapsuleBounds(184, 0, 216, 28))
        val tall = place(CapsuleBounds(184, 0, 216, 80))
        assertEquals(shallow.window, tall.window)
        assertEquals(shallow.text, tall.text)
        assertEquals(20, tall.camera.height)
    }

    @Test fun `off centre camera uses right side when label cannot fit on left`() {
        val result = place(CapsuleBounds(60, 0, 80, 28), textWidth = 100)
        assertEquals(100, result.text.width)
        assertEquals(result.camera.right + 4, result.window.left)
        assertContained(result, 400, 800)
    }

    private fun assertContained(result: CameraCapsulePlacement, width: Int, height: Int) {
        assertTrue(result.window.left >= 0)
        assertTrue(result.window.top >= 0)
        assertTrue(result.window.right <= width)
        assertTrue(result.window.bottom <= height)
        assertTrue(result.text.left >= 0)
        assertTrue(result.text.top >= 0)
        assertTrue(result.text.right <= result.window.width)
        assertTrue(result.text.bottom <= result.window.height)
    }
}
