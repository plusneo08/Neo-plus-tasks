package com.neoplus.ai

import android.app.Application
import com.neoplus.ai.agent.AgentEngine

class NeoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentEngine.init(this)
    }
}
