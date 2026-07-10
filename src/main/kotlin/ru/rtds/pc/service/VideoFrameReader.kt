package ru.rtds.pc.service

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

data class VideoFrameSnapshot(
    val frameIndex: Int,
    val image: BufferedImage,
    val width: Int,
    val height: Int,
)

@Service
class VideoFrameReader(
    // Минимальный размер стороны кадра для анализа.
    // Если видео меньше — апскейлим до этого значения, сохраняя пропорции.
    // Все абсолютные пороги (door-zone-px, anchor-movement-px, center-distance-px)
    // рассчитаны под кадры ~480–720px высотой; при меньшем разрешении они ломаются.
    @Value("\${pc.min-frame-height:480}") private val minFrameHeight: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(
        sourcePath: String,
        skipFrames: Int = 2,
        onFrame: (frameIndex: Int, image: BufferedImage, width: Int, height: Int) -> Boolean,
    ): Boolean {
        val grabber = FFmpegFrameGrabber(sourcePath)
        if (sourcePath.startsWith("rtsp://")) {
            grabber.setOption("rtsp_transport", "tcp")
            grabber.setOption("stimeout", "5000000")
        }
        val converter = Java2DFrameConverter()
        try {
            grabber.start()
            val origW = grabber.imageWidth
            val origH = grabber.imageHeight
            val scale = upscaleRatio(origW, origH)
            val outW = (origW * scale).toInt()
            val outH = (origH * scale).toInt()

            if (scale > 1.0f) {
                log.info(
                    "Opened: {} ({}x{} @ {} fps) — upscaling to {}x{} (ratio={}) for analysis",
                    sourcePath, origW, origH, grabber.frameRate, outW, outH,
                    "%.2f".format(scale),
                )
            } else {
                log.info(
                    "Opened: {} ({}x{} @ {} fps)",
                    sourcePath, origW, origH, grabber.frameRate,
                )
            }

            var idx = 0
            while (true) {
                val frame: Frame = grabber.grabImage() ?: break
                if (idx % (skipFrames + 1) == 0) {
                    val raw = converter.convert(frame) ?: continue
                    // Апскейл если нужен; иначе отдаём как есть (без лишней копии)
                    val img = if (scale > 1.0f) upscale(raw, outW, outH) else raw
                    val carry = onFrame(idx, img, outW, outH)
                    if (!carry) return false
                }
                idx++
            }
            log.info("Finished: {} frames", idx)
            return true
        } catch (e: Exception) {
            log.error("Frame reader error: {}", e.message, e)
            throw e
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /**
     * Вычисляет коэффициент апскейла так, чтобы меньшая сторона была >= minFrameHeight.
     * Возвращает 1.0 если видео уже достаточно большое.
     */
    fun readFrame(sourcePath: String, requestedFrameIndex: Int): VideoFrameSnapshot? {
        val targetFrameIndex = requestedFrameIndex.coerceAtLeast(0)
        var snapshot: VideoFrameSnapshot? = null
        process(sourcePath, skipFrames = 0) { frameIndex, image, width, height ->
            if (frameIndex >= targetFrameIndex) {
                snapshot = VideoFrameSnapshot(frameIndex, image, width, height)
                false
            } else {
                true
            }
        }
        return snapshot
    }

    fun upscaleRatio(width: Int, height: Int): Float {
        val minSide = minOf(width, height)
        return if (minSide < minFrameHeight) minFrameHeight.toFloat() / minSide else 1.0f
    }

    private fun upscale(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        // BILINEAR достаточно для апскейла видео и быстрее BICUBIC
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return dst
    }

    /**
     * Letterbox-ресайз и нормализация для YOLO.
     */
    fun preprocess(img: BufferedImage, targetSize: Int): FloatArray {
        val w = img.width
        val h = img.height
        val gain = minOf(targetSize.toFloat() / w, targetSize.toFloat() / h)
        val newW = (w * gain).toInt()
        val newH = (h * gain).toInt()
        val padX = (targetSize - newW) / 2
        val padY = (targetSize - newH) / 2

        val resized = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.color = java.awt.Color(114, 114, 114)
        g.fillRect(0, 0, targetSize, targetSize)
        g.drawImage(img, padX, padY, newW, newH, null)
        g.dispose()

        val out = FloatArray(3 * targetSize * targetSize)
        val pixels = IntArray(targetSize * targetSize)
        resized.getRGB(0, 0, targetSize, targetSize, pixels, 0, targetSize)
        val plane = targetSize * targetSize
        for (i in 0 until plane) {
            val rgb = pixels[i]
            out[i] = ((rgb shr 16) and 0xFF) / 255f
            out[plane + i] = ((rgb shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (rgb and 0xFF) / 255f
        }
        return out
    }

    fun encodeJpegBase64(img: BufferedImage, quality: Float): String {
        val maxW = 800
        val src = if (img.width > maxW) {
            val ratio = maxW.toFloat() / img.width
            val h = (img.height * ratio).toInt()
            val small = BufferedImage(maxW, h, BufferedImage.TYPE_INT_RGB)
            val g = small.createGraphics()
            g.drawImage(img, 0, 0, maxW, h, null)
            g.dispose()
            small
        } else img

        val baos = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        MemoryCacheImageOutputStream(baos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(src, null, null), params)
            writer.dispose()
        }
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
