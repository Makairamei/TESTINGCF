package com.sad25kag.idlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        IdlixProvider.context = context
        LicenseClient.init(context, "IdlixProvider")
        pingAnalytics("IdlixProvider")
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
    }
}
