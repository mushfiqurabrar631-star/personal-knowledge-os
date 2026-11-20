package com.pkos.core.notion

import com.pkos.core.notion.api.HttpRequest
import com.pkos.core.notion.api.HttpResponse
import com.pkos.core.notion.api.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.UUID

const val TEST_DS = "3d290cde-650c-8001-9cab-000bf4ce921c"

/** Live options of the Master Database as inspected on 2026-11-20. */
object MasterSchemaFixture {
    val selectOptions = mapOf(
        "Type" to listOf("Website", "File", "Note", "Idea", "Research", "Resources", "Video"),
        "Status" to listOf("To Learn", "Inbox", "Completed", "In Progress"),
        "Priority" to listOf("High", "Normal"),
        "platform " to listOf("Youtube", "Facebook"),
        "Notes" to listOf("Sample entry — edit or delete"),
    )
    val multiOptions = mapOf(
        "Tags" to listOf("Career", "English", "IELTS", "Business", "Education", "Website", "Technology"),
        "category " to listOf("AI", "Education", "Technology", "Personal life", "Mobile trick", "idea"),
    )
    val simple = mapOf(
        "Topic name" to "title", "Link/URL" to "url", "Date Added" to "date", "Pinned" to "checkbox",
        "Files & media" to "files", "Parent item" to "relation", "Sub-item" to "relation",
    )

    fun json(captureIdType: String? = "rich_text"): JsonObject = buildJsonObject {
        put("object", "data_source")
        put("id", TEST_DS)
        putJsonObject("properties") {
            var n = 0
            fun prop(name: String, type: String, options: List<String>? = null) = putJsonObject(name) {
                put("id", "p${n++}")
                put("name", name)
                put("type", type)
                putJsonObject(type) {
                    if (options != null) putJsonArray("options") { options.forEach { o -> addJsonObject { put("name", o) } } }
                }
            }
            simple.forEach { (k, t) -> prop(k, t) }
            selectOptions.forEach { (k, o) -> prop(k, "select", o) }
            multiOptions.forEach { (k, o) -> prop(k, "multi_select", o) }
            if (captureIdType != null) prop("Capture ID", captureIdType)
        }
    }
}

/**
 * In-memory Notion API good enough to exercise the sync engine end to end.
 * Unknown select options are REJECTED here (real Notion would silently create them),
 * so any attempt to change the schema shows up as a test failure.
 */
class FakeNotion(var schema: JsonObject = MasterSchemaFixture.json()) : HttpTransport {
    data class Call(val method: String, val path: String, val body: JsonObject?)
    private data class Failure(val method: String, val pathPrefix: String, val status: Int, val code: String, val retryAfter: String?)

    val calls = mutableListOf<Call>()
    val pages = linkedMapOf<String, JsonObject>()
    val children = mutableMapOf<String, MutableList<JsonObject>>()
    val optionViolations = mutableListOf<String>()
    private val failures = ArrayDeque<Failure>()
    private var clock = 0
    var loseNextCreateResponse = false
    var throwNetworkOnNext = false

    fun failNext(method: String, pathPrefix: String, status: Int, code: String = "error", retryAfter: String? = null) {
        failures += Failure(method, pathPrefix, status, code, retryAfter)
    }

    fun writes() = calls.filter { it.method != "GET" && !(it.method == "POST" && it.path.endsWith("/query")) }

    /** Seeds a pre-existing entry with no Capture ID, like the user's 15 old rows. */
    fun seedLegacyPage(title: String): String {
        val id = UUID.randomUUID().toString()
        pages[id] = page(id, buildJsonObject { put("Topic name", inputText("title", title)) })
        return id
    }

    /** Simulates the user editing a property in the Notion UI. */
    fun userEdit(pageId: String, name: String, input: JsonObject) {
        val p = pages.getValue(pageId)
        val props = p["properties"]!!.jsonObject.toMutableMap()
        props[name] = toResponse(name, input)
        pages[pageId] = JsonObject(p.toMutableMap().apply {
            put("properties", JsonObject(props)); put("last_edited_time", JsonPrimitive(tick()))
        })
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val path = request.url.removePrefix("https://api.notion.com/v1")
        val body = request.body?.let { Json.parseToJsonElement(it).jsonObject }
        calls += Call(request.method, path, body)
        check(request.headers["Notion-Version"] == "2026-03-11")
        if (throwNetworkOnNext) { throwNetworkOnNext = false; throw IOException("simulated offline") }
        failures.firstOrNull { it.method == request.method && path.startsWith(it.pathPrefix) }?.let {
            failures.remove(it)
            return HttpResponse(
                it.status,
                if (it.retryAfter != null) mapOf("Retry-After" to it.retryAfter) else emptyMap(),
                """{"object":"error","status":${it.status},"code":"${it.code}","message":"simulated"}""",
            )
        }
        val pathOnly = path.substringBefore('?')
        return when {
            request.method == "GET" && pathOnly == "/data_sources/$TEST_DS" -> ok(schema)
            request.method == "GET" && pathOnly.startsWith("/data_sources/") -> notFound()
            request.method == "POST" && pathOnly == "/data_sources/$TEST_DS/query" -> query(body!!)
            request.method == "POST" && pathOnly == "/pages" -> create(body!!)
            request.method == "GET" && pathOnly.startsWith("/pages/") -> pages[pathOnly.removePrefix("/pages/")]?.let { ok(it) } ?: notFound()
            request.method == "PATCH" && pathOnly.startsWith("/pages/") -> update(pathOnly.removePrefix("/pages/"), body!!)
            request.method == "GET" && pathOnly.endsWith("/children") -> {
                val id = pathOnly.removePrefix("/blocks/").removeSuffix("/children")
                ok(buildJsonObject { put("results", JsonArray(children[id].orEmpty())) })
            }
            request.method == "PATCH" && pathOnly.endsWith("/children") -> {
                val id = pathOnly.removePrefix("/blocks/").removeSuffix("/children")
                if (id !in pages) return notFound()
                children.getOrPut(id) { mutableListOf() } += body!!["children"]!!.jsonArray.map { it.jsonObject }
                bump(id)
                ok(buildJsonObject { put("object", "list") })
            }
            else -> HttpResponse(400, body = """{"object":"error","code":"invalid_request_url","message":"no route"}""")
        }
    }

    private fun query(body: JsonObject): HttpResponse {
        val filter = body["filter"]!!.jsonObject
        val prop = filter["property"]!!.jsonPrimitive.content
        val equals = filter["rich_text"]!!.jsonObject["equals"]!!.jsonPrimitive.content
        val matches = pages.values.filter { p ->
            p["in_trash"]?.jsonPrimitive?.content != "true" &&
                plain(p["properties"]!!.jsonObject[prop]) == equals
        }
        return ok(buildJsonObject { put("results", JsonArray(matches)); put("has_more", false) })
    }

    private fun create(body: JsonObject): HttpResponse {
        val parent = body["parent"]!!.jsonObject
        if (parent["data_source_id"]?.jsonPrimitive?.contentOrNull != TEST_DS) return notFound()
        val props = body["properties"]!!.jsonObject
        validate(props)?.let { return it }
        val id = UUID.randomUUID().toString()
        pages[id] = page(id, props)
        body["children"]?.jsonArray?.let { children[id] = it.map { c -> c.jsonObject }.toMutableList() }
        if (loseNextCreateResponse) { loseNextCreateResponse = false; throw IOException("response lost after create") }
        return ok(pages.getValue(id))
    }

    private fun update(id: String, body: JsonObject): HttpResponse {
        val existing = pages[id] ?: return notFound()
        val props = body["properties"]!!.jsonObject
        validate(props)?.let { return it }
        val merged = existing["properties"]!!.jsonObject.toMutableMap()
        props.forEach { (k, v) -> merged[k] = toResponse(k, v.jsonObject) }
        pages[id] = JsonObject(existing.toMutableMap().apply {
            put("properties", JsonObject(merged)); put("last_edited_time", JsonPrimitive(tick()))
        })
        return ok(pages.getValue(id))
    }

    private fun validate(props: JsonObject): HttpResponse? {
        val schemaProps = schema["properties"]!!.jsonObject
        for ((name, value) in props) {
            val def = schemaProps[name]?.jsonObject
                ?: return HttpResponse(400, body = """{"object":"error","code":"validation_error","message":"unknown property"}""")
            val type = def["type"]!!.jsonPrimitive.content
            val options = def[type]?.jsonObject?.get("options")?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            val v = value.jsonObject
            if (!v.containsKey(type)) return HttpResponse(400, body = """{"object":"error","code":"validation_error","message":"type mismatch"}""")
            val names = when (type) {
                "select" -> listOfNotNull((v["select"] as? JsonObject)?.get("name")?.jsonPrimitive?.content)
                "multi_select" -> v["multi_select"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                else -> emptyList()
            }
            names.filter { options != null && it !in options }.forEach { optionViolations += "$name=$it" }
        }
        return null
    }

    private fun page(id: String, inputProps: JsonObject): JsonObject {
        val schemaProps = schema["properties"]!!.jsonObject
        val full = schemaProps.mapValues { (name, def) ->
            inputProps[name]?.let { toResponse(name, it.jsonObject) } ?: empty(name, def.jsonObject["type"]!!.jsonPrimitive.content)
        }
        return buildJsonObject {
            put("object", "page")
            put("id", id)
            put("url", "https://www.notion.so/${id.replace("-", "")}")
            put("in_trash", false)
            putJsonObject("parent") { put("type", "data_source_id"); put("data_source_id", TEST_DS) }
            put("last_edited_time", tick())
            put("properties", JsonObject(full))
        }
    }

    private fun toResponse(name: String, input: JsonObject): JsonObject {
        val type = input.keys.first { it != "type" }
        val value: JsonElement = when (type) {
            "title", "rich_text" -> buildJsonArray {
                input[type]!!.jsonArray.forEach { t ->
                    val content = t.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
                    addJsonObject { put("type", "text"); putJsonObject("text") { put("content", content) }; put("plain_text", content) }
                }
            }
            else -> input[type]!!
        }
        return buildJsonObject { put("id", name); put("type", type); put(type, value) }
    }

    private fun empty(name: String, type: String): JsonObject = buildJsonObject {
        put("id", name); put("type", type)
        when (type) {
            "title", "rich_text", "multi_select", "files", "relation" -> put(type, JsonArray(emptyList()))
            "checkbox" -> put(type, false)
            else -> put(type, JsonNull)
        }
    }

    private fun bump(id: String) {
        pages[id] = JsonObject(pages.getValue(id).toMutableMap().apply { put("last_edited_time", JsonPrimitive(tick())) })
    }

    private fun plain(prop: JsonElement?): String =
        (prop as? JsonObject)?.let { o ->
            val t = o["type"]?.jsonPrimitive?.content ?: return ""
            (o[t] as? JsonArray)?.joinToString("") { it.jsonObject["plain_text"]?.jsonPrimitive?.content ?: "" }
        } ?: ""

    private fun tick() = "2026-11-20T05:%02d:00.000Z".format((clock++) % 60)
    private fun ok(json: JsonObject) = HttpResponse(200, body = json.toString())
    private fun notFound() = HttpResponse(404, body = """{"object":"error","status":404,"code":"object_not_found","message":"not found"}""")

    companion object {
        fun inputText(type: String, content: String) = buildJsonObject {
            putJsonArray(type) { addJsonObject { put("type", "text"); putJsonObject("text") { put("content", content) } } }
        }
    }
}
