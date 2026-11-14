import Foundation
import Testing
import TandemLogic

@Suite struct UpdateRulesTests {
    @Test func aMissingOrBrokenKeyIsNoKey() {
        #expect(UpdateRules.publicKey(nil) == nil)
        #expect(UpdateRules.publicKey("") == nil)
        #expect(UpdateRules.publicKey("not base64 !") == nil)
        #expect(UpdateRules.publicKey(Data(repeating: 1, count: 16).base64EncodedString()) == nil)
        #expect(UpdateRules.publicKey("sbMlUsuXyctYz4zszEFtc7Uq6PmGKo+nuqh8+RMc6IU=")?.count == 32)
    }

    @Test func theUnpackedAppMustBeTheVersionOfTheRelease() {
        #expect(UpdateRules.sameVersion(unpacked: "0.3.17", release: "0.3.17"))
        #expect(UpdateRules.sameVersion(unpacked: "0.3.17", release: "v0.3.17"))
        #expect(!UpdateRules.sameVersion(unpacked: "0.3.12", release: "0.3.17"))
        #expect(!UpdateRules.sameVersion(unpacked: nil, release: "0.3.17"))
        #expect(!UpdateRules.sameVersion(unpacked: "", release: "0.3.17"))
    }

    @Test func versionsCompareAsAPersonReadsThem() {
        #expect(VersionComparator.isNewer("1.10.0", than: "1.9.0"))
        #expect(VersionComparator.isNewer("1.0.0", than: "1.0.0-rc1"))
        #expect(!VersionComparator.isNewer("v0.3.17", than: "0.3.17"))
    }
}
