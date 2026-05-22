// ! Bu araÃ§ @Kraptor123 tarafÄ±ndan | @Cs-GizliKeyif iÃ§in yazÄ±lmÄ±ÅŸtÄ±r.
package com.kraptor

import android.content.Context

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AZNudePlugin: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(AZNude())
    }
}
