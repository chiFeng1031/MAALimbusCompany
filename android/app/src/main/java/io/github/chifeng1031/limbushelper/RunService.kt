package io.github.chifeng1031.limbushelper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import io.github.chifeng1031.limbushelper.core.DESIGN_H
import io.github.chifeng1031.limbushelper.core.Device
import io.github.chifeng1031.limbushelper.core.Engine
import io.github.chifeng1031.limbushelper.core.Matcher
import io.github.chifeng1031.limbushelper.core.Pipeline
import io.github.chifeng1031.limbushelper.core.TaskResult
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Foreground service holding the screen capture and running the selected tasks on a worker thread */
class RunService : Service() {
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var engine: Engine? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LogBus.log("收到停止指令")
                engine?.stopRequested = true
                if (worker == null) shutdown()
            }
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (worker != null) {
            LogBus.log("已在執行中")
            return
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        running = true

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val entries = intent.getStringArrayListExtra(EXTRA_ENTRIES).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()

        if (!OpenCVLoader.initLocal()) {
            LogBus.log("OpenCV 載入失敗")
            shutdown()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = data?.let { mpm.getMediaProjection(resultCode, it) }
        if (proj == null) {
            LogBus.log("無法取得螢幕錄製授權")
            shutdown()
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                LogBus.log("螢幕錄製已結束")
                engine?.stopRequested = true
            }
        }, Handler(Looper.getMainLooper()))

        // Landscape capture with height 720, keeping the screen's own aspect ratio
        val real = Point().also {
            @Suppress("DEPRECATION")
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
        }
        val long = maxOf(real.x, real.y)
        val short = minOf(real.x, real.y)
        val w = (DESIGN_H.toLong() * long / short).toInt() and 1.inv()
        LogBus.log("螢幕 ${long}x$short，擷取 ${w}x$DESIGN_H")
        capture = ScreenCapture(proj, w, DESIGN_H, resources.displayMetrics.densityDpi)

        worker = Thread({ runTasks(entries, names) }, "engine").apply { start() }
    }

    private fun runTasks(entries: List<String>, names: List<String>) {
        try {
            val pipeline = Pipeline.parse(
                assets.list("pi/resource/pipeline").orEmpty().sorted().filter { it.endsWith(".json") }
                    .map { assets.open("pi/resource/pipeline/$it").bufferedReader().readText() },
            )
            val matcher = Matcher(::loadTemplate) { LogBus.log(it) }
            val e = Engine(pipeline, matcher, AndroidDevice())
            engine = e
            for ((i, entry) in entries.withIndex()) {
                val name = names.getOrElse(i) { entry }
                LogBus.log("▶ 開始任務：$name")
                val result = e.run(entry)
                LogBus.log(
                    when (result) {
                        TaskResult.SUCCESS -> "✔ 完成：$name"
                        TaskResult.FAILED -> "✘ 失敗：$name"
                        TaskResult.STOPPED -> "■ 已停止：$name"
                    },
                )
                if (result == TaskResult.STOPPED) break
            }
            LogBus.log("全部任務結束")
        } catch (t: Throwable) {
            LogBus.log("發生錯誤：$t")
        } finally {
            Handler(Looper.getMainLooper()).post { shutdown() }
        }
    }

    private fun loadTemplate(path: String): Mat? = try {
        assets.open("pi/resource/image/$path").use { stream ->
            val bmp = BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inScaled = false })
                ?: return null
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            Mat().also {
                Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2BGR)
                rgba.release()
            }
        }
    } catch (e: java.io.IOException) {
        null
    }

    private inner class AndroidDevice : Device {
        private var blackFrames = 0
        private var savedShots = 0

        override fun screencap(): Mat {
            val f = capture?.frame()
            if (f == null) {
                LogBus.log("取不到畫面")
                return Mat(DESIGN_H, capture?.width ?: 1280, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
            }
            val mean = Core.mean(f).`val`
            if (mean[0] + mean[1] + mean[2] < 3.0) {
                if (++blackFrames == 5) LogBus.log("畫面持續全黑：遊戲可能禁止錄製畫面，或錄製時沒有選「整個螢幕」")
            } else {
                blackFrames = 0
            }
            return f
        }

        private fun tap(): TapService? = TapService.instance.also {
            if (it == null) LogBus.log("無障礙服務未開啟，無法點擊")
        }

        override fun click(x: Int, y: Int) {
            tap()?.tap(x, y, DESIGN_H)
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
            tap()?.swipe(x1, y1, x2, y2, durationMs, DESIGN_H)
        }

        override fun startApp(pkg: String) {
            LogBus.log("啟動 $pkg")
            tap()?.startApp(pkg)
        }

        override fun log(msg: String) = LogBus.log(msg)

        override fun onTimeout(nodeName: String, frame: Mat) {
            if (savedShots >= 10) return
            savedShots++
            saveScreenshot(frame, nodeName)
        }
    }

    /** Saves to Pictures/LimbusHelper so the user can send it for debugging */
    private fun saveScreenshot(frame: Mat, tag: String) {
        try {
            val rgba = Mat()
            Imgproc.cvtColor(frame, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            rgba.release()
            val name = "timeout_${SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())}_$tag.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LimbusHelper")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            LogBus.log("已存截圖：圖片/LimbusHelper/$name")
        } catch (t: Throwable) {
            LogBus.log("存截圖失敗：$t")
        }
    }

    private fun shutdown() {
        worker = null
        engine = null
        capture?.release()
        capture = null
        projection?.stop()
        projection = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "執行中", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Limbus 助手執行中")
            .setContentText("點「停止」可隨時中止")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_ENTRIES = "entries"
        const val EXTRA_NAMES = "names"
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RunService::class.java).setAction(ACTION_STOP))
        }
    }
}
