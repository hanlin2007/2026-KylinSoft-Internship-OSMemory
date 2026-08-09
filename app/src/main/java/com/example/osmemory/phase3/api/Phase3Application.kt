package com.example.osmemory.phase3.api

import android.app.Application

/** 在任何入口创建 Repository/ModelProvider 之前安装本机安全配置。 */
class Phase3Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Phase3ModelBootstrap.installLocalKeyIfUnset(this)
    }
}
