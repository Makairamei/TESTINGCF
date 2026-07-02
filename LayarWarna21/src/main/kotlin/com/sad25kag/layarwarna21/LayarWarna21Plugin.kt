package com.sad25kag.layarwarna21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarWarna21Plugin : Plugin() {
    override fun load(context: Context) {
        LayarWarna21.context = context
        LicenseClient.init(context, "LayarWarna21")
        registerMainAPI(LayarWarna21())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Streamcasthub())
        registerExtractorAPI(Dm21upns())
        registerExtractorAPI(Gofile())
    }
}
