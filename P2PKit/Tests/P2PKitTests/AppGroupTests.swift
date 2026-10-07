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
