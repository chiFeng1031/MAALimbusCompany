package io.github.chifeng1031.limbushelper.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Frames are BGR and already scaled so that their height is [Layout.DESIGN_H] */
const val DESIGN_W = 1280
const val DESIGN_H = 720

/**
 * Maps 1280x720 design coordinates onto a frame of the same height but any width.
 *
 * On a 20:9 phone the frame is about 1560x720. The game scales its UI by height, so a button keeps its
 * size, but depending on how it is anchored it stays at the left edge, moves with the center, or sticks
 * to the right edge. A recognition ROI is therefore stretched to cover all three positions; a fixed
 * click target is shifted according to which third of the screen it sits in.
 */
class Layout(val frameW: Int) {
    val extra = max(0, frameW - DESIGN_W)

    fun roi(r: Rect): Rect {
        if (r.isEmpty) return Rect(0, 0, frameW, DESIGN_H)
        return clip(Rect(r.x, r.y, r.w + extra, r.h))
    }

    fun fixed(r: Rect): Rect {
        val cx = r.x + r.w / 2
        val dx = when {
            cx < DESIGN_W / 3 -> 0
            cx > DESIGN_W * 2 / 3 -> extra
            else -> extra / 2
        }
        return Rect(r.x + dx, r.y, r.w, r.h)
    }

    fun clip(r: Rect): Rect {
        val x1 = r.x.coerceIn(0, frameW)
        val y1 = r.y.coerceIn(0, DESIGN_H)
        val x2 = (r.x + r.w).coerceIn(0, frameW)
        val y2 = (r.y + r.h).coerceIn(0, DESIGN_H)
        return Rect(x1, y1, x2 - x1, y2 - y1)
    }
}

data class Hit(val box: Rect, val score: Double)

fun interface TemplateSource {
    /** Returns a BGR template, or null when it does not exist */
    fun load(path: String): Mat?
}

class Matcher(private val templates: TemplateSource, private val log: (String) -> Unit = {}) {
    private val cache = HashMap<String, Mat?>()
    private val missing = HashSet<String>()

    private fun template(path: String): Mat? = cache.getOrPut(path) {
        templates.load(path).also { if (it == null && missing.add(path)) log("找不到模板: $path") }
    }

    fun recognize(reco: Recognition, frame: Mat, layout: Layout): Hit? = when (reco) {
        is Recognition.DirectHit -> Hit(Rect(0, 0, frame.cols(), frame.rows()), 1.0)
        is Recognition.TemplateMatch -> templateMatch(reco, frame, layout)
        is Recognition.Or -> reco.anyOf.firstNotNullOfOrNull { recognize(it, frame, layout) }
        is Recognition.Unsupported -> {
            log("不支持的识别类型: ${reco.type}")
            null
        }
    }

    /**
     * Same rules as MaaFramework's TemplateMatcher: colour TM_CCOEFF_NORMED by default, every template
     * has its own threshold, and of all results above threshold the leftmost one wins.
     */
    private fun templateMatch(reco: Recognition.TemplateMatch, frame: Mat, layout: Layout): Hit? {
        val roi = layout.roi(reco.roi)
        if (roi.isEmpty) return null
        val region = frame.submat(roi.y, roi.y + roi.h, roi.x, roi.x + roi.w)
        val hits = ArrayList<Hit>()
        try {
            reco.templates.forEachIndexed { i, path ->
                val templ = template(path) ?: return@forEachIndexed
                if (templ.cols() > region.cols() || templ.rows() > region.rows()) return@forEachIndexed
                val threshold = reco.thresholds.getOrElse(i) { reco.thresholds.last() }
                matchOne(region, templ, reco.method, reco.greenMask, threshold) { x, y, score ->
                    hits += Hit(Rect(roi.x + x, roi.y + y, templ.cols(), templ.rows()), score)
                }
            }
        } finally {
            region.release()
        }
        return hits.minWithOrNull(compareBy<Hit> { it.box.x }.thenBy { it.box.y })
    }

    private inline fun matchOne(
        image: Mat,
        templ: Mat,
        method: Int,
        greenMask: Boolean,
        threshold: Double,
        onHit: (Int, Int, Double) -> Unit,
    ) {
        val invert = method >= 10000
        val cvMethod = if (invert) method - 10000 else method
        val lowBetter = !invert && (cvMethod == Imgproc.TM_SQDIFF || cvMethod == Imgproc.TM_SQDIFF_NORMED)
        val result = Mat()
        var mask: Mat? = null
        try {
            if (greenMask) {
                mask = Mat()
                Core.inRange(templ, Scalar(0.0, 255.0, 0.0), Scalar(0.0, 255.0, 0.0), mask)
                Core.bitwise_not(mask, mask)
                if (Core.countNonZero(mask) == mask.rows() * mask.cols()) {
                    mask.release()
                    mask = null
                }
            }
            if (mask == null) Imgproc.matchTemplate(image, templ, result, cvMethod)
            else Imgproc.matchTemplate(image, templ, result, cvMethod, mask)
            Core.patchNaNs(result, if (lowBetter) Double.MAX_VALUE else -1.0)
            if (invert) {
                Core.multiply(result, Scalar(-1.0), result)
                Core.add(result, Scalar(1.0), result)
            }

            // Peaks above threshold, suppressing the neighbourhood of each one (simple NMS)
            repeat(MAX_PEAKS) {
                val mm = Core.minMaxLoc(result)
                val score = if (lowBetter) mm.minVal else mm.maxVal
                val pass = if (lowBetter) score <= threshold else score >= threshold
                if (!pass) return
                val loc = if (lowBetter) mm.minLoc else mm.maxLoc
                val x = loc.x.toInt()
                val y = loc.y.toInt()
                onHit(x, y, score)
                val sx = max(0, x - templ.cols() / 2)
                val sy = max(0, y - templ.rows() / 2)
                val ex = min(result.cols(), x + templ.cols() / 2 + 1)
                val ey = min(result.rows(), y + templ.rows() / 2 + 1)
                result.submat(sy, ey, sx, ex).setTo(Scalar(if (lowBetter) Double.MAX_VALUE else -1.0))
            }
        } finally {
            result.release()
            mask?.release()
        }
    }

    companion object {
        private const val MAX_PEAKS = 8
    }
}

/** Similarity of two same-sized images for wait_freezes; flat images fall back to a pixel diff */
fun similarity(a: Mat, b: Mat): Double {
    val result = Mat()
    try {
        Imgproc.matchTemplate(a, b, result, Imgproc.TM_CCOEFF_NORMED)
        val v = result.get(0, 0)[0]
        if (!v.isNaN() && !v.isInfinite()) return v
    } finally {
        result.release()
    }
    val diff = Mat()
    try {
        Core.absdiff(a, b, diff)
        val mean = Core.mean(diff).`val`
        return if (abs(mean[0]) + abs(mean[1]) + abs(mean[2]) < 6.0) 1.0 else 0.0
    } finally {
        diff.release()
    }
}
