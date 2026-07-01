package com.sad25kag.Animexin

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimexinProvider : Plugin() {
    override fun load(context: Context) {
        Animexin.context = context
        LicenseClient.init(context, "Animexin")
        registerMainAPI(Animexin())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(AnimexinVtbe())
        registerExtractorAPI(AnimexinWishFast())
        registerExtractorAPI(AnimexinSeekPlayer())
        registerExtractorAPI(AnimexinWaaw())
        registerExtractorAPI(AnimexinFileMoon())
    }
}
