package com.dsh.wechatshot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.dsh.wechatshot.ui.IosAppTheme
import com.dsh.wechatshot.ui.WeChatShotsRoot

/**
 * 唯一 Activity：只负责窗口外观与根 Composable，具体界面在 com.dsh.wechatshot.ui 包。
 */
class MainActivity : ComponentActivity() {

    private val incomingContact = mutableStateOf("")

    /** 每次回到前台 +1，用于重新检查权限（用户可能刚从系统设置页返回）。 */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边绘制：状态栏/导航栏透明，内容由 Compose 的 insets padding 控制
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        incomingContact.value = intent?.getStringExtra(EXTRA_CONTACT).orEmpty()
        setContent {
            IosAppTheme {
                WeChatShotsRoot(
                    incomingContact = incomingContact.value,
                    resumeTick = resumeTick.intValue,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingContact.value = intent.getStringExtra(EXTRA_CONTACT).orEmpty()
    }

    companion object {
        const val EXTRA_CONTACT = "contact"
    }
}
