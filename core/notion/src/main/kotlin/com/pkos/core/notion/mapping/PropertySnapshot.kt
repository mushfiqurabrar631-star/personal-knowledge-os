package com.pkos.core.notion.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Normalizes PKOS-managed property values and hashes them. Works on both shapes:
 * - request values PKOS sends (`{"select":{"name":"Video"}}`)
 * - response values Notion returns (`{"id":"x","type":"select","select":{...}}`)
 * so "what we would write" and "what is in Notion now" can be compared directly.
 * Empty values (missing, null, "", []) all normalize to the same token.
 */
object PropertySnapshot {
    private const val EMPTY = "∅"

    /** Hash of a retrieved page's managed properties. */
    fun hash(page: JsonObject, propertyNames: Collection<String>): String =
        hashProperties(page["properties"] as? JsonObject ?: JsonObject(emptyMap()), propertyNames)

    fun hashProperties(properties: JsonObject, propertyNames: Collection<String>): String {
        val values = normalizedValues(properties, propertyNames)
        val canonical = values.entries.sortedBy { it.key }.joinToString("\u001F") { "${it.key}=${it.value}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun normalizedValues(properties: JsonObject, propertyNames: Collection<String>): Map<String, String> =
        propertyNames.associateWith { normalize(properties[it]) }

    /** Names whose normalized values differ between two property sets. */
    fun diff(a: JsonObject, b: JsonObject, propertyNames: Collection<String>): List<String> {
        val va = normalizedValues(a, propertyNames)
        val vb = normalizedValues(b, propertyNames)
        return propertyNames.filter { va[it] != vb[it] }.sorted()
    }

    fun plainText(property: JsonElement?): String {
        val obj = property as? JsonObject ?: return ""
        val type = typeOf(obj) ?: return ""
        val arr = obj[type] as? JsonArray ?: return ""
        return arr.joinToString("") { el -> segmentText(el) }
    }

    /** Text of one rich-text segment, from plain_text (responses) or text.content (requests). */
    fun segmentText(segment: JsonElement): String {
        val o = segment as? JsonObject ?: return ""
        return o["plain_text"]?.jsonPrimitive?.contentOrNull
            ?: (o["text"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull
            ?: ""
    }

    private fun typeOf(obj: JsonObject): String? =
        obj["type"]?.jsonPrimitive?.contentOrNull ?: obj.keys.firstOrNull { it != "id" }

    private fun normalize(property: JsonElement?): String {
        val obj = property as? JsonObject ?: return EMPTY
        val type = typeOf(obj) ?: return EMPTY
        val value = obj[type]
        val text = when (type) {
            "title", "rich_text" -> plainText(obj)
            "select", "status" -> (value as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            "multi_select" -> (value as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }?.sorted()?.joinToString(",")
            "url", "checkbox", "number", "email", "phone_number" -> (value as? JsonPrimitive)?.contentOrNull
            "date" -> (value as? JsonObject)?.let {
                (it["start"]?.jsonPrimitive?.contentOrNull ?: "") + "/" + (it["end"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            "files" -> (value as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }?.joinToString(",")
            else -> value?.toString()
        }
        return if (text.isNullOrEmpty()) EMPTY else text
    }
}
