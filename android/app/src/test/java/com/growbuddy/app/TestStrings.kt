package com.growbuddy.app

import com.growbuddy.app.ui.common.AppText
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** AppText for JVM unit tests: the English texts straight from src/main/res/values/strings.xml. */
object TestStrings : AppText.Source {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    private fun unescape(raw: String): String {
        val s = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw.trim()
        return s.replace("\\'", "'").replace("\\\"", "\"").replace("\\@", "@").replace("\\?", "?")
    }

    private fun name(id: Int, type: String): String =
        Class.forName("com.growbuddy.app.R\$$type").fields.first { it.getInt(null) == id }.name

    override fun string(id: Int, args: Array<out Any>): String = strings.getValue(name(id, "string")).format(*args)

    override fun plural(id: Int, count: Int, args: Array<out Any>): String {
        val forms = plurals.getValue(name(id, "plurals"))
        return (if (count == 1) forms["one"] else forms["other"])!!.format(*args)
    }

    fun install() {
        AppText.source = this
    }
}
