import Foundation

/// Shared SQLite store. The collector app writes; the widget extension reads.
/// WAL mode lets those overlap across processes without the reader blocking.
///
/// `@unchecked Sendable` is sound here specifically because the handle is
/// opened with `SQLITE_OPEN_FULLMUTEX`, SQLite's serialized threading mode, so
/// concurrent use from multiple threads is safe. Remove that flag and this
/// conformance becomes a lie.
public final class Store: @unchecked Sendable {
    private let database: Database

    public init(fileURL: URL) throws {
        database = try Database(fileURL: fileURL)
        try database.execute("PRAGMA journal_mode=WAL;")
        try database.execute("PRAGMA synchronous=NORMAL;")
        try migrate()
    }

    func migrate() throws {
        try database.execute("""
        CREATE TABLE IF NOT EXISTS samples (
            ts             INTEGER NOT NULL,
            side           TEXT    NOT NULL,
            amount_usdt    INTEGER NOT NULL,
            fillable_price REAL,
            top_price      REAL    NOT NULL,
            median_top10   REAL,
            adv_name       TEXT,
            adv_available  REAL,
            adv_min_fiat   REAL,
            adv_max_fiat   REAL,
            PRIMARY KEY (ts, side, amount_usdt)
        );
        CREATE INDEX IF NOT EXISTS samples_lookup
            ON samples (side, amount_usdt, ts DESC);

        CREATE TABLE IF NOT EXISTS snapshot (
            side        TEXT PRIMARY KEY,
            captured_at INTEGER NOT NULL,
            payload     TEXT    NOT NULL
        );

        CREATE TABLE IF NOT EXISTS alerts (
            id            TEXT PRIMARY KEY,
            side          TEXT    NOT NULL,
            amount_usdt   INTEGER NOT NULL,
            threshold     REAL    NOT NULL,
            direction     TEXT    NOT NULL,
            last_state    TEXT    NOT NULL,
            last_fired_at INTEGER
        );
        """)
    }

    public func append(_ sample: Sample) throws {
        try database.statement("""
        INSERT OR REPLACE INTO samples
          (ts, side, amount_usdt, fillable_price, top_price, median_top10,
           adv_name, adv_available, adv_min_fiat, adv_max_fiat)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10);
        """) { statement in
            statement.bind(1, Int(sample.timestamp.timeIntervalSince1970))
            statement.bind(2, sample.side.rawValue)
            statement.bind(3, sample.amountUSDT)
            statement.bind(4, sample.fillablePrice)
            statement.bind(5, sample.topPrice)
            statement.bind(6, sample.medianTop10)
            statement.bind(7, sample.advertiserName)
            statement.bind(8, sample.advertiserAvailable)
            statement.bind(9, sample.advertiserMinFiat)
            statement.bind(10, sample.advertiserMaxFiat)
            _ = try statement.step()
        }
    }

    public func latest(side: Side, amountUSDT: Int) throws -> Sample? {
        try database.statement("""
        SELECT ts, fillable_price, top_price, median_top10,
               adv_name, adv_available, adv_min_fiat, adv_max_fiat
        FROM samples WHERE side = ?1 AND amount_usdt = ?2
        ORDER BY ts DESC LIMIT 1;
        """) { statement in
            statement.bind(1, side.rawValue)
            statement.bind(2, amountUSDT)
            guard try statement.step() else { return nil }
            return Sample(
                timestamp: Date(timeIntervalSince1970: TimeInterval(statement.int(0))),
                side: side,
                amountUSDT: amountUSDT,
                fillablePrice: statement.optionalDouble(1),
                topPrice: statement.double(2),
                medianTop10: statement.optionalDouble(3),
                advertiserName: statement.string(4),
                advertiserAvailable: statement.optionalDouble(5),
                advertiserMinFiat: statement.optionalDouble(6),
                advertiserMaxFiat: statement.optionalDouble(7)
            )
        }
    }

    /// Returns the number of rows removed.
    @discardableResult
    public func prune(olderThan cutoff: Date) throws -> Int {
        try database.statement("DELETE FROM samples WHERE ts < ?1;") { statement in
            statement.bind(1, Int(cutoff.timeIntervalSince1970))
            _ = try statement.step()
            return database.changes
        }
    }

    public func sampleCount() throws -> Int {
        try database.statement("SELECT COUNT(*) FROM samples;") { statement in
            _ = try statement.step()
            return statement.int(0)
        }
    }

    func journalMode() throws -> String {
        try database.statement("PRAGMA journal_mode;") { statement in
            _ = try statement.step()
            return statement.string(0) ?? ""
        }
    }

    /// Fillable prices inside `window`, oldest first, bucket-averaged per the
    /// window's `bucketSeconds`.
    ///
    /// Rows whose `fillable_price` is NULL are excluded: nothing was tradeable
    /// then, and substituting a zero or carrying the previous value forward
    /// would draw a line that never existed.
    public func series(side: Side, amountUSDT: Int,
                       window: ChartWindow, now: Date = .now) throws -> [SeriesPoint] {
        let cutoff = Int(now.addingTimeInterval(-window.duration).timeIntervalSince1970)
        let raw: [SeriesPoint] = try database.statement("""
        SELECT ts, fillable_price FROM samples
        WHERE side = ?1 AND amount_usdt = ?2 AND ts >= ?3 AND fillable_price IS NOT NULL
        ORDER BY ts ASC;
        """) { statement in
            statement.bind(1, side.rawValue)
            statement.bind(2, amountUSDT)
            statement.bind(3, cutoff)
            var points: [SeriesPoint] = []
            while try statement.step() {
                points.append(SeriesPoint(
                    timestamp: Date(timeIntervalSince1970: TimeInterval(statement.int(0))),
                    price: statement.double(1)))
            }
            return points
        }
        return MetricsEngine.downsample(raw, bucketSeconds: window.bucketSeconds)
    }

    public func replaceSnapshot(side: Side, ads: [Ad], capturedAt: Date) throws {
        let payload = try JSONEncoder().encode(ads)
        let text = String(decoding: payload, as: UTF8.self)
        try database.statement("""
        INSERT OR REPLACE INTO snapshot (side, captured_at, payload) VALUES (?1, ?2, ?3);
        """) { statement in
            statement.bind(1, side.rawValue)
            statement.bind(2, Int(capturedAt.timeIntervalSince1970))
            statement.bind(3, text)
            _ = try statement.step()
        }
    }

    public func snapshot(side: Side) throws -> (capturedAt: Date, ads: [Ad])? {
        try database.statement("""
        SELECT captured_at, payload FROM snapshot WHERE side = ?1;
        """) { statement in
            statement.bind(1, side.rawValue)
            guard try statement.step() else { return nil }
            let capturedAt = Date(timeIntervalSince1970: TimeInterval(statement.int(0)))
            guard let text = statement.string(1) else { return nil }
            let ads = try JSONDecoder().decode([Ad].self, from: Data(text.utf8))
            return (capturedAt, ads)
        }
    }

    public func upsertAlert(_ rule: AlertRule, state: AlertState, firedAt: Date?) throws {
        try database.statement("""
        INSERT OR REPLACE INTO alerts
          (id, side, amount_usdt, threshold, direction, last_state, last_fired_at)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7);
        """) { statement in
            statement.bind(1, rule.id)
            statement.bind(2, rule.side.rawValue)
            statement.bind(3, rule.amountUSDT)
            statement.bind(4, rule.threshold)
            statement.bind(5, rule.direction.rawValue)
            statement.bind(6, state.rawValue)
            if let firedAt {
                statement.bind(7, Int(firedAt.timeIntervalSince1970))
            } else {
                statement.bind(7, nil as Double?)
            }
            _ = try statement.step()
        }
    }

    public func alertRules() throws -> [AlertRule] {
        try database.statement("""
        SELECT id, side, amount_usdt, threshold, direction FROM alerts ORDER BY id;
        """) { statement in
            var rules: [AlertRule] = []
            while try statement.step() {
                guard let id = statement.string(0),
                      let side = statement.string(1).flatMap({ Side(rawValue: $0) }),
                      let direction = statement.string(4)
                          .flatMap({ ThresholdDirection(rawValue: $0) })
                else { continue }
                rules.append(AlertRule(id: id, side: side, amountUSDT: statement.int(2),
                                       threshold: statement.double(3), direction: direction))
            }
            return rules
        }
    }

    public func alertState(id: String) throws -> AlertState? {
        try database.statement("SELECT last_state FROM alerts WHERE id = ?1;") { statement in
            statement.bind(1, id)
            guard try statement.step() else { return nil }
            return statement.string(0).flatMap { AlertState(rawValue: $0) }
        }
    }

    public func deleteAlert(id: String) throws {
        try database.statement("DELETE FROM alerts WHERE id = ?1;") { statement in
            statement.bind(1, id)
            _ = try statement.step()
        }
    }
}
