package dev.dfanso.lkrp2p.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.dfanso.lkrp2p.core.Ad
import dev.dfanso.lkrp2p.core.AlertRule
import dev.dfanso.lkrp2p.core.AlertState
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.core.SeriesPoint
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.ThresholdDirection
import org.json.JSONArray
import org.json.JSONObject

data class Snapshot(val capturedAtSec: Long, val ads: List<Ad>)

/** SQLite history. Same schema as the macOS P2PKit store. */
class Store private constructor(context: Context) :
    SQLiteOpenHelper(context, "p2p.sqlite", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        // The worker writes while the app or widget reads.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE samples (
                ts INTEGER NOT NULL,
                side TEXT NOT NULL,
                amount_usdt INTEGER NOT NULL,
                fillable_price REAL,
                top_price REAL NOT NULL,
                median_top10 REAL,
                adv_name TEXT,
                adv_available REAL,
                adv_min_fiat REAL,
                adv_max_fiat REAL
            );
            """
        )
        db.execSQL("CREATE INDEX samples_lookup ON samples (side, amount_usdt, ts);")
        db.execSQL(
            "CREATE TABLE snapshot (side TEXT PRIMARY KEY, captured_at INTEGER NOT NULL, payload TEXT NOT NULL);"
        )
        db.execSQL(
            """
            CREATE TABLE alerts (
                id TEXT PRIMARY KEY,
                side TEXT NOT NULL,
                amount_usdt INTEGER NOT NULL,
                threshold REAL NOT NULL,
                direction TEXT NOT NULL,
                state TEXT NOT NULL,
                fired_at INTEGER
            );
            """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun append(sample: Sample) {
        writableDatabase.insertOrThrow("samples", null, ContentValues().apply {
            put("ts", sample.timestampSec)
            put("side", sample.side.wire)
            put("amount_usdt", sample.amountUsdt)
            put("fillable_price", sample.fillablePrice)
            put("top_price", sample.topPrice)
            put("median_top10", sample.medianTop10)
            put("adv_name", sample.advertiserName)
            put("adv_available", sample.advertiserAvailable)
            put("adv_min_fiat", sample.advertiserMinFiat)
            put("adv_max_fiat", sample.advertiserMaxFiat)
        })
    }

    fun latest(side: Side, amountUsdt: Int): Sample? =
        readableDatabase.rawQuery(
            "SELECT * FROM samples WHERE side = ? AND amount_usdt = ? ORDER BY ts DESC LIMIT 1;",
            arrayOf(side.wire, amountUsdt.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            Sample(
                timestampSec = c.getLong(c.col("ts")),
                side = side,
                amountUsdt = amountUsdt,
                fillablePrice = c.doubleOrNull("fillable_price"),
                topPrice = c.getDouble(c.col("top_price")),
                medianTop10 = c.doubleOrNull("median_top10"),
                advertiserName = c.stringOrNull("adv_name"),
                advertiserAvailable = c.doubleOrNull("adv_available"),
                advertiserMinFiat = c.doubleOrNull("adv_min_fiat"),
                advertiserMaxFiat = c.doubleOrNull("adv_max_fiat"),
            )
        }

    /**
     * Fillable prices inside [window], oldest first, bucket-averaged.
     *
     * Rows whose fillable_price is NULL are excluded: nothing was tradeable
     * then, and substituting a value would draw a line that never existed.
     */
    fun series(
        side: Side,
        amountUsdt: Int,
        window: ChartWindow,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): List<SeriesPoint> {
        val cutoff = nowSec - window.durationSec
        val raw = readableDatabase.rawQuery(
            """
            SELECT ts, fillable_price FROM samples
            WHERE side = ? AND amount_usdt = ? AND ts >= ? AND fillable_price IS NOT NULL
            ORDER BY ts ASC;
            """,
            arrayOf(side.wire, amountUsdt.toString(), cutoff.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SeriesPoint(c.getLong(0), c.getDouble(1))) }
        }
        return MetricsEngine.downsample(raw, window.bucketSec)
    }

    /** Every stored sample as CSV, oldest first. */
    fun exportCsv(): String = buildString {
        appendLine("timestamp_utc,side,order_usdt,fillable_lkr,top_lkr,median_top10_lkr,advertiser")
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        readableDatabase.rawQuery(
            "SELECT ts, side, amount_usdt, fillable_price, top_price, median_top10, adv_name FROM samples ORDER BY ts ASC;",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.stringOrNull("adv_name")?.replace("\"", "\"\"")?.let { "\"$it\"" }.orEmpty()
                appendLine(
                    listOf(
                        format.format(java.util.Date(c.getLong(0) * 1000)), c.getString(1), c.getInt(2).toString(),
                        c.doubleOrNull("fillable_price")?.toString().orEmpty(), c.getDouble(4).toString(),
                        c.doubleOrNull("median_top10")?.toString().orEmpty(), name,
                    ).joinToString(",")
                )
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun clearForTests() {
        writableDatabase.apply { delete("samples", null, null); delete("snapshot", null, null); delete("alerts", null, null) }
    }

    fun prune(olderThanSec: Long): Int =
        writableDatabase.delete("samples", "ts < ?", arrayOf(olderThanSec.toString()))

    fun replaceSnapshot(side: Side, ads: List<Ad>, capturedAtSec: Long) {
        val payload = JSONArray().apply {
            ads.forEach { ad ->
                put(JSONObject().apply {
                    put("price", ad.price)
                    put("available", ad.availableUsdt)
                    put("minFiat", ad.minFiat)
                    put("maxFiat", ad.maxFiat)
                    put("payTime", ad.payTimeLimitMinutes)
                    put("name", ad.advertiserName)
                    put("orders", ad.monthOrderCount)
                    put("finish", ad.monthFinishRate)
                    put("positive", ad.positiveRate)
                })
            }
        }
        writableDatabase.insertWithOnConflict("snapshot", null, ContentValues().apply {
            put("side", side.wire)
            put("captured_at", capturedAtSec)
            put("payload", payload.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun snapshot(side: Side): Snapshot? =
        readableDatabase.rawQuery(
            "SELECT captured_at, payload FROM snapshot WHERE side = ?;", arrayOf(side.wire),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val array = JSONArray(c.getString(1))
            val ads = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Ad(
                    price = o.getDouble("price"),
                    availableUsdt = o.getDouble("available"),
                    minFiat = o.getDouble("minFiat"),
                    maxFiat = o.getDouble("maxFiat"),
                    payTimeLimitMinutes = o.optInt("payTime"),
                    advertiserName = o.optString("name"),
                    monthOrderCount = o.optInt("orders"),
                    monthFinishRate = o.optDouble("finish", 0.0),
                    positiveRate = o.optDouble("positive", 0.0),
                )
            }
            Snapshot(c.getLong(0), ads)
        }

    fun upsertAlert(rule: AlertRule, state: AlertState, firedAtSec: Long?) {
        val values = ContentValues().apply {
            put("id", rule.id)
            put("side", rule.side.wire)
            put("amount_usdt", rule.amountUsdt)
            put("threshold", rule.threshold)
            put("direction", rule.direction.name)
            put("state", state.name)
        }
        val db = writableDatabase
        // Keep the previous fired_at unless this decision fired.
        if (firedAtSec != null) values.put("fired_at", firedAtSec)
        val updated = db.update("alerts", values, "id = ?", arrayOf(rule.id))
        if (updated == 0) db.insertOrThrow("alerts", null, values)
    }

    fun alertRules(): List<AlertRule> =
        readableDatabase.rawQuery(
            "SELECT id, side, amount_usdt, threshold, direction FROM alerts ORDER BY threshold;", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val side = Side.fromWire(c.getString(1)) ?: continue
                    val direction = runCatching { ThresholdDirection.valueOf(c.getString(4)) }.getOrNull() ?: continue
                    add(AlertRule(c.getString(0), side, c.getInt(2), c.getDouble(3), direction))
                }
            }
        }

    fun alertState(id: String): AlertState? =
        readableDatabase.rawQuery("SELECT state FROM alerts WHERE id = ?;", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null else runCatching { AlertState.valueOf(c.getString(0)) }.getOrNull()
        }

    fun deleteAlert(id: String) {
        writableDatabase.delete("alerts", "id = ?", arrayOf(id))
    }

    private fun Cursor.col(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.doubleOrNull(name: String) = col(name).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.stringOrNull(name: String) = col(name).let { if (isNull(it)) null else getString(it) }

    companion object {
        @Volatile private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }
}
