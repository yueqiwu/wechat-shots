package com.dsh.wechatshot.automation

import android.graphics.Rect
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun asRect(): Rect = Rect(left, top, right, bottom)
}

class UiNode(
    val text: String,
    val desc: String,
    val className: String,
    val bounds: Bounds,
    val clickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val parent: UiNode?,
) {
    internal val children: MutableList<UiNode> = mutableListOf()

    val visible: Boolean get() = bounds.width > 0 && bounds.height > 0

    fun clickableAncestor(): UiNode? {
        var current: UiNode? = this
        var guard = 0
        while (current != null && guard < 20) {
            if (current.clickable && current.enabled && current.visible) return current
            current = current.parent
            guard++
        }
        return null
    }

    fun label(): String = text.ifBlank { desc }
}

class UiDocument(
    val root: UiNode,
    val width: Int,
    val height: Int,
    val packageName: String = "",
) {
    val nodes: List<UiNode> = buildList {
        fun visit(node: UiNode) {
            add(node)
            node.children.forEach(::visit)
        }
        visit(root)
    }.filter { it.visible }

    fun textNodes(): List<UiNode> = nodes.filter { it.text.isNotBlank() }

    fun findExactText(value: String): UiNode? =
        nodes.firstOrNull { it.text.trim() == value }

    fun findContainsText(value: String, exclude: (UiNode) -> Boolean = { false }): UiNode? =
        nodes.firstOrNull { it.text.contains(value) && !exclude(it) }

    fun titleMatches(contact: String): UiNode? {
        val candidates = nodes.filter {
            it.text.trim() == contact &&
                it.bounds.top < height * 0.20 &&
                it.bounds.height > 8
        }
        return candidates.minByOrNull { it.bounds.top }
    }

    fun bottomEditable(): UiNode? = nodes.filter {
        it.editable && it.enabled && it.bounds.top > height * 0.40
    }.maxByOrNull { it.bounds.top }

    fun topEditable(): UiNode? = nodes.filter {
        it.editable && it.enabled && it.bounds.top < height * 0.35
    }.minByOrNull { it.bounds.top }

    fun focusedEditable(): UiNode? = nodes.firstOrNull { it.editable && it.focused && it.enabled }

    fun searchEntry(): UiNode? {
        val byDesc = nodes.filter {
            it.desc.trim() == "搜索" && it.bounds.top < height * 0.30
        }.minByOrNull { it.bounds.top }
        if (byDesc != null) return byDesc
        return nodes.filter {
            it.text.trim() == "搜索" && it.bounds.top < height * 0.30
        }.minByOrNull { it.bounds.top }
    }

    /** 界面可见文本签名，用于判断“已经滑到顶部”。 */
    fun visibleTextSignature(): String = nodes.asSequence()
        .filter { it.bounds.top >= 0 && it.bounds.bottom <= height + 2 }
        .map { it.text.trim().ifEmpty { it.desc.trim() } }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    fun searchResultCandidates(contact: String, afterY: Int): List<UiNode> {
        val exact = nodes.filter {
            it.text.trim() == contact &&
                !it.editable &&
                it.bounds.top >= afterY &&
                it.bounds.height >= 18 &&
                it.bounds.top < height * 0.90
        }
        if (exact.isNotEmpty()) return exact.sortedBy { it.bounds.top }

        val lower = contact.lowercase()
        return nodes.filter {
            val t = it.text.trim().lowercase()
            t.contains(lower) &&
                !it.editable &&
                it.bounds.top >= afterY &&
                it.bounds.height >= 18 &&
                !t.startsWith("搜索") &&
                it.bounds.top < height * 0.90
        }.sortedBy { it.bounds.top }
    }

    companion object {
        /** 把 OCR 识别到的文本行包装成 UiDocument，供 DateTags 等现有逻辑复用。 */
        fun fromOcr(
            width: Int,
            height: Int,
            lines: List<OcrLine>,
            packageName: String = WeChatAutomator.WECHAT_PACKAGE,
        ): UiDocument {
            val root = UiNode(
                text = "",
                desc = "",
                className = "android.widget.FrameLayout",
                bounds = Bounds(0, 0, width, height),
                clickable = false,
                scrollable = false,
                enabled = true,
                editable = false,
                focused = false,
                parent = null,
            )
            lines.forEach { line ->
                root.children.add(
                    UiNode(
                        text = line.text,
                        desc = "",
                        className = "android.widget.TextView",
                        bounds = Bounds(line.left, line.top, line.right, line.bottom),
                        clickable = false,
                        scrollable = false,
                        enabled = true,
                        editable = false,
                        focused = false,
                        parent = root,
                    )
                )
            }
            return UiDocument(root, width, height, packageName)
        }

        fun parse(xml: String): UiDocument {
            if (!xml.contains("<hierarchy")) throw IOException("界面 XML 不完整")
            val factory = DocumentBuilderFactory.newInstance()
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching { factory.isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val rootElement = document.documentElement ?: throw IOException("界面 XML 没有根节点")
            val screen = parseBounds(rootElement.getAttribute("bounds"))
                ?: Bounds(0, 0, 1080, 2400)
            val root = parseElement(rootElement, null)
            // <hierarchy> 本身没有 package 属性，包名在第一个 <node> 上。
            val topNode = rootElement.getElementsByTagName("node").item(0) as? Element
            val pkg = rootElement.getAttribute("package").ifBlank {
                topNode?.getAttribute("package").orEmpty()
            }
            return UiDocument(
                root = root,
                width = screen.width,
                height = screen.height,
                packageName = pkg,
            )
        }

        private val boundsRegex = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

        fun parseBounds(raw: String?): Bounds? {
            if (raw.isNullOrBlank()) return null
            val match = boundsRegex.find(raw) ?: return null
            val values = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (values.size != 4) return null
            return Bounds(values[0], values[1], values[2], values[3])
        }

        private fun parseElement(element: Element, parent: UiNode?): UiNode {
            val bounds = parseBounds(element.getAttribute("bounds")) ?: Bounds(0, 0, 0, 0)
            val node = UiNode(
                text = element.getAttribute("text").orEmpty(),
                desc = element.getAttribute("content-desc").orEmpty(),
                className = element.getAttribute("class").orEmpty(),
                bounds = bounds,
                clickable = element.getAttribute("clickable") == "true",
                scrollable = element.getAttribute("scrollable") == "true",
                enabled = element.getAttribute("enabled") != "false",
                editable = element.getAttribute("class").contains("EditText", true),
                focused = element.getAttribute("focused") == "true",
                parent = parent,
            )
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element) {
                    node.children.add(parseElement(child, node))
                }
            }
            return node
        }
    }
}
