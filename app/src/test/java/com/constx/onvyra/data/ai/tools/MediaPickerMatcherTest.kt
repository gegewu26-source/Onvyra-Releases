package com.constx.onvyra.data.ai.tools

import org.junit.Assert.*
import org.junit.Test

class MediaPickerMatcherTest {
    private fun picture(width: Int = 120, height: Int = 120, seed: Int = 1) = PickerRaster(width, height,
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val r = (x / 9 * 73 + y / 13 * 41 + seed * 29) % 256
            val g = (x / 11 * 47 + y / 7 * 83 + seed * 53) % 256
            val b = (x / 17 * 97 + y / 5 * 31 + seed * 17) % 256
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        })
    private fun screen() = PickerRaster(360, 640, IntArray(360 * 640) { 0xff242424.toInt() })
    private fun paint(screen: PickerRaster, photo: PickerRaster, rect: PickerRect) {
        val aspect = rect.width.toDouble() / rect.height
        val cropW = minOf(photo.width.toDouble(), photo.height * aspect)
        val cropH = minOf(photo.height.toDouble(), photo.width / aspect)
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val sx = ((photo.width - cropW) / 2 + (x - rect.left + .5) * cropW / rect.width).toInt()
            val sy = ((photo.height - cropH) / 2 + (y - rect.top + .5) * cropH / rect.height).toInt()
            screen.pixels[y * screen.width + x] = photo.pixel(sx, sy)
        }
    }

    @Test fun exactSavedPhotoIsLocatedInsteadOfBlindlyChoosingTheFirstImage() {
        val photo = picture()
        val visible = screen()
        val first = PickerRect(0, 110, 120, 230)
        val correct = PickerRect(120, 110, 240, 230)
        paint(visible, picture(seed = 9), first)
        paint(visible, photo, correct)
        val result = matchSavedMediaThumbnail(photo, visible, listOf(first, correct))
        assertEquals("found", result.status)
        assertEquals(correct, result.candidates.first().rect)
        assertTrue(result.candidates.first().score >= .98)
    }

    @Test fun anUnlabelledThreeColumnPickerCanBeLocatedWithoutCloudVision() {
        val photo = picture()
        val visible = screen()
        val correct = PickerRect(0, 123, 120, 243)
        paint(visible, photo, correct)
        paint(visible, picture(seed = 7), PickerRect(120, 123, 240, 243))
        val result = matchSavedMediaThumbnail(photo, visible)
        assertEquals("found", result.status)
        assertTrue(kotlin.math.abs(result.candidates.first().rect.centerX - correct.centerX) <= 4)
        assertTrue(kotlin.math.abs(result.candidates.first().rect.centerY - correct.centerY) <= 4)
        assertTrue(result.evaluated < 20_000)
    }

    @Test fun twoMatchingPhotosAreAmbiguousInsteadOfInventingAUniqueMatch() {
        val photo = picture()
        val visible = screen()
        val first = PickerRect(0, 110, 120, 230)
        val second = PickerRect(120, 110, 240, 230)
        paint(visible, photo, first)
        paint(visible, photo, second)
        assertEquals("ambiguous", matchSavedMediaThumbnail(photo, visible, listOf(first, second)).status)
    }

    @Test fun aPlainImageCannotAuthorizeGuessingANearbyPrivatePhoto() {
        val white = PickerRaster(120, 120, IntArray(120 * 120) { -1 })
        assertEquals("not_distinctive", matchSavedMediaThumbnail(white, screen()).status)
    }

    @Test fun absentImagesReturnNotFoundWithoutSelectingAnyGridPosition() {
        assertEquals("not_found", matchSavedMediaThumbnail(picture(), screen()).status)
    }

    @Test fun portraitPhotosMatchThePickersCenterCrop() {
        val photo = picture(120, 240)
        val visible = screen()
        val rect = PickerRect(120, 110, 240, 230)
        paint(visible, photo, rect)
        assertEquals("found", matchSavedMediaThumbnail(photo, visible, listOf(rect)).status)
    }

    @Test fun logicalBoundsAreMappedIntoTheExactCapturedImageFrame() {
        val rect = pickerImageRect(listOf(144, 332, 300, 488), listOf(0, 100), 600, 1200, 300, 600)!!
        assertEquals(PickerRect(72, 116, 150, 194), rect)
        assertEquals(111, rect.centerX)
        assertEquals(155, rect.centerY)
        assertNull(pickerImageRect(listOf(1, 2), listOf(0, 0), 600, 1200, 300, 600))
    }

    @Test fun nativePickersWithoutAnAlbumHeadingStillNeedPhotoAndSelectionEvidence() {
        assertTrue(isVisibleMediaPicker("全部 照片 视频 下一步"))
        assertTrue(isVisibleMediaPicker("Select photos Done"))
        assertTrue(isVisibleMediaPicker("最近项目 选择"))
        assertFalse(isVisibleMediaPicker("图片已保存成功 返回"))
        assertFalse(isVisibleMediaPicker("首页 搜索 推荐 发布"))
    }

    @Test fun aCheckboxIsSelectedOnlyInsideTheActuallyMatchedThumbnail() {
        val photo = PickerRect(120, 110, 240, 230)
        val wrong = PickerSelector(PickerRect(90, 114, 115, 139), false)
        val correct = PickerSelector(PickerRect(210, 114, 235, 139), true)
        assertEquals(correct, selectorForMatchedThumbnail(photo, listOf(wrong, correct)))
        assertEquals(true, selectorForMatchedThumbnail(photo, listOf(wrong, correct))?.checked)
        assertNull(selectorForMatchedThumbnail(photo, listOf(wrong)))
    }
}
