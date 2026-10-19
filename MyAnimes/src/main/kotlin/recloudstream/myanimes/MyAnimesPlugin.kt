package recloudstream.myanimes

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MyAnimesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MyAnimesProvider())
    }
}
