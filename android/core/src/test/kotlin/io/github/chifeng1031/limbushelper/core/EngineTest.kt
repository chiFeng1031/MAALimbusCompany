package io.github.chifeng1031.limbushelper.core

import nu.pattern.OpenCV
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val root = File(System.getProperty("repoRoot"))
    private val resource = File(root, "assets/resource")
    private val imageDir = File(resource, "image")

    private lateinit var pipeline: Pipeline
    private lateinit var matcher: Matcher

    @BeforeTest
    fun setUp() {
        OpenCV.loadLocally()
        val files = File(resource, "pipeline").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        pipeline = Pipeline.parse(files.map { it.readText() })
        matcher = Matcher({ path -> Imgcodecs.imread(File(imageDir, path).path).takeIf { !it.empty() } }, ::println)
    }

    // --- synthetic screens --------------------------------------------------------------------

    private fun img(path: String): Mat = Imgcodecs.imread(File(imageDir, path).path).also { check(!it.empty()) { path } }

    /** A black frame [width]x720 with templates pasted at design coordinates shifted by [dx] */
    private fun screen(width: Int, dx: Int, vararg items: Pair<String, Pair<Int, Int>>): Mat {
        val frame = Mat(DESIGN_H, width, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        for ((path, pos) in items) {
            val t = img(path)
            t.copyTo(frame.submat(pos.second, pos.second + t.rows(), pos.first + dx, pos.first + dx + t.cols()))
        }
        return frame
    }

    // Positions measured from AhabAssistantLimbusCompany's 2560x1440 canvases, halved
    private val DRIVE = "aalc/share/home/drive_assets.png" to (954 to 616)
    private val WINDOW = "aalc/share/home/window_assets.png" to (798 to 621)
    private val LUXCAVATION = "aalc/en/home/luxcavation_assets.png" to (387 to 186)
    private val THREAD = "aalc/share/luxcavation/thread_assets.png" to (79 to 308)
    private val TO_BATTLE = "aalc/en/battle/normal_to_battle_assets.png" to (1077 to 567)
    private val BACK = "aalc/share/home/back_assets.png" to (57 to 21)
    private val CLOSE = "aalc/en/base/notification_close_assets.png" to (544 to 438)
    private val ALL_IN = "aalc/share/enkephalin/all_in_assets.png" to (792 to 313)
    private val CONFIRM = "aalc/share/enkephalin/enkephalin_confirm_assets.png" to (701 to 519)
    private val CANCEL = "aalc/share/enkephalin/enkephalin_cancel_assets.png" to (471 to 517)
    private val TITLE = "limbus_template/Startup/StartGame.png" to (440 to 480)

    private fun hits(node: String, frame: Mat) =
        matcher.recognize(pipeline[node].recognition, frame, Layout(frame.cols()))

    // --- tests --------------------------------------------------------------------------------

    @Test
    fun everyReferenceResolves() {
        val tasks = Pipeline.parseTasks(File(root, "assets/interface.json").readText())
        assertTrue(tasks.isNotEmpty())
        tasks.forEach { assertNotNull(pipeline.nodes[it.entry], "task entry ${it.entry}") }
        for (node in pipeline.nodes.values) {
            for (ref in node.next + node.onError) assertNotNull(pipeline.nodes[ref.name], "${node.name} -> ${ref.name}")
        }
        assertTrue(pipeline["Sub_Limbus_Startup"].next.first().jumpBack)
        assertTrue(pipeline["Sub_DiffSelect"].recognition is Recognition.Or)
    }

    @Test
    fun recognizesAtSixteenByNine() = recognizeAll(DESIGN_W, 0)

    // S25+ at FHD+ is 2340x1080 -> 1560x720; the game may keep a button left, centred or right
    @Test
    fun recognizesOnWideScreenLeftAnchored() = recognizeAll(1560, 0)

    @Test
    fun recognizesOnWideScreenCentred() = recognizeAll(1560, 140)

    @Test
    fun recognizesOnWideScreenRightAnchored() = recognizeAll(1560, 280)

    private fun recognizeAll(width: Int, dx: Int) {
        val cases = listOf(
            "Sub_DriveView" to DRIVE,
            "Limbus_Home" to WINDOW,
            "Sub_LuxcavationBtn" to LUXCAVATION,
            "Sub_ThreadLuxcavationBtn" to THREAD,
            "PartySelectBtn" to TO_BATTLE,
            "Sub_Limbus_BtnBack" to BACK,
            "Sub_NotificationClose" to CLOSE,
            "SelectAll" to ALL_IN,
            "ConfirmCraft" to CONFIRM,
            "CancelCraft" to CANCEL,
            "Limbus_TapToStart" to TITLE,
        )
        for ((node, item) in cases) {
            val frame = screen(width, dx, item)
            val hit = assertNotNull(hits(node, frame), "$node should hit (w=$width dx=$dx)")
            // the click (box centre) must land on the button that was pasted
            val t = img(item.first)
            val x0 = item.second.first + dx
            val y0 = item.second.second
            assertTrue(hit.box.centerX in x0 until x0 + t.cols(), "$node click x ${hit.box.centerX} not in [$x0, ${x0 + t.cols()})")
            assertTrue(hit.box.centerY in y0 until y0 + t.rows(), "$node click y ${hit.box.centerY} not in [$y0, ${y0 + t.rows()})")
        }
        // must not fire
        assertNull(hits("Limbus_HomeBtn", screen(width, dx, WINDOW)), "already home: HomeBtn must not hit")
        assertNull(hits("Sub_DriveView", screen(width, dx, WINDOW)))
        assertNull(hits("ConfirmCraft", screen(width, dx, CANCEL)))
        assertNull(hits("Sub_NotificationClose", screen(width, dx)))
    }

    @Test
    fun fixedTargetsFollowTheirAnchor() {
        val l = Layout(1560)
        assertEquals(65, l.fixed(Rect(65, 28, 63, 35)).x) // top-left back button stays
        assertEquals(632 + 140, l.fixed(Rect(632, 116, 147, 189)).x) // centre moves by half
        assertEquals(1100 + 280, l.fixed(Rect(1100, 0, 100, 100)).x) // right edge moves fully
        assertEquals(Rect(0, 0, 1560, 720), l.roi(Rect.ZERO))
    }

    /** Scripted phone: each click moves to the screen whose button was clicked */
    private class FakeDevice(var screen: Mat, val transitions: List<Pair<Rect, () -> Mat>>) : Device {
        val clicks = ArrayList<Pair<Int, Int>>()
        val started = ArrayList<String>()
        override fun screencap(): Mat = screen.clone()
        override fun click(x: Int, y: Int) {
            clicks += x to y
            transitions.firstOrNull { (r, _) -> x in r.x until r.x + r.w && y in r.y until r.y + r.h }?.let { screen = it.second() }
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {}
        override fun startApp(pkg: String) {
            started += pkg
        }

        override fun log(msg: String) = println("  [device] $msg")
    }

    private class FakeClock : Engine.Clock {
        var t = 0L
        override fun now() = t
        override fun sleep(ms: Long) {
            if (ms > 0) t += ms
        }
    }

    @Test
    fun startGameFlowOnWideScreen() {
        val w = 1560
        val dx = 140
        fun rectOf(item: Pair<String, Pair<Int, Int>>): Rect {
            val t = img(item.first)
            return Rect(item.second.first + dx, item.second.second, t.cols(), t.rows())
        }
        val home = screen(w, dx, WINDOW, DRIVE)
        val title = screen(w, dx, TITLE)
        val popup = screen(w, dx, CLOSE)
        val device = FakeDevice(
            screen = popup,
            transitions = listOf(rectOf(CLOSE) to { title }, rectOf(TITLE) to { home }),
        )
        val engine = Engine(pipeline, matcher, device, FakeClock())
        assertEquals(TaskResult.SUCCESS, engine.run("LimbusStartGame"))
        assertEquals(listOf("com.ProjectMoon.LimbusCompany"), device.started)
        assertEquals(2, device.clicks.size, "close popup, then tap title: ${device.clicks}")
        assertTrue(device.screen === home)
    }

    @Test
    fun timesOutWhenNothingMatches() {
        val device = FakeDevice(screen(1560, 0), emptyList())
        val clock = FakeClock()
        val engine = Engine(pipeline, matcher, device, clock)
        assertEquals(TaskResult.FAILED, engine.run("CraftModule"))
    }

    @Test
    fun stopsOnRequest() {
        val device = FakeDevice(screen(1560, 0), emptyList())
        val engine = Engine(pipeline, matcher, device, FakeClock())
        engine.stopRequested = true
        assertEquals(TaskResult.STOPPED, engine.run("LimbusStartGame"))
    }
}
