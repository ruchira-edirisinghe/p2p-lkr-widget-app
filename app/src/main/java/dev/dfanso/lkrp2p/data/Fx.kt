package dev.dfanso.lkrp2p.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import dev.dfanso.lkrp2p.core.FxClient
import dev.dfanso.lkrp2p.core.FxDay
import dev.dfanso.lkrp2p.core.FxSource
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.SeriesPoint
import dev.dfanso.lkrp2p.core.Trend
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Chart ranges for daily reference rates. */
enum class FxRange(val key: String, val label: String, val days: Int) {
    WEEK("7d", "1W", 7),
    MONTH("30d", "1M", 30),
    QUARTER("90d", "3M", 90),
    YEAR("365d", "1Y", 365);

    /** Days between archived samples: about 45 points per chart, however long the range. */
    val stepDays: Int get() = (days + 44) / 45

    companion object {
        fun fromKey(value: String?): FxRange? = entries.firstOrNull { it.key == value }
    }
}

/** A currency pair at one moment, with its history over [range]. */
data class FxQuote(
    val from: String,
    val to: String,
    val range: FxRange,
    /** Units of [to] per one [from], on [date]; null before the first fetch. */
    val rate: Double?,
    val date: String?,
    val series: List<SeriesPoint>,
    val trend: Trend?,
    val fetchedAtSec: Long?,
) {
    val pair: String get() = "${from.uppercase()}/${to.uppercase()}"
}

/** Daily rates, one row per currency per day, all against the US dollar. */
private class FxDb(context: Context) : SQLiteOpenHelper(context, "fx.sqlite", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE rates (date TEXT NOT NULL, code TEXT NOT NULL, per_usd REAL NOT NULL, " +
                "PRIMARY KEY (date, code)) WITHOUT ROWID;"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/**
 * Reference exchange rates for any pair, kept on the phone so the converter
 * and widgets work offline. Any pair is a cross through the dollar:
 * EUR→LKR = (LKR per USD) / (EUR per USD).
 */
class FxRepository private constructor(context: Context, private val source: FxSource) {
    private val db = FxDb(context)
    private val prefs = context.getSharedPreferences("fx", Context.MODE_PRIVATE)

    private fun save(day: FxDay) {
        db.writableDatabase.transaction {
            for ((code, value) in day.perUsd) {
                insertWithOnConflict("rates", null, ContentValues().apply {
                    put("date", day.date)
                    put("code", code)
                    put("per_usd", value)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun hasDay(date: String): Boolean =
        db.readableDatabase.rawQuery("SELECT 1 FROM rates WHERE date = ? LIMIT 1;", arrayOf(date)).use { it.moveToFirst() }

    val lastFetchSec: Long? get() = prefs.getLong("fetchedAt", 0).takeIf { it > 0 }

    /** Fetches the latest day. Throws on failure; the cache is left as it was. */
    suspend fun refresh() {
        save(source.day(null))
        prefs.edit { putLong("fetchedAt", System.currentTimeMillis() / 1000) }
        val client = source as? FxClient
        if (prefs.getString("names", null) == null && client != null) {
            runCatching { client.names() }.getOrNull()?.let { names: Map<String, String> ->
                prefs.edit { putString("names", JSONObject(names).toString()) }
                cachedNames = null
            }
        }
    }

    /** The source publishes once a day; checking every few hours is plenty. */
    suspend fun refreshIfDue(maxAgeSec: Long = 3 * 3_600) {
        val last = lastFetchSec ?: 0
        if (System.currentTimeMillis() / 1000 - last >= maxAgeSec) runCatching { refresh() }
    }

    /** Downloads the archived days [range] needs and does not have yet. */
    suspend fun backfill(range: FxRange) = coroutineScope {
        val today = System.currentTimeMillis()
        val dates = (range.stepDays..range.days step range.stepDays).map { dayString(today - it * 86_400_000L) }
            .filterNot(::hasDay)
        // A handful at a time: the archive is a CDN, but there is no need to hammer it.
        val gate = Semaphore(6)
        dates.map { date ->
            async { gate.withPermit { runCatching { source.day(date) }.getOrNull()?.let(::save) } }
        }.forEach { it.await() }
    }

    /** Currency codes with a rate on the latest stored day, sorted by display name. */
    fun currencies(): List<Pair<String, String>> {
        val names = names()
        val latest = latestDate() ?: return Defaults.COMMON.map { it to (names[it] ?: it.uppercase()) }
        val codes = db.readableDatabase.rawQuery("SELECT code FROM rates WHERE date = ?;", arrayOf(latest)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return codes.map { it to (names[it]?.takeIf(String::isNotBlank) ?: it.uppercase()) }
            .sortedWith(compareBy({ it.first !in Defaults.COMMON }, { it.second.lowercase() }))
    }

    fun name(code: String): String = names()[code]?.takeIf(String::isNotBlank) ?: code.uppercase()

    private var cachedNames: Map<String, String>? = null

    private fun names(): Map<String, String> = cachedNames ?: run {
        val json = prefs.getString("names", null)?.let(::JSONObject) ?: return Defaults.NAMES
        json.keys().asSequence().associateWith { json.optString(it) }.also { cachedNames = it }
    }

    private fun latestDate(): String? =
        db.readableDatabase.rawQuery("SELECT MAX(date) FROM rates;", null).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Units of [to] per one [from] on the latest day both are known. */
    fun quote(from: String, to: String, range: FxRange = FxRange.MONTH): FxQuote {
        val f = from.lowercase()
        val t = to.lowercase()
        val cutoff = dayString(System.currentTimeMillis() - range.days * 86_400_000L)
        val rows = db.readableDatabase.rawQuery(
            """
            SELECT a.date, b.per_usd / a.per_usd FROM rates a JOIN rates b ON a.date = b.date
            WHERE a.code = ? AND b.code = ? AND a.date >= ? ORDER BY a.date ASC;
            """,
            arrayOf(f, t, cutoff),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getDouble(1)) } }
        val latest = db.readableDatabase.rawQuery(
            """
            SELECT a.date, b.per_usd / a.per_usd FROM rates a JOIN rates b ON a.date = b.date
            WHERE a.code = ? AND b.code = ? ORDER BY a.date DESC LIMIT 1;
            """,
            arrayOf(f, t),
        ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getDouble(1) else null }
        val series = rows.map { (date, rate) -> SeriesPoint(parseDay(date), rate) }
        return FxQuote(
            from = f, to = t, range = range,
            rate = latest?.second, date = latest?.first,
            series = series, trend = MetricsEngine.trend(series),
            fetchedAtSec = lastFetchSec,
        )
    }

    /** Every stored day of [from]→[to], oldest first, for CSV export. */
    fun history(from: String, to: String): List<Pair<String, Double>> =
        db.readableDatabase.rawQuery(
            """
            SELECT a.date, b.per_usd / a.per_usd FROM rates a JOIN rates b ON a.date = b.date
            WHERE a.code = ? AND b.code = ? ORDER BY a.date ASC;
            """,
            arrayOf(from.lowercase(), to.lowercase()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getDouble(1)) } }

    object Defaults {
        /** Listed first in pickers: the currencies Sri Lankans deal in most. */
        val COMMON = listOf(
            "usd", "lkr", "eur", "gbp", "aud", "cad", "jpy", "inr", "aed", "sar", "qar", "kwd",
            "omr", "bhd", "sgd", "myr", "cny", "krw", "chf", "nzd", "mvr", "usdt", "btc", "eth",
        )

        /** Names for the common codes, used until the full list has been fetched once. */
        val NAMES = mapOf(
            "usd" to "US Dollar", "lkr" to "Sri Lankan Rupee", "eur" to "Euro", "gbp" to "British Pound",
            "aud" to "Australian Dollar", "cad" to "Canadian Dollar", "jpy" to "Japanese Yen",
            "inr" to "Indian Rupee", "aed" to "UAE Dirham", "sar" to "Saudi Riyal", "qar" to "Qatari Riyal",
            "kwd" to "Kuwaiti Dinar", "omr" to "Omani Rial", "bhd" to "Bahraini Dinar",
            "sgd" to "Singapore Dollar", "myr" to "Malaysian Ringgit", "cny" to "Chinese Yuan",
            "krw" to "South Korean Won", "chf" to "Swiss Franc", "nzd" to "New Zealand Dollar",
            "mvr" to "Maldivian Rufiyaa", "usdt" to "Tether", "btc" to "Bitcoin", "eth" to "Ethereum",
        )
    }

    companion object {
        @Volatile private var instance: FxRepository? = null

        fun get(context: Context): FxRepository = instance ?: synchronized(this) {
            instance ?: FxRepository(context.applicationContext, FxClient()).also { instance = it }
        }

        /** Swaps in an offline source, for render tests. */
        @androidx.annotation.VisibleForTesting
        fun installForTests(context: Context, source: FxSource): FxRepository =
            FxRepository(context.applicationContext, source).also { instance = it }

        private fun utcDayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun dayString(millis: Long): String = utcDayFormat().format(Date(millis))

        /** A published day, placed at noon UTC so it lands on the same date in every time zone. */
        fun parseDay(date: String): Long = (utcDayFormat().parse(date)?.time ?: 0L) / 1000 + 43_200
    }
}
