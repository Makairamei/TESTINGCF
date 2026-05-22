package recloudstream

import android.content.Context

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TwitchPlugin: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(TwitchProvider())
        registerExtractorAPI(TwitchProvider.TwitchExtractor())
    }
}
