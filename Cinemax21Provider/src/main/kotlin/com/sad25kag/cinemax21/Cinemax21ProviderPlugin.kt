package com.sad25kag.cinemax21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Cinemax21ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        Cinemax21Provider.context = context
        LicenseClient.init(context, "Cinemax21Provider")
        registerMainAPI(Cinemax21Provider())
        registerExtractorAPI(Jeniusplay())
    }
}
