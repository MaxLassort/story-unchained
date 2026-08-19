package com.maxlass.studio.pack.format.utils

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal SVG renderer (zero dependency): parses the `d` attribute of the `<path>` elements
 * plus the basic shapes (`<circle>`, `<ellipse>`, `<rect>`, `<line>`, `<polyline>`,
 * `<polygon>`) and draws them all as **white strokes on a black background**, using Java 2D.
 *
 * Designed for Lucide icons (downloaded as-is from the CDN): all-stroke, `stroke-width="2"`,
 * `fill="none"`. Colors, fills, gradients and text are ignored.
 */
object SvgIconRenderer {

    const val DEFAULT_WIDTH = 320
    const val DEFAULT_HEIGHT = 240

    /** Renders the `<path d="...">` elements of [svg] as white strokes on black, [width]x[height]. */
    fun render(svg: String, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT): ByteArray {
        val path = parsePaths(svg)
        val viewBox = parseViewBox(svg)
        val strokeWidth = parseStrokeWidth(svg).coerceAtLeast(1.0)

        val bounds = viewBox ?: path.bounds2D
        if (bounds.width <= 0.0 || bounds.height <= 0.0) {
            throw IllegalArgumentException("SVG path has empty bounds")
        }
        val scale = min(width / bounds.width, height / bounds.height)
        val tx = (width - bounds.width * scale) / 2.0 - bounds.x * scale
        val ty = (height - bounds.height * scale) / 2.0 - bounds.y * scale
        val transform = AffineTransform.getTranslateInstance(tx, ty)
        transform.concatenate(AffineTransform.getScaleInstance(scale, scale))

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        try {
            g2d.color = Color.BLACK
            g2d.fillRect(0, 0, width, height)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2d.setTransform(transform)

            g2d.color = Color.WHITE
            g2d.stroke = BasicStroke(
                strokeWidth.toFloat(),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )
            g2d.draw(path)
        } finally {
            g2d.dispose()
        }

        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "PNG", output)) throw IllegalArgumentException("Failed to write PNG")
        return output.toByteArray()
    }

    /** Parses all `<path d="...">` elements plus the basic shapes and merges them into one [Path2D]. */
    private fun parsePaths(svg: String): Path2D {
        val merged = Path2D.Double()
        var count = 0

        val dRegex = Regex("""<path\b[^>]*\bd\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        for (match in dRegex.findAll(svg)) {
            merged.append(parsePathData(match.groupValues[1]), false)
            count++
        }

        for (regex in shapeRegexes) {
            for (match in regex.findAll(svg)) {
                val shape = parseShape(regex, match.groupValues[1]) ?: continue
                merged.append(shape, false)
                count++
            }
        }

        if (count == 0) throw IllegalArgumentException("No supported SVG shape found")
        return merged
    }

    private val shapeRegexes = listOf(
        Regex("""<circle\b([^>]*?)/?>""", RegexOption.IGNORE_CASE),
        Regex("""<ellipse\b([^>]*?)/?>""", RegexOption.IGNORE_CASE),
        Regex("""<rect\b([^>]*?)/?>""", RegexOption.IGNORE_CASE),
        Regex("""<line\b([^>]*?)/?>""", RegexOption.IGNORE_CASE),
        Regex("""<polyline\b([^>]*?)/?>""", RegexOption.IGNORE_CASE),
        Regex("""<polygon\b([^>]*?)/?>""", RegexOption.IGNORE_CASE),
    )

    private fun parseShape(regex: Regex, tag: String): Path2D? {
        fun attr(name: String): String? =
            Regex("""(?:^|\s)$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)

        return when (regex) {
            shapeRegexes[0] -> {
                val cx = attr("cx")?.toDoubleOrNull() ?: 0.0
                val cy = attr("cy")?.toDoubleOrNull() ?: 0.0
                val r = attr("r")?.toDoubleOrNull() ?: return null
                Path2D.Double().apply { append(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r), false) }
            }
            shapeRegexes[1] -> {
                val cx = attr("cx")?.toDoubleOrNull() ?: 0.0
                val cy = attr("cy")?.toDoubleOrNull() ?: 0.0
                val rx = attr("rx")?.toDoubleOrNull() ?: return null
                val ry = attr("ry")?.toDoubleOrNull() ?: return null
                Path2D.Double().apply { append(Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry), false) }
            }
            shapeRegexes[2] -> {
                val x = attr("x")?.toDoubleOrNull() ?: 0.0
                val y = attr("y")?.toDoubleOrNull() ?: 0.0
                val w = attr("width")?.toDoubleOrNull() ?: return null
                val h = attr("height")?.toDoubleOrNull() ?: return null
                Path2D.Double().apply { append(Rectangle2D.Double(x, y, w, h), false) }
            }
            shapeRegexes[3] -> {
                val x1 = attr("x1")?.toDoubleOrNull() ?: return null
                val y1 = attr("y1")?.toDoubleOrNull() ?: return null
                val x2 = attr("x2")?.toDoubleOrNull() ?: return null
                val y2 = attr("y2")?.toDoubleOrNull() ?: return null
                Path2D.Double().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
            }
            else -> {
                val pairs = (attr("points") ?: return null)
                    .trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
                if (pairs.size < 4 || pairs.size % 2 != 0) return null
                Path2D.Double().apply {
                    moveTo(pairs[0], pairs[1])
                    for (i in 2 until pairs.size step 2) lineTo(pairs[i], pairs[i + 1])
                    if (regex == shapeRegexes[5]) closePath()
                }
            }
        }
    }

    private fun parseViewBox(svg: String): Rectangle2D.Double? {
        val vbRegex = Regex("""viewBox\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val match = vbRegex.find(svg) ?: return null
        val parts = match.groupValues[1].trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 4 || parts[2] <= 0.0 || parts[3] <= 0.0) return null
        return Rectangle2D.Double(parts[0], parts[1], parts[2], parts[3])
    }

    /** Stroke width in SVG user units (Lucide uses `stroke-width="2"`), or 0 when absent. */
    private fun parseStrokeWidth(svg: String): Double {
        val swRegex = Regex("""stroke-width\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val match = swRegex.find(svg) ?: return 0.0
        return match.groupValues[1].trim().toDoubleOrNull() ?: 0.0
    }

    /** Tokenizes and interprets a single `d` attribute into a [Path2D]. */
    private fun parsePathData(d: String): Path2D {
        val path = Path2D.Double()
        var i = 0
        var cx = 0.0
        var cy = 0.0
        var startX = 0.0
        var startY = 0.0
        var lastCmd = ' '
        var lastCtrlX = 0.0
        var lastCtrlY = 0.0

        fun skipSeparators() {
            while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++
        }

        fun readNumber(): Double {
            skipSeparators()
            val start = i
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            var hasDot = false
            while (i < d.length) {
                val c = d[i]
                when {
                    c.isDigit() -> i++
                    c == '.' && !hasDot -> {
                        hasDot = true
                        i++
                    }
                    c == '.' -> break
                    c == 'e' || c == 'E' -> i++
                    (c == '-' || c == '+') && i > start && (d[i - 1] == 'e' || d[i - 1] == 'E') -> i++
                    else -> break
                }
            }
            if (i == start) throw IllegalArgumentException("Expected number at offset $i in path data")
            val token = d.substring(start, i)
            return token.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number '$token' at offset $start in path data")
        }

        fun nextCommand(): Char {
            skipSeparators()
            if (i >= d.length) return ' '
            val c = d[i]
            if (c.isLetter()) {
                i++
                return c
            }
            return lastCmd
        }

        /** Arc flags are single characters (`0`/`1`) that may be glued to the following number
         * (e.g. `A5 5 0 0012 5` means flags `0,0` then `x=12,y=5`). */
        fun readFlag(): Double {
            skipSeparators()
            if (i >= d.length) throw IllegalArgumentException("Expected arc flag at offset $i in path data")
            val c = d[i]
            if (c != '0' && c != '1') {
                throw IllegalArgumentException("Invalid arc flag '$c' at offset $i in path data")
            }
            i++
            return if (c == '1') 1.0 else 0.0
        }

        while (true) {
            val cmd = nextCommand()
            if (cmd == ' ') break
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = readNumber()
                    val y = readNumber()
                    val nx = if (cmd == 'm') cx + x else x
                    val ny = if (cmd == 'm') cy + y else y
                    path.moveTo(nx, ny)
                    cx = nx; cy = ny; startX = nx; startY = ny
                    lastCmd = if (cmd == 'm') 'l' else 'L'
                }
                'L' -> {
                    val x = readNumber()
                    val y = readNumber()
                    val nx = if (cmd == 'l') cx + x else x
                    val ny = if (cmd == 'l') cy + y else y
                    path.lineTo(nx, ny)
                    cx = nx; cy = ny
                    lastCmd = cmd
                }
                'H' -> {
                    val x = readNumber()
                    val nx = if (cmd == 'h') cx + x else x
                    path.lineTo(nx, cy)
                    cx = nx
                    lastCmd = cmd
                }
                'V' -> {
                    val y = readNumber()
                    val ny = if (cmd == 'v') cy + y else y
                    path.lineTo(cx, ny)
                    cy = ny
                    lastCmd = cmd
                }
                'C' -> {
                    val c1x = readNumber(); val c1y = readNumber()
                    val c2x = readNumber(); val c2y = readNumber()
                    val x = readNumber(); val y = readNumber()
                    val dx = if (cmd == 'c') cx else 0.0
                    val dy = if (cmd == 'c') cy else 0.0
                    path.curveTo(c1x + dx, c1y + dy, c2x + dx, c2y + dy, x + dx, y + dy)
                    lastCtrlX = c2x + dx; lastCtrlY = c2y + dy
                    cx = x + dx; cy = y + dy
                    lastCmd = cmd
                }
                'S' -> {
                    val c2x = readNumber(); val c2y = readNumber()
                    val x = readNumber(); val y = readNumber()
                    val dx = if (cmd == 's') cx else 0.0
                    val dy = if (cmd == 's') cy else 0.0
                    val reflect = (lastCmd == 'c' || lastCmd == 'C' || lastCmd == 's' || lastCmd == 'S')
                    val c1x = if (reflect) 2 * cx - lastCtrlX else cx
                    val c1y = if (reflect) 2 * cy - lastCtrlY else cy
                    path.curveTo(c1x, c1y, c2x + dx, c2y + dy, x + dx, y + dy)
                    lastCtrlX = c2x + dx; lastCtrlY = c2y + dy
                    cx = x + dx; cy = y + dy
                    lastCmd = cmd
                }
                'Q' -> {
                    val qx = readNumber(); val qy = readNumber()
                    val x = readNumber(); val y = readNumber()
                    val dx = if (cmd == 'q') cx else 0.0
                    val dy = if (cmd == 'q') cy else 0.0
                    path.quadTo(qx + dx, qy + dy, x + dx, y + dy)
                    lastCtrlX = qx + dx; lastCtrlY = qy + dy
                    cx = x + dx; cy = y + dy
                    lastCmd = cmd
                }
                'T' -> {
                    val x = readNumber(); val y = readNumber()
                    val dx = if (cmd == 't') cx else 0.0
                    val dy = if (cmd == 't') cy else 0.0
                    val reflect = (lastCmd == 'q' || lastCmd == 'Q' || lastCmd == 't' || lastCmd == 'T')
                    val qx = if (reflect) 2 * cx - lastCtrlX else cx
                    val qy = if (reflect) 2 * cy - lastCtrlY else cy
                    path.quadTo(qx, qy, x + dx, y + dy)
                    lastCtrlX = qx; lastCtrlY = qy
                    cx = x + dx; cy = y + dy
                    lastCmd = cmd
                }
                'A' -> {
                    val rx = readNumber(); val ry = readNumber()
                    val rot = readNumber()
                    val largeArc = readFlag() != 0.0
                    val sweep = readFlag() != 0.0
                    val x = readNumber(); val y = readNumber()
                    val nx = if (cmd == 'a') cx + x else x
                    val ny = if (cmd == 'a') cy + y else y
                    addArc(path, cx, cy, nx, ny, rx, ry, rot, largeArc, sweep)
                    cx = nx; cy = ny
                    lastCmd = cmd
                }
                'Z' -> {
                    path.closePath()
                    cx = startX; cy = startY
                    lastCmd = cmd
                }
                else -> throw IllegalArgumentException("Unsupported SVG path command '$cmd'")
            }
        }
        return path
    }

    /**
     * Adds an SVG elliptical arc from (x1,y1) to (x2,y2) to [path], approximated with
     * line segments (endpoint → center parameterization, SVG spec F.6.5).
     */
    private fun addArc(
        path: Path2D,
        x1: Double, y1: Double, x2: Double, y2: Double,
        rx0: Double, ry0: Double, rotDeg: Double, largeArc: Boolean, sweep: Boolean,
    ) {
        var rx = abs(rx0)
        var ry = abs(ry0)
        if (rx == 0.0 || ry == 0.0 || (x1 == x2 && y1 == y2)) {
            path.lineTo(x2, y2)
            return
        }
        val phi = Math.toRadians(rotDeg % 360.0)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x1 - x2) / 2.0
        val dy2 = (y1 - y2) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        val sign = if (largeArc == sweep) -1.0 else 1.0
        val numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = sign * sqrt(max(0.0, numerator / denominator))
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0

        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            val a = Math.toDegrees(atan2(abs(ux * vy - uy * vx), dot / len))
            return if (ux * vy - uy * vx < 0) -a else a
        }

        val startAngle = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 360.0
        if (sweep && delta < 0) delta += 360.0
        if (abs(delta) >= 360.0) delta = if (sweep) 360.0 else -360.0

        val segments = max(2, (abs(delta) / 10.0).toInt())
        var prevX = x1
        var prevY = y1
        for (s in 1..segments) {
            val a = Math.toRadians(startAngle + delta * s / segments)
            val ex = rx * cos(a)
            val ey = ry * sin(a)
            val nx = cosPhi * ex - sinPhi * ey + cx
            val ny = sinPhi * ex + cosPhi * ey + cy
            if (s == 1) path.lineTo(prevX, prevY)
            path.lineTo(nx, ny)
            prevX = nx
            prevY = ny
        }
        path.lineTo(x2, y2)
    }
}