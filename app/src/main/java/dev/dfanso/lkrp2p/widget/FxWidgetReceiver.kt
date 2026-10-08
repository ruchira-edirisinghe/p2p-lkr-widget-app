package dev.dfanso.lkrp2p.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class FxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FxWidget()
}

/** Repaints every widget of both kinds, e.g. after a theme change. */
object Widgets {
    suspend fun refreshAll(context: Context) {
        RateWidget.refreshAll(context)
        FxWidget.refreshAll(context)
    }
}
