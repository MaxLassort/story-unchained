package com.maxlass.studio.pack.format.utils

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prepares an arbitrary photo/illustration for the Lunii screen look without AI:
 * background removal (edge flood-fill), optional white stroke from alpha,
 * 320×240 letterbox on black, high-contrast grayscale on a short fixed palette.
 *
 * Output is a PNG suitable for archive storage and wizard preview; FS export
 * still applies [ImageConversion.anyToRLECompressedBitmap] later.
 */
object LuniiImagePrepare {

    const val WIDTH = 320
    const val HEIGHT = 240

    /** Stroke radius (px) drawn around opaque alpha before compositing on black. */
    private const val ALPHA_STROKE_RADIUS = 3

    /**
     * Max RGB channel distance to the estimated border background color for a
     * pixel to be treated as background during flood-fill.
     */
    private const val BG_COLOR_TOLERANCE = 42

    /**
     * Minimum fraction of border pixels that must agree on a background color
     * before flood-fill runs (avoids eating into busy full-bleed images).
     */
    private const val BG_BORDER_CONSENSUS = 0.55f

    /** Fixed grayscale palette matching official pack readability (black → white). */
    private val PALETTE = intArrayOf(0x000000, 0x404040, 0x808080, 0xC0C0C0, 0xFFFFFF)

    /** Sobel edge strength blended as white (0..1). Kept mild — strong edges look noisy. */
    private const val EDGE_STRENGTH = 0.28f

    /** Edges below this luminance gradient are ignored. */
    private const val EDGE_THRESHOLD = 55

    /**
     * Converts any ImageIO-readable image (PNG/JPEG/BMP…) into a stylized
     * 320×240 PNG optimized for the Lunii display.
     */
    fun prepare(data: ByteArray): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(data))
            ?: throw IOException("Failed to read image")
        val prepared = prepareBuffered(source)
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(prepared, "PNG", out)) {
            throw IOException("Failed to write prepared PNG")
        }
        return out.toByteArray()
    }

    internal fun prepareBuffered(source: BufferedImage): BufferedImage {
        val cutout = when {
            hasMeaningfulAlpha(source) -> source
            else -> removeSolidBackground(source)
        }
        val flattened = strokeAlphaThenFlatten(cutout)
        val fitted = ImageConversion.letterboxToDevice(flattened)
        return stylize(fitted)
    }

    private fun hasMeaningfulAlpha(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return false
        val w = image.width
        val h = image.height
        var transparent = 0
        var opaque = 0
        for (y in 0 until h step max(1, h / 40)) {
            for (x in 0 until w step max(1, w / 40)) {
                val a = (image.getRGB(x, y) ushr 24) and 0xFF
                if (a < 16) transparent++ else opaque++
            }
        }
        return transparent > 0 && opaque > 0
    }

    /**
     * Estimates the background from border pixels and flood-fills matching
     * connected regions from the edges to transparency. Leaves the image
     * unchanged when the border is not a coherent solid background.
     */
    internal fun removeSolidBackground(source: BufferedImage): BufferedImage {
        val rgb = toRgb(source)
        val w = rgb.width
        val h = rgb.height
        val bg = estimateBorderBackground(rgb) ?: return toArgb(rgb)

        val isBg = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        fun tryEnqueue(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val i = y * w + x
            if (isBg[i]) return
            if (!colorClose(rgb.getRGB(x, y) and 0xFFFFFF, bg, BG_COLOR_TOLERANCE)) return
            isBg[i] = true
            queue.add(i)
        }

        for (x in 0 until w) {
            tryEnqueue(x, 0)
            tryEnqueue(x, h - 1)
        }
        for (y in 0 until h) {
            tryEnqueue(0, y)
            tryEnqueue(w - 1, y)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w
            val y = i / w
            tryEnqueue(x - 1, y)
            tryEnqueue(x + 1, y)
            tryEnqueue(x, y - 1)
            tryEnqueue(x, y + 1)
        }

        val bgCount = isBg.count { it }
        // If "background" ate most of the image, abort (unsafe).
        if (bgCount > w * h * 0.92) return toArgb(rgb)

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (isBg[y * w + x]) {
                    out.setRGB(x, y, 0x00000000)
                } else {
                    out.setRGB(x, y, 0xFF000000.toInt() or (rgb.getRGB(x, y) and 0xFFFFFF))
                }
            }
        }
        return out
    }

    /**
     * Median-ish border color when a majority of edge samples agree; null otherwise.
     */
    private fun estimateBorderBackground(rgb: BufferedImage): Int? {
        val w = rgb.width
        val h = rgb.height
        val samples = ArrayList<Int>(w * 2 + h * 2)
        for (x in 0 until w) {
            samples.add(rgb.getRGB(x, 0) and 0xFFFFFF)
            samples.add(rgb.getRGB(x, h - 1) and 0xFFFFFF)
        }
        for (y in 1 until h - 1) {
            samples.add(rgb.getRGB(0, y) and 0xFFFFFF)
            samples.add(rgb.getRGB(w - 1, y) and 0xFFFFFF)
        }
        if (samples.isEmpty()) return null

        // Quantize to buckets so near-white JPEG noise still clusters.
        val buckets = HashMap<Int, Int>()
        for (c in samples) {
            val key = quantizeColor(c, 16)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val (bestKey, bestCount) = buckets.maxByOrNull { it.value } ?: return null
        if (bestCount < samples.size * BG_BORDER_CONSENSUS) return null

        // Average of samples that fall in the winning bucket → smoother seed color.
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (c in samples) {
            if (quantizeColor(c, 16) != bestKey) continue
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        if (n == 0) return null
        return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    private fun quantizeColor(rgb: Int, step: Int): Int {
        val r = ((rgb shr 16) and 0xFF) / step * step
        val g = ((rgb shr 8) and 0xFF) / step * step
        val b = (rgb and 0xFF) / step * step
        return (r shl 16) or (g shl 8) or b
    }

    private fun colorClose(a: Int, b: Int, tol: Int): Boolean {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db)) <= tol
    }

    private fun toRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, out.width, out.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun toArgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_ARGB) return source
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** Draws a white halo around opaque pixels, then composites on black. */
    private fun strokeAlphaThenFlatten(source: BufferedImage): BufferedImage {
        val w = source.width
        val h = source.height
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                mask[y * w + x] = ((source.getRGB(x, y) ushr 24) and 0xFF) >= 16
            }
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, w, h)
        } finally {
            g.dispose()
        }
        val r = ALPHA_STROKE_RADIUS
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x]) continue
                var near = false
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        if (mask[ny * w + nx] && dx * dx + dy * dy <= r * r) {
                            near = true
                            break
                        }
                    }
                    if (near) break
                }
                if (near) out.setRGB(x, y, 0xFFFFFF)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = source.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                if (a < 16) continue
                if (a >= 250) {
                    out.setRGB(x, y, argb and 0xFFFFFF)
                } else {
                    val bg = out.getRGB(x, y) and 0xFFFFFF
                    out.setRGB(x, y, blendRgb(bg, argb and 0xFFFFFF, a / 255f))
                }
            }
        }
        return out
    }

    private fun stylize(rgb: BufferedImage): BufferedImage {
        val w = rgb.width
        val h = rgb.height
        val gray = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                gray[y * w + x] = luminance(rgb.getRGB(x, y))
            }
        }
        stretchContrast(gray)
        val edges = sobelMagnitude(gray, w, h)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = gray[y * w + x]
                val e = edges[y * w + x]
                if (e >= EDGE_THRESHOLD) {
                    val boost = ((e / 255f) * EDGE_STRENGTH * 255f).roundToInt()
                    v = min(255, v + boost)
                }
                out.setRGB(x, y, quantizeToPalette(v))
            }
        }
        return out
    }

    private fun stretchContrast(gray: IntArray) {
        if (gray.isEmpty()) return
        val sorted = gray.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.02).toInt().coerceIn(0, sorted.lastIndex)]
        val hi = sorted[(sorted.size * 0.98).toInt().coerceIn(0, sorted.lastIndex)]
        val range = (hi - lo).coerceAtLeast(1)
        for (i in gray.indices) {
            gray[i] = ((gray[i] - lo) * 255 / range).coerceIn(0, 255)
        }
    }

    private fun sobelMagnitude(gray: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx =
                    -gray[(y - 1) * w + (x - 1)] + gray[(y - 1) * w + (x + 1)] +
                        -2 * gray[y * w + (x - 1)] + 2 * gray[y * w + (x + 1)] +
                        -gray[(y + 1) * w + (x - 1)] + gray[(y + 1) * w + (x + 1)]
                val gy =
                    -gray[(y - 1) * w + (x - 1)] - 2 * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)] +
                        gray[(y + 1) * w + (x - 1)] + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)]
                out[y * w + x] = min(255, sqrt((gx * gx + gy * gy).toDouble()).toInt())
            }
        }
        return out
    }

    private fun quantizeToPalette(luma: Int): Int {
        var best = PALETTE[0]
        var bestDist = Int.MAX_VALUE
        for (c in PALETTE) {
            val g = c and 0xFF
            val d = abs(luma - g)
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    private fun luminance(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return ((0.299 * r) + (0.587 * g) + (0.114 * b)).roundToInt().coerceIn(0, 255)
    }

    private fun blendRgb(bg: Int, fg: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val b = (bg shr shift) and 0xFF
            val f = (fg shr shift) and 0xFF
            return (b * (1 - a) + f * a).roundToInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
