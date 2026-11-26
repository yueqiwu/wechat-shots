package com.dsh.wechatshot.video

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dsh.wechatshot.ui.IosAppTheme

/**
 * 仅 debug 包存在的调试入口（不进入 release）。
 *
 * 用法：
 * adb shell am start -n com.dsh.wechatshot/com.dsh.wechatshot.video.VideoDebugActivity \
 *     --es dsh_video_path /sdcard/Android/data/com.dsh.wechatshot/files/test.mp4
 *
 * VideoExportScreen 检测到 FLAG_DEBUGGABLE 且 intent 带 dsh_video_path 时会自动开始导出，
 * 方便 adb 在没有 UI 接线的情况下跑完整流程、抓 logcat / 截图验证。
 * 外面套 IosAppTheme 以复现正式 App 的配色（含深色模式）；状态栏留白由 VideoExportScreen 自己处理。
 */
class VideoDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IosAppTheme {
                VideoExportScreen(onBack = { finish() })
            }
        }
    }
}
