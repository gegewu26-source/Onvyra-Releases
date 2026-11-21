package me.rerere.androidcontrol

/** Rectangles use exclusive right/bottom edges, unlike a touch point. */
internal fun sliderImageRectangle(rect: List<Int>, imageWidth: Int, imageHeight: Int,
    windowWidth: Int, windowHeight: Int): IntArray {
    require(rect.size == 4 && imageWidth > 0 && imageHeight > 0 && windowWidth > 0 && windowHeight > 0 &&
        rect[0] >= 0 && rect[1] >= 0 && rect[2] <= imageWidth && rect[3] <= imageHeight &&
        rect[2] > rect[0] && rect[3] > rect[1]) { "invalid_slider_region" }
    return intArrayOf((rect[0].toLong() * windowWidth / imageWidth).toInt(),
        (rect[1].toLong() * windowHeight / imageHeight).toInt(),
        (rect[2].toLong() * windowWidth / imageWidth).toInt(),
        (rect[3].toLong() * windowHeight / imageHeight).toInt()).also {
        require(it[2] > it[0] && it[3] > it[1]) { "invalid_slider_region" }
    }
}

internal fun sliderScaledDistance(distance: Int, imageWidth: Int, windowWidth: Int): Int {
    require(distance != 0 && imageWidth > 0 && windowWidth > 0) { "invalid_slider_distance" }
    val scaled = distance.toLong() * windowWidth / imageWidth
    require(scaled in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && scaled != 0L) { "invalid_slider_distance" }
    return scaled.toInt()
}
