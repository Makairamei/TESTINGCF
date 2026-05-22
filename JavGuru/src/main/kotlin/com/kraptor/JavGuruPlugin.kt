// ! Bu araÃ§ @Kraptor123 tarafÄ±ndan | @Cs-GizliKeyif iÃ§in yazÄ±lmÄ±ÅŸtÄ±r.
package com.kraptor

import android.content.Context

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JavGuruPlugin: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(JavGuru())
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(DoodDoply())
        registerExtractorAPI(DoodVideo())
        registerExtractorAPI(d000d())
        registerExtractorAPI(VidhideVIP())
        registerExtractorAPI(Voe())
        registerExtractorAPI(javclan())
        registerExtractorAPI(Javggvideo())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(Javlion())
        registerExtractorAPI(swhoi())
        registerExtractorAPI(Javsw())
        registerExtractorAPI(Javmoon())
        registerExtractorAPI(Maxstream())
        registerExtractorAPI(MixDropis())
        registerExtractorAPI(Ds2Play())
        registerExtractorAPI(Streamwish())
        registerExtractorAPI(Vidhidepro())
        registerExtractorAPI(Streamhihi())
        registerExtractorAPI(Stape())
        registerExtractorAPI(StreamTAPE())
        registerExtractorAPI(StreamTapeNet())
        registerExtractorAPI(StreamTapeXyz())
        registerExtractorAPI(ShaveTape())
        registerExtractorAPI(Watchadsontape())
        registerExtractorAPI(Lancewhoisdifficult())
        registerExtractorAPI(Javlesbians())
    }
}
