package io.github.chifeng1031.limbushelper.core

import org.opencv.core.Mat

/** What the engine needs from the phone. Coordinates are in frame space (height 720). */
interface Device {
    /** Latest screen frame: BGR, height [DESIGN_H]. The engine owns and releases it. */
    fun screencap(): Mat

    fun click(x: Int, y: Int)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long)

    fun startApp(pkg: String)

    fun log(msg: String)

    /** Called when a node times out, so the frame can be saved for debugging */
    fun onTimeout(nodeName: String, frame: Mat) {}
}

class StoppedException : RuntimeException("stopped")

enum class TaskResult { SUCCESS, FAILED, STOPPED }

/**
 * Runs pipeline tasks the way MaaFramework does:
 * a node's action runs, then its `next` list is recognized in order until one hits or `timeout` passes.
 * A hit on a plain entry continues the chain from that node; a hit on a `[JumpBack]` entry runs that
 * node's chain and afterwards returns to recognizing the parent's `next` list again.
 */
class Engine(
    private val pipeline: Pipeline,
    private val matcher: Matcher,
    private val device: Device,
    private val clock: Clock = Clock.SYSTEM,
) {
    interface Clock {
        fun now(): Long
        fun sleep(ms: Long)

        companion object {
            val SYSTEM = object : Clock {
                override fun now() = System.currentTimeMillis()
                override fun sleep(ms: Long) {
                    if (ms > 0) Thread.sleep(ms)
                }
            }
        }
    }

    @Volatile
    var stopRequested = false

    private val hitCounts = HashMap<String, Int>()

    fun run(entry: String): TaskResult {
        hitCounts.clear()
        return try {
            val first = waitFor(listOf(NodeRef(entry, false)), 20_000, 1_000, entry)
            if (first == null) {
                device.log("任务入口未命中: $entry")
                TaskResult.FAILED
            } else if (runChain(first.first, first.second)) TaskResult.SUCCESS else TaskResult.FAILED
        } catch (e: StoppedException) {
            TaskResult.STOPPED
        }
    }

    /** Executes [startName] and follows its chain. Returns false on timeout or error. */
    private fun runChain(startName: String, startBox: Rect): Boolean {
        var name = startName
        var box = startBox
        while (true) {
            val node = pipeline[name]
            hitCounts[name] = (hitCounts[name] ?: 0) + 1
            if (!act(node, box)) return true // StopTask ends the task normally
            if (node.next.isEmpty()) return true

            while (true) {
                val hit = waitFor(node.next, node.timeoutMs, node.rateLimitMs, name)
                if (hit == null) {
                    device.log("超时: $name 的 next 均未命中")
                    if (node.onError.isNotEmpty()) {
                        val err = waitFor(node.onError, node.timeoutMs, node.rateLimitMs, name)
                        if (err != null) runChain(err.first, err.second)
                    }
                    return false
                }
                val (hitName, hitBox, ref) = hit
                if (ref.jumpBack) {
                    if (!runChain(hitName, hitBox)) return false
                    continue // back to recognizing this node's next list
                }
                name = hitName
                box = hitBox
                break
            }
        }
    }

    private data class Found(val first: String, val second: Rect, val ref: NodeRef)

    private fun waitFor(refs: List<NodeRef>, timeoutMs: Long, rateLimitMs: Long, parent: String): Found? {
        val start = clock.now()
        while (true) {
            checkStop()
            val loopStart = clock.now()
            val frame = device.screencap()
            lastWidth = frame.cols()
            try {
                val layout = Layout(frame.cols())
                for (ref in refs) {
                    val node = pipeline.nodes[ref.name]
                    if (node == null) {
                        device.log("节点不存在: ${ref.name}")
                        continue
                    }
                    if (!node.enabled || (hitCounts[ref.name] ?: 0) >= node.maxHit) continue
                    var hit = matcher.recognize(node.recognition, frame, layout)
                    if (node.inverse) hit = if (hit == null) Hit(Rect(0, 0, frame.cols(), frame.rows()), 1.0) else null
                    if (hit != null) {
                        device.log("命中: ${ref.name}")
                        return Found(ref.name, hit.box, ref)
                    }
                }
                if (timeoutMs >= 0 && clock.now() - start >= timeoutMs) {
                    device.onTimeout(parent, frame)
                    return null
                }
            } finally {
                frame.release()
            }
            clock.sleep(rateLimitMs - (clock.now() - loopStart))
        }
    }

    /** Returns false when the task should stop (StopTask) */
    private fun act(node: Node, box: Rect): Boolean {
        if (node.preWaitFreezesMs > 0) waitFreezes(node.preWaitFreezesMs, box)
        sleep(node.preDelayMs)
        val layout = Layout(lastWidth)
        when (val a = node.action) {
            is Action.DoNothing -> {}
            is Action.Click -> {
                val r = resolve(a.target, box, layout).offset(a.offset)
                device.click(r.centerX, r.centerY)
            }
            is Action.Swipe -> {
                val b = resolve(a.begin, box, layout).offset(a.beginOffset)
                val e = resolve(a.end, box, layout).offset(a.endOffset)
                device.swipe(b.centerX, b.centerY, e.centerX, e.centerY, a.durationMs)
            }
            is Action.StartApp -> device.startApp(a.pkg)
            is Action.StopTask -> return false
            is Action.Unsupported -> device.log("不支持的动作: ${a.type}（${node.name}）")
        }
        if (node.postWaitFreezesMs > 0) waitFreezes(node.postWaitFreezesMs, box)
        sleep(node.postDelayMs)
        return true
    }

    private fun resolve(t: Target, box: Rect, layout: Layout): Rect = when (t) {
        is Target.Self -> box
        is Target.Fixed -> layout.fixed(t.rect)
    }

    /** Width of the latest frame, for mapping fixed targets */
    private var lastWidth = DESIGN_W

    /** Waits until the region stops changing for [ms] (similarity >= 0.95), at most 20 s */
    private fun waitFreezes(ms: Long, box: Rect) {
        val start = clock.now()
        var prev: Mat? = null
        var stableSince = clock.now()
        try {
            while (clock.now() - start < 20_000) {
                checkStop()
                val frame = device.screencap()
                lastWidth = frame.cols()
                val r = Layout(frame.cols()).clip(if (box.isEmpty) Rect(0, 0, frame.cols(), frame.rows()) else box)
                if (r.isEmpty) {
                    frame.release()
                    return
                }
                val crop = frame.submat(r.y, r.y + r.h, r.x, r.x + r.w).clone()
                frame.release()
                val p = prev
                if (p != null && p.size() == crop.size()) {
                    if (similarity(p, crop) < 0.95) stableSince = clock.now()
                    else if (clock.now() - stableSince >= ms) {
                        crop.release()
                        return
                    }
                } else {
                    stableSince = clock.now()
                }
                p?.release()
                prev = crop
                clock.sleep(100)
            }
        } finally {
            prev?.release()
        }
    }

    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            checkStop()
            val step = minOf(left, 100)
            clock.sleep(step)
            left -= step
        }
    }

    private fun checkStop() {
        if (stopRequested) throw StoppedException()
    }
}
