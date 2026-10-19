package recloudstream.desidubanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DesiDubAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DesiDubAnimeProvider())
    }
}
