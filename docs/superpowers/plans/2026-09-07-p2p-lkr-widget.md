# USDT/LKR P2P Rate Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a native macOS desktop widget that shows the best *tradeable* Binance P2P USDT/LKR rate, its direction, and a chart built from 5-minute samples.

**Architecture:** A hidden login-item app owns all polling — it fetches the P2P ad book every 5 minutes, computes the best price that can actually fill a configured trade size, writes samples to SQLite in a shared App Group container, posts threshold notifications, and reloads widget timelines. A read-only WidgetKit extension renders from that store. All logic lives in a `P2PKit` Swift package so it is testable without launching either bundle.

**Tech Stack:** Swift 6, SwiftUI, WidgetKit, Swift Charts, AppIntents, Swift Testing (`import Testing`), raw SQLite3 via `import SQLite3` (zero external dependencies), XcodeGen for project generation.

**Spec:** `docs/superpowers/specs/2026-09-07-p2p-lkr-widget-design.md`

## Global Constraints

- **Deployment target:** macOS 14.0. SDK in use is MacOSX26.5; Xcode 26.6; Swift 6.3.
- **Team ID:** `UN798LFFKG`, signing identity `Developer ID Application`.
- **App Group:** `UN798LFFKG.group.dev.dfanso.p2pmonitor`. The team-ID prefix is **mandatory** — this app ships outside the Mac App Store, where the bare `group.` form is rejected at runtime.
- **Bundle IDs:** app `dev.dfanso.p2pmonitor`, widget `dev.dfanso.p2pmonitor.widget`.
- **Zero external Swift dependencies.** SQLite comes from the system via `import SQLite3`.
- **Never name an app-target source file `main.swift`.** Swift treats that filename as top-level code and `@main` becomes a compile error: *"'main' attribute cannot be used in a module that contains top-level code."* Verified during design.
- **Re-run `xcodegen generate` after adding, renaming, or deleting any source file.** The file list is baked into the `.xcodeproj`; a stale project fails with *"Build input file cannot be found."* Verified during design.
- **`amountUSDT` is `Int` (whole USDT) everywhere.** It is a database key, and float equality in a `WHERE` clause is a bug waiting to happen.
- **`Store` and `Database` must be `@unchecked Sendable`.** `Store` is passed
  into the `Poller` actor, and Swift 6 strict concurrency rejects sending a
  non-Sendable class across an actor boundary. The conformance is sound only
  because the handle is opened `SQLITE_OPEN_FULLMUTEX` (serialized mode);
  drop that flag and the conformance becomes a lie. Hit during Task 10.
- **`SwiftUI.Settings` collides with `P2PKit.Settings`.** In any file importing
  both, EVERY bare use of `Settings` is ambiguous — the scene must be written
  `SwiftUI.Settings { ... }` and type annotations must be written
  `P2PKit.Settings`. Hit twice, in Tasks 11 and 17.
- **Do not use `@Published` outside an `ObservableObject`.** It needs Combine
  and an `ObservableObject` conformance to mean anything; plain stored
  properties (or `@Observable`) are correct for `CollectorService`.
- **Annotate `AppDelegate` `@MainActor`.** It touches the `@MainActor`
  `AppEnvironment`, which strict concurrency otherwise rejects.
- **AppIntents cannot use enums from another module.** The metadata extractor
  fails with "enums implemented in an imported framework or library are not
  supported", and it also requires `typeDisplayRepresentation` and
  `caseDisplayRepresentations` be compile-time constants (`static let`), not
  computed properties. The widget therefore declares local `SideOption` /
  `WindowOption` mirrors and maps to P2PKit types. Confirmed in Task 13.
- **`AppDelegate` MUST return `false` from
  `applicationShouldTerminateAfterLastWindowClosed`.** SwiftUI otherwise
  terminates this windowless `LSUIElement` agent seconds after launch; it then
  never reaches the poll timer and only ever writes the one immediate poll,
  while launchd respawns it on backoff. Rows keep appearing so collection
  looks fine — the tell is a different PID on every poll. Found only by
  running the installed app; no test catches it.
- **`log show` hides `info`-level entries** unless `--info` is passed, and
  `log` may be shadowed by a shell function — use `/usr/bin/log`.
- **No emoji in UI copy.** Use SF Symbols for iconography.
- **Tests never touch the network.** `URLProtocol` stubs serve the recorded fixtures.
- **Swift Testing runs tests in parallel by default.** Any suite touching
  process-global mutable state (the `URLProtocol` stub) MUST be declared
  `@Suite(.serialized)`, or concurrent tests read each other's stubbed
  responses. This bit Task 3 during execution.
- **No AI attribution in commit messages.**

## Verified Ground Truth

These were measured against the live API during design and are the source of the
test assertions below. Do not re-derive them; do not "correct" them.

Numeric JSON fields arrive as **strings** (`price`, `tradableQuantity`,
`minSingleTransAmount`, `dynamicMaxSingleTransAmount`, `maxSingleTransAmount`)
while `payTimeLimit`, `monthOrderCount`, `monthFinishRate`, and `positiveRate`
arrive as **numbers**. All were non-null across 40 sampled ads.

`minSingleTransAmount` / `dynamicMaxSingleTransAmount` are denominated in
**fiat (LKR)**, not USDT. Confirmed: 499,999 LKR ÷ 332.00 = 1,506 USDT, matching
that ad's own `minSingleTransQuantity` of 1506.02.

Ads arrive **best-first on both sides** — sell prices descending, buy prices
ascending (verified across all 20 ads per side). This is why the first
qualifying ad is the answer for both sides, with no maximise/minimise branch.

From `fixtures/lkr-sell-20260907.json` at 500 USDT:

| # | Price | Stock | LKR limits | Fiat needed | Verdict |
|---|---|---|---|---|---|
| 0 | 332.00 | 1510.00 | 499,999–500,000 | 166,000 | reject — below the 499,999 minimum |
| 1 | 331.00 | 100.00 | 5,000–15,000 | 165,500 | reject — stock 100 < 500, and over max |
| 2 | 331.00 | 700.00 | 10,000–220,000 | 165,500 | **QUALIFIES → 331.00, TD_TrustPay_LK** |
| 3 | 330.95 | 660.10 | 4,000–50,000 | 165,475 | reject — over max |

Rows 1 and 2 share a price but only one qualifies, which is exactly why the
filter cannot be a simple price sort.

Other verified values:

| Query | Fixture | Expected |
|---|---|---|
| sell @ 500 USDT | sell | 331.00, `TD_TrustPay_LK` |
| sell @ 2000 USDT | sell | 330.67, `HASSY-THECRYPTOQUEEN` |
| buy @ 500 USDT | buy | 331.49, `Alilruben` |
| median of top 10 | sell | 330.765 |
| top-of-book | sell | 332.00 |

---

### Task 1: Project scaffold, App Group entitlements, buildable skeleton

**Files:**
- Create: `project.yml`
- Create: `P2PMonitor/P2PMonitor.entitlements`, `P2PWidget/P2PWidget.entitlements`
- Create: `P2PMonitor/AppMain.swift`, `P2PWidget/WidgetBundleMain.swift`
- Create: `P2PKit/Package.swift`, `P2PKit/Sources/P2PKit/AppGroup.swift`
- Create: `P2PKit/Tests/P2PKitTests/AppGroupTests.swift`
- Create: `Makefile`, `.gitignore` (append)

**Interfaces:**
- Consumes: nothing.
- Produces: `AppGroup.identifier: String`, `AppGroup.containerURL: URL?`, `AppGroup.databaseURL: URL?`. A buildable `P2PMonitor.app` embedding `P2PWidget.appex`, both signed with the App Group entitlement.

- [ ] **Step 1: Create the P2PKit package**

`P2PKit/Package.swift`:

```swift
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "P2PKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "P2PKit", targets: ["P2PKit"]),
    ],
    targets: [
        .target(name: "P2PKit"),
        .testTarget(
            name: "P2PKitTests",
            dependencies: ["P2PKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
```

- [ ] **Step 2: Copy fixtures into the test bundle**

The fixtures live at repo root but the test target needs them as resources.

```bash
mkdir -p P2PKit/Tests/P2PKitTests/Fixtures
cp fixtures/lkr-sell-20260907.json fixtures/lkr-buy-20260907.json \
   P2PKit/Tests/P2PKitTests/Fixtures/
```

- [ ] **Step 3: Create an empty source file so SPM can configure the target**

SwiftPM refuses to resolve a package whose target directory has no sources
(`target 'P2PKit' referenced in product 'P2PKit' is empty`), which would mask
the intended test failure with a package error.

```bash
printf 'import Foundation\n' > P2PKit/Sources/P2PKit/AppGroup.swift
```

- [ ] **Step 4: Write the failing test for AppGroup**

`P2PKit/Tests/P2PKitTests/AppGroupTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

@Test func appGroupIdentifierCarriesTeamPrefix() {
    // Outside the Mac App Store the team-ID prefix is mandatory; the bare
    // "group." form is rejected at runtime.
    #expect(AppGroup.identifier == "UN798LFFKG.group.dev.dfanso.p2pmonitor")
    #expect(AppGroup.identifier.hasPrefix("UN798LFFKG."))
}

@Test func databaseURLSitsInsideContainerWhenAvailable() {
    // Unit tests are not signed with the entitlement, so containerURL is nil
    // here. Assert the relationship rather than a concrete path.
    if let container = AppGroup.containerURL {
        let db = AppGroup.databaseURL
        #expect(db?.path.hasPrefix(container.path) == true)
        #expect(db?.lastPathComponent == "p2p.sqlite")
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd P2PKit && swift test --filter AppGroup`
Expected: FAIL — `cannot find 'AppGroup' in scope`.

- [ ] **Step 6: Implement AppGroup**

`P2PKit/Sources/P2PKit/AppGroup.swift`:

```swift
import Foundation

/// Locations shared between the collector app and the read-only widget.
public enum AppGroup {
    /// Team-ID prefix is required for Developer ID (non-App-Store) distribution.
    public static let identifier = "UN798LFFKG.group.dev.dfanso.p2pmonitor"

    /// Nil when the calling process is not signed with the app-group
    /// entitlement — notably in unit tests.
    public static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    public static var databaseURL: URL? {
        containerURL?.appendingPathComponent("p2p.sqlite")
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd P2PKit && swift test --filter AppGroup`
Expected: PASS, 2 tests.

- [ ] **Step 8: Write the entitlements files**

`P2PMonitor/P2PMonitor.entitlements`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.app-sandbox</key><true/>
  <key>com.apple.security.network.client</key><true/>
  <key>com.apple.security.application-groups</key>
  <array><string>UN798LFFKG.group.dev.dfanso.p2pmonitor</string></array>
</dict></plist>
```

`P2PWidget/P2PWidget.entitlements` is identical **except** it omits
`network.client` — the widget never makes network requests, and granting it
would invite exactly the design mistake this architecture avoids.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.app-sandbox</key><true/>
  <key>com.apple.security.application-groups</key>
  <array><string>UN798LFFKG.group.dev.dfanso.p2pmonitor</string></array>
</dict></plist>
```

- [ ] **Step 9: Write project.yml**

```yaml
name: P2PMonitor
options:
  bundleIdPrefix: dev.dfanso
  deploymentTarget: { macOS: "14.0" }
  createIntermediateGroups: true
settings:
  base:
    DEVELOPMENT_TEAM: UN798LFFKG
    SWIFT_VERSION: "6.0"
    MARKETING_VERSION: "0.1.0"
    CURRENT_PROJECT_VERSION: "1"
    SWIFT_STRICT_CONCURRENCY: complete
packages:
  P2PKit:
    path: P2PKit
targets:
  P2PMonitor:
    type: application
    platform: macOS
    sources: [P2PMonitor]
    dependencies:
      - package: P2PKit
      - target: P2PWidget
        embed: true
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.dfanso.p2pmonitor
        CODE_SIGN_ENTITLEMENTS: P2PMonitor/P2PMonitor.entitlements
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_LSUIElement: YES
        INFOPLIST_KEY_NSHumanReadableCopyright: ""
  P2PWidget:
    type: app-extension
    platform: macOS
    sources: [P2PWidget]
    dependencies:
      - package: P2PKit
    info:
      path: P2PWidget/Info.plist
      properties:
        CFBundleDisplayName: USDT/LKR Rate
        NSExtension:
          NSExtensionPointIdentifier: com.apple.widgetkit-extension
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.dfanso.p2pmonitor.widget
        CODE_SIGN_ENTITLEMENTS: P2PWidget/P2PWidget.entitlements
```

- [ ] **Step 10: Write the two entry points**

`P2PMonitor/AppMain.swift` — **note the filename**, see Global Constraints:

```swift
import SwiftUI

@main
struct P2PMonitorApp: App {
    var body: some Scene {
        // Replaced by the real detail window in Task 16.
        WindowGroup { Text("P2P Monitor") .frame(width: 320, height: 200) }
    }
}
```

`P2PWidget/WidgetBundleMain.swift`:

```swift
import WidgetKit
import SwiftUI

struct PlaceholderEntry: TimelineEntry { let date: Date }

struct PlaceholderProvider: TimelineProvider {
    func placeholder(in context: Context) -> PlaceholderEntry { PlaceholderEntry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (PlaceholderEntry) -> Void) {
        completion(PlaceholderEntry(date: .now))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<PlaceholderEntry>) -> Void) {
        completion(Timeline(entries: [PlaceholderEntry(date: .now)], policy: .atEnd))
    }
}

struct RateWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "RateWidget", provider: PlaceholderProvider()) { _ in
            Text("—").containerBackground(.fill, for: .widget)
        }
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct P2PWidgetBundle: WidgetBundle {
    var body: some Widget { RateWidget() }
}
```

- [ ] **Step 11: Write the Makefile**

```makefile
.PHONY: project build test sign-check clean

project:
	xcodegen generate

build: project
	xcodebuild -project P2PMonitor.xcodeproj -scheme P2PMonitor \
	  -configuration Debug CODE_SIGNING_ALLOWED=NO build

test:
	cd P2PKit && swift test

sign-check: project
	xcodebuild -project P2PMonitor.xcodeproj -scheme P2PMonitor \
	  -configuration Debug CODE_SIGN_STYLE=Manual \
	  CODE_SIGN_IDENTITY="Developer ID Application" \
	  OTHER_CODE_SIGN_FLAGS="--timestamp=none" build
	@APP=$$(find ~/Library/Developer/Xcode/DerivedData -name P2PMonitor.app -path '*Debug*' | head -1); \
	 echo "app:    $$APP"; \
	 ls "$$APP/Contents/PlugIns/" ; \
	 codesign -d --entitlements - --xml "$$APP" 2>/dev/null | plutil -convert xml1 -o - - | grep -A2 application-groups; \
	 codesign -d --entitlements - --xml "$$APP/Contents/PlugIns/P2PWidget.appex" 2>/dev/null | plutil -convert xml1 -o - - | grep -A2 application-groups

clean:
	rm -rf P2PMonitor.xcodeproj P2PKit/.build
```

- [ ] **Step 12: Add generated artifacts to .gitignore**

```bash
printf '\n# XcodeGen output (regenerate with `make project`)\nP2PMonitor.xcodeproj/\n' >> .gitignore
```

- [ ] **Step 13: Verify the unsigned build succeeds**

Run: `make build`
Expected: `** BUILD SUCCEEDED **`

- [ ] **Step 14: Verify signing and App Group propagation**

Run: `make sign-check`
Expected: `** BUILD SUCCEEDED **`, `P2PWidget.appex` listed under `Contents/PlugIns/`, and `UN798LFFKG.group.dev.dfanso.p2pmonitor` printed **twice** — once for the app, once for the widget. If it appears only once the widget cannot read the database.

- [ ] **Step 15: Commit**

```bash
git add -A
git commit -m "Add XcodeGen scaffold with app, widget extension, and shared App Group

The widget extension is deliberately denied network.client: it must only
ever read the store the collector writes, and granting it network access
would invite the budget-capped self-polling design the spec rejects."
```

---

### Task 2: Models and wire decoding

**Files:**
- Create: `P2PKit/Sources/P2PKit/Models.swift`
- Create: `P2PKit/Sources/P2PKit/WireFormat.swift`
- Create: `P2PKit/Tests/P2PKitTests/WireFormatTests.swift`
- Create: `P2PKit/Tests/P2PKitTests/FixtureLoader.swift`

**Interfaces:**
- Consumes: `AppGroup` (Task 1).
- Produces: `Side`, `Ad`, `Sample`, `SeriesPoint`, `ChartWindow`, `PaymentMethod`, `P2PError`, and `WireFormat.decodeAds(from: Data) throws -> [Ad]`.

- [ ] **Step 1: Write Models.swift**

```swift
import Foundation

/// The user's action, not the advertiser's. Requesting `.sell` returns ads
/// whose own `adv.tradeType` reads "BUY" — the API states the counterparty's
/// side, so the two are always inverted.
public enum Side: String, Sendable, Codable, CaseIterable {
    case sell = "SELL"
    case buy  = "BUY"
}

public enum PaymentMethod: String, Sendable, Codable, CaseIterable {
    case bankSriLanka = "BankSriLanka"
    case bankTransfer = "BANK"

    public var displayName: String {
        switch self {
        case .bankSriLanka: "Bank Transfer (Sri Lanka)"
        case .bankTransfer: "Bank Transfer"
        }
    }
}

/// One advertisement, normalised. Fiat limits are in the quote currency (LKR),
/// never in USDT.
public struct Ad: Sendable, Equatable, Codable {
    public let price: Double
    public let availableUSDT: Double
    public let minFiat: Double
    public let maxFiat: Double
    public let payTimeLimitMinutes: Int
    public let advertiserName: String
    public let monthOrderCount: Int
    public let monthFinishRate: Double
    public let positiveRate: Double

    public init(price: Double, availableUSDT: Double, minFiat: Double, maxFiat: Double,
                payTimeLimitMinutes: Int, advertiserName: String, monthOrderCount: Int,
                monthFinishRate: Double, positiveRate: Double) {
        self.price = price
        self.availableUSDT = availableUSDT
        self.minFiat = minFiat
        self.maxFiat = maxFiat
        self.payTimeLimitMinutes = payTimeLimitMinutes
        self.advertiserName = advertiserName
        self.monthOrderCount = monthOrderCount
        self.monthFinishRate = monthFinishRate
        self.positiveRate = positiveRate
    }

    /// Whether this ad can absorb `amountUSDT` in a single order.
    public func canFill(amountUSDT: Int) -> Bool {
        let amount = Double(amountUSDT)
        guard availableUSDT >= amount else { return false }
        let fiat = amount * price
        return fiat >= minFiat && fiat <= maxFiat
    }
}

/// One observation. `fillablePrice` is nil when no ad could fill the amount —
/// a real, recordable finding, distinct from a failed poll, which stores nothing.
public struct Sample: Sendable, Equatable, Codable {
    public let timestamp: Date
    public let side: Side
    public let amountUSDT: Int
    public let fillablePrice: Double?
    public let topPrice: Double
    public let medianTop10: Double?
    public let advertiserName: String?
    public let advertiserAvailable: Double?
    public let advertiserMinFiat: Double?
    public let advertiserMaxFiat: Double?

    public init(timestamp: Date, side: Side, amountUSDT: Int, fillablePrice: Double?,
                topPrice: Double, medianTop10: Double?, advertiserName: String?,
                advertiserAvailable: Double?, advertiserMinFiat: Double?,
                advertiserMaxFiat: Double?) {
        self.timestamp = timestamp
        self.side = side
        self.amountUSDT = amountUSDT
        self.fillablePrice = fillablePrice
        self.topPrice = topPrice
        self.medianTop10 = medianTop10
        self.advertiserName = advertiserName
        self.advertiserAvailable = advertiserAvailable
        self.advertiserMinFiat = advertiserMinFiat
        self.advertiserMaxFiat = advertiserMaxFiat
    }
}

public struct SeriesPoint: Sendable, Equatable, Codable {
    public let timestamp: Date
    public let price: Double
    public init(timestamp: Date, price: Double) {
        self.timestamp = timestamp
        self.price = price
    }
}

public enum ChartWindow: String, Sendable, Codable, CaseIterable {
    case hour1  = "1h"
    case hour24 = "24h"
    case day7   = "7d"
    case day30  = "30d"

    public var duration: TimeInterval {
        switch self {
        case .hour1:  3_600
        case .hour24: 86_400
        case .day7:   604_800
        case .day30:  2_592_000
        }
    }

    /// Averaging bucket in seconds. Zero means return raw samples.
    public var bucketSeconds: Int {
        switch self {
        case .hour1, .hour24: 0
        case .day7:           3_600
        case .day30:          21_600
        }
    }
}

public enum P2PError: Error, Equatable {
    case transport(String)
    case httpStatus(Int)
    case apiCode(String, String?)
    case emptyResult
    case malformed(String)
}
```

- [ ] **Step 2: Write the fixture loader**

`P2PKit/Tests/P2PKitTests/FixtureLoader.swift`:

```swift
import Foundation
import Testing

enum Fixture {
    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: "Fixtures/\(name)", withExtension: "json") else {
            throw P2PErrorForTests.missingFixture(name)
        }
        return try Data(contentsOf: url)
    }
    static let sell = "lkr-sell-20260907"
    static let buy  = "lkr-buy-20260907"
}

enum P2PErrorForTests: Error { case missingFixture(String) }
```

- [ ] **Step 3: Write the failing decoding tests**

`P2PKit/Tests/P2PKitTests/WireFormatTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

@Test func decodesSellFixtureIntoTwentyAds() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(ads.count == 20)
}

@Test func decodesStringNumbersAndPreservesOrder() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    // Verified ground truth: the top-of-book ad demands a 499,999 LKR minimum.
    #expect(ads[0].price == 332.00)
    #expect(ads[0].availableUSDT == 1510.00)
    #expect(ads[0].minFiat == 499_999)
    #expect(ads[0].maxFiat == 500_000)
    #expect(ads[0].advertiserName == "jeewani 98")
    #expect(ads[0].payTimeLimitMinutes == 15)
}

@Test func sellAdsArriveBestFirstDescending() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(ads.map(\.price) == ads.map(\.price).sorted(by: >))
}

@Test func buyAdsArriveBestFirstAscending() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.buy))
    #expect(ads.map(\.price) == ads.map(\.price).sorted(by: <))
}

@Test func rejectsNonSuccessApiCode() throws {
    let body = Data(#"{"code":"000002","message":"rate limited","data":[]}"#.utf8)
    #expect(throws: P2PError.apiCode("000002", "rate limited")) {
        try WireFormat.decodeAds(from: body)
    }
}

@Test func reportsEmptyResultDistinctly() throws {
    let body = Data(#"{"code":"000000","message":null,"data":[]}"#.utf8)
    #expect(throws: P2PError.emptyResult) {
        try WireFormat.decodeAds(from: body)
    }
}

@Test func reportsMalformedJsonDistinctly() throws {
    #expect(throws: (any Error).self) {
        try WireFormat.decodeAds(from: Data("not json".utf8))
    }
}

@Test func skipsIndividualAdsWithUnparseableNumbers() throws {
    // One bad ad must not discard a whole otherwise-valid response.
    let body = Data("""
    {"code":"000000","data":[
      {"adv":{"price":"oops","tradableQuantity":"1","minSingleTransAmount":"1",
              "dynamicMaxSingleTransAmount":"2","payTimeLimit":15},
       "advertiser":{"nickName":"bad","monthOrderCount":1,"monthFinishRate":1,"positiveRate":1}},
      {"adv":{"price":"330.50","tradableQuantity":"700","minSingleTransAmount":"1000",
              "dynamicMaxSingleTransAmount":"200000","payTimeLimit":15},
       "advertiser":{"nickName":"good","monthOrderCount":5,"monthFinishRate":0.99,"positiveRate":1}}
    ]}
    """.utf8)
    let ads = try WireFormat.decodeAds(from: body)
    #expect(ads.count == 1)
    #expect(ads[0].advertiserName == "good")
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter WireFormat`
Expected: FAIL — `cannot find 'WireFormat' in scope`.

- [ ] **Step 5: Implement WireFormat**

`P2PKit/Sources/P2PKit/WireFormat.swift`:

```swift
import Foundation

/// The **only** place that knows Binance's wire format. A schema change upstream
/// is a one-file fix by design.
public enum WireFormat {
    static let successCode = "000000"

    private struct Response: Decodable {
        let code: String
        let message: String?
        let data: [Row]?
    }

    private struct Row: Decodable {
        let adv: Adv
        let advertiser: Advertiser
    }

    /// Numeric fields arrive as strings; rates and counts arrive as numbers.
    private struct Adv: Decodable {
        let price: String?
        let tradableQuantity: String?
        let minSingleTransAmount: String?
        let dynamicMaxSingleTransAmount: String?
        let maxSingleTransAmount: String?
        let payTimeLimit: Int?
    }

    private struct Advertiser: Decodable {
        let nickName: String?
        let monthOrderCount: Int?
        let monthFinishRate: Double?
        let positiveRate: Double?
    }

    public static func decodeAds(from data: Data) throws -> [Ad] {
        let response: Response
        do {
            response = try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw P2PError.malformed(String(describing: error))
        }

        guard response.code == successCode else {
            throw P2PError.apiCode(response.code, response.message)
        }

        let rows = response.data ?? []
        guard !rows.isEmpty else { throw P2PError.emptyResult }

        // A single unparseable ad is skipped, not fatal: the rest of the book
        // is still a usable observation.
        let ads = rows.compactMap(normalise)
        guard !ads.isEmpty else { throw P2PError.emptyResult }
        return ads
    }

    private static func normalise(_ row: Row) -> Ad? {
        guard let price = row.adv.price.flatMap(Double.init), price > 0,
              let stock = row.adv.tradableQuantity.flatMap(Double.init),
              let minFiat = row.adv.minSingleTransAmount.flatMap(Double.init)
        else { return nil }

        // dynamicMaxSingleTransAmount reflects live stock; fall back to the
        // static ceiling if it is ever absent.
        let maxRaw = row.adv.dynamicMaxSingleTransAmount ?? row.adv.maxSingleTransAmount
        guard let maxFiat = maxRaw.flatMap(Double.init) else { return nil }

        return Ad(
            price: price,
            availableUSDT: stock,
            minFiat: minFiat,
            maxFiat: maxFiat,
            payTimeLimitMinutes: row.adv.payTimeLimit ?? 0,
            advertiserName: row.advertiser.nickName ?? "Unknown",
            monthOrderCount: row.advertiser.monthOrderCount ?? 0,
            monthFinishRate: row.advertiser.monthFinishRate ?? 0,
            positiveRate: row.advertiser.positiveRate ?? 0
        )
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter WireFormat`
Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add normalised models and Binance wire decoding

Numeric fields arrive as JSON strings and the fiat limits are denominated
in LKR rather than USDT, so decoding normalises both. A single ad with
unparseable numbers is skipped rather than discarding the whole book."
```

---

### Task 3: Network client with stubbed transport

**Files:**
- Create: `P2PKit/Sources/P2PKit/BinanceP2PClient.swift`
- Create: `P2PKit/Tests/P2PKitTests/BinanceP2PClientTests.swift`
- Create: `P2PKit/Tests/P2PKitTests/StubURLProtocol.swift`

**Interfaces:**
- Consumes: `WireFormat`, `Side`, `PaymentMethod`, `Ad`, `P2PError` (Task 2).
- Produces: `BinanceP2PClient(session:)`, `func search(side:fiat:payment:rows:) async throws -> [Ad]`, and `BinanceP2PClient.requestBody(side:fiat:payment:rows:) -> [String: Any]`.

- [ ] **Step 1: Write the URLProtocol stub**

`P2PKit/Tests/P2PKitTests/StubURLProtocol.swift`:

```swift
import Foundation

/// Serves canned responses so the suite never touches the network.
final class StubURLProtocol: URLProtocol {
    struct Stub {
        var status: Int = 200
        var body: Data = Data()
        var error: Error?
    }

    nonisolated(unsafe) static var stub = Stub()
    nonisolated(unsafe) static var lastRequestBody: Data?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        // URLSession strips httpBody from the request handed to a protocol,
        // so read it back from the body stream.
        Self.lastRequestBody = request.httpBody ?? request.httpBodyStream.flatMap { stream in
            stream.open()
            var data = Data()
            let size = 4096
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
            defer { buffer.deallocate(); stream.close() }
            while stream.hasBytesAvailable {
                let read = stream.read(buffer, maxLength: size)
                if read <= 0 { break }
                data.append(buffer, count: read)
            }
            return data
        }

        if let error = Self.stub.error {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.stub.status,
                                       httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    /// Sets the stub and clears any previously captured request.
    static func install(status: Int = 200, body: Data = Data(), error: Error? = nil) {
        stub = Stub(status: status, body: body, error: error)
        lastRequestBody = nil
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }
}
```

- [ ] **Step 2: Write the failing client tests**

`P2PKit/Tests/P2PKitTests/BinanceP2PClientTests.swift` — note the
`@Suite(.serialized)` wrapper; without it these tests race over the global stub:

```swift
import Testing
import Foundation
@testable import P2PKit

@Suite(.serialized)
struct BinanceP2PClientTests {

@Test func requestBodyMapsUrlParametersOntoJsonFields() {
    let body = BinanceP2PClient.requestBody(side: .sell, fiat: "LKR",
                                            payment: .bankSriLanka, rows: 20)
    #expect(body["fiat"] as? String == "LKR")
    #expect(body["asset"] as? String == "USDT")
    // Request tradeType is the *user's* action.
    #expect(body["tradeType"] as? String == "SELL")
    #expect(body["payTypes"] as? [String] == ["BankSriLanka"])
    #expect(body["rows"] as? Int == 20)
    #expect(body["page"] as? Int == 1)
}

@Test func searchDecodesStubbedFixture() async throws {
    StubURLProtocol.stub = .init(status: 200, body: try Fixture.data(Fixture.sell))
    let client = BinanceP2PClient(session: StubURLProtocol.session())
    let ads = try await client.search(side: .sell, fiat: "LKR", payment: .bankSriLanka, rows: 20)
    #expect(ads.count == 20)
    #expect(ads[0].price == 332.00)
}

@Test func searchSendsTradeTypeMatchingRequestedSide() async throws {
    StubURLProtocol.stub = .init(status: 200, body: try Fixture.data(Fixture.buy))
    let client = BinanceP2PClient(session: StubURLProtocol.session())
    _ = try await client.search(side: .buy, fiat: "LKR", payment: .bankSriLanka, rows: 20)
    let sent = try #require(StubURLProtocol.lastRequestBody)
    let json = try #require(try JSONSerialization.jsonObject(with: sent) as? [String: Any])
    #expect(json["tradeType"] as? String == "BUY")
}

@Test func mapsNonSuccessHttpStatusToHttpStatusError() async throws {
    StubURLProtocol.stub = .init(status: 503, body: Data())
    let client = BinanceP2PClient(session: StubURLProtocol.session())
    await #expect(throws: P2PError.httpStatus(503)) {
        try await client.search(side: .sell, fiat: "LKR", payment: .bankSriLanka, rows: 20)
    }
}

@Test func mapsTransportFailureToTransportError() async throws {
    StubURLProtocol.stub = .init(error: URLError(.notConnectedToInternet))
    let client = BinanceP2PClient(session: StubURLProtocol.session())
    await #expect(throws: (any Error).self) {
        try await client.search(side: .sell, fiat: "LKR", payment: .bankSriLanka, rows: 20)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter BinanceP2PClient`
Expected: FAIL — `cannot find 'BinanceP2PClient' in scope`.

- [ ] **Step 4: Implement the client**

`P2PKit/Sources/P2PKit/BinanceP2PClient.swift`:

```swift
import Foundation

public struct BinanceP2PClient: Sendable {
    public static let endpoint = URL(string:
        "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search")!

    /// The page sends a browser UA; an obviously scripted one invites blocking.
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                           "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    /// Mirrors the query parameters of
    /// p2p.binance.com/trade/sell/USDT?fiat=LKR&payment=BankSriLanka
    public static func requestBody(side: Side, fiat: String,
                                   payment: PaymentMethod, rows: Int) -> [String: Any] {
        [
            "fiat": fiat,
            "asset": "USDT",
            "tradeType": side.rawValue,
            "payTypes": [payment.rawValue],
            "page": 1,
            "rows": rows,
            "countries": [],
            "periods": [],
            "proMerchantAds": false,
            "shieldMerchantAds": false,
            "filterType": "all",
            "additionalKycVerifyFilter": 0,
            "publisherType": NSNull(),
            "classifies": ["mass", "profession", "fiat_trade"],
        ]
    }

    public func search(side: Side, fiat: String = "LKR",
                       payment: PaymentMethod = .bankSriLanka,
                       rows: Int = 20) async throws -> [Ad] {
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        request.httpBody = try JSONSerialization.data(
            withJSONObject: Self.requestBody(side: side, fiat: fiat, payment: payment, rows: rows))

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw P2PError.transport(error.localizedDescription)
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw P2PError.httpStatus(http.statusCode)
        }
        return try WireFormat.decodeAds(from: data)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter BinanceP2PClient`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add P2P search client with stubbed transport

Tests drive a URLProtocol stub so the suite stays deterministic and offline
as the live order book moves."
```

---

### Task 4: Fillable-price selection

This task is the reason the project exists. Reporting the top of the book would
be wrong by roughly 1 LKR per unit on the verified data.

**Files:**
- Create: `P2PKit/Sources/P2PKit/MetricsEngine.swift`
- Create: `P2PKit/Tests/P2PKitTests/FillableTests.swift`

**Interfaces:**
- Consumes: `Ad`, `Side` (Task 2).
- Produces: `MetricsEngine.fillable(_:amountUSDT:) -> Ad?`, `MetricsEngine.topPrice(_:) -> Double?`, `MetricsEngine.medianTop10(_:) -> Double?`, `MetricsEngine.makeSample(ads:side:amountUSDT:timestamp:) -> Sample?`.

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/FillableTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

@Test func sellAt500SkipsTheUntradeableTopOfBook() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 500))
    // Top of book is 332.00 but demands a 499,999 LKR minimum (~1,500 USDT).
    #expect(best.price == 331.00)
    #expect(best.advertiserName == "TD_TrustPay_LK")
}

@Test func rejectsSamePricedAdThatLacksStock() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 500))
    // Two ads quote 331.00; the earlier one holds only 100 USDT. Proves the
    // filter is not a price sort.
    #expect(best.availableUSDT == 700.00)
}

@Test func sellAtLargerAmountPicksADeeperAd() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 2000))
    #expect(best.price == 330.67)
    #expect(best.advertiserName == "HASSY-THECRYPTOQUEEN")
}

@Test func buySideUsesTheSameFirstMatchRule() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.buy))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 500))
    #expect(best.price == 331.49)
    #expect(best.advertiserName == "Alilruben")
}

@Test func returnsNilWhenNoAdCanFill() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(MetricsEngine.fillable(ads, amountUSDT: 10_000_000) == nil)
}

@Test func returnsNilForAnEmptyBook() {
    #expect(MetricsEngine.fillable([], amountUSDT: 500) == nil)
}

@Test func topPriceIsTheRawFirstAd() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(MetricsEngine.topPrice(ads) == 332.00)
}

@Test func medianOfTopTenAveragesTheMiddlePair() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let median = try #require(MetricsEngine.medianTop10(ads))
    #expect(abs(median - 330.765) < 0.0001)
}

@Test func medianHandlesFewerThanTenAds() {
    let ads = [330.0, 331.0, 332.0].map { price in
        Ad(price: price, availableUSDT: 1000, minFiat: 0, maxFiat: 1_000_000,
           payTimeLimitMinutes: 15, advertiserName: "x", monthOrderCount: 1,
           monthFinishRate: 1, positiveRate: 1)
    }
    #expect(MetricsEngine.medianTop10(ads) == 331.0)
}

@Test func sampleRecordsNilFillableButKeepsTopOfBook() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let sample = try #require(MetricsEngine.makeSample(
        ads: ads, side: .sell, amountUSDT: 10_000_000, timestamp: .now))
    // "No ad could fill it" is a finding worth storing, not a failure.
    #expect(sample.fillablePrice == nil)
    #expect(sample.topPrice == 332.00)
    #expect(sample.advertiserName == nil)
}

@Test func sampleCapturesWinningAdvertiserDetails() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let sample = try #require(MetricsEngine.makeSample(
        ads: ads, side: .sell, amountUSDT: 500, timestamp: .now))
    #expect(sample.fillablePrice == 331.00)
    #expect(sample.advertiserName == "TD_TrustPay_LK")
    #expect(sample.advertiserAvailable == 700.00)
    #expect(sample.advertiserMinFiat == 10_000)
    #expect(sample.advertiserMaxFiat == 220_000)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter Fillable`
Expected: FAIL — `cannot find 'MetricsEngine' in scope`.

- [ ] **Step 3: Implement fillable selection**

`P2PKit/Sources/P2PKit/MetricsEngine.swift`:

```swift
import Foundation

public enum MetricsEngine {

    /// The best ad that can actually absorb `amountUSDT`.
    ///
    /// Binance returns ads best-first on both sides — sell descending, buy
    /// ascending (verified across the recorded fixtures) — so the first
    /// qualifying ad is optimal for either side and no maximise/minimise
    /// branch is needed.
    public static func fillable(_ ads: [Ad], amountUSDT: Int) -> Ad? {
        ads.first { $0.canFill(amountUSDT: amountUSDT) }
    }

    /// Raw top of the book, stored alongside the fillable price so the
    /// headline metric can change later without re-collecting history.
    public static func topPrice(_ ads: [Ad]) -> Double? {
        ads.first?.price
    }

    public static func medianTop10(_ ads: [Ad]) -> Double? {
        let prices = ads.prefix(10).map(\.price).sorted()
        guard !prices.isEmpty else { return nil }
        let mid = prices.count / 2
        return prices.count.isMultiple(of: 2)
            ? (prices[mid - 1] + prices[mid]) / 2
            : prices[mid]
    }

    /// Nil only when the book is empty — that is a failed observation. A book
    /// with no ad big enough yields a sample whose `fillablePrice` is nil,
    /// which is a real finding and must be stored.
    public static func makeSample(ads: [Ad], side: Side, amountUSDT: Int,
                                  timestamp: Date) -> Sample? {
        guard let top = topPrice(ads) else { return nil }
        let best = fillable(ads, amountUSDT: amountUSDT)
        return Sample(
            timestamp: timestamp,
            side: side,
            amountUSDT: amountUSDT,
            fillablePrice: best?.price,
            topPrice: top,
            medianTop10: medianTop10(ads),
            advertiserName: best?.advertiserName,
            advertiserAvailable: best?.availableUSDT,
            advertiserMinFiat: best?.minFiat,
            advertiserMaxFiat: best?.maxFiat
        )
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter Fillable`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Select the best fillable ad rather than the top of the book

On the recorded book the top ad quotes 332.00 but demands a 499,999 LKR
minimum, roughly 1,500 USDT. The best price actually tradeable at 500 USDT
is 331.00, so a naive reading is wrong by about 1 LKR on every unit.

Because the API returns ads best-first on both sides, the first qualifying
ad is optimal for sell and buy alike."
```

---

### Task 5: Trend and downsampling

**Files:**
- Modify: `P2PKit/Sources/P2PKit/MetricsEngine.swift`
- Create: `P2PKit/Sources/P2PKit/Trend.swift`
- Create: `P2PKit/Tests/P2PKitTests/TrendTests.swift`

**Interfaces:**
- Consumes: `SeriesPoint`, `ChartWindow` (Task 2).
- Produces: `Trend` with `current`, `previous`, `delta`, `percent`, `direction`; `Trend.Direction` (`.up`, `.down`, `.flat`); `MetricsEngine.trend(series:) -> Trend?`; `MetricsEngine.downsample(_:bucketSeconds:) -> [SeriesPoint]`.

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/TrendTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

private func points(_ values: [(Int, Double)]) -> [SeriesPoint] {
    values.map { SeriesPoint(timestamp: Date(timeIntervalSince1970: TimeInterval($0.0)),
                             price: $0.1) }
}

@Test func trendRisesFromFirstToLast() {
    let t = try! #require(MetricsEngine.trend(series: points([(0, 330.0), (60, 331.5)])))
    #expect(t.previous == 330.0)
    #expect(t.current == 331.5)
    #expect(abs(t.delta - 1.5) < 0.0001)
    #expect(abs(t.percent - 0.4545) < 0.001)
    #expect(t.direction == .up)
}

@Test func trendFallsWhenPriceDrops() {
    let t = try! #require(MetricsEngine.trend(series: points([(0, 331.5), (60, 330.0)])))
    #expect(t.direction == .down)
    #expect(t.delta < 0)
}

@Test func trendIsFlatWhenUnchanged() {
    let t = try! #require(MetricsEngine.trend(series: points([(0, 331.0), (60, 331.0)])))
    #expect(t.direction == .flat)
    #expect(t.delta == 0)
}

@Test func trendNeedsAtLeastTwoPoints() {
    #expect(MetricsEngine.trend(series: points([(0, 331.0)])) == nil)
    #expect(MetricsEngine.trend(series: []) == nil)
}

@Test func downsampleAveragesWithinBuckets() {
    // Two 3600s buckets: [0,3600) averages 330 and 332 to 331.
    let raw = points([(0, 330.0), (1800, 332.0), (3600, 340.0), (5400, 342.0)])
    let out = MetricsEngine.downsample(raw, bucketSeconds: 3600)
    #expect(out.count == 2)
    #expect(out[0].price == 331.0)
    #expect(out[1].price == 341.0)
    #expect(out[0].timestamp == Date(timeIntervalSince1970: 0))
    #expect(out[1].timestamp == Date(timeIntervalSince1970: 3600))
}

@Test func downsampleWithZeroBucketReturnsRawSeries() {
    let raw = points([(0, 330.0), (300, 331.0)])
    #expect(MetricsEngine.downsample(raw, bucketSeconds: 0) == raw)
}

@Test func downsamplePreservesGapsRatherThanInterpolating() {
    // A 24h hole between the two clusters must not become a synthetic bucket:
    // overnight sleep gaps have to read as missing data, never as a flat rate.
    let raw = points([(0, 330.0), (3600, 331.0), (90_000, 340.0)])
    let out = MetricsEngine.downsample(raw, bucketSeconds: 3600)
    #expect(out.count == 3)
    // 90_000 is exactly 25 buckets of 3600, so its bucket key is 90_000 itself.
    // The 24h hole between bucket 3600 and bucket 90_000 produces no rows at
    // all rather than a run of interpolated ones.
    let stamps = out.map { Int($0.timestamp.timeIntervalSince1970) }
    #expect(stamps == [0, 3600, 90_000])
}

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter Trend`
Expected: FAIL — `cannot find 'Trend' in scope`.

- [ ] **Step 3: Implement Trend**

`P2PKit/Sources/P2PKit/Trend.swift`:

```swift
import Foundation

public struct Trend: Sendable, Equatable {
    public enum Direction: Sendable, Equatable { case up, down, flat }

    public let previous: Double
    public let current: Double

    public init(previous: Double, current: Double) {
        self.previous = previous
        self.current = current
    }

    public var delta: Double { current - previous }

    public var percent: Double {
        previous == 0 ? 0 : (delta / previous) * 100
    }

    public var direction: Direction {
        if delta > 0 { return .up }
        if delta < 0 { return .down }
        return .flat
    }
}
```

- [ ] **Step 4: Add trend and downsample to MetricsEngine**

Append inside `enum MetricsEngine`:

```swift
    /// Direction across the supplied window. The caller has already scoped the
    /// series to the window, so this compares its ends.
    public static func trend(series: [SeriesPoint]) -> Trend? {
        guard let first = series.first, let last = series.last, series.count >= 2
        else { return nil }
        return Trend(previous: first.price, current: last.price)
    }

    /// Bucket-average a series. `bucketSeconds == 0` returns it unchanged.
    ///
    /// Empty buckets are omitted rather than filled: a gap must stay a gap so
    /// the chart draws a break instead of implying a flat rate overnight.
    public static func downsample(_ series: [SeriesPoint], bucketSeconds: Int) -> [SeriesPoint] {
        guard bucketSeconds > 0 else { return series }
        let bucket = TimeInterval(bucketSeconds)

        var order: [TimeInterval] = []
        var sums: [TimeInterval: (total: Double, count: Int)] = [:]

        for point in series {
            let key = (point.timestamp.timeIntervalSince1970 / bucket).rounded(.down) * bucket
            if sums[key] == nil { order.append(key) }
            sums[key, default: (0, 0)].total += point.price
            sums[key, default: (0, 0)].count += 1
        }

        return order.map { key in
            let entry = sums[key]!
            return SeriesPoint(timestamp: Date(timeIntervalSince1970: key),
                               price: entry.total / Double(entry.count))
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter Trend`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add trend calculation and gap-preserving downsampling

Empty buckets are omitted rather than interpolated so overnight sleep gaps
render as breaks in the line instead of implying a flat rate."
```

---

### Task 6: Store — schema, append, latest, prune

**Files:**
- Create: `P2PKit/Sources/P2PKit/Store.swift`
- Create: `P2PKit/Sources/P2PKit/SQLite.swift`
- Create: `P2PKit/Tests/P2PKitTests/StoreTests.swift`

**Interfaces:**
- Consumes: `Sample`, `Side`, `P2PError` (Task 2).
- Produces: `Store(fileURL:) throws`, `append(_:) throws`, `latest(side:amountUSDT:) throws -> Sample?`, `prune(olderThan:) throws -> Int`, `sampleCount() throws -> Int`, and `StoreError`.

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/StoreTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

private func tempStore() throws -> (Store, URL) {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-test-\(UUID().uuidString).sqlite")
    return (try Store(fileURL: url), url)
}

private func sample(_ ts: TimeInterval, _ price: Double?, amount: Int = 500,
                    side: Side = .sell) -> Sample {
    Sample(timestamp: Date(timeIntervalSince1970: ts), side: side, amountUSDT: amount,
           fillablePrice: price, topPrice: 332.0, medianTop10: 330.765,
           advertiserName: price == nil ? nil : "TD_TrustPay_LK",
           advertiserAvailable: 700, advertiserMinFiat: 10_000, advertiserMaxFiat: 220_000)
}

@Test func appendThenReadBackRoundTrips() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 331.00))
    let got = try #require(try store.latest(side: .sell, amountUSDT: 500))
    #expect(got.fillablePrice == 331.00)
    #expect(got.topPrice == 332.0)
    #expect(got.advertiserName == "TD_TrustPay_LK")
    #expect(got.advertiserMaxFiat == 220_000)
    #expect(got.timestamp == Date(timeIntervalSince1970: 1_000))
}

@Test func latestReturnsTheNewestRow() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 330.00))
    try store.append(sample(2_000, 331.00))
    try store.append(sample(1_500, 330.50))
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
}

@Test func nilFillablePriceSurvivesTheRoundTrip() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, nil))
    let got = try #require(try store.latest(side: .sell, amountUSDT: 500))
    // Distinct from a failed poll, which stores no row at all.
    #expect(got.fillablePrice == nil)
    #expect(got.topPrice == 332.0)
}

@Test func latestIsScopedBySideAndAmount() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 331.00, amount: 500, side: .sell))
    try store.append(sample(1_000, 331.49, amount: 500, side: .buy))
    try store.append(sample(1_000, 330.67, amount: 2_000, side: .sell))

    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
    #expect(try store.latest(side: .buy,  amountUSDT: 500)?.fillablePrice == 331.49)
    #expect(try store.latest(side: .sell, amountUSDT: 2_000)?.fillablePrice == 330.67)
    #expect(try store.latest(side: .buy,  amountUSDT: 9_999) == nil)
}

@Test func appendingTheSameKeyTwiceReplacesRatherThanDuplicates() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 330.00))
    try store.append(sample(1_000, 331.00))
    #expect(try store.sampleCount() == 1)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
}

@Test func pruneDeletesOnlyRowsStrictlyOlderThanTheCutoff() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 330.00))
    try store.append(sample(2_000, 331.00))
    try store.append(sample(3_000, 332.00))

    let deleted = try store.prune(olderThan: Date(timeIntervalSince1970: 2_000))
    #expect(deleted == 1)
    #expect(try store.sampleCount() == 2)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 332.00)
}

@Test func reopeningAnExistingFileKeepsItsRows() throws {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-reopen-\(UUID().uuidString).sqlite")
    defer { try? FileManager.default.removeItem(at: url) }

    do {
        let store = try Store(fileURL: url)
        try store.append(sample(1_000, 331.00))
    }
    let reopened = try Store(fileURL: url)
    #expect(try reopened.sampleCount() == 1)
}

@Test func walModeIsEnabledSoTheWidgetCanReadDuringWrites() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }
    #expect(try store.journalMode().lowercased() == "wal")
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter Store`
Expected: FAIL — `cannot find 'Store' in scope`.

- [ ] **Step 3: Implement the SQLite helper**

`P2PKit/Sources/P2PKit/SQLite.swift`:

```swift
import Foundation
import SQLite3

public enum StoreError: Error, Equatable {
    case open(String)
    case prepare(String)
    case step(String)
}

/// Minimal wrapper over the system SQLite so the package stays dependency-free.
final class Database {
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
```

- [ ] **Step 4: Implement the Store**

`P2PKit/Sources/P2PKit/Store.swift`:

```swift
import Foundation

/// Shared SQLite store. The collector app writes; the widget extension reads.
/// WAL mode lets those overlap across processes without the reader blocking.
public final class Store {
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
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter Store`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add SQLite store with WAL, append, latest, and pruning

WAL matters across processes here: the app writes while the widget
extension reads from a separate process, and WAL keeps the reader from
blocking. amount_usdt is an integer column because it is part of the
primary key and float equality in a WHERE clause is a bug waiting to
happen."
```

---

### Task 7: Store — windowed series and snapshot

**Files:**
- Modify: `P2PKit/Sources/P2PKit/Store.swift`
- Create: `P2PKit/Tests/P2PKitTests/StoreSeriesTests.swift`

**Interfaces:**
- Consumes: `Store` (Task 6), `ChartWindow`, `Ad`, `MetricsEngine.downsample` (Tasks 2, 5).
- Produces: `Store.series(side:amountUSDT:window:now:) throws -> [SeriesPoint]`, `Store.replaceSnapshot(side:ads:capturedAt:) throws`, `Store.snapshot(side:) throws -> (capturedAt: Date, ads: [Ad])?`.

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/StoreSeriesTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

private func seriesStore() throws -> (Store, URL) {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-series-\(UUID().uuidString).sqlite")
    return (try Store(fileURL: url), url)
}

private func makeSample(_ ts: TimeInterval, _ price: Double?) -> Sample {
    Sample(timestamp: Date(timeIntervalSince1970: ts), side: .sell, amountUSDT: 500,
           fillablePrice: price, topPrice: 332.0, medianTop10: nil, advertiserName: "x",
           advertiserAvailable: 700, advertiserMinFiat: 1_000, advertiserMaxFiat: 220_000)
}

@Test func seriesReturnsOnlyRowsInsideTheWindowAscending() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 100_000)

    try store.append(makeSample(100_000 - 7_200, 320.0))  // outside 1h
    try store.append(makeSample(100_000 - 1_800, 330.0))  // inside
    try store.append(makeSample(100_000 - 600,   331.0))  // inside

    let series = try store.series(side: .sell, amountUSDT: 500, window: .hour1, now: now)
    #expect(series.map(\.price) == [330.0, 331.0])
}

@Test func seriesOmitsRowsWithNoFillablePrice() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 100_000)

    try store.append(makeSample(100_000 - 1_800, 330.0))
    try store.append(makeSample(100_000 - 1_200, nil))    // nothing fillable then
    try store.append(makeSample(100_000 - 600,   331.0))

    // A price the chart cannot plot must not become a zero or a flat carry.
    let series = try store.series(side: .sell, amountUSDT: 500, window: .hour1, now: now)
    #expect(series.map(\.price) == [330.0, 331.0])
}

@Test func sevenDayWindowIsBucketedHourly() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 700_000)

    // Two samples inside one hour bucket, one in the next.
    try store.append(makeSample(690_000, 330.0))
    try store.append(makeSample(690_600, 332.0))
    try store.append(makeSample(694_000, 340.0))

    let series = try store.series(side: .sell, amountUSDT: 500, window: .day7, now: now)
    #expect(series.count == 2)
    #expect(series[0].price == 331.0)
    #expect(series[1].price == 340.0)
}

@Test func seriesIsScopedBySideAndAmount() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 100_000)

    try store.append(makeSample(99_000, 330.0))
    try store.append(Sample(timestamp: Date(timeIntervalSince1970: 99_000), side: .buy,
                            amountUSDT: 500, fillablePrice: 999.0, topPrice: 999.0,
                            medianTop10: nil, advertiserName: nil, advertiserAvailable: nil,
                            advertiserMinFiat: nil, advertiserMaxFiat: nil))

    let series = try store.series(side: .sell, amountUSDT: 500, window: .hour1, now: now)
    #expect(series.map(\.price) == [330.0])
}

@Test func snapshotRoundTripsTheAdList() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }

    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let captured = Date(timeIntervalSince1970: 100_000)
    try store.replaceSnapshot(side: .sell, ads: ads, capturedAt: captured)

    let got = try #require(try store.snapshot(side: .sell))
    #expect(got.capturedAt == captured)
    #expect(got.ads.count == 20)
    #expect(got.ads[0].advertiserName == "jeewani 98")
}

@Test func replacingASnapshotDoesNotAccumulateRows() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }

    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    try store.replaceSnapshot(side: .sell, ads: ads, capturedAt: Date(timeIntervalSince1970: 1))
    try store.replaceSnapshot(side: .sell, ads: Array(ads.prefix(3)),
                              capturedAt: Date(timeIntervalSince1970: 2))

    let got = try #require(try store.snapshot(side: .sell))
    #expect(got.ads.count == 3)
    #expect(got.capturedAt == Date(timeIntervalSince1970: 2))
}

@Test func snapshotIsNilBeforeAnyCapture() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    #expect(try store.snapshot(side: .buy) == nil)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter StoreSeries`
Expected: FAIL — `value of type 'Store' has no member 'series'`.

- [ ] **Step 3: Implement series and snapshot**

Append to `Store`:

```swift
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter StoreSeries`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole suite**

Run: `make test`
Expected: PASS, 48 tests, no failures.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add windowed series queries and latest-book snapshots

Samples with a NULL fillable price are excluded from the series rather than
zero-filled or carried forward: nothing was tradeable at that moment, and
either substitute would draw a line that never existed."
```

---

### Task 8: Alert threshold state machine

**Files:**
- Create: `P2PKit/Sources/P2PKit/AlertEngine.swift`
- Modify: `P2PKit/Sources/P2PKit/Store.swift`
- Create: `P2PKit/Tests/P2PKitTests/AlertEngineTests.swift`

**Interfaces:**
- Consumes: `Side`, `Store` (Tasks 2, 6).
- Produces: `AlertRule`, `ThresholdDirection` (`.above`, `.below`), `AlertState` (`.armed`, `.triggered`), `AlertEngine.evaluate(rule:price:state:) -> AlertDecision` with `fire: Bool` and `newState: AlertState`, plus `Store.alertRules()`, `Store.upsertAlert(_:state:firedAt:)`, `Store.alertState(id:)`.

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/AlertEngineTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

private let aboveRule = AlertRule(id: "a", side: .sell, amountUSDT: 500,
                                  threshold: 335.0, direction: .above)
private let belowRule = AlertRule(id: "b", side: .sell, amountUSDT: 500,
                                  threshold: 325.0, direction: .below)

@Test func firesOnceWhenCrossingAbove() {
    let decision = AlertEngine.evaluate(rule: aboveRule, price: 335.40, state: .armed)
    #expect(decision.fire)
    #expect(decision.newState == .triggered)
}

@Test func staysQuietWhileStillAboveThreshold() {
    // The whole point: no notification every 5 minutes for hours.
    let decision = AlertEngine.evaluate(rule: aboveRule, price: 336.00, state: .triggered)
    #expect(!decision.fire)
    #expect(decision.newState == .triggered)
}

@Test func reArmsAfterFallingBackBelow() {
    let decision = AlertEngine.evaluate(rule: aboveRule, price: 334.00, state: .triggered)
    #expect(!decision.fire)
    #expect(decision.newState == .armed)
}

@Test func firesAgainOnASecondCrossing() {
    var state = AlertState.armed
    state = AlertEngine.evaluate(rule: aboveRule, price: 336.0, state: state).newState
    state = AlertEngine.evaluate(rule: aboveRule, price: 330.0, state: state).newState
    let second = AlertEngine.evaluate(rule: aboveRule, price: 336.0, state: state)
    #expect(second.fire)
}

@Test func doesNotFireWhileBelowAnAboveThreshold() {
    let decision = AlertEngine.evaluate(rule: aboveRule, price: 330.0, state: .armed)
    #expect(!decision.fire)
    #expect(decision.newState == .armed)
}

@Test func thresholdIsInclusiveOnExactMatch() {
    #expect(AlertEngine.evaluate(rule: aboveRule, price: 335.0, state: .armed).fire)
    #expect(AlertEngine.evaluate(rule: belowRule, price: 325.0, state: .armed).fire)
}

@Test func belowDirectionMirrorsAbove() {
    let crossing = AlertEngine.evaluate(rule: belowRule, price: 324.0, state: .armed)
    #expect(crossing.fire)
    #expect(crossing.newState == .triggered)

    let staying = AlertEngine.evaluate(rule: belowRule, price: 320.0, state: .triggered)
    #expect(!staying.fire)

    let recovering = AlertEngine.evaluate(rule: belowRule, price: 326.0, state: .triggered)
    #expect(!recovering.fire)
    #expect(recovering.newState == .armed)
}

@Test func alertRulesRoundTripThroughTheStore() throws {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-alerts-\(UUID().uuidString).sqlite")
    defer { try? FileManager.default.removeItem(at: url) }
    let store = try Store(fileURL: url)

    try store.upsertAlert(aboveRule, state: .armed, firedAt: nil)
    #expect(try store.alertRules().count == 1)
    #expect(try store.alertState(id: "a") == .armed)

    try store.upsertAlert(aboveRule, state: .triggered,
                          firedAt: Date(timeIntervalSince1970: 5_000))
    #expect(try store.alertRules().count == 1)
    #expect(try store.alertState(id: "a") == .triggered)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter AlertEngine`
Expected: FAIL — `cannot find 'AlertRule' in scope`.

- [ ] **Step 3: Implement AlertEngine**

`P2PKit/Sources/P2PKit/AlertEngine.swift`:

```swift
import Foundation

public enum ThresholdDirection: String, Sendable, Codable, CaseIterable {
    case above, below
}

public enum AlertState: String, Sendable, Codable {
    /// Waiting for a crossing.
    case armed
    /// Already notified; will not notify again until the price crosses back.
    case triggered
}

public struct AlertRule: Sendable, Codable, Equatable, Identifiable {
    public let id: String
    public let side: Side
    public let amountUSDT: Int
    public let threshold: Double
    public let direction: ThresholdDirection

    public init(id: String = UUID().uuidString, side: Side, amountUSDT: Int,
                threshold: Double, direction: ThresholdDirection) {
        self.id = id
        self.side = side
        self.amountUSDT = amountUSDT
        self.threshold = threshold
        self.direction = direction
    }
}

public struct AlertDecision: Sendable, Equatable {
    public let fire: Bool
    public let newState: AlertState
}

public enum AlertEngine {
    /// Edge-triggered, not level-triggered: a notification fires on the
    /// transition into the threshold and the rule then re-arms only once the
    /// price crosses back. Without this the collector would notify every five
    /// minutes for as long as the rate stayed past the threshold.
    public static func evaluate(rule: AlertRule, price: Double,
                                state: AlertState) -> AlertDecision {
        let beyond = switch rule.direction {
        case .above: price >= rule.threshold
        case .below: price <= rule.threshold
        }

        switch (beyond, state) {
        case (true,  .armed):     return AlertDecision(fire: true,  newState: .triggered)
        case (true,  .triggered): return AlertDecision(fire: false, newState: .triggered)
        case (false, .triggered): return AlertDecision(fire: false, newState: .armed)
        case (false, .armed):     return AlertDecision(fire: false, newState: .armed)
        }
    }
}
```

- [ ] **Step 4: Add alert persistence to Store**

Append to `Store`:

```swift
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
                      let side = statement.string(1).flatMap(Side.init(rawValue:)),
                      let direction = statement.string(4)
                          .flatMap(ThresholdDirection.init(rawValue:))
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
            return statement.string(0).flatMap(AlertState.init(rawValue:))
        }
    }

    public func deleteAlert(id: String) throws {
        try database.statement("DELETE FROM alerts WHERE id = ?1;") { statement in
            statement.bind(1, id)
            _ = try statement.step()
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter AlertEngine`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add edge-triggered threshold alerts

Alerts fire on the transition past a threshold and re-arm only after the
price crosses back. Level-triggering would notify every five minutes for
as long as the rate stayed beyond the threshold."
```

---

### Task 9: Shared settings

**Files:**
- Create: `P2PKit/Sources/P2PKit/Settings.swift`
- Create: `P2PKit/Tests/P2PKitTests/SettingsTests.swift`

**Interfaces:**
- Consumes: `Side`, `PaymentMethod`, `ChartWindow`, `AppGroup` (Tasks 1, 2).
- Produces: `Settings(defaults:)` with `amountUSDT: Int`, `side: Side`, `payment: PaymentMethod`, `window: ChartWindow`, `fiat: String`, `launchAtLogin: Bool`, plus `Settings.shared` and `Settings.Defaults` constants.

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/SettingsTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

private func isolatedDefaults() -> UserDefaults {
    let suite = "p2p-settings-test-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return defaults
}

@Test func seedsTheDocumentedDefaults() {
    let settings = Settings(defaults: isolatedDefaults())
    #expect(settings.amountUSDT == 500)          // spec: seeded default
    #expect(settings.side == .sell)
    #expect(settings.payment == .bankSriLanka)
    #expect(settings.window == .hour24)
    #expect(settings.fiat == "LKR")
}

@Test func persistsChangedValues() {
    let defaults = isolatedDefaults()
    let settings = Settings(defaults: defaults)
    settings.amountUSDT = 2_000
    settings.side = .buy
    settings.window = .day7

    let reloaded = Settings(defaults: defaults)
    #expect(reloaded.amountUSDT == 2_000)
    #expect(reloaded.side == .buy)
    #expect(reloaded.window == .day7)
}

@Test func rejectsNonPositiveAmounts() {
    let settings = Settings(defaults: isolatedDefaults())
    settings.amountUSDT = 0
    #expect(settings.amountUSDT == 500)
    settings.amountUSDT = -100
    #expect(settings.amountUSDT == 500)
}

@Test func fallsBackWhenStoredEnumIsUnrecognised() {
    let defaults = isolatedDefaults()
    defaults.set("SIDEWAYS", forKey: "side")
    defaults.set("99y", forKey: "window")
    let settings = Settings(defaults: defaults)
    #expect(settings.side == .sell)
    #expect(settings.window == .hour24)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter Settings`
Expected: FAIL — `cannot find 'Settings' in scope`.

- [ ] **Step 3: Implement Settings**

`P2PKit/Sources/P2PKit/Settings.swift`:

```swift
import Foundation

/// Preferences shared between the app and the widget through the App Group
/// defaults suite. Widget instances may override side, amount, and window via
/// their own AppIntent configuration; these are the global fallbacks.
public final class Settings: @unchecked Sendable {
    public enum Defaults {
        /// Seeded amount from the design decisions. Roughly 165,000 LKR, which
        /// several ads in the recorded book can fill.
        public static let amountUSDT = 500
        public static let side = Side.sell
        public static let payment = PaymentMethod.bankSriLanka
        public static let window = ChartWindow.hour24
        public static let fiat = "LKR"
        public static let pollInterval: TimeInterval = 300
        public static let jitter: TimeInterval = 20
        public static let retentionDays = 30
        /// Beyond this age the widget renders a stale state.
        public static let stalenessThreshold: TimeInterval = 900
    }

    private let defaults: UserDefaults

    public static let shared = Settings(
        defaults: UserDefaults(suiteName: AppGroup.identifier) ?? .standard)

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    public var amountUSDT: Int {
        get {
            let stored = defaults.integer(forKey: "amountUSDT")
            return stored > 0 ? stored : Defaults.amountUSDT
        }
        // A non-positive amount cannot be filled by any ad, so refuse it
        // rather than storing a value that guarantees an empty chart.
        set { if newValue > 0 { defaults.set(newValue, forKey: "amountUSDT") } }
    }

    public var side: Side {
        get { defaults.string(forKey: "side").flatMap(Side.init(rawValue:)) ?? Defaults.side }
        set { defaults.set(newValue.rawValue, forKey: "side") }
    }

    public var payment: PaymentMethod {
        get {
            defaults.string(forKey: "payment")
                .flatMap(PaymentMethod.init(rawValue:)) ?? Defaults.payment
        }
        set { defaults.set(newValue.rawValue, forKey: "payment") }
    }

    public var window: ChartWindow {
        get {
            defaults.string(forKey: "window")
                .flatMap(ChartWindow.init(rawValue:)) ?? Defaults.window
        }
        set { defaults.set(newValue.rawValue, forKey: "window") }
    }

    public var fiat: String {
        get { defaults.string(forKey: "fiat") ?? Defaults.fiat }
        set { defaults.set(newValue, forKey: "fiat") }
    }

    public var launchAtLogin: Bool {
        get { defaults.object(forKey: "launchAtLogin") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "launchAtLogin") }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter Settings`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add App Group-shared settings with validated defaults

Seeds 500 USDT per the design decisions and refuses non-positive amounts,
which no ad could ever fill."
```

---

### Task 10: Poller orchestration

**Files:**
- Create: `P2PKit/Sources/P2PKit/Poller.swift`
- Create: `P2PKit/Tests/P2PKitTests/PollerTests.swift`

**Interfaces:**
- Consumes: everything from Tasks 2–9.
- Produces: `protocol AdSource { func fetch(side:fiat:payment:rows:) async throws -> [Ad] }`, `protocol WidgetReloader { func reload() }`, `protocol AlertPresenter { func present(rule:price:) async }`, `Poller(source:store:settings:reloader:presenter:)`, `Poller.pollOnce(now:) async -> PollOutcome`, and `PollOutcome` (`.stored(Sample)`, `.failed(P2PError)`).

- [ ] **Step 1: Write the failing tests**

`P2PKit/Tests/P2PKitTests/PollerTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

private actor FakeSource: AdSource {
    var results: [Result<[Ad], any Error>]
    private(set) var callCount = 0
    init(results: [Result<[Ad], any Error>]) { self.results = results }
    func fetch(side: Side, fiat: String, payment: PaymentMethod, rows: Int) async throws -> [Ad] {
        callCount += 1
        guard !results.isEmpty else { throw P2PError.emptyResult }
        return try results.removeFirst().get()
    }
    func calls() -> Int { callCount }
}

private final class SpyReloader: WidgetReloader, @unchecked Sendable {
    var count = 0
    func reload() { count += 1 }
}

private actor SpyPresenter: AlertPresenter {
    private(set) var presented: [(String, Double)] = []
    func present(rule: AlertRule, price: Double) async { presented.append((rule.id, price)) }
    func all() -> [(String, Double)] { presented }
}

private func pollerFixtures() throws -> (Store, URL, Settings) {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-poller-\(UUID().uuidString).sqlite")
    let suite = "p2p-poller-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return (try Store(fileURL: url), url, Settings(defaults: defaults))
}

@Test func successfulPollStoresSampleSnapshotAndReloadsWidget() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let reloader = SpyReloader()

    let poller = Poller(source: FakeSource(results: [.success(ads)]), store: store,
                        settings: settings, reloader: reloader, presenter: SpyPresenter())
    let outcome = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))

    guard case .stored(let sample) = outcome else {
        Issue.record("expected .stored, got \(outcome)"); return
    }
    #expect(sample.fillablePrice == 331.00)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
    #expect(try store.snapshot(side: .sell)?.ads.count == 20)
    #expect(reloader.count == 1)
}

@Test func failedPollStoresNothingAndLeavesAGap() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let reloader = SpyReloader()

    let source = FakeSource(results: [
        .failure(P2PError.httpStatus(503)),
        .failure(P2PError.httpStatus(503)),
    ])
    let poller = Poller(source: source, store: store, settings: settings,
                        reloader: reloader, presenter: SpyPresenter())
    let outcome = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))

    guard case .failed = outcome else { Issue.record("expected .failed"); return }
    // A fabricated price would be worse than a hole in the chart.
    #expect(try store.sampleCount() == 0)
    #expect(reloader.count == 0)
}

@Test func retriesOnceBeforeGivingUp() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))

    let source = FakeSource(results: [.failure(P2PError.httpStatus(500)), .success(ads)])
    let poller = Poller(source: source, store: store, settings: settings,
                        reloader: SpyReloader(), presenter: SpyPresenter(),
                        retryDelay: 0)
    let outcome = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))

    guard case .stored = outcome else { Issue.record("expected .stored"); return }
    #expect(await source.calls() == 2)
}

@Test func firesAlertOnceWhenThresholdIsCrossed() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))

    let rule = AlertRule(id: "r1", side: .sell, amountUSDT: 500,
                         threshold: 330.0, direction: .above)
    try store.upsertAlert(rule, state: .armed, firedAt: nil)

    let presenter = SpyPresenter()
    let poller = Poller(source: FakeSource(results: [.success(ads), .success(ads)]),
                        store: store, settings: settings,
                        reloader: SpyReloader(), presenter: presenter)

    _ = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))
    _ = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_300))

    // 331.00 clears the 330.0 threshold on both polls, but only the first
    // transition notifies.
    #expect(await presenter.all().count == 1)
    #expect(try store.alertState(id: "r1") == .triggered)
}

@Test func prunesRowsBeyondTheRetentionWindow() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))

    let now = Date(timeIntervalSince1970: 40 * 86_400)
    try store.append(Sample(timestamp: Date(timeIntervalSince1970: 0), side: .sell,
                            amountUSDT: 500, fillablePrice: 300.0, topPrice: 300.0,
                            medianTop10: nil, advertiserName: nil, advertiserAvailable: nil,
                            advertiserMinFiat: nil, advertiserMaxFiat: nil))

    let poller = Poller(source: FakeSource(results: [.success(ads)]), store: store,
                        settings: settings, reloader: SpyReloader(), presenter: SpyPresenter())
    _ = await poller.pollOnce(now: now)

    // The 30-day-old row goes; the one just written stays.
    #expect(try store.sampleCount() == 1)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
}

@Test func jitterStaysWithinTheConfiguredBound() {
    let base = Settings.Defaults.pollInterval
    let jitter = Settings.Defaults.jitter
    for _ in 0..<200 {
        let delay = Poller.nextDelay(interval: base, jitter: jitter)
        #expect(delay >= base - jitter)
        #expect(delay <= base + jitter)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter Poller`
Expected: FAIL — `cannot find 'Poller' in scope`.

- [ ] **Step 3: Implement the Poller**

`P2PKit/Sources/P2PKit/Poller.swift`:

```swift
import Foundation

public protocol AdSource: Sendable {
    func fetch(side: Side, fiat: String, payment: PaymentMethod, rows: Int) async throws -> [Ad]
}

public protocol WidgetReloader: Sendable {
    func reload()
}

public protocol AlertPresenter: Sendable {
    func present(rule: AlertRule, price: Double) async
}

extension BinanceP2PClient: AdSource {
    public func fetch(side: Side, fiat: String,
                      payment: PaymentMethod, rows: Int) async throws -> [Ad] {
        try await search(side: side, fiat: fiat, payment: payment, rows: rows)
    }
}

public enum PollOutcome: Sendable {
    case stored(Sample)
    case failed(P2PError)
}

/// Owns one polling cycle: fetch, compute, persist, alert, reload.
public actor Poller {
    private let source: any AdSource
    private let store: Store
    private let settings: Settings
    private let reloader: any WidgetReloader
    private let presenter: any AlertPresenter
    private let retryDelay: TimeInterval

    public init(source: any AdSource, store: Store, settings: Settings,
                reloader: any WidgetReloader, presenter: any AlertPresenter,
                retryDelay: TimeInterval = 2) {
        self.source = source
        self.store = store
        self.settings = settings
        self.reloader = reloader
        self.presenter = presenter
        self.retryDelay = retryDelay
    }

    /// Spread requests around the interval instead of hitting the endpoint on
    /// a fixed beat.
    public static func nextDelay(interval: TimeInterval, jitter: TimeInterval) -> TimeInterval {
        interval + TimeInterval.random(in: -jitter...jitter)
    }

    public func pollOnce(now: Date = .now) async -> PollOutcome {
        let side = settings.side
        let amount = settings.amountUSDT

        let ads: [Ad]
        do {
            ads = try await fetchWithOneRetry(side: side)
        } catch let error as P2PError {
            return .failed(error)
        } catch {
            return .failed(.transport(error.localizedDescription))
        }

        guard let sample = MetricsEngine.makeSample(
            ads: ads, side: side, amountUSDT: amount, timestamp: now) else {
            return .failed(.emptyResult)
        }

        do {
            // A failed poll writes nothing at all: a gap in the chart is
            // honest, a fabricated price is not.
            try store.append(sample)
            try store.replaceSnapshot(side: side, ads: ads, capturedAt: now)
            try store.prune(olderThan: now.addingTimeInterval(
                -Double(Settings.Defaults.retentionDays) * 86_400))
        } catch {
            return .failed(.malformed(String(describing: error)))
        }

        if let price = sample.fillablePrice {
            await evaluateAlerts(side: side, amount: amount, price: price, now: now)
        }
        reloader.reload()
        return .stored(sample)
    }

    private func fetchWithOneRetry(side: Side) async throws -> [Ad] {
        do {
            return try await source.fetch(side: side, fiat: settings.fiat,
                                          payment: settings.payment, rows: 20)
        } catch {
            if retryDelay > 0 {
                try? await Task.sleep(for: .seconds(retryDelay))
            }
            return try await source.fetch(side: side, fiat: settings.fiat,
                                          payment: settings.payment, rows: 20)
        }
    }

    private func evaluateAlerts(side: Side, amount: Int, price: Double, now: Date) async {
        guard let rules = try? store.alertRules() else { return }
        for rule in rules where rule.side == side && rule.amountUSDT == amount {
            // `try?` on an optional-returning call yields a double optional;
            // flatten it before use. An unknown rule starts armed.
            let stored: AlertState?? = try? store.alertState(id: rule.id)
            let state = stored.flatMap { $0 } ?? .armed
            let decision = AlertEngine.evaluate(rule: rule, price: price, state: state)
            if decision.fire {
                await presenter.present(rule: rule, price: price)
            }
            try? store.upsertAlert(rule, state: decision.newState,
                                   firedAt: decision.fire ? now : nil)
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter Poller`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the whole suite**

Run: `make test`
Expected: PASS, 66 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add poller orchestration with retry, pruning, and jitter

A failed poll writes no row, so the chart shows a gap instead of a
fabricated price. Requests are jittered around the five-minute mark rather
than landing on a fixed beat."
```

---

### Task 11: App shell — login item, lifecycle, timer

**Files:**
- Modify: `P2PMonitor/AppMain.swift`
- Create: `P2PMonitor/AppEnvironment.swift`
- Create: `P2PMonitor/CollectorService.swift`
- Create: `P2PMonitor/WidgetCenterReloader.swift`

**Interfaces:**
- Consumes: `Poller`, `Store`, `Settings`, `AppGroup`, `BinanceP2PClient` (Tasks 1–10).
- Produces: `AppEnvironment.shared` exposing `store: Store?`, `settings: Settings`, `collector: CollectorService?`; `CollectorService.start()`, `.stop()`, `.pollNow() async`.

- [ ] **Step 1: Implement the widget reloader**

`P2PMonitor/WidgetCenterReloader.swift`:

```swift
import WidgetKit
import P2PKit

struct WidgetCenterReloader: WidgetReloader {
    func reload() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
```

- [ ] **Step 2: Implement the app environment**

`P2PMonitor/AppEnvironment.swift`:

```swift
import Foundation
import P2PKit
import OSLog

/// Resolves the shared container once and holds the long-lived objects.
@MainActor
final class AppEnvironment {
    static let shared = AppEnvironment()

    let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "app")
    let settings = Settings.shared
    let store: Store?
    let collector: CollectorService?

    private init() {
        guard let url = AppGroup.databaseURL else {
            // Without the entitlement there is no shared store and the widget
            // could never read anything, so fail loudly rather than silently
            // collecting into a private container.
            logger.error("App Group container unavailable — check entitlements")
            store = nil
            collector = nil
            return
        }
        do {
            let store = try Store(fileURL: url)
            self.store = store
            self.collector = CollectorService(
                store: store,
                settings: settings,
                presenter: NotificationPresenter())
        } catch {
            logger.error("Store open failed: \(String(describing: error))")
            store = nil
            collector = nil
        }
    }
}
```

- [ ] **Step 3: Implement the collector service**

`P2PMonitor/CollectorService.swift`:

```swift
import Foundation
import P2PKit
import OSLog

/// Drives `Poller` on a jittered five-minute cadence for as long as the app runs.
@MainActor
final class CollectorService {
    private let poller: Poller
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "collector")
    private var task: Task<Void, Never>?

    @Published private(set) var lastOutcomeDescription: String = "not started"

    init(store: Store, settings: Settings, presenter: any AlertPresenter) {
        poller = Poller(source: BinanceP2PClient(), store: store, settings: settings,
                        reloader: WidgetCenterReloader(), presenter: presenter)
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            // Poll immediately so a fresh launch is not blank for five minutes.
            await self?.pollNow()
            while !Task.isCancelled {
                let delay = Poller.nextDelay(interval: Settings.Defaults.pollInterval,
                                             jitter: Settings.Defaults.jitter)
                try? await Task.sleep(for: .seconds(delay))
                if Task.isCancelled { break }
                await self?.pollNow()
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    func pollNow() async {
        let outcome = await poller.pollOnce()
        switch outcome {
        case .stored(let sample):
            let price = sample.fillablePrice.map { "\($0)" } ?? "none fillable"
            lastOutcomeDescription = "ok — \(price)"
            logger.info("poll stored \(price, privacy: .public)")
        case .failed(let error):
            lastOutcomeDescription = "failed — \(error)"
            // Deliberately no row written; the chart will show a gap.
            logger.warning("poll failed \(String(describing: error), privacy: .public)")
        }
    }
}
```

- [ ] **Step 4: Rewrite the app entry point**

`P2PMonitor/AppMain.swift`:

```swift
import SwiftUI
import ServiceManagement
import P2PKit
import OSLog

@main
struct P2PMonitorApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        // Replaced with the real detail window in Task 16.
        Window("USDT/LKR Rate", id: "detail") {
            Text("Collecting…").frame(minWidth: 420, minHeight: 320)
        }
        Settings { Text("Preferences").padding() }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "lifecycle")

    func applicationDidFinishLaunching(_ notification: Notification) {
        AppEnvironment.shared.collector?.start()
        registerLoginItemIfWanted()
    }

    func applicationWillTerminate(_ notification: Notification) {
        AppEnvironment.shared.collector?.stop()
    }

    /// LSUIElement hides the Dock icon, so a second launch would otherwise do
    /// nothing visible. Surfacing the detail window keeps the app reachable.
    func applicationShouldHandleReopen(_ sender: NSApplication,
                                       hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows {
            NSApp.windows.first?.makeKeyAndOrderFront(nil)
        }
        NSApp.activate(ignoringOtherApps: true)
        return true
    }

    private func registerLoginItemIfWanted() {
        guard AppEnvironment.shared.settings.launchAtLogin else { return }
        do {
            if SMAppService.mainApp.status != .enabled {
                try SMAppService.mainApp.register()
            }
        } catch {
            logger.warning("login item registration failed: \(String(describing: error), privacy: .public)")
        }
    }
}
```

- [ ] **Step 5: Regenerate and build**

Run: `make build`
Expected: `** BUILD SUCCEEDED **`. `NotificationPresenter` does not exist yet, so this step **will fail to compile** — implement Task 12 before re-running. Proceed to Task 12 now.

- [ ] **Step 6: Commit after Task 12 builds**

Deferred — this task and Task 12 land in one commit because the app target does not compile without the presenter.

---

### Task 12: Threshold notifications

**Files:**
- Create: `P2PMonitor/NotificationPresenter.swift`

**Interfaces:**
- Consumes: `AlertPresenter`, `AlertRule` (Tasks 8, 10).
- Produces: `NotificationPresenter` conforming to `AlertPresenter`, plus `requestAuthorization()`.

- [ ] **Step 1: Implement the presenter**

`P2PMonitor/NotificationPresenter.swift`:

```swift
import Foundation
import UserNotifications
import P2PKit
import OSLog

/// Posts a macOS notification when a threshold is crossed. The once-per-crossing
/// guarantee lives in AlertEngine; this type only presents.
struct NotificationPresenter: AlertPresenter {
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "notify")

    func requestAuthorization() {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound]) { granted, error in
                if let error {
                    logger.warning("authorization failed: \(String(describing: error), privacy: .public)")
                } else {
                    logger.info("notification authorization granted: \(granted, privacy: .public)")
                }
            }
    }

    func present(rule: AlertRule, price: Double) async {
        let content = UNMutableNotificationContent()
        content.title = "USDT/LKR \(rule.side == .sell ? "sell" : "buy") rate"
        let comparison = rule.direction == .above ? "rose above" : "fell below"
        content.body = String(
            format: "%@ %.2f — now %.2f for %d USDT",
            comparison, rule.threshold, price, rule.amountUSDT)
        content.sound = .default

        let request = UNNotificationRequest(identifier: "\(rule.id)-\(Int(Date.now.timeIntervalSince1970))",
                                            content: content, trigger: nil)
        do {
            try await UNUserNotificationCenter.current().add(request)
        } catch {
            logger.warning("post failed: \(String(describing: error), privacy: .public)")
        }
    }
}
```

- [ ] **Step 2: Request authorization at launch**

In `P2PMonitor/AppMain.swift`, inside `applicationDidFinishLaunching`, before
`collector?.start()`:

```swift
        NotificationPresenter().requestAuthorization()
```

- [ ] **Step 3: Regenerate and build**

Run: `make build`
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Verify signing still propagates the App Group**

Run: `make sign-check`
Expected: the group identifier printed twice.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add collector service, login item, and threshold notifications

The app is a hidden LSUIElement login item, so a second launch would
otherwise do nothing visible; applicationShouldHandleReopen surfaces the
detail window to keep it reachable."
```

---

### Task 13: Widget configuration intent and timeline provider

**Files:**
- Create: `P2PWidget/RateConfigurationIntent.swift`
- Create: `P2PWidget/RateTimelineProvider.swift`
- Create: `P2PWidget/RateEntry.swift`
- Modify: `P2PWidget/WidgetBundleMain.swift`

**Interfaces:**
- Consumes: `Store`, `Settings`, `MetricsEngine`, `ChartWindow`, `Side`, `AppGroup` (Tasks 1–9).
- Produces: `RateConfigurationIntent` (`side`, `amountUSDT`, `window`), `RateEntry` (`date`, `sample`, `series`, `trend`, `state`), `RateEntry.State` (`.ok`, `.stale(Date)`, `.noData`, `.error`), `RateTimelineProvider`.

- [ ] **Step 1: Implement the entry**

`P2PWidget/RateEntry.swift`:

```swift
import WidgetKit
import Foundation
import P2PKit

struct RateEntry: TimelineEntry {
    enum State: Equatable {
        case ok
        /// Newest sample is older than the staleness threshold.
        case stale(since: Date)
        case noData
        case error
    }

    let date: Date
    let side: Side
    let amountUSDT: Int
    let window: ChartWindow
    let sample: Sample?
    let series: [SeriesPoint]
    let trend: Trend?
    let state: State
    /// Top ads for the large family.
    let topAds: [Ad]

    static func placeholder(date: Date = .now) -> RateEntry {
        RateEntry(date: date, side: .sell, amountUSDT: 500, window: .hour24,
                  sample: nil, series: [], trend: nil, state: .noData, topAds: [])
    }
}
```

- [ ] **Step 2: Implement the configuration intent**

`P2PWidget/RateConfigurationIntent.swift`:

```swift
import AppIntents
import WidgetKit
import P2PKit

extension Side: @retroactive AppEnum {
    public static var typeDisplayRepresentation: TypeDisplayRepresentation { "Side" }
    public static var caseDisplayRepresentations: [Side: DisplayRepresentation] {
        [.sell: "Sell USDT", .buy: "Buy USDT"]
    }
}

extension ChartWindow: @retroactive AppEnum {
    public static var typeDisplayRepresentation: TypeDisplayRepresentation { "Window" }
    public static var caseDisplayRepresentations: [ChartWindow: DisplayRepresentation] {
        [.hour1: "1 hour", .hour24: "24 hours", .day7: "7 days", .day30: "30 days"]
    }
}

struct RateConfigurationIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "USDT/LKR Rate" }
    static var description: IntentDescription {
        IntentDescription("Best P2P rate that can fill your trade size.")
    }

    @Parameter(title: "Side", default: .sell)
    var side: Side

    /// The amount decides which ads even qualify, so it is the most important
    /// knob on the widget.
    @Parameter(title: "Trade amount (USDT)", default: 500,
               inclusiveRange: (1, 1_000_000))
    var amountUSDT: Int

    @Parameter(title: "Chart window", default: .hour24)
    var window: ChartWindow
}
```

> **Confirmed during execution: the retroactive-conformance version above
> does NOT compile.** `appintentsmetadataprocessor` rejects it with "enums
> implemented in an imported framework or library are not supported", and
> separately requires the display representations be `static let` constants
> rather than computed properties. Use local mirrors instead — declare
> `enum SideOption: String, AppEnum { case sell, buy }` and
> `enum WindowOption: String, AppEnum { case hour1, hour24, day7, day30 }`
> in the widget target with `var asSide: Side` / `var asWindow: ChartWindow`
> accessors, expose those from the intent, and convert in
> `RateTimelineProvider.entry(for:now:)`. This keeps P2PKit free of any
> AppIntents dependency. See `P2PWidget/RateConfigurationIntent.swift` for
> the shipped version.

- [ ] **Step 3: Implement the timeline provider**

`P2PWidget/RateTimelineProvider.swift`:

```swift
import WidgetKit
import Foundation
import P2PKit

/// Reads only. All fetching happens in the container app: widget timeline
/// reloads are budget-capped by the OS and cannot hold a five-minute cadence.
struct RateTimelineProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> RateEntry { .placeholder() }

    func snapshot(for configuration: RateConfigurationIntent,
                  in context: Context) async -> RateEntry {
        entry(for: configuration, now: .now)
    }

    func timeline(for configuration: RateConfigurationIntent,
                  in context: Context) async -> Timeline<RateEntry> {
        let now = Date.now
        // The app reloads timelines after each successful poll; this policy is
        // only a backstop for when the collector is not running.
        return Timeline(entries: [entry(for: configuration, now: now)],
                        policy: .after(now.addingTimeInterval(600)))
    }

    private func entry(for configuration: RateConfigurationIntent, now: Date) -> RateEntry {
        guard let url = AppGroup.databaseURL, let store = try? Store(fileURL: url) else {
            return RateEntry(date: now, side: configuration.side,
                             amountUSDT: configuration.amountUSDT,
                             window: configuration.window, sample: nil, series: [],
                             trend: nil, state: .error, topAds: [])
        }

        let side = configuration.side
        let amount = configuration.amountUSDT
        let window = configuration.window

        guard let sample = try? store.latest(side: side, amountUSDT: amount) else {
            return RateEntry(date: now, side: side, amountUSDT: amount, window: window,
                             sample: nil, series: [], trend: nil, state: .noData, topAds: [])
        }

        let series = (try? store.series(side: side, amountUSDT: amount,
                                        window: window, now: now)) ?? []
        let ads = (try? store.snapshot(side: side))?.ads ?? []

        let age = now.timeIntervalSince(sample.timestamp)
        let state: RateEntry.State = age > Settings.Defaults.stalenessThreshold
            ? .stale(since: sample.timestamp)
            : .ok

        return RateEntry(date: now, side: side, amountUSDT: amount, window: window,
                         sample: sample, series: series,
                         trend: MetricsEngine.trend(series: series), state: state,
                         topAds: Array(ads.prefix(5)))
    }
}
```

- [ ] **Step 4: Wire the widget bundle**

Replace `P2PWidget/WidgetBundleMain.swift`:

```swift
import WidgetKit
import SwiftUI

struct RateWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "RateWidget",
                               intent: RateConfigurationIntent.self,
                               provider: RateTimelineProvider()) { entry in
            RateWidgetView(entry: entry)
        }
        .configurationDisplayName("USDT/LKR Rate")
        .description("Best Binance P2P rate that can fill your trade size.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct P2PWidgetBundle: WidgetBundle {
    var body: some Widget { RateWidget() }
}
```

- [ ] **Step 5: Add a temporary placeholder view so the target compiles**

`P2PWidget/RateWidgetView.swift` — replaced in Tasks 14 and 15:

```swift
import SwiftUI
import WidgetKit

struct RateWidgetView: View {
    let entry: RateEntry
    var body: some View {
        Text(entry.sample?.fillablePrice.map { String(format: "%.2f", $0) } ?? "—")
            .containerBackground(.fill, for: .widget)
    }
}
```

- [ ] **Step 6: Build**

Run: `make build`
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add widget configuration intent and read-only timeline provider

Trade amount is a per-widget parameter because it decides which ads
qualify at all, so two widgets can track different sizes."
```

---

### Task 14: Small and medium widget views

**Files:**
- Create: `P2PWidget/Views/RateHeader.swift`
- Create: `P2PWidget/Views/RateSparkline.swift`
- Create: `P2PWidget/Views/SmallRateView.swift`
- Create: `P2PWidget/Views/MediumRateView.swift`
- Modify: `P2PWidget/RateWidgetView.swift`
- Create: `P2PKit/Sources/P2PKit/Formatting.swift`
- Create: `P2PKit/Tests/P2PKitTests/FormattingTests.swift`

**Interfaces:**
- Consumes: `RateEntry`, `Trend`, `SeriesPoint` (Tasks 5, 13).
- Produces: `Formatting.price(_:)`, `Formatting.signedDelta(_:)`, `Formatting.percent(_:)`, `Formatting.relativeAge(_:from:)`, `Formatting.usdt(_:)`; views `RateHeader`, `RateSparkline`, `SmallRateView`, `MediumRateView`.

- [ ] **Step 1: Write the failing formatting tests**

`P2PKit/Tests/P2PKitTests/FormattingTests.swift`:

```swift
import Testing
import Foundation
@testable import P2PKit

@Test func priceAlwaysShowsTwoDecimals() {
    #expect(Formatting.price(331.0) == "331.00")
    #expect(Formatting.price(330.765) == "330.77")
    #expect(Formatting.price(nil) == "—")
}

@Test func deltaCarriesAnExplicitSign() {
    #expect(Formatting.signedDelta(0.34) == "+0.34")
    #expect(Formatting.signedDelta(-0.34) == "-0.34")
    #expect(Formatting.signedDelta(0) == "0.00")
}

@Test func percentIsSignedToTwoDecimals() {
    #expect(Formatting.percent(0.1027) == "+0.10%")
    #expect(Formatting.percent(-1.5) == "-1.50%")
}

@Test func usdtAmountsAreGrouped() {
    #expect(Formatting.usdt(8000) == "8,000 USDT")
    #expect(Formatting.usdt(500) == "500 USDT")
}

@Test func relativeAgeReadsInPlainWords() {
    let now = Date(timeIntervalSince1970: 10_000)
    #expect(Formatting.relativeAge(now.addingTimeInterval(-30), from: now) == "just now")
    #expect(Formatting.relativeAge(now.addingTimeInterval(-120), from: now) == "2m ago")
    #expect(Formatting.relativeAge(now.addingTimeInterval(-7_200), from: now) == "2h ago")
    #expect(Formatting.relativeAge(now.addingTimeInterval(-172_800), from: now) == "2d ago")
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd P2PKit && swift test --filter Formatting`
Expected: FAIL — `cannot find 'Formatting' in scope`.

- [ ] **Step 3: Implement Formatting**

`P2PKit/Sources/P2PKit/Formatting.swift`:

```swift
import Foundation

public enum Formatting {
    public static func price(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%.2f", value)
    }

    public static func signedDelta(_ value: Double) -> String {
        value > 0 ? String(format: "+%.2f", value) : String(format: "%.2f", value)
    }

    public static func percent(_ value: Double) -> String {
        value > 0 ? String(format: "+%.2f%%", value) : String(format: "%.2f%%", value)
    }

    public static func usdt(_ amount: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        let number = formatter.string(from: NSNumber(value: amount)) ?? "\(amount)"
        return "\(number) USDT"
    }

    public static func usdt(_ amount: Double) -> String {
        usdt(Int(amount.rounded()))
    }

    public static func fiat(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        formatter.groupingSeparator = ","
        return formatter.string(from: NSNumber(value: amount)) ?? "\(Int(amount))"
    }

    public static func relativeAge(_ date: Date, from now: Date = .now) -> String {
        let seconds = Int(now.timeIntervalSince(date))
        switch seconds {
        case ..<60:      return "just now"
        case ..<3_600:   return "\(seconds / 60)m ago"
        case ..<86_400:  return "\(seconds / 3_600)h ago"
        default:         return "\(seconds / 86_400)d ago"
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd P2PKit && swift test --filter Formatting`
Expected: PASS, 5 tests.

- [ ] **Step 5: Implement the shared header**

`P2PWidget/Views/RateHeader.swift`:

```swift
import SwiftUI
import P2PKit

/// Price plus direction. SF Symbols, never emoji.
struct RateHeader: View {
    let entry: RateEntry
    let showsCaption: Bool

    private var directionColor: Color {
        switch entry.trend?.direction {
        case .up:   .green
        case .down: .red
        default:    .secondary
        }
    }

    private var directionSymbol: String {
        switch entry.trend?.direction {
        case .up:   "arrow.up.right"
        case .down: "arrow.down.right"
        default:    "minus"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(Formatting.price(entry.sample?.fillablePrice))
                    .font(.system(.title2, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                if let trend = entry.trend {
                    HStack(spacing: 2) {
                        Image(systemName: directionSymbol).imageScale(.small)
                        Text(Formatting.percent(trend.percent)).monospacedDigit()
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(directionColor)
                }
            }
            if showsCaption {
                Text("\(entry.side == .sell ? "Sell" : "Buy") \(Formatting.usdt(entry.amountUSDT))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
```

- [ ] **Step 6: Implement the sparkline**

`P2PWidget/Views/RateSparkline.swift`:

```swift
import SwiftUI
import Charts
import P2PKit

/// Gaps in `series` stay gaps: the collector writes no row for a failed poll,
/// and a connected line there would imply a rate that never existed.
struct RateSparkline: View {
    let series: [SeriesPoint]
    let direction: Trend.Direction?
    let showsAxes: Bool

    private var lineColor: Color {
        switch direction {
        case .up:   .green
        case .down: .red
        default:    .accentColor
        }
    }

    /// A gap longer than this breaks the line.
    private var gapThreshold: TimeInterval {
        guard series.count > 2 else { return .greatestFiniteMagnitude }
        let deltas = zip(series, series.dropFirst()).map {
            $1.timestamp.timeIntervalSince($0.timestamp)
        }
        let median = deltas.sorted()[deltas.count / 2]
        return max(median * 3, 60)
    }

    /// Splits the series wherever a sampling gap occurs so Charts draws
    /// separate lines rather than bridging the hole.
    private var segments: [[SeriesPoint]] {
        var result: [[SeriesPoint]] = []
        var current: [SeriesPoint] = []
        for point in series {
            if let last = current.last,
               point.timestamp.timeIntervalSince(last.timestamp) > gapThreshold {
                result.append(current)
                current = []
            }
            current.append(point)
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    var body: some View {
        if series.count < 2 {
            Text("Collecting history")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Chart {
                ForEach(Array(segments.enumerated()), id: \.offset) { index, segment in
                    ForEach(segment, id: \.timestamp) { point in
                        LineMark(x: .value("Time", point.timestamp),
                                 y: .value("Price", point.price),
                                 series: .value("Segment", index))
                        .interpolationMethod(.monotone)
                        .foregroundStyle(lineColor)
                        .lineStyle(StrokeStyle(lineWidth: 1.8))
                    }
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXAxis(showsAxes ? .automatic : .hidden)
            .chartYAxis(showsAxes ? .automatic : .hidden)
        }
    }
}
```

- [ ] **Step 7: Implement the small and medium views**

`P2PWidget/Views/SmallRateView.swift`:

```swift
import SwiftUI
import P2PKit

struct SmallRateView: View {
    let entry: RateEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("USDT → LKR")
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
            RateHeader(entry: entry, showsCaption: false)
            RateSparkline(series: entry.series,
                          direction: entry.trend?.direction,
                          showsAxes: false)
                .frame(maxHeight: .infinity)
            StatusFooter(entry: entry, compact: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
```

`P2PWidget/Views/MediumRateView.swift`:

```swift
import SwiftUI
import P2PKit

struct MediumRateView: View {
    let entry: RateEntry

    private var low: Double? { entry.series.map(\.price).min() }
    private var high: Double? { entry.series.map(\.price).max() }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                RateHeader(entry: entry, showsCaption: true)
                Spacer()
                Text("P2P · \(entry.window.rawValue)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            RateSparkline(series: entry.series,
                          direction: entry.trend?.direction,
                          showsAxes: false)
                .frame(maxHeight: .infinity)

            HStack(spacing: 10) {
                if let low, let high {
                    Text("low \(Formatting.price(low))")
                    Text("high \(Formatting.price(high))")
                }
                Spacer()
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .monospacedDigit()

            if let name = entry.sample?.advertiserName,
               let available = entry.sample?.advertiserAvailable {
                Text("\(name) · \(Formatting.usdt(available))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            StatusFooter(entry: entry, compact: false)
        }
    }
}
```

- [ ] **Step 8: Route by family**

Replace `P2PWidget/RateWidgetView.swift`:

```swift
import SwiftUI
import WidgetKit

struct RateWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: RateEntry

    var body: some View {
        content.containerBackground(.fill.tertiary, for: .widget)
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .systemSmall:  SmallRateView(entry: entry)
        case .systemLarge:  LargeRateView(entry: entry)
        default:            MediumRateView(entry: entry)
        }
    }
}
```

- [ ] **Step 9: Build after Task 15**

`StatusFooter` and `LargeRateView` arrive in Task 15, so the target does not
compile yet. Proceed to Task 15, then build.

---

### Task 15: Large view, status states, and previews

**Files:**
- Create: `P2PWidget/Views/LargeRateView.swift`
- Create: `P2PWidget/Views/StatusFooter.swift`
- Create: `P2PWidget/Views/WidgetPreviews.swift`

**Interfaces:**
- Consumes: `RateEntry`, `Formatting` (Tasks 13, 14).
- Produces: `StatusFooter`, `LargeRateView`.

- [ ] **Step 1: Implement the status footer**

`P2PWidget/Views/StatusFooter.swift`:

```swift
import SwiftUI
import P2PKit

/// Makes staleness and feed failure visible. A frozen number that still looks
/// live is the failure mode this exists to prevent.
struct StatusFooter: View {
    let entry: RateEntry
    let compact: Bool

    var body: some View {
        switch entry.state {
        case .ok:
            if let sample = entry.sample {
                label("clock", Formatting.relativeAge(sample.timestamp, from: entry.date),
                      .secondary)
            }
        case .stale(let since):
            label("exclamationmark.triangle",
                  compact ? Formatting.relativeAge(since, from: entry.date)
                          : "stale · \(Formatting.relativeAge(since, from: entry.date))",
                  .orange)
        case .noData:
            label("hourglass", compact ? "no data" : "waiting for first sample", .secondary)
        case .error:
            label("bolt.horizontal.circle", compact ? "feed error" : "cannot read store", .red)
        }
    }

    private func label(_ symbol: String, _ text: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Image(systemName: symbol).imageScale(.small)
            Text(text).lineLimit(1)
        }
        .font(.caption2)
        .foregroundStyle(color)
    }
}
```

- [ ] **Step 2: Implement the large view**

`P2PWidget/Views/LargeRateView.swift`:

```swift
import SwiftUI
import P2PKit

struct LargeRateView: View {
    let entry: RateEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                RateHeader(entry: entry, showsCaption: true)
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("P2P · \(entry.window.rawValue)")
                    if let top = entry.sample?.topPrice {
                        // Shown for contrast: the headline is the fillable
                        // price, which is often lower than this.
                        Text("top of book \(Formatting.price(top))")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            RateSparkline(series: entry.series,
                          direction: entry.trend?.direction,
                          showsAxes: true)
                .frame(minHeight: 90)

            Divider()

            if entry.topAds.isEmpty {
                Text("No ad book captured yet")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 3) {
                    ForEach(Array(entry.topAds.enumerated()), id: \.offset) { _, ad in
                        HStack(spacing: 6) {
                            Text(Formatting.price(ad.price))
                                .monospacedDigit()
                                .frame(width: 54, alignment: .leading)
                            Text(Formatting.usdt(ad.availableUSDT))
                                .monospacedDigit()
                                .frame(width: 82, alignment: .leading)
                                .foregroundStyle(.secondary)
                            Text(ad.advertiserName)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Spacer()
                            if ad.canFill(amountUSDT: entry.amountUSDT) {
                                Image(systemName: "checkmark.circle.fill")
                                    .imageScale(.small)
                                    .foregroundStyle(.green)
                            }
                        }
                        .font(.caption2)
                    }
                }
            }

            Spacer(minLength: 0)
            StatusFooter(entry: entry, compact: false)
        }
    }
}
```

- [ ] **Step 3: Add previews covering every state**

`P2PWidget/Views/WidgetPreviews.swift`:

```swift
import SwiftUI
import WidgetKit
import P2PKit

private func previewSample(_ price: Double?, at date: Date) -> Sample {
    Sample(timestamp: date, side: .sell, amountUSDT: 500, fillablePrice: price,
           topPrice: 332.00, medianTop10: 330.765, advertiserName: "TD_TrustPay_LK",
           advertiserAvailable: 700, advertiserMinFiat: 10_000, advertiserMaxFiat: 220_000)
}

private func previewSeries(rising: Bool) -> [SeriesPoint] {
    (0..<48).map { index in
        let drift = rising ? Double(index) * 0.02 : -Double(index) * 0.02
        let wobble = sin(Double(index) / 4) * 0.15
        return SeriesPoint(timestamp: Date(timeIntervalSince1970: 1_000 + Double(index) * 300),
                           price: 330.6 + drift + wobble)
    }
}

private func previewAds() -> [Ad] {
    [
        ("332.00", 1510.0, 499_999.0, 500_000.0, "jeewani 98"),
        ("331.00", 700.0, 10_000.0, 220_000.0, "TD_TrustPay_LK"),
        ("330.95", 660.1, 4_000.0, 50_000.0, "Supuni_Tharanga"),
        ("330.85", 22.65, 1_000.0, 7_000.0, "SHPK_Crypto"),
        ("330.68", 1700.0, 300_000.0, 562_156.0, "DIGIT_FAST_BLACKROCK"),
    ].map { price, stock, low, high, who in
        Ad(price: Double(price)!, availableUSDT: stock, minFiat: low, maxFiat: high,
           payTimeLimitMinutes: 15, advertiserName: who, monthOrderCount: 300,
           monthFinishRate: 0.99, positiveRate: 1.0)
    }
}

private func previewEntry(state: RateEntry.State = .ok, rising: Bool = true) -> RateEntry {
    let now = Date(timeIntervalSince1970: 1_000 + 48 * 300)
    let series = previewSeries(rising: rising)
    return RateEntry(date: now, side: .sell, amountUSDT: 500, window: .hour24,
                     sample: previewSample(331.00, at: now), series: series,
                     trend: MetricsEngine.trend(series: series), state: state,
                     topAds: previewAds())
}

#Preview("Small · rising", as: .systemSmall) {
    RateWidget()
} timeline: {
    previewEntry()
}

#Preview("Medium · falling", as: .systemMedium) {
    RateWidget()
} timeline: {
    previewEntry(rising: false)
}

#Preview("Medium · stale", as: .systemMedium) {
    RateWidget()
} timeline: {
    previewEntry(state: .stale(since: Date(timeIntervalSince1970: 1_000)))
}

#Preview("Large · full book", as: .systemLarge) {
    RateWidget()
} timeline: {
    previewEntry()
}

#Preview("Small · no data", as: .systemSmall) {
    RateWidget()
} timeline: {
    RateEntry.placeholder()
}
```

- [ ] **Step 4: Build**

Run: `make build`
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: Run the whole suite**

Run: `make test`
Expected: PASS, 71 tests.

- [ ] **Step 6: Verify signing**

Run: `make sign-check`
Expected: `** BUILD SUCCEEDED **`, group identifier printed twice.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add small, medium, and large widget views with explicit status states

The sparkline splits its series at sampling gaps so a hole reads as missing
data instead of bridging into a rate that never existed, and a stale sample
is labelled rather than shown as though it were live."
```

---

### Task 16: Detail window

**Files:**
- Create: `P2PMonitor/Views/DetailWindow.swift`
- Create: `P2PMonitor/Views/AdTable.swift`
- Create: `P2PMonitor/DetailViewModel.swift`
- Modify: `P2PMonitor/AppMain.swift`

**Interfaces:**
- Consumes: `Store`, `Settings`, `MetricsEngine`, `Formatting`, `CollectorService` (Tasks 1–11, 14).
- Produces: `DetailViewModel` (`@MainActor`, `@Observable`) with `reload()`, `refreshNow() async`, `window`, `series`, `trend`, `latest`, `ads`; views `DetailWindow`, `AdTable`.

- [ ] **Step 1: Implement the view model**

`P2PMonitor/DetailViewModel.swift`:

```swift
import Foundation
import Observation
import P2PKit

@MainActor
@Observable
final class DetailViewModel {
    var window: ChartWindow {
        didSet { reload() }
    }
    private(set) var series: [SeriesPoint] = []
    private(set) var trend: Trend?
    private(set) var latest: Sample?
    private(set) var ads: [Ad] = []
    private(set) var capturedAt: Date?
    private(set) var isRefreshing = false

    private let store: Store?
    private let settings: Settings
    private let collector: CollectorService?

    init(store: Store?, settings: Settings, collector: CollectorService?) {
        self.store = store
        self.settings = settings
        self.collector = collector
        self.window = settings.window
        reload()
    }

    func reload() {
        guard let store else { return }
        let side = settings.side
        let amount = settings.amountUSDT
        latest = try? store.latest(side: side, amountUSDT: amount)
        series = (try? store.series(side: side, amountUSDT: amount, window: window)) ?? []
        trend = MetricsEngine.trend(series: series)
        // Flatten the double optional that `try?` produces over an
        // optional-returning throwing call.
        let snapshot = (try? store.snapshot(side: side)).flatMap { $0 }
        ads = snapshot?.ads ?? []
        capturedAt = snapshot?.capturedAt
    }

    func refreshNow() async {
        isRefreshing = true
        await collector?.pollNow()
        isRefreshing = false
        reload()
    }
}
```

- [ ] **Step 2: Implement the ad table**

`P2PMonitor/Views/AdTable.swift`:

```swift
import SwiftUI
import P2PKit

struct AdTable: View {
    let ads: [Ad]
    let amountUSDT: Int

    var body: some View {
        Table(Array(ads.enumerated()), id: \.offset) {
            TableColumn("Price") { row in
                Text(Formatting.price(row.element.price)).monospacedDigit()
            }
            .width(min: 64, ideal: 72)

            TableColumn("Available") { row in
                Text(Formatting.usdt(row.element.availableUSDT)).monospacedDigit()
            }
            .width(min: 90, ideal: 100)

            TableColumn("Limit (LKR)") { row in
                Text("\(Formatting.fiat(row.element.minFiat)) – \(Formatting.fiat(row.element.maxFiat))")
                    .monospacedDigit()
            }
            .width(min: 140, ideal: 170)

            TableColumn("Advertiser") { row in
                Text(row.element.advertiserName).lineLimit(1)
            }

            TableColumn("Orders") { row in
                Text("\(row.element.monthOrderCount)").monospacedDigit()
            }
            .width(min: 56, ideal: 64)

            // The point of the whole app: which ads your size can actually use.
            TableColumn("Fills \(Formatting.usdt(amountUSDT))") { row in
                if row.element.canFill(amountUSDT: amountUSDT) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                } else {
                    Image(systemName: "minus").foregroundStyle(.tertiary)
                }
            }
            .width(min: 96, ideal: 110)
        }
    }
}
```

- [ ] **Step 3: Implement the detail window**

`P2PMonitor/Views/DetailWindow.swift`:

```swift
import SwiftUI
import Charts
import P2PKit

struct DetailWindow: View {
    @State private var model: DetailViewModel

    init(model: DetailViewModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            chart.frame(minHeight: 180)
            Divider()
            AdTable(ads: model.ads, amountUSDT: AppEnvironment.shared.settings.amountUSDT)
                .frame(minHeight: 200)
            footer
        }
        .padding(16)
        .frame(minWidth: 720, minHeight: 560)
        .task { model.reload() }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Formatting.price(model.latest?.fillablePrice))
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Text("best fillable · \(Formatting.usdt(AppEnvironment.shared.settings.amountUSDT))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let trend = model.trend {
                let up = trend.direction == .up
                let flat = trend.direction == .flat
                HStack(spacing: 4) {
                    Image(systemName: flat ? "minus" : (up ? "arrow.up.right" : "arrow.down.right"))
                    Text("\(Formatting.signedDelta(trend.delta)) (\(Formatting.percent(trend.percent)))")
                        .monospacedDigit()
                }
                .font(.callout.weight(.medium))
                .foregroundStyle(flat ? .secondary : (up ? .green : .red))
            }

            Spacer()

            Picker("", selection: $model.window) {
                ForEach(ChartWindow.allCases, id: \.self) { window in
                    Text(window.rawValue).tag(window)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 220)
        }
    }

    private var chart: some View {
        Group {
            if model.series.count < 2 {
                ContentUnavailableView(
                    "Collecting history",
                    systemImage: "chart.xyaxis.line",
                    description: Text("Samples arrive every five minutes. The chart fills in as they accumulate."))
            } else {
                Chart(model.series, id: \.timestamp) { point in
                    LineMark(x: .value("Time", point.timestamp),
                             y: .value("Price", point.price))
                    .interpolationMethod(.monotone)
                    AreaMark(x: .value("Time", point.timestamp),
                             y: .value("Price", point.price))
                    .interpolationMethod(.monotone)
                    .foregroundStyle(.linearGradient(
                        colors: [.accentColor.opacity(0.28), .accentColor.opacity(0.02)],
                        startPoint: .top, endPoint: .bottom))
                }
                .chartYScale(domain: .automatic(includesZero: false))
            }
        }
    }

    private var footer: some View {
        HStack(spacing: 12) {
            if let captured = model.capturedAt {
                Label("book \(Formatting.relativeAge(captured))", systemImage: "clock")
            }
            if let top = model.latest?.topPrice {
                Label("top of book \(Formatting.price(top))", systemImage: "arrow.up.to.line")
            }
            Spacer()
            Button {
                Task { await model.refreshNow() }
            } label: {
                if model.isRefreshing {
                    ProgressView().controlSize(.small)
                } else {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
            }
            .disabled(model.isRefreshing)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
```

- [ ] **Step 4: Wire the window into the scene**

In `P2PMonitor/AppMain.swift`, replace the placeholder `Window` body:

```swift
        Window("USDT/LKR Rate", id: "detail") {
            DetailWindow(model: DetailViewModel(
                store: AppEnvironment.shared.store,
                settings: AppEnvironment.shared.settings,
                collector: AppEnvironment.shared.collector))
        }
        .defaultSize(width: 760, height: 600)
```

- [ ] **Step 5: Build**

Run: `make build`
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add detail window with window picker, chart, and ad table

The table marks which ads can actually fill the configured size, making
the gap between top-of-book and tradeable price visible directly."
```

---

### Task 17: Settings window and first-run verification

**Files:**
- Create: `P2PMonitor/Views/SettingsView.swift`
- Create: `P2PMonitor/Views/AlertRulesEditor.swift`
- Modify: `P2PMonitor/AppMain.swift`
- Modify: `README.md`

**Interfaces:**
- Consumes: `Settings`, `Store`, `AlertRule`, `SMAppService` (Tasks 1–11).
- Produces: `SettingsView`, `AlertRulesEditor`.

- [ ] **Step 1: Implement the alert rules editor**

`P2PMonitor/Views/AlertRulesEditor.swift`:

```swift
import SwiftUI
import P2PKit

struct AlertRulesEditor: View {
    let store: Store?
    let settings: Settings

    @State private var rules: [AlertRule] = []
    @State private var threshold: String = ""
    @State private var direction: ThresholdDirection = .above

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if rules.isEmpty {
                Text("No alerts. Add one to be notified when the rate crosses a level.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(rules) { rule in
                    HStack {
                        Image(systemName: rule.direction == .above
                              ? "arrow.up.right.circle" : "arrow.down.right.circle")
                        Text("\(rule.side == .sell ? "Sell" : "Buy") \(Formatting.usdt(rule.amountUSDT)) \(rule.direction == .above ? "above" : "below") \(Formatting.price(rule.threshold))")
                            .font(.callout)
                        Spacer()
                        Button {
                            try? store?.deleteAlert(id: rule.id)
                            load()
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }

            HStack(spacing: 8) {
                Picker("", selection: $direction) {
                    Text("Above").tag(ThresholdDirection.above)
                    Text("Below").tag(ThresholdDirection.below)
                }
                .labelsHidden()
                .frame(width: 110)

                TextField("Threshold", text: $threshold)
                    .frame(width: 100)
                    .monospacedDigit()

                Button("Add") { add() }
                    .disabled(Double(threshold) == nil)
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        rules = (try? store?.alertRules()) ?? []
    }

    private func add() {
        guard let value = Double(threshold), let store else { return }
        let rule = AlertRule(side: settings.side, amountUSDT: settings.amountUSDT,
                             threshold: value, direction: direction)
        // Start armed so the first crossing after creation notifies.
        try? store.upsertAlert(rule, state: .armed, firedAt: nil)
        threshold = ""
        load()
    }
}
```

- [ ] **Step 2: Implement the settings view**

`P2PMonitor/Views/SettingsView.swift`:

```swift
import SwiftUI
import ServiceManagement
import P2PKit

struct SettingsView: View {
    private let settings = AppEnvironment.shared.settings
    private let store = AppEnvironment.shared.store

    @State private var amountText: String = ""
    @State private var side: Side = .sell
    @State private var payment: PaymentMethod = .bankSriLanka
    @State private var launchAtLogin = true

    var body: some View {
        Form {
            Section("Rate") {
                Picker("Side", selection: $side) {
                    Text("Sell USDT").tag(Side.sell)
                    Text("Buy USDT").tag(Side.buy)
                }
                .onChange(of: side) { settings.side = $1 }

                TextField("Trade amount (USDT)", text: $amountText)
                    .monospacedDigit()
                    .onSubmit(commitAmount)

                Text("Only ads whose stock and per-order limits can absorb this amount are considered. Raising it usually lowers the quoted rate.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Picker("Payment", selection: $payment) {
                    ForEach(PaymentMethod.allCases, id: \.self) { method in
                        Text(method.displayName).tag(method)
                    }
                }
                .onChange(of: payment) { settings.payment = $1 }
            }

            Section("Alerts") {
                AlertRulesEditor(store: store, settings: settings)
            }

            Section("General") {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, isOn in
                        settings.launchAtLogin = isOn
                        try? isOn ? SMAppService.mainApp.register()
                                  : SMAppService.mainApp.unregister()
                    }
                Text("The app has no Dock or menu bar icon. It collects a sample every five minutes while running; quitting it stops collection and the widget will show a stale state.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .onAppear {
            amountText = String(settings.amountUSDT)
            side = settings.side
            payment = settings.payment
            launchAtLogin = settings.launchAtLogin
        }
    }

    private func commitAmount() {
        guard let value = Int(amountText), value > 0 else {
            amountText = String(settings.amountUSDT)
            return
        }
        settings.amountUSDT = value
    }
}
```

- [ ] **Step 3: Wire the settings scene**

In `P2PMonitor/AppMain.swift`, replace the placeholder `Settings` scene:

```swift
        Settings { SettingsView() }
```

- [ ] **Step 4: Build, test, and verify signing**

```bash
make build && make test && make sign-check
```

Expected: `** BUILD SUCCEEDED **` twice, 71 tests passing, App Group identifier printed twice.

- [ ] **Step 5: Install and verify end to end**

```bash
APP=$(find ~/Library/Developer/Xcode/DerivedData -name P2PMonitor.app -path '*Debug*' | head -1)
cp -R "$APP" /Applications/
open /Applications/P2PMonitor.app
sleep 90
log show --predicate 'subsystem == "dev.dfanso.p2pmonitor"' --last 2m --style compact
```

Expected: a `poll stored` line with a price near the live rate. Then confirm the
shared database exists and holds rows:

```bash
DB="$HOME/Library/Group Containers/UN798LFFKG.group.dev.dfanso.p2pmonitor/p2p.sqlite"
sqlite3 "$DB" 'SELECT ts, side, amount_usdt, fillable_price, top_price, adv_name FROM samples ORDER BY ts DESC LIMIT 3;'
```

Expected: at least one row, with `fillable_price` at or below `top_price` — that
inequality is the fillability filter doing its job.

**A row is not sufficient evidence.** Confirm the collector is actually running
on a cadence rather than being respawned once per poll:

```bash
/usr/bin/log show --predicate 'subsystem == "dev.dfanso.p2pmonitor"' --info --last 15m \
  | grep 'poll stored'
```

Every line must carry the **same PID**, and consecutive timestamps must be
about 300 seconds apart. Differing PIDs with short irregular gaps means the app
is exiting after each poll and launchd is restarting it.

Finally add the widget: right-click the desktop, choose Edit Widgets, search for
"USDT/LKR Rate", and place the medium size. Confirm it shows a price rather than
a dash, then right-click it, choose Edit Widget, and change the trade amount to
2000 to confirm the quoted rate moves.

- [ ] **Step 6: Update the README status**

Replace the status line in `README.md`:

```markdown
Status: **working.** Collector and widget shipped; see
[the design spec](docs/superpowers/specs/2026-09-07-p2p-lkr-widget-design.md)
and [the implementation plan](docs/superpowers/plans/2026-09-07-p2p-lkr-widget.md).
```

- [ ] **Step 7: Commit and push**

```bash
git add -A
git commit -m "Add settings window with alert rules and amount configuration

Settings explains that raising the trade amount usually lowers the quoted
rate, which is the one non-obvious consequence of the fillability metric."
git push
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: the endpoint and inverted
`tradeType` semantics to Tasks 2–3; the fillable metric with its worked example
to Task 4; the `samples`/`snapshot`/`alerts` schema, retention, and bucketing to
Tasks 6–7; failure handling as gaps and the 15-minute staleness threshold to
Tasks 10 and 15; the containment mitigation for schema drift to Task 2, which
confines the wire format to `WireFormat.swift`; jitter to Task 10; the three
widget families and per-widget AppIntent configuration to Tasks 13–15;
notifications to Task 12; the hidden login item with re-launch opening the
detail window to Task 11; the click-through detail window to Task 16; and the
500 USDT seeded default to Task 9.

**Interface consistency.** `amountUSDT` is `Int` in every signature, column, and
intent parameter. `Trend` is constructed only as `Trend(previous:current:)` and
read through `delta`, `percent`, and `direction` throughout. `Store.series`
takes `(side:amountUSDT:window:now:)` at every call site. `MetricsEngine.trend`
takes a pre-scoped `[SeriesPoint]` in both the widget provider and the detail
view model.

**Two deliberate deviations from the usual per-task commit rhythm**, both
because the app target genuinely does not compile in between: Task 11 defers its
commit into Task 12 (`NotificationPresenter` does not exist yet), and Task 14
defers its build into Task 15 (`StatusFooter` and `LargeRateView` do not exist
yet). Each is called out in the task itself.

**Residual risk, unchanged from the spec.** The endpoint is undocumented. If
Binance alters the schema or adds bot protection, Task 2's decoder is the single
file to repair, and the widget will surface a feed-error state rather than
freezing on a stale number.
