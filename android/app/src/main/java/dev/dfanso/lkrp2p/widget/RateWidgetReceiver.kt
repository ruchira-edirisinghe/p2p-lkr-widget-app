package dev.dfanso.lkrp2p.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dev.dfanso.lkrp2p.work.CollectWorker

class RateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RateWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // The first widget on the home screen is what makes collection matter.
        CollectWorker.schedule(context)
        CollectWorker.runNow(context)
    }
}
