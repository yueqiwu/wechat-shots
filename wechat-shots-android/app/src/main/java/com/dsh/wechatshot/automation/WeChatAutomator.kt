package com.dsh.wechatshot.automation

import android.util.Log
import com.dsh.wechatshot.adb.AdbController
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.LocalDate

data class ChatLayout(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

/** 打开聊天页的结果。useOcr 表示微信没有暴露界面树，后续截图和日期识别走 OCR。 */
data class ChatOpenResult(
    val layout: ChatLayout,
    val useOcr: Boolean,
)

class AutomationException(
    message: String,
    val stage: String,
    val uiDump: String? = null,
) : IOException(message)

/**
 * 通过“搜索联系人 -> 打开聊天”的流程进入指定微信聊天。
 * 不使用任何微信内部接口，只依赖 Android 无障碍树和 input 命令。
 */
class WeChatAutomator(
    private val adb: AdbController,
    private val log: (String) -> Unit,
) {
    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    private var lastDump: String? = null

    suspend fun openChat(contactName: String): ChatOpenResult {
        val contact = contactName.trim()
        if (contact.isEmpty()) throw AutomationException("联系人名称不能为空", "准备")

        log("准备微信界面…")
        adb.wakeAndUnlock()
        if (!adb.isPackageInstalled(WECHAT_PACKAGE)) {
            throw AutomationException("手机上未安装微信（com.tencent.mm）", "启动")
        }

        adb.launchWeChat()
        // 真实微信对 UIAutomation / 无障碍服务都返回空节点树，先探测一次；
        // 空树时改走“坐标点击 + 截图 OCR”的路径。
        val probe = dumpQuiet()
        if (probe == null || probe.nodes.size <= 1) {
            return openChatByOcr(contact)
        }
        var doc = waitForWeChat()
        if (doc == null) {
            throw AutomationException("微信没有正常启动，请确认微信可以打开并已登录", "启动", lastDump)
        }
        if (isLoginScreen(doc)) {
            throw AutomationException("微信当前未登录，请先手动登录微信后再导出", "启动", lastDump)
        }

        // 打开搜索页。优先点 content-desc=“搜索”的入口，失败时点右上角兜底坐标。
        var searchPage = enterSearch(doc, contact)
        if (searchPage == null) {
            throw AutomationException("没有找到微信搜索入口，请保持微信主界面在前台后重试", "搜索", lastDump)
        }

        var field = searchPage.topEditable()
        if (field == null) {
            throw AutomationException("搜索页没有找到输入框", "搜索", lastDump)
        }
        adb.tap(field.bounds.centerX, field.bounds.centerY)
        delay(300)

        // 中文联系人无法用 input text 输入，使用系统剪贴板 + KEYCODE_PASTE。
        adb.keyEvent(279)
        delay(1_000)

        var afterPaste = dumpQuiet()
        val typed = afterPaste?.nodes?.firstOrNull { it.editable }?.text.orEmpty()
        if (!typed.contains(contact)) {
            // ASCII 联系人作为兜底，不依赖剪贴板。
            if (contact.all { it.code in 32..126 }) {
                log("剪贴板粘贴未生效，改用 input text 输入英文/数字联系人")
                adb.shell("input text " + contact.replace(" ", "%s"), 15_000)
                delay(1_000)
                afterPaste = dumpQuiet()
            }
        }
        if (afterPaste == null) afterPaste = dumpQuiet()
        val searchFieldBottom = afterPaste?.topEditable()?.bounds?.bottom ?: field.bounds.bottom

        val results = waitForResults(contact, searchFieldBottom + 4)
        if (results.isNullOrEmpty()) {
            throw AutomationException(
                "没有搜索到联系人“" + contact + "”。请确认微信搜索能手动找到该联系人",
                "搜索",
                lastDump
            )
        }

        val candidate = results.first()
        log("找到联系人“" + candidate.text.trim() + "”，正在打开聊天…")
        tapNode(candidate)
        delay(1_600)

        var chatDoc = dumpQuiet()
        detectChat(chatDoc, contact)?.let {
            hideKeyboardQuietly(chatDoc)
            val finalLayout = detectChat(dumpQuiet(), contact)
                ?: throw AutomationException("聊天界面被意外关闭，请保持微信在前台后重试", "打开聊天", lastDump)
            return ChatOpenResult(finalLayout, false)
        }

        // 有时会先进入联系人资料页，需要再点“发消息”。
        val sendButton = chatDoc?.nodes?.firstOrNull {
            val text = it.text.trim()
            (text == "发消息" || text == "发送消息") && it.bounds.height > 10
        }
        if (sendButton != null) {
            log("进入联系人资料页，点击“发消息”…")
            tapNode(sendButton)
            delay(1_600)
            chatDoc = dumpQuiet()
            detectChat(chatDoc, contact)?.let {
                hideKeyboardQuietly(chatDoc)
                val finalLayout = detectChat(dumpQuiet(), contact)
                    ?: throw AutomationException("聊天界面被意外关闭，请保持微信在前台后重试", "打开聊天", lastDump)
                return ChatOpenResult(finalLayout, false)
            }
        }

        // 搜索结果可能没有响应第一次点击，再点一次联系人文字。
        val again = dumpQuiet()?.searchResultCandidates(contact, searchFieldBottom + 4)
        if (!again.isNullOrEmpty()) {
            log("聊天页尚未打开，重试点击联系人…")
            adb.tap(again.first().bounds.centerX, again.first().bounds.centerY)
            delay(1_500)
            chatDoc = dumpQuiet()
            detectChat(chatDoc, contact)?.let {
                hideKeyboardQuietly(chatDoc)
                val finalLayout = detectChat(dumpQuiet(), contact)
                    ?: throw AutomationException("聊天界面被意外关闭，请保持微信在前台后重试", "打开聊天", lastDump)
                return ChatOpenResult(finalLayout, false)
            }
        }

        throw AutomationException(
            "已找到联系人，但没有进入聊天界面。请确认该联系人可以正常发消息",
            "打开聊天",
            lastDump
        )
    }

    /**
     * 微信不提供界面树时的兜底路径：用固定比例点开搜索，再用截图 OCR 找到联系人。
     * 不读取聊天内容，只识别联系人名、标题和底部输入框，用于定位。
     */
    private suspend fun openChatByOcr(contact: String): ChatOpenResult {
        log("微信不暴露界面结构，改用截图 OCR 定位…")
        val screenSize = adb.screenSize()
        val width = screenSize.width
        val height = screenSize.height

        var foreground = false
        for (i in 0 until 20) {
            if (adb.topPackage() == WECHAT_PACKAGE) {
                foreground = true
                break
            }
            delay(500)
        }
        if (!foreground) {
            throw AutomationException("微信没有正常启动，请确认微信可以打开并已登录", "启动")
        }

        // 真机冷启动微信很慢（10s+），只判断“微信在前台”会把开屏动画或切换过程
        // 当成微信主界面，进而在错误屏幕上点搜索框（甚至点回我们自己的 App）。
        // 这里等主界面真正就绪：底部「微信/通讯录/发现/我」至少出现 3 个。
        if (!waitForWeChatMainReady()) {
            throw AutomationException("微信没有正常启动或未登录，请先手动打开微信并登录后再试", "启动")
        }

        // 不再先 OCR 微信主界面的聊天列表：在真机上这一步会拖慢一分钟以上，
        // 而且“文件传输助手”这类功能入口的卡片标题不可点。直接走搜索最稳。
        log("打开微信搜索…")
        adb.tap((width * 0.85f).toInt(), (height * 0.055f).toInt())
        delay(1_200)
        var screen = recognizeScreen()
        Log.d(
            "WeChatAutomator",
            "搜索页 OCR：" + screen.lines.joinToString(" | ") { it.text + "@" + it.top + "," + it.centerX },
        )
        if (screen.lines.none { it.text.contains("搜索") || it.text.contains("取消") }) {
            // 部分版本搜索图标略低，再试一次。
            adb.tap((width * 0.85f).toInt(), (height * 0.075f).toInt())
            delay(1_200)
            screen = recognizeScreen()
        }

        val searchField = screen.lines.firstOrNull {
            it.top < height * 0.18f &&
                (it.text.contains("搜索本地或网络结果") || it.text.trim() == "搜索")
        }
        val fieldX = searchField?.centerX ?: (width * 0.50f).toInt()
        // 真机上搜索框中心大约在 0.07h；0.10h 已经落到输入框下方的空白区域，
        // 会导致粘贴时输入框没有焦点。
        val fieldY = searchField?.centerY ?: (height * 0.075f).toInt()
        Log.d(
            "WeChatAutomator",
            "点击搜索框 " + fieldX + "," + fieldY + "，字段=" + (searchField?.text ?: "兜底坐标"),
        )
        adb.tap(fieldX, fieldY)
        delay(500)
        adb.keyEvent(279)
        delay(1_800)

        screen = recognizeScreen()
        Log.d(
            "WeChatAutomator",
            "粘贴后 OCR：" + screen.lines.joinToString(" | ") { it.text + "@" + it.top + "," + it.centerX },
        )
        var networkHeader = screen.lines.firstOrNull { it.text.trim() == "搜索网络结果" }
        var candidate: OcrLine? = findContactLine(
            screen.lines,
            contact,
            minTop = (height * 0.12f).toInt(),
            maxTop = networkHeader?.top ?: (height * 0.85f).toInt(),
        )
        if (candidate == null) {
            // 粘贴有时早于输入框获得焦点，再补一次。
            adb.tap(fieldX, fieldY)
            delay(350)
            adb.keyEvent(279)
            delay(1_500)
            screen = recognizeScreen()
            networkHeader = screen.lines.firstOrNull { it.text.trim() == "搜索网络结果" }
            candidate = findContactLine(
                screen.lines,
                contact,
                minTop = (height * 0.12f).toInt(),
                maxTop = networkHeader?.top ?: (height * 0.85f).toInt(),
            )
        }
        if (candidate == null) {
            throw AutomationException(
                "没有在微信搜索结果中找到“" + contact + "”。请确认名称与微信里显示的一致",
                "搜索",
                null,
            )
        }

        log("找到联系人“" + contact + "”，正在打开聊天…")
        // 微信搜索结果里的“功能”卡片整块不可点，右侧有“前往”按钮；
        // 普通联系人结果则直接点姓名所在行。
        // “前往”按钮和卡片标题可能相隔很远（卡片较高），按最近的“前往”取，
        // 不能用 2 倍文字高度的距离限制，否则会退回去点不可点的标题。
        val go = screen.lines.filter {
            it.text.trim() == "前往" && it.centerX > width * 0.65f
        }.minByOrNull { Math.abs(it.centerY - candidate.centerY) }
        val target = go ?: candidate
        if (go != null) log("点击联系人卡片的“前往”按钮…")
        adb.tap(target.centerX, target.centerY)
        delay(1_800)
        var opened = ensureChat(contact, height)
        if (!opened) {
            // 搜索结果页偶尔第一次点击只选中卡片，再点一次联系人名/前往。
            delay(700)
            val retry = recognizeScreen()
            val retryCandidate = findContactLine(
                retry.lines,
                contact,
                minTop = (height * 0.12f).toInt(),
                maxTop = (height * 0.85f).toInt(),
            ) ?: candidate
            val retryGo = retry.lines.filter {
                it.text.trim() == "前往" && it.centerX > width * 0.65f
            }.minByOrNull { Math.abs(it.centerY - retryCandidate.centerY) }
            val retryTarget = retryGo ?: retryCandidate
            adb.tap(retryTarget.centerX, retryTarget.centerY)
            delay(1_800)
            opened = ensureChat(contact, height)
        }
        if (opened) {
            val layout = chatLayout(width, height)
            delay(700)
            scrollChatToBottom(layout)
            return ChatOpenResult(layout, true)
        }
        throw AutomationException(
            "已找到联系人，但没有进入聊天界面。请确认该联系人可以正常发消息",
            "打开聊天",
            null,
        )
    }

    private fun chatLayout(width: Int, height: Int): ChatLayout =
        ChatLayout(0, (height * 0.11f).toInt(), width, (height * 0.92f).toInt())

    /**
     * 微信重新打开聊天时可能恢复上次的滚动位置。导出前先向下滚到最新消息，
     * 否则“全部记录”会漏掉最新部分，日期区间也可能因为起点落在区间之前而直接结束。
     */
    private suspend fun scrollChatToBottom(layout: ChatLayout) {
        // 微信会恢复上次的滚动位置，可能停在几个月前。这里用快速甩动多翻几屏，
        // 确保从最新消息开始截图；到顶后继续甩动不会产生副作用。
        // 起点不能太靠近底部：88% 高度已经落在输入栏上，滑动会被输入框吃掉。
        repeat(16) {
            val startY = layout.top + (layout.height * 0.72f).toInt()
            val endY = layout.top + (layout.height * 0.18f).toInt()
            adb.swipe(layout.centerX, startY, layout.centerX, endY, 160)
            delay(250)
        }
        delay(900)
        log("已滚动到聊天最新消息")
    }

    private suspend fun recognizeScreen(): OcrScreen =
        OcrEngine.recognizePng(adb.screencapPng())

    private suspend fun ensureChat(contact: String, height: Int): Boolean {
        repeat(4) { attempt ->
            val screen = recognizeScreen()
            if (isChatPage(screen.lines, contact, height)) return true
            val send = screen.lines.firstOrNull {
                isSendLabel(it.text) && it.top > height * 0.12f && it.top < height * 0.95f
            }
            if (send != null) {
                log("进入联系人资料页，点击“发消息”…")
                adb.tap(send.centerX, send.centerY)
                delay(1_600)
                return@repeat
            } else {
                Log.d(
                    "WeChatAutomator",
                    "ensureChat 未找到发消息，OCR 行：" + screen.lines.joinToString(" | ") {
                        it.text + "@" + it.top + "," + it.centerX
                    },
                )
            }
            if (attempt == 0) delay(900)
        }
        return false
    }

    /** 顶部标题可能被 OCR 认成繁体/异体字（如“文件传輸助手”），用字符重合度兜底。 */
    private fun titleMatches(raw: String, contact: String): Boolean {
        val title = raw.replace(" ", "").replace("\u00A0", "")
        val target = contact.replace(" ", "").replace("\u00A0", "")
        if (title.contains(target) || target.contains(title)) return true
        if (target.isEmpty()) return false
        val common = target.count { it in title }
        return common >= Math.ceil(target.length * 0.6).toInt().coerceAtLeast(2)
    }

    private fun isChatPage(lines: List<OcrLine>, contact: String, height: Int): Boolean {
        // 搜索结果页顶部有“AI搜索/全部/视频…”标签，偶尔还残留“搜索网络结果”；
        // 这些标记可以可靠地把搜索结果页排除掉，避免把视频流当成聊天记录一直截图。
        val searchMarkers = listOf("AI搜索", "搜索网络结果", "应用下载服务", "相关搜索")
        if (lines.any { line ->
                line.top < height * 0.45f &&
                    searchMarkers.any { marker -> line.text.contains(marker) }
            }
        ) {
            return false
        }
        val hasTitle = lines.any {
            it.top < height * 0.12f && titleMatches(it.text, contact)
        }
        if (!hasTitle) return false
        val hasInput = lines.any {
            val text = it.text.trim()
            (text.contains("输入消息") || text.contains("按住") || text == "发送") &&
                it.top > height * 0.80f
        }
        if (hasInput) return true
        // 资料页有明显的“发消息”按钮，聊天页没有。
        val hasSendButton = lines.any { isSendLabel(it.text) }
        if (hasSendButton) return false
        // 搜索页的键盘会识别出大量单字母行，不能靠“底部有文字”误判成聊天页。
        val keyboardLike = lines.count {
            it.top > height * 0.78f && it.text.trim().length == 1
        } > 10
        if (keyboardLike) return false
        // 真实聊天页的时间分隔（周一 22:02 / 昨天 01:36 / 2026年11月18日）是关键特征；
        // 搜索结果页只有“15天前/2个月前”，不会被解析成日期。
        return lines.any {
            it.top > height * 0.12f && it.top < height * 0.90f &&
                DateTags.parse(it.text, LocalDate.now()) != null
        }
    }

    /** OCR 会把“发消息”前面的气泡图标识别成 O/○/回 等字符，先去掉非汉字前缀再比较。 */
    private fun isSendLabel(raw: String): Boolean {
        val compact = raw.replace(" ", "").replace(" ", "")
        val chinese = compact.dropWhile { it !in '一'..'鿿' }
        return chinese == "发消息" || chinese == "发送消息"
    }

    private fun findContactLine(
        lines: List<OcrLine>,
        contact: String,
        minTop: Int,
        maxTop: Int,
    ): OcrLine? {
        val target = contact.trim()
        if (target.isEmpty()) return null
        val exact = lines.filter {
            it.text.trim() == target && it.top in minTop..maxTop && it.height >= 18
        }
        if (exact.isNotEmpty()) return exact.minByOrNull { it.top }

        val lower = target.lowercase()
        return lines.filter {
            val text = it.text.trim().lowercase()
            text.contains(lower) &&
                text.length <= target.length + 16 &&
                !text.startsWith("搜索") &&
                it.top in minTop..maxTop &&
                it.height >= 18
        }.minByOrNull { it.top }
    }

    private suspend fun enterSearch(main: UiDocument, contact: String): UiDocument? {
        val entry = main.searchEntry()
        if (entry != null) {
            tapNode(entry)
        } else {
            log("没有识别到搜索按钮，尝试点击右上角…")
            adb.tap((main.width * 0.93f).toInt(), (main.height * 0.07f).toInt())
        }
        var page = waitForSearchPage()
        if (page != null) return page

        // 再尝试一次：某些版本主界面的搜索入口在顶部中间。
        val fallbackEntry = main.nodes.firstOrNull {
            (it.text.trim() == "搜索" || it.desc.trim() == "搜索") &&
                it.bounds.top < main.height * 0.25
        }
        if (fallbackEntry != null) {
            tapNode(fallbackEntry)
        } else {
            adb.tap((main.width * 0.50f).toInt(), (main.height * 0.07f).toInt())
        }
        page = waitForSearchPage()
        return page
    }

    private suspend fun waitForWeChatMainReady(): Boolean {
        var relaunched = 0
        var backs = 0
        repeat(16) { attempt ->
            val pkg = adb.topPackage()
            if (pkg != WECHAT_PACKAGE) {
                // 微信被系统切到后台（或冷启动还在切换），必要时重新拉起。
                if (attempt > 0 && relaunched < 2) {
                    relaunched++
                    adb.launchWeChat()
                    delay(1_500)
                    return@repeat
                }
                delay(700)
                return@repeat
            }
            val screen = recognizeScreen()
            val texts = screen.lines.map { it.text.trim() }
            if (isLoginScreenTexts(texts)) {
                throw AutomationException("微信未登录，请先登录微信后再导出", "启动")
            }
            val tabCount = listOf("微信", "通讯录", "发现", "我").count { tab -> texts.any { it == tab } }
            if (tabCount >= 3) {
                delay(500)
                return true
            }
            // 微信会恢复上次停留的页面（聊天气泡页/搜索页/朋友圈等），
            // 这里按返回键退回主界面（有底部四个标签页），否则后续点不到搜索入口。
            if (backs < 6) {
                backs++
                runCatching { adb.shell("input keyevent 4", 3_000) }
                delay(800)
                return@repeat
            }
            delay(900)
        }
        return false
    }

    private fun isLoginScreenTexts(texts: List<String>): Boolean =
        texts.any { it == "进入微信" || it == "登录" } &&
            texts.any { it.contains("手机号") || it.contains("用其他方式登录") }

    private suspend fun waitForWeChat(): UiDocument? {
        repeat(7) { index ->
            if (index > 0) delay(800)
            val doc = dumpQuiet() ?: return@repeat
            if (isLoginScreen(doc)) return doc
            if (doc.nodes.any { it.text.trim() == "微信" || it.text.trim() == "通讯录" }) return doc
            if (index >= 3 && adb.isWeChatForeground()) return doc
        }
        return dumpQuiet()
    }

    private suspend fun waitForSearchPage(): UiDocument? {
        repeat(8) {
            delay(450)
            val doc = dumpQuiet() ?: return@repeat
            if (doc.topEditable() != null) return doc
            // 搜索页即使输入框还没出现，只要出现了“取消/搜索”也再等一轮。
        }
        return dumpQuiet()?.takeIf { it.topEditable() != null }
    }

    private suspend fun waitForResults(contact: String, afterY: Int): List<UiNode>? {
        repeat(8) {
            delay(450)
            val doc = dumpQuiet() ?: return@repeat
            val candidates = doc.searchResultCandidates(contact, afterY)
            if (candidates.isNotEmpty()) return candidates
        }
        return null
    }

    private fun isLoginScreen(doc: UiDocument): Boolean {
        val hasLoginButton = doc.nodes.any {
            it.text.trim() == "登录" && it.bounds.height > 20 && it.clickable
        }
        val hasWeChatBrand = doc.nodes.any {
            it.text.trim() == "微信" && it.bounds.top < doc.height * 0.30
        }
        return hasLoginButton && hasWeChatBrand
    }

    private fun dumpQuiet(): UiDocument? {
        return try {
            val xml = adb.dumpUi()
            lastDump = xml
            UiDocument.parse(xml)
        } catch (e: Exception) {
            log("读取界面结构失败：" + (e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun tapNode(node: UiNode) {
        val target = node.clickableAncestor() ?: node
        adb.tap(target.bounds.centerX, target.bounds.centerY)
    }

    private fun hideKeyboardQuietly(doc: UiDocument?) {
        // 只在一个输入框确实持有焦点时按返回键；否则 dumpsys 的状态可能滞后，
        // 一次误按就会把聊天页关掉，随后截到的是本 App 自己。
        val hasFocusedEditor = doc?.focusedEditable() != null
        if (hasFocusedEditor && runCatching { adb.isKeyboardShown() }.getOrDefault(false)) {
            runCatching { adb.keyEvent(4) }
            Thread.sleep(450)
        }
    }

    private fun detectChat(doc: UiDocument?, contact: String): ChatLayout? {
        if (doc == null) return null
        if (doc.packageName != WECHAT_PACKAGE) return null
        val height = doc.height
        val title = doc.titleMatches(contact) ?: doc.nodes.asSequence()
            .filter {
                !it.editable &&
                    it.bounds.top < height * 0.18 &&
                    it.text.trim().contains(contact) &&
                    it.bounds.height > 12
            }
            .minByOrNull { it.bounds.top }
            ?: return null
        val input = doc.bottomEditable() ?: return null

        var top = (title.bounds.bottom + 2).coerceAtLeast((height * 0.06f).toInt())
        var bottom = (input.bounds.top - 2).coerceAtMost((height * 0.97f).toInt())
        if (bottom - top < height * 0.20f) {
            top = (height * 0.10f).toInt()
            bottom = (height * 0.88f).toInt()
        }

        val scroll = doc.nodes.asSequence()
            .filter {
                it.scrollable &&
                    it.bounds.height > height * 0.25 &&
                    it.bounds.top >= height * 0.03 &&
                    it.bounds.top < height * 0.30 &&
                    it.bounds.bottom <= input.bounds.top + 4
            }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height.toLong() }
        if (scroll != null) {
            val left = scroll.bounds.left.coerceAtLeast(0)
            val right = scroll.bounds.right.coerceAtMost(doc.width)
            val scrollTop = scroll.bounds.top.coerceAtLeast(top)
            val scrollBottom = scroll.bounds.bottom.coerceAtMost(bottom)
            if (right - left > 10 && scrollBottom - scrollTop > height * 0.20f) {
                return ChatLayout(left, scrollTop, right, scrollBottom)
            }
        }
        return ChatLayout(0, top, doc.width, bottom)
    }
}
