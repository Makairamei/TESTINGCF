package com.sad25kag.dailymotion

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class DailymotionPlugin : Plugin() {
    override fun load(context: Context) {
        DailymotionProvider.context = context
        LicenseClient.init(context, "Dailymotion")
        registerMainAPI(DailymotionProvider())
    }
}
