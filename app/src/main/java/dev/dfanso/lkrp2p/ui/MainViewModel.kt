package dev.dfanso.lkrp2p.ui

import android.app.Application
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dfanso.lkrp2p.core.AlertRule
import dev.dfanso.lkrp2p.core.AlertState
import dev.dfanso.lkrp2p.core.BinanceP2PClient
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.Converter
import dev.dfanso.lkrp2p.core.P2PError
import dev.dfanso.lkrp2p.core.PaymentMethod
import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.ThresholdDirection
import dev.dfanso.lkrp2p.data.PollOutcome
import dev.dfanso.lkrp2p.data.Poller
import dev.dfanso.lkrp2p.data.RateSnapshot
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.data.Snapshot
import dev.dfanso.lkrp2p.data.Store
import dev.dfanso.lkrp2p.widget.RateWidget
import dev.dfanso.lkrp2p.widget.Widgets
import dev.dfanso.lkrp2p.work.AlertNotifier
import dev.dfanso.lkrp2p.work.CollectWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user has typed into the converter. */
data class ConverterInput(val text: String = "1", val isUsdt: Boolean = true) {
    val amount: Double? get() = text.replace(",", "").toDoubleOrNull()
}

data class AlertView(val rule: AlertRule, val state: AlertState)

data class UiState(
    val side: Side,
    val window: ChartWindow,
    /** Ads must accept an order this big to count towards the rate. */
    val orderSize: Int,
    val payment: PaymentMethod,
    val pollMinutes: Int,
    val defaultSide: Side,
    val widgetOpacity: Int = 100,
    val rate: RateSnapshot? = null,
    /** Latest sample for each side, for the Sell/Buy switch and the spread. */
    val latest: Map<Side, Sample?> = emptyMap(),
    val book: Snapshot? = null,
    val alerts: List<AlertView> = emptyList(),
    val converter: ConverterInput = ConverterInput(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val hasWidget: Boolean = true,
) {
    val quote: Converter.Quote?
        get() = converter.amount?.let { Converter.quote(book?.ads.orEmpty(), rate?.price, it, converter.isUsdt) }

    val spread: Double?
        get() {
            val buy = latest[Side.BUY]?.fillablePrice ?: return null
            val sell = latest[Side.SELL]?.fillablePrice ?: return null
            return buy - sell
        }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings.get(app)
    private val store = Store.get(app)

    private val _state = MutableStateFlow(
        UiState(
            side = settings.side,
            window = ChartWindow.DAY,
            orderSize = settings.amountUsdt,
            payment = settings.payment,
            pollMinutes = settings.pollMinutes,
            defaultSide = settings.side,
            widgetOpacity = settings.widgetOpacity,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** Called when the app comes to the front; fetches when the newest rate is over a minute old. */
    fun refreshIfOld() {
        viewModelScope.launch {
            val side = _state.value.side
            val sample = withContext(Dispatchers.IO) { store.latest(side, settings.amountUsdt) }
            val age = sample?.let { System.currentTimeMillis() / 1000 - it.timestampSec } ?: Long.MAX_VALUE
            if (age > 60) refresh() else reload()
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val outcomes = withContext(Dispatchers.IO) {
                Poller(BinanceP2PClient(), store, settings, { rule, price ->
                    AlertNotifier.present(context, rule, price)
                }).pollOnce()
            }
            val failure = outcomes.filterIsInstance<PollOutcome.Failed>()
                .firstOrNull { it.side == _state.value.side }
            RateWidget.refreshAll(context)
            _state.update { it.copy(refreshing = false, error = failure?.error?.let(::describe)) }
            reload()
        }
    }

    /** Plain-language errors; the raw ones are for logs, not people. */
    private fun describe(error: P2PError): String = when (error) {
        is P2PError.Transport -> "Couldn't reach Binance. Check your internet connection."
        is P2PError.HttpStatus -> "Binance didn't answer (error ${error.code}). Try again in a minute."
        is P2PError.EmptyResult -> "Binance returned no ads for this payment method right now."
        else -> "Binance sent something unexpected. Try again in a minute."
    }

    fun reload() {
        viewModelScope.launch {
            val s = _state.value
            val context = getApplication<Application>()
            val loaded = withContext(Dispatchers.IO) {
                Loaded(
                    rate = RateSnapshot.load(context, s.side, s.window),
                    latest = Side.entries.associateWith { store.latest(it, settings.amountUsdt) },
                    book = store.snapshot(s.side),
                    alerts = store.alertRules().map { AlertView(it, store.alertState(it.id) ?: AlertState.ARMED) },
                )
            }
            val hasWidget = runCatching {
                GlanceAppWidgetManager(context).getGlanceIds(RateWidget::class.java).isNotEmpty()
            }.getOrDefault(true)
            _state.update {
                it.copy(
                    rate = loaded.rate, latest = loaded.latest, book = loaded.book,
                    alerts = loaded.alerts, hasWidget = hasWidget,
                )
            }
        }
    }

    private class Loaded(
        val rate: RateSnapshot,
        val latest: Map<Side, Sample?>,
        val book: Snapshot?,
        val alerts: List<AlertView>,
    )

    fun setSide(side: Side) {
        _state.update { it.copy(side = side) }
        reload()
    }

    fun setWindow(window: ChartWindow) {
        _state.update { it.copy(window = window) }
        reload()
    }

    fun setConverterText(text: String) {
        // Digits and one decimal point; grouping commas are re-added on display.
        val clean = text.filter { it.isDigit() || it == '.' }
            .let { t -> val i = t.indexOf('.'); if (i < 0) t else t.substring(0, i + 1) + t.substring(i + 1).replace(".", "") }
            .take(12)
        _state.update { it.copy(converter = it.converter.copy(text = clean)) }
    }

    /** ↓↑: the result becomes the input, so the numbers on screen stay put. */
    fun swapConverter() {
        val s = _state.value
        val quote = s.quote
        val next = if (quote == null) {
            ConverterInput(if (s.converter.isUsdt) "" else "1", !s.converter.isUsdt)
        } else if (s.converter.isUsdt) {
            ConverterInput(trimNumber(quote.lkr, 2), false)
        } else {
            ConverterInput(trimNumber(quote.usdt, 2), true)
        }
        _state.update { it.copy(converter = next) }
    }

    private fun trimNumber(value: Double, decimals: Int): String =
        "%.${decimals}f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

    fun setWidgetOpacity(percent: Int) {
        settings.widgetOpacity = percent
        _state.update { it.copy(widgetOpacity = settings.widgetOpacity) }
        viewModelScope.launch { Widgets.refreshAll(getApplication()) }
    }

    fun setDefaultSide(side: Side) {
        settings.side = side
        _state.update { it.copy(defaultSide = side) }
    }

    fun setOrderSize(amount: Int) {
        if (amount <= 0) return
        settings.amountUsdt = amount
        _state.update { it.copy(orderSize = amount) }
        // A new order size is a new series: collect its first point straight away.
        refresh()
    }

    fun setPayment(payment: PaymentMethod) {
        settings.payment = payment
        _state.update { it.copy(payment = payment) }
        refresh()
    }

    fun setPollMinutes(minutes: Int) {
        settings.pollMinutes = minutes
        _state.update { it.copy(pollMinutes = settings.pollMinutes) }
        CollectWorker.schedule(getApplication(), replace = true)
    }

    fun addAlert(side: Side, threshold: Double, direction: ThresholdDirection) {
        val rule = AlertRule(side = side, amountUsdt = settings.amountUsdt, threshold = threshold, direction = direction)
        viewModelScope.launch(Dispatchers.IO) {
            store.upsertAlert(rule, AlertState.ARMED, null)
            reload()
        }
    }

    fun deleteAlert(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteAlert(id)
            reload()
        }
    }
}
