package com.growbuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation (res/values-<code>/strings.xml) against the English original: no unknown
 * keys, and the same %1$s / %1$d placeholders. Missing keys fall back to English at runtime;
 * lint reports them as MissingTranslation. See docs/LOCALIZATION.md.
 */
class LocalizationTest {
    private val res = File("src/main/res")

    /** name -> text (plurals: all forms joined), translatable strings only. */
    private fun load(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    private val translations = res.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").exists() }.orEmpty()

    @Test
    fun danishIsComplete() {
        val english = load(File(res, "values/strings.xml"))
        assertEquals(english.keys, load(File(res, "values-da/strings.xml")).keys)
    }

    @Test
    fun translationsMatchTheEnglishStrings() {
        val english = load(File(res, "values/strings.xml"))
        assertTrue(translations.isNotEmpty())
        translations.forEach { dir ->
            val translated = load(File(dir, "strings.xml"))
            assertEquals("${dir.name}: keys not in values/strings.xml", emptySet<String>(), translated.keys - english.keys)
            translated.forEach { (key, text) ->
                assertEquals("${dir.name}/$key placeholders", placeholders(english.getValue(key)), placeholders(text))
            }
        }
    }
}
