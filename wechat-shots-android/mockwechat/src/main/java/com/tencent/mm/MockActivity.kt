package com.tencent.mm

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 仅用于端到端测试的微信界面模拟器。包名与真实微信一致，但不包含任何微信代码。
 * 界面元素刻意保持和真实微信相似：主界面有“搜索”，搜索结果可点，聊天页有联系人标题、
 * 居中日期标签、可滚动消息列表和底部输入框。
 */
class MockActivity : Activity() {

    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(245, 245, 245))
        root.setPadding(0, dp(44), 0, 0)
        setContentView(root)
        showMain()
    }

    private fun reset() {
        root.removeAllViews()
    }

    private fun showMain() {
        reset()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(60))
        }
        top.addView(TextView(this).apply {
            text = "微信"
            textSize = 22f
            setTextColor(Color.rgb(20, 20, 20))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        top.addView(TextView(this).apply {
            text = "搜索"
            textSize = 16f
            setTextColor(Color.rgb(15, 118, 110))
            contentDescription = "搜索"
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showSearch("") }
        })
        page.addView(top)

        page.addView(divider())

        val hint = TextView(this).apply {
            text = "聊天"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(dp(18), dp(14), dp(18), dp(6))
        }
        page.addView(hint)

        listOf("张三" to "周末一起吃饭吗？", "李四" to "收到，谢谢").forEach { (name, preview) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setOnClickListener { showChat(name) }
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 18f
                setTextColor(Color.rgb(20, 20, 20))
            })
            row.addView(TextView(this).apply {
                text = preview
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            page.addView(row)
            page.addView(divider())
        }

        root.addView(page)
    }

    private fun showSearch(initial: String) {
        reset()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val field = EditText(this).apply {
            hint = "搜索"
            textSize = 17f
            setSingleLine(true)
            setText(initial)
            setTextColor(Color.rgb(20, 20, 20))
            setHintTextColor(Color.GRAY)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(238, 238, 238), dp(20))
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        top.addView(field)
        top.addView(TextView(this).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.rgb(15, 118, 110))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { showMain() }
        })
        page.addView(top)

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        page.addView(results)

        fun render(query: String) {
            results.removeAllViews()
            if (query.isBlank()) return
            val contacts = listOf(
                Triple("张三", "微信号：zhangsan", "周末一起吃饭吗？"),
                Triple("李四", "微信号：lisi", "收到，谢谢"),
            )
            contacts.forEach { (name, id, preview) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    setOnClickListener { showChat(name) }
                }
                row.addView(TextView(this).apply {
                    text = name.take(1)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(Color.rgb(15, 118, 110), dp(22))
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                })
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                }
                texts.addView(TextView(this).apply {
                    text = name
                    textSize = 18f
                    setTextColor(Color.rgb(20, 20, 20))
                })
                texts.addView(TextView(this).apply {
                    text = id + "  " + preview
                    textSize = 13f
                    setTextColor(Color.GRAY)
                })
                row.addView(texts)
                results.addView(row)
                results.addView(divider())
            }
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render(initial)

        root.addView(page)
        field.requestFocus()
    }

    private fun showChat(contact: String) {
        reset()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.rgb(237, 237, 237))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(247, 247, 247))
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.rgb(20, 20, 20))
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            setOnClickListener { showMain() }
        })
        header.addView(TextView(this).apply {
            text = contact
            textSize = 18f
            setTextColor(Color.rgb(20, 20, 20))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        header.addView(TextView(this).apply {
            text = "···"
            textSize = 22f
            setTextColor(Color.rgb(20, 20, 20))
            setPadding(dp(14), 0, dp(10), 0)
        })
        page.addView(header)
        page.addView(divider())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        buildMessages(messages)
        scroll.addView(messages)
        page.addView(scroll)

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(247, 247, 247))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        inputBar.addView(EditText(this).apply {
            hint = "输入消息"
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.WHITE, dp(20))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        })
        inputBar.addView(Button(this).apply {
            text = "发送"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(44)).apply {
                marginStart = dp(8)
            }
        })
        page.addView(inputBar)

        root.addView(page)
        scroll.postDelayed({ scroll.fullScroll(View.FOCUS_DOWN) }, 300)
    }

    private fun buildMessages(parent: LinearLayout) {
        // 真实微信里最早的消息在顶部、最新的在底部；这里按时间正序追加。
        val offsets = listOf(0L, 1L, 3L, 5L, 8L, 12L, 20L, 45L)
        offsets.reversed().forEachIndexed { position, daysAgo ->
            val groupIndex = offsets.lastIndex - position
            parent.addView(dateLabel(daysAgo))
            val count = if (groupIndex == 0) 28 else 24
            for (i in 1..count) {
                val left = (i + groupIndex) % 2 == 0
                val text = messageText(groupIndex, i, count, offsets.lastIndex)
                parent.addView(bubble(text, left))
            }
        }
    }

    /**
     * 每条消息都带唯一编号 + 伪随机长度/词语，避免聊天内容高度重复。
     * 重复气泡会让拼接引擎更容易把相邻截图对齐到错误位置，真实微信的内容重复度很低，
     * 测试数据也应该尽量接近这一点。
     */
    private fun messageText(groupIndex: Int, i: Int, count: Int, lastGroup: Int): String {
        val rnd = java.util.Random((groupIndex + 1) * 1_000_003L + i * 97L)
        val group = groupIndex + 1
        val id = String.format(Locale.US, "G%02d-M%02d", group, i)
        val body = when {
            groupIndex == 0 && i == count -> "最后一条消息，测试导出结束位置"
            groupIndex == lastGroup && i == 1 -> "这是很早以前的消息，应该只在全部记录里出现"
            i % 7 == 0 -> "第 " + group + " 组第 " + i + " 条，收到"
            i % 5 == 0 -> "好的，没问题"
            i % 3 == 0 -> "这是一条比较长的聊天消息，用来测试气泡换行和页面滚动效果"
            else -> "第 " + group + " 组消息 " + i
        }
        val words = listOf("中午", "晚上", "明天", "周末", "照片", "地铁", "咖啡", "文档", "会议", "散步")
        val filler = (0..rnd.nextInt(3)).joinToString("") { words[rnd.nextInt(words.size)] }
        return body + "（" + id + " " + filler + "）"
    }

    private fun dateLabel(daysAgo: Long): TextView {
        val today = LocalDate.now()
        val text = when (daysAgo) {
            0L -> "今天"
            1L -> "昨天"
            else -> today.minusDays(daysAgo).format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
        }
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(140, 140, 140))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
    }

    private fun bubble(text: String, left: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (left) Gravity.START else Gravity.END
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(if (left) Color.WHITE else Color.rgb(149, 236, 105), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        })
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(225, 225, 225))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
