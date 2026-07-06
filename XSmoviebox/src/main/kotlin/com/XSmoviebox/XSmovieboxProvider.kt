package com.XSmoviebox

import android.content.Context

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XSmovieboxProvider: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(XSmoviebox())
    }
}

