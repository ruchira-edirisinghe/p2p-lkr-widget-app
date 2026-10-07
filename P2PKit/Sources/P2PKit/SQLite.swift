import Foundation
import SQLite3

public enum StoreError: Error, Equatable {
    case open(String)
    case prepare(String)
    case step(String)
}

/// Minimal wrapper over the system SQLite so the package stays dependency-free.
final class Database: @unchecked Sendable {
    private var handle: OpaquePointer?

    init(fileURL: URL) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(fileURL.path, &handle, flags, nil) == SQLITE_OK,
              handle != nil else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            throw StoreError.open(message)
        }
        sqlite3_busy_timeout(handle, 3_000)
    }

    deinit { if let handle { sqlite3_close_v2(handle) } }

    var errorMessage: String {
        handle.map { String(cString: sqlite3_errmsg($0)) } ?? "no handle"
    }

    func execute(_ sql: String) throws {
        guard sqlite3_exec(handle, sql, nil, nil, nil) == SQLITE_OK else {
            throw StoreError.step(errorMessage)
        }
    }

    /// Prepares `sql`, hands the statement to `body`, and always finalises it.
    func statement<T>(_ sql: String, _ body: (Statement) throws -> T) throws -> T {
        var raw: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &raw, nil) == SQLITE_OK, let raw else {
            throw StoreError.prepare(errorMessage)
        }
        defer { sqlite3_finalize(raw) }
        return try body(Statement(raw: raw, database: self))
    }

    var changes: Int { Int(sqlite3_changes(handle)) }
}

/// Bind indices are 1-based; column indices are 0-based. SQLite's convention,
/// preserved here rather than hidden, to match its documentation.
struct Statement {
    let raw: OpaquePointer
    let database: Database

    func bind(_ index: Int32, _ value: Double) { sqlite3_bind_double(raw, index, value) }
    func bind(_ index: Int32, _ value: Int) { sqlite3_bind_int64(raw, index, Int64(value)) }
    func bind(_ index: Int32, _ value: String) {
        sqlite3_bind_text(raw, index, value, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
    }
    func bind(_ index: Int32, _ value: Double?) {
        if let value { bind(index, value) } else { sqlite3_bind_null(raw, index) }
    }
    func bind(_ index: Int32, _ value: String?) {
        if let value { bind(index, value) } else { sqlite3_bind_null(raw, index) }
    }

    /// True when a row is available.
    func step() throws -> Bool {
        switch sqlite3_step(raw) {
        case SQLITE_ROW:  return true
        case SQLITE_DONE: return false
        default:          throw StoreError.step(database.errorMessage)
        }
    }

    func double(_ column: Int32) -> Double { sqlite3_column_double(raw, column) }
    func int(_ column: Int32) -> Int { Int(sqlite3_column_int64(raw, column)) }

    func optionalDouble(_ column: Int32) -> Double? {
        sqlite3_column_type(raw, column) == SQLITE_NULL ? nil : double(column)
    }
    func string(_ column: Int32) -> String? {
        guard let text = sqlite3_column_text(raw, column) else { return nil }
        return String(cString: text)
    }
}
