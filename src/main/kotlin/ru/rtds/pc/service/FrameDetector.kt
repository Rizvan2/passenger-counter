package ru.rtds.pc.service

import ru.rtds.pc.model.Detection

interface FrameDetector {
    val id: String
    val inputSize: Int

    fun detect(letterboxed: FloatArray, origWidth: Int, origHeight: Int): List<Detection>
}
