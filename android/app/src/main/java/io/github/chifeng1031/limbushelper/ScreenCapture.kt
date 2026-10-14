package io.github.chifeng1031.limbushelper

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Mirrors the screen into an ImageReader at [width]x[height] (height 720, landscape).
 * Only the newest Image is kept open; it is converted to a BGR Mat when the engine asks for a frame,
 * and the last converted frame is reused while the screen does not change.
 */
class ScreenCapture(projection: MediaProjection, val width: Int, val height: Int, dpi: Int) {
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private val lock = Object()
    private var pending: Image? = null
    private var last: Mat? = null
    private var buffer = ByteArray(0)
    private val display: VirtualDisplay

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                pending?.close()
                pending = img
                lock.notifyAll()
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "LimbusHelper", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    /** Newest frame as BGR; waits up to [waitMs] for the very first one. Caller releases it. */
    fun frame(waitMs: Long = 5000): Mat? {
        synchronized(lock) {
            if (pending == null && last == null) lock.wait(waitMs)
            pending?.let { img ->
                pending = null
                val m = convert(img)
                img.close()
                last?.release()
                last = m
            }
            return last?.clone()
        }
    }

    private fun convert(img: Image): Mat {
        val plane = img.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buf = plane.buffer
        val size = buf.remaining()
        if (buffer.size < size) buffer = ByteArray(size)
        buf.get(buffer, 0, size)
        val rows = img.height
        val rgba = Mat(rows, rowStride / pixelStride, CvType.CV_8UC4)
        rgba.put(0, 0, buffer.copyOf(minOf(size, rows * rowStride)).let {
            if (it.size < rows * rowStride) it.copyOf(rows * rowStride) else it
        })
        val cropped = rgba.submat(0, rows, 0, img.width)
        val bgr = Mat()
        Imgproc.cvtColor(cropped, bgr, Imgproc.COLOR_RGBA2BGR)
        cropped.release()
        rgba.release()
        return bgr
    }

    fun release() {
        display.release()
        reader.close()
        synchronized(lock) {
            pending?.close()
            pending = null
            last?.release()
            last = null
        }
        thread.quitSafely()
    }
}
