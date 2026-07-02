package com.sad25kag.idlix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IdlixProviderPlugin: BasePlugin() {
    override fun load() {
        val ctx = context ?: return
        IdlixProvider.context = ctx
        LicenseClient.init(ctx, "IdlixProvider")
        pingAnalytics("IdlixProvider")
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
    }
}