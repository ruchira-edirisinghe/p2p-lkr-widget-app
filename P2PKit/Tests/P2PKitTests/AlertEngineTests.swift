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
