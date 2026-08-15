package com.maxlass.studio.pack.format.utils

import java.awt.image.BufferedImage

/**
 * Simple median-cut color quantizer producing a small palette (used for the Lunii 4-bpp BMP).
 * Replaces the jhlabs `OctTreeQuantizer` used by the legacy studio-core.
 */
object ColorQuantizer {

    private class Box(colors: MutableList<Int>) {
        var colors: MutableList<Int> = colors
        var rMin = 255
        var rMax = 0
        var gMin = 255
        var gMax = 0
        var bMin = 255
        var bMax = 0
        var avg = 0

        init {
            recomputeBounds()
            recomputeAverage()
        }

        fun recomputeBounds() {
            rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0
            for (c in colors) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < rMin) rMin = r
                if (r > rMax) rMax = r
                if (g < gMin) gMin = g
                if (g > gMax) gMax = g
                if (b < bMin) bMin = b
                if (b > bMax) bMax = b
            }
        }

        fun recomputeAverage() {
            if (colors.isEmpty()) return
            var sumR = 0L; var sumG = 0L; var sumB = 0L
            for (c in colors) {
                sumR += (c shr 16) and 0xFF
                sumG += (c shr 8) and 0xFF
                sumB += c and 0xFF
            }
            val n = colors.size
            avg = ((sumR / n).toInt() shl 16) or ((sumG / n).toInt() shl 8) or (sumB / n).toInt()
        }

        /** Channel (0=R, 1=G, 2=B) with the widest spread. */
        fun widestChannel(): Int {
            val r = rMax - rMin
            val g = gMax - gMin
            val b = bMax - bMin
            return when {
                r >= g && r >= b -> 0
                g >= b -> 1
                else -> 2
            }
        }

        /** Splits the box in two at the median of [channel], or null when too small. */
        fun split(): Pair<Box, Box>? {
            if (colors.size < 2) return null
            val channel = widestChannel()
            colors.sortBy { channelValue(it, channel) }
            val mid = colors.size / 2
            val left = Box(colors.subList(0, mid).toMutableList())
            val right = Box(colors.subList(mid, colors.size).toMutableList())
            return left to right
        }

        private fun channelValue(c: Int, channel: Int): Int = when (channel) {
            0 -> (c shr 16) and 0xFF
            1 -> (c shr 8) and 0xFF
            else -> c and 0xFF
        }
    }

    /**
     * Reduces [source] to an indexed image with up to [maxColors] colors (median cut),
     * drawn with best-match color conversion.
     */
    fun quantizeToIndexed(source: BufferedImage, maxColors: Int): BufferedImage {
        val width = source.width
        val height = source.height
        val rgb = source.getRGB(0, 0, width, height, null, 0, width)
        val palette = buildPalette(rgb, maxColors)
        val cm = java.awt.image.IndexColorModel(8, palette.size, palette, 0, false, -1, java.awt.image.DataBuffer.TYPE_BYTE)
        val indexed = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, cm)
        val g = indexed.createGraphics()
        try {
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return indexed
    }

    /** Builds a [maxColors]-entry palette from an ARGB pixel array via median cut. */
    fun buildPalette(rgb: IntArray, maxColors: Int): IntArray {
        val counts = HashMap<Int, Int>()
        for (p in rgb) {
            counts[p] = (counts[p] ?: 0) + 1
        }
        val boxes = mutableListOf(Box(counts.keys.toMutableList()))
        while (boxes.size < maxColors) {
            val biggest = boxes.maxByOrNull { it.rMax - it.rMin + it.gMax - it.gMin + it.bMax - it.bMin } ?: break
            val split = biggest.split() ?: break
            boxes.remove(biggest)
            boxes.add(split.first)
            boxes.add(split.second)
        }
        val palette = IntArray(boxes.size)
        for (i in boxes.indices) {
            boxes[i].recomputeAverage()
            palette[i] = boxes[i].avg
        }
        return palette
    }
}
