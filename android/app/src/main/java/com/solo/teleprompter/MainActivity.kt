package com.solo.teleprompter

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(TeleprompterPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
