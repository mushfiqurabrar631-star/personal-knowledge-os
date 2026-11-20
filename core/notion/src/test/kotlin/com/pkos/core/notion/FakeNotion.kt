package com.pkos.core.notion

import com.pkos.core.notion.api.HttpRequest
import com.pkos.core.notion.api.HttpResponse
import com.pkos.core.notion.api.HttpTransport
import com.pkos.core.notion.sync.SyncJournal
import com.pkos.core.notion.sync.WriteIntent
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

/** In-memory journal; can be told to fail to simulate a broken local database. */
class FakeJournal : SyncJournal {
    val intents = mutableMapOf<String, WriteIntent>()
    val history = mutableListOf<WriteIntent>()
    var failReads = false
    var failWrites = false

    override suspend fun lastIntent(captureId: String): WriteIntent? {
        if (failReads) throw IllegalStateException("db locked")
        return intents[captureId]
    }

    override suspend fun recordIntent(intent: WriteIntent) {
        if (failWrites) throw IllegalStateException("disk full")
        intents[intent.captureId] = intent
        history += intent
    }
}

/**
 * In-memory Notion API good enough to exercise the sync engine end to end.
 * - Unknown select options are recorded as violations (real Notion would silently create them).
 * - There is no DELETE route: any delete attempt would fail the request and show in [calls].
 * - Supports failure injection by route or by call index, lost responses (applied, then IOException),
 *   query-index lag, and the owner adding/editing content in the Notion UI.
 */
class FakeNotion(var schema: JsonObject = MasterSchemaFixture.json()) : HttpTransport {
    data class Call(val index: Int, val method: String, val path: String, val body: JsonObject?)
    enum class Mode { FAIL_BEFORE, LOSE_RESPONSE_AFTER }
    private data class RouteFailure(val method: String, val pathPrefix: String, val status: Int, val code: String, val retryAfter: String?)

    val calls = mutableListOf<Call>()
    val pages = linkedMapOf<String, JsonObject>()
    val children = mutableMapOf<String, MutableList<JsonObject>>()
    val optionViolations = mutableListOf<String>()
    /** Pages the query endpoint does not return yet (index lag). */
    val hiddenFromQuery = mutableSetOf<String>()
    var hideNewPagesFromQuery = false
    var childrenPageSize = 100
    private val routeFailures = ArrayDeque<RouteFailure>()
    private val indexFailures = mutableMapOf<Int, Mode>()
    private val loseResponses = ArrayDeque<Pair<String, String>>()
    private var clock = 0
    var throwNetworkOnNext = false

    fun failNext(method: String, pathPrefix: String, status: Int, code: String = "error", retryAfter: String? = null) {
        routeFailures += RouteFailure(method, pathPrefix, status, code, retryAfter)
    }

    /** The request is applied on the server, but the client never sees the response. */
    fun loseResponseOfNext(method: String, pathPrefix: String) {
        loseResponses += method to pathPrefix
    }

    /** Fail the call with this 0-based global index (counting from now on, across all calls so far). */
    fun failAtCall(index: Int, mode: Mode) {
        indexFailures[index] = mode
    }

    fun writes() = calls.filter { it.method != "GET" && !(it.method == "POST" && it.path.endsWith("/query")) }
    fun createCount() = calls.count { it.method == "POST" && it.path == "/pages" }
    fun appendCount() = calls.count { it.method == "PATCH" && it.path.endsWith("/children") }
    fun propertyPatchCount(pageId: String? = null) =
        calls.count { it.method == "PATCH" && it.path.startsWith("/pages/") && (pageId == null || it.path == "/pages/$pageId") }

    fun markerCount(pageId: String, captureId: String) =
        children[pageId].orEmpty().count { it.toString().contains("pkos-body:v1:$captureId") }

    fun pagesWithCaptureId(captureId: String) =
        pages.values.count { plain(it["properties"]!!.jsonObject["Capture ID"]) == captureId }

    /** Seeds a pre-existing entry with no Capture ID, like the owner's 15 old rows. */
    fun seedLegacyPage(title: String): String {
        val id = UUID.randomUUID().toString()
        pages[id] = page(id, buildJsonObject { put("Topic name", inputText("title", title)) })
        children[id] = mutableListOf(blockResponse(paragraphInput("Owner's old note for $title")))
        return id
    }

    /** Simulates the owner editing a property in the Notion UI. */
    fun userEdit(pageId: String, name: String, input: JsonObject) {
        val p = pages.getValue(pageId)
        val props = p["properties"]!!.jsonObject.toMutableMap()
        props[name] = toResponse(name, input)
        pages[pageId] = JsonObject(p.toMutableMap().apply {
            put("properties", JsonObject(props)); put("last_edited_time", JsonPrimitive(tick()))
        })
    }

    /** Simulates the owner typing their own blocks into the page. */
    fun userAppend(pageId: String, vararg texts: String) {
        children.getOrPut(pageId) { mutableListOf() } += texts.map { blockResponse(paragraphInput(it)) }
        bump(pageId)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val path = request.url.removePrefix("https://api.notion.com/v1")
        val body = request.body?.let { Json.parseToJsonElement(it).jsonObject }
        val index = calls.size
        calls += Call(index, request.method, path, body)
        check(request.headers["Notion-Version"] == "2026-03-11")
        if (throwNetworkOnNext) { throwNetworkOnNext = false; throw IOException("simulated offline") }
        val byIndex = indexFailures.remove(index)
        if (byIndex == Mode.FAIL_BEFORE) return error(503, "service_unavailable")
        routeFailures.firstOrNull { it.method == request.method && path.startsWith(it.pathPrefix) }?.let {
            routeFailures.remove(it)
            return HttpResponse(
                it.status,
                if (it.retryAfter != null) mapOf("Retry-After" to it.retryAfter) else emptyMap(),
                """{"object":"error","status":${it.status},"code":"${it.code}","message":"simulated"}""",
            )
        }
        val response = route(request.method, path, body)
        val lose = loseResponses.firstOrNull { it.first == request.method && path.startsWith(it.second) }
        if (lose != null) { loseResponses.remove(lose); throw IOException("response lost") }
        if (byIndex == Mode.LOSE_RESPONSE_AFTER) throw IOException("response lost (index $index)")
        return response
    }

    private fun route(method: String, path: String, body: JsonObject?): HttpResponse {
        val pathOnly = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return when {
            method == "GET" && pathOnly == "/data_sources/$TEST_DS" -> ok(schema)
            method == "GET" && pathOnly.startsWith("/data_sources/") -> notFound()
            method == "POST" && pathOnly == "/data_sources/$TEST_DS/query" -> query(body!!)
            method == "POST" && pathOnly == "/pages" -> create(body!!)
            method == "GET" && pathOnly.startsWith("/pages/") -> pages[pathOnly.removePrefix("/pages/")]?.let { ok(it) } ?: notFound()
            method == "PATCH" && pathOnly.startsWith("/pages/") -> update(pathOnly.removePrefix("/pages/"), body!!)
            method == "GET" && pathOnly.endsWith("/children") -> listChildren(
                pathOnly.removePrefix("/blocks/").removeSuffix("/children"),
                query["page_size"]?.toInt() ?: 100,
                query["start_cursor"],
            )
            method == "PATCH" && pathOnly.endsWith("/children") -> {
                val id = pathOnly.removePrefix("/blocks/").removeSuffix("/children")
                if (id !in pages) return notFound()
                children.getOrPut(id) { mutableListOf() } += body!!["children"]!!.jsonArray.map { blockResponse(it.jsonObject) }
                bump(id)
                ok(buildJsonObject { put("object", "list") })
            }
            else -> error(400, "invalid_request_url")
        }
    }

    private fun listChildren(id: String, requested: Int, cursor: String?): HttpResponse {
        if (id !in pages) return notFound()
        val size = minOf(requested, childrenPageSize)
        val all = children[id].orEmpty()
        val start = cursor?.toInt() ?: 0
        val slice = all.drop(start).take(size)
        val next = start + slice.size
        return ok(buildJsonObject {
            put("object", "list")
            put("results", JsonArray(slice))
            put("has_more", next < all.size)
            if (next < all.size) put("next_cursor", next.toString()) else put("next_cursor", JsonNull)
        })
    }

    private fun query(body: JsonObject): HttpResponse {
        val filter = body["filter"]!!.jsonObject
        val prop = filter["property"]!!.jsonPrimitive.content
        val equals = filter["rich_text"]!!.jsonObject["equals"]!!.jsonPrimitive.content
        val matches = pages.filter { (id, p) ->
            id !in hiddenFromQuery &&
                p["in_trash"]?.jsonPrimitive?.content != "true" &&
                plain(p["properties"]!!.jsonObject[prop]) == equals
        }.values
        return ok(buildJsonObject { put("results", JsonArray(matches.toList())); put("has_more", false) })
    }

    private fun create(body: JsonObject): HttpResponse {
        val parent = body["parent"]!!.jsonObject
        if (parent["data_source_id"]?.jsonPrimitive?.contentOrNull != TEST_DS) return notFound()
        val props = body["properties"]!!.jsonObject
        validate(props)?.let { return it }
        val id = UUID.randomUUID().toString()
        pages[id] = page(id, props)
        if (hideNewPagesFromQuery) hiddenFromQuery += id
        body["children"]?.jsonArray?.let { children[id] = it.map { c -> blockResponse(c.jsonObject) }.toMutableList() }
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
            val def = schemaProps[name]?.jsonObject ?: return error(400, "validation_error")
            val type = def["type"]!!.jsonPrimitive.content
            val options = def[type]?.jsonObject?.get("options")?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            val v = value.jsonObject
            if (!v.containsKey(type)) return error(400, "validation_error")
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

    /** Converts request property values to the response shape (adds plain_text, type, id; date end/time_zone). */
    private fun toResponse(name: String, input: JsonObject): JsonObject {
        val type = input.keys.first { it != "type" }
        val value: JsonElement = when (type) {
            "title", "rich_text" -> richResponse(input[type]!!.jsonArray)
            "date" -> buildJsonObject {
                put("start", input["date"]!!.jsonObject["start"]!!)
                put("end", input["date"]!!.jsonObject["end"] ?: JsonNull)
                put("time_zone", JsonNull)
            }
            else -> input[type]!!
        }
        return buildJsonObject { put("id", name); put("type", type); put(type, value) }
    }

    private fun blockResponse(input: JsonObject): JsonObject {
        val type = input["type"]!!.jsonPrimitive.content
        val content = input[type]!!.jsonObject
        val rich = content["rich_text"]?.jsonArray
        return buildJsonObject {
            put("object", "block")
            put("id", UUID.randomUUID().toString())
            put("type", type)
            put(type, if (rich == null) content else JsonObject(content.toMutableMap().apply { put("rich_text", richResponse(rich)) }))
        }
    }

    private fun richResponse(arr: JsonArray) = buildJsonArray {
        arr.forEach { t ->
            val content = t.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
            addJsonObject { put("type", "text"); putJsonObject("text") { put("content", content) }; put("plain_text", content) }
        }
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
    private fun notFound() = error(404, "object_not_found")
    private fun error(status: Int, code: String) =
        HttpResponse(status, body = """{"object":"error","status":$status,"code":"$code","message":"simulated"}""")

    companion object {
        fun inputText(type: String, content: String) = buildJsonObject {
            putJsonArray(type) { addJsonObject { put("type", "text"); putJsonObject("text") { put("content", content) } } }
        }

        fun paragraphInput(text: String) = buildJsonObject {
            put("object", "block"); put("type", "paragraph")
            putJsonObject("paragraph") { put("rich_text", inputText("rich_text", text)["rich_text"]!!) }
        }
    }
}
