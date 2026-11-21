package com.pkos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** M7 bn/en consistency: every Bengali string has an English one (and back) with the same placeholders. */
class TranslationParityTest {
    private fun load(dir: String): Map<String, String> {
        val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = linkedMapOf<String, String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as org.w3c.dom.Element
                if (e.getAttribute("translatable") == "false") continue
                out[tag + ":" + e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.toSortedSet()

    @Test fun bengaliAndEnglishMatch() {
        val bn = load("values"); val en = load("values-en")
        assertEquals("only in Bengali", emptySet<String>(), bn.keys - en.keys)
        assertEquals("only in English", emptySet<String>(), en.keys - bn.keys)
        val bad = bn.keys.filter { placeholders(bn[it]!!) != placeholders(en[it]!!) }
        assertTrue("placeholder mismatch: $bad", bad.isEmpty())
        assertTrue(bn.values.none { it.isBlank() } && en.values.none { it.isBlank() })
    }
}
