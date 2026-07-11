package com.reynime

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class ReynimePlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context, "Reynime")
        ReynimeProvider.context = context
        registerMainAPI(ReynimeProvider())
    }
}
