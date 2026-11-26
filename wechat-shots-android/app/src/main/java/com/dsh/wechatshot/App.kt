package com.dsh.wechatshot

import android.app.Application
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.export.NotificationHelper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AdbManager.init(this)
        NotificationHelper.ensureChannel(this)
    }
}
