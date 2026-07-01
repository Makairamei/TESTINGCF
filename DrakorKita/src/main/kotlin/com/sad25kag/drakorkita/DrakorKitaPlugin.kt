package com.sad25kag.drakorkita

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class DrakorKitaPlugin : Plugin() {
    override fun load(context: Context) {
        DrakorKita.context = context
        LicenseClient.init(context, "DrakorKita")
        registerMainAPI(DrakorKita())
    }
}
