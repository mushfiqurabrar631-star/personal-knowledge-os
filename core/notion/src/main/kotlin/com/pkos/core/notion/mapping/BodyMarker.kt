package com.pkos.core.notion.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * PKOS writes its body blocks in one request; the last block is a footer paragraph that contains
 * `pkos-body:v1:<capture-id>`. Only that exact token (for this item's Capture ID) proves the PKOS
 * body exists. Other blocks — the owner's own notes, or a footer copied from another item — do not.
 */
object BodyMarker {
    const val PREFIX = "pkos-body:v1:"

    fun token(captureId: String) = PREFIX + captureId

    /** True if any block in a list-children response page carries the marker for [captureId]. */
    fun containsMarker(blocks: JsonArray, captureId: String): Boolean {
        val wanted = token(captureId)
        return blocks.any { block -> blockText(block as? JsonObject).contains(wanted) }
    }

    private fun blockText(block: JsonObject?): String {
        if (block == null) return ""
        val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return ""
        val content = block[type] as? JsonObject ?: return ""
        val rich = content["rich_text"] as? JsonArray ?: return ""
        return rich.joinToString("") { PropertySnapshot.segmentText(it) }
    }
}
