package io.github.chifeng1031.limbushelper.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A subset of the MaaFramework pipeline protocol (v5), enough for the tasks in assets/resource/pipeline:
 * DirectHit / TemplateMatch / Or recognition, DoNothing / Click / Swipe / StartApp / StopTask actions,
 * next + [JumpBack], on_error, timeout, rate_limit, delays and wait_freezes.
 *
 * Coordinates are in the design space the templates were cut from: 1280x720.
 */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val isEmpty get() = w <= 0 || h <= 0
    val centerX get() = x + w / 2
    val centerY get() = y + h / 2

    fun offset(o: Rect) = Rect(x + o.x, y + o.y, w + o.w, h + o.h)

    companion object {
        val ZERO = Rect(0, 0, 0, 0)
    }
}

data class NodeRef(val name: String, val jumpBack: Boolean)

sealed interface Recognition {
    data object DirectHit : Recognition
    data class TemplateMatch(
        val templates: List<String>,
        val roi: Rect,
        val thresholds: List<Double>,
        val method: Int,
        val greenMask: Boolean,
    ) : Recognition

    data class Or(val anyOf: List<Recognition>) : Recognition
    data class Unsupported(val type: String) : Recognition
}

sealed interface Target {
    /** The box of this node's own recognition result */
    data object Self : Target
    data class Fixed(val rect: Rect) : Target
}

sealed interface Action {
    data object DoNothing : Action
    data class Click(val target: Target, val offset: Rect) : Action
    data class Swipe(val begin: Target, val beginOffset: Rect, val end: Target, val endOffset: Rect, val durationMs: Long) : Action
    data class StartApp(val pkg: String) : Action
    data object StopTask : Action
    data class Unsupported(val type: String) : Action
}

data class Node(
    val name: String,
    val recognition: Recognition,
    val inverse: Boolean,
    val action: Action,
    val next: List<NodeRef>,
    val onError: List<NodeRef>,
    val timeoutMs: Long,
    val rateLimitMs: Long,
    val preDelayMs: Long,
    val postDelayMs: Long,
    val preWaitFreezesMs: Long,
    val postWaitFreezesMs: Long,
    val enabled: Boolean,
    val maxHit: Int,
)

data class TaskInfo(val name: String, val entry: String, val description: String?)

class Pipeline(val nodes: Map<String, Node>) {
    operator fun get(name: String): Node =
        nodes[name] ?: throw IllegalArgumentException("pipeline node not found: $name")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Later files override earlier ones node by node, as MaaFramework does */
        fun parse(jsonFiles: List<String>): Pipeline {
            val nodes = LinkedHashMap<String, Node>()
            for (text in jsonFiles) {
                val root = json.parseToJsonElement(text).jsonObject
                for ((name, value) in root) {
                    if (name.startsWith("$")) continue
                    nodes[name] = parseNode(name, value.jsonObject)
                }
            }
            return Pipeline(nodes)
        }

        fun parseTasks(interfaceJson: String): List<TaskInfo> =
            json.parseToJsonElement(interfaceJson).jsonObject["task"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                TaskInfo(
                    name = o.str("name") ?: o.str("entry")!!,
                    entry = o.str("entry")!!,
                    description = o.str("description"),
                )
            }

        internal fun parseNode(name: String, o: JsonObject) = Node(
            name = name,
            recognition = parseRecognition(o),
            inverse = o.bool("inverse") ?: false,
            action = parseAction(o),
            next = parseRefs(o["next"]),
            onError = parseRefs(o["on_error"]),
            timeoutMs = o.long("timeout") ?: 20_000,
            rateLimitMs = o.long("rate_limit") ?: 1_000,
            preDelayMs = o.long("pre_delay") ?: 200,
            postDelayMs = o.long("post_delay") ?: 200,
            preWaitFreezesMs = parseFreezes(o["pre_wait_freezes"]),
            postWaitFreezesMs = parseFreezes(o["post_wait_freezes"]),
            enabled = o.bool("enabled") ?: true,
            maxHit = o.long("max_hit")?.toInt() ?: Int.MAX_VALUE,
        )

        private fun parseRecognition(o: JsonObject): Recognition =
            when (val type = o.str("recognition") ?: "DirectHit") {
                "DirectHit" -> Recognition.DirectHit
                "TemplateMatch" -> {
                    val templates = o.strings("template")
                    Recognition.TemplateMatch(
                        templates = templates,
                        roi = o.rect("roi") ?: Rect.ZERO,
                        thresholds = o.doubles("threshold").ifEmpty { listOf(0.7) },
                        method = o.long("method")?.toInt() ?: 5,
                        greenMask = o.bool("green_mask") ?: false,
                    )
                }
                "Or" -> Recognition.Or(o["any_of"]?.jsonArray.orEmpty().map { parseRecognition(it.jsonObject) })
                else -> Recognition.Unsupported(type)
            }

        private fun parseAction(o: JsonObject): Action =
            when (val type = o.str("action") ?: "DoNothing") {
                "DoNothing" -> Action.DoNothing
                "Click" -> Action.Click(parseTarget(o["target"]), o.rect("target_offset") ?: Rect.ZERO)
                "Swipe" -> Action.Swipe(
                    begin = parseTarget(o["begin"]),
                    beginOffset = o.rect("begin_offset") ?: Rect.ZERO,
                    end = parseTarget(o["end"]),
                    endOffset = o.rect("end_offset") ?: Rect.ZERO,
                    durationMs = o.long("duration") ?: 200,
                )
                "StartApp" -> Action.StartApp(o.str("package") ?: "")
                "StopTask" -> Action.StopTask
                else -> Action.Unsupported(type)
            }

        private fun parseTarget(e: JsonElement?): Target {
            if (e == null || (e is JsonPrimitive && e.booleanOrNull == true)) return Target.Self
            if (e is JsonArray) {
                val v = e.map { it.jsonPrimitive.intOrNull ?: 0 }
                return when (v.size) {
                    4 -> Target.Fixed(Rect(v[0], v[1], v[2], v[3]))
                    2 -> Target.Fixed(Rect(v[0], v[1], 0, 0))
                    else -> Target.Self
                }
            }
            return Target.Self
        }

        private fun parseRefs(e: JsonElement?): List<NodeRef> {
            val items = when (e) {
                null -> emptyList()
                is JsonArray -> e.toList()
                else -> listOf(e)
            }
            return items.map { item ->
                if (item is JsonObject) {
                    NodeRef(item.str("name")!!, item.bool("jump_back") ?: false)
                } else {
                    var s = item.jsonPrimitive.content
                    var jumpBack = false
                    while (s.startsWith("[")) {
                        val end = s.indexOf(']')
                        if (s.substring(1, end).equals("JumpBack", ignoreCase = true)) jumpBack = true
                        s = s.substring(end + 1)
                    }
                    NodeRef(s, jumpBack)
                }
            }
        }

        private fun parseFreezes(e: JsonElement?): Long = when (e) {
            null -> 0
            is JsonObject -> e.long("time") ?: 0
            else -> e.jsonPrimitive.doubleOrNull?.toLong() ?: 0
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull?.toLong()

        private fun JsonObject.strings(k: String): List<String> = when (val e = this[k]) {
            null -> emptyList()
            is JsonArray -> e.map { it.jsonPrimitive.content }
            else -> listOf(e.jsonPrimitive.content)
        }

        private fun JsonObject.doubles(k: String): List<Double> = when (val e = this[k]) {
            null -> emptyList()
            is JsonArray -> e.mapNotNull { it.jsonPrimitive.doubleOrNull }
            else -> listOfNotNull(e.jsonPrimitive.doubleOrNull)
        }

        private fun JsonObject.rect(k: String): Rect? {
            val a = this[k] as? JsonArray ?: return null
            val v = a.map { it.jsonPrimitive.intOrNull ?: return null }
            return when (v.size) {
                4 -> Rect(v[0], v[1], v[2], v[3])
                2 -> Rect(v[0], v[1], 0, 0)
                else -> null
            }
        }
    }
}
