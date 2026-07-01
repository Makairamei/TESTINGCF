package com.sad25kag.donghuafilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaFilmPlugin : Plugin() {
    override fun load(context: Context) {
        DonghuaFilmCosmetic.context = context
        DonghuaFilm.context = context
        LicenseClient.init(context, "DonghuaFilm")
        registerMainAPI(DonghuaFilmCosmetic())
        registerExtractorAPI(DonghuaFilmDailyMotion())
        registerExtractorAPI(DonghuaFilmGeoDailyMotion())
        registerExtractorAPI(DonghuaFilmOdnoklassniki())
        registerExtractorAPI(DonghuaFilmOkRuSSL())
        registerExtractorAPI(DonghuaFilmOkRuHTTP())
    }
}
