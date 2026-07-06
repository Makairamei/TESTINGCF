package com.sad25kag.movieon21

import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieOn21Plugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context, "MovieOn21")
        MovieOn21.context = context
        registerMainAPI(MovieOn21())
    }
}
