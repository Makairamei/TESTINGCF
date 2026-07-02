package com.sad25kag.gudangfilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GudangFilmPlugin : Plugin() {
    override fun load(context: Context) {
        GudangFilm.context = context
        LicenseClient.init(context, "GudangFilm")
        registerMainAPI(GudangFilm())
    }
}
