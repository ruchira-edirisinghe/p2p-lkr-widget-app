package dev.dfanso.lkrp2p

import android.app.Application
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.ui.Ui
import dev.dfanso.lkrp2p.work.AlertNotifier
import dev.dfanso.lkrp2p.work.CollectWorker

class P2PApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertNotifier.createChannel(this)
        Ui.apply(this, Settings.get(this).appTheme)
        // KEEP: re-launching the app must not reset the periodic schedule.
        CollectWorker.schedule(this)
    }
}
