import Foundation
import Testing
import TandemLogic

@Suite struct HotspotRulesTests {
    private let known = HotspotProfile(ssid: "Pixel", password: "secret")

    @Test func aKnownHotspotSaysNothing() {
        #expect(HotspotRules.judge(reported: "Pixel", stored: known, told: nil) == .fine)
    }

    @Test func aPhoneThisMacNeverSawIsNew() {
        #expect(HotspotRules.judge(reported: "Galaxy", stored: nil, told: nil) == .tell(.new))
        #expect(HotspotRules.judge(reported: "Galaxy", stored: HotspotProfile(), told: nil) == .tell(.new))
    }

    @Test func aRenamedHotspotIsSaid() {
        #expect(HotspotRules.judge(reported: "Pixel van Mark", stored: known, told: nil) == .tell(.renamed))
    }

    @Test func aNameWithoutAPasswordAsksForIt() {
        let nameOnly = HotspotProfile(ssid: "Pixel", password: "")
        #expect(HotspotRules.judge(reported: "Pixel", stored: nameOnly, told: nil) == .tell(.passwordMissing))
    }

    @Test func eachNameIsToldOnceNotEveryTimeThePhoneSaysIt() {
        #expect(HotspotRules.judge(reported: "Galaxy", stored: nil, told: "Galaxy") == .fine)
        // Another name is news again.
        #expect(HotspotRules.judge(reported: "Galaxy 2", stored: nil, told: "Galaxy") == .tell(.new))
    }

    @Test func aFailureWithAKnownHotspotIsToldOnceToo() {
        #expect(HotspotRules.judgeFailure(stored: known, told: nil) == .tell(.couldNotJoin))
        // The marker is the name this Mac has. Saving new credentials clears it, so the next failure is news again.
        #expect(HotspotRules.judgeFailure(stored: known, told: HotspotRules.failureMarker(stored: known)) == .fine)
    }

    @Test func aFailureOfAPhoneNeverSetUpIsTheNewPhoneCase() {
        #expect(HotspotRules.judgeFailure(stored: nil, told: nil) == .tell(.new))
        #expect(HotspotRules.judgeFailure(stored: nil, told: HotspotRules.failureMarker(stored: nil)) == .fine)
    }

    @Test func aNewNameThatWasToldAboutIsNotToldAgainWhenJoiningItFails() {
        // The phone said "Galaxy" and the person was told. Joining then fails because nothing is filled in: not a second word.
        #expect(HotspotRules.judgeFailure(stored: nil, reported: "Galaxy", told: "Galaxy") == .fine)
        // A renamed hotspot, told about, whose old password no longer works.
        #expect(HotspotRules.judgeFailure(stored: known, reported: "Pixel van Mark", told: "Pixel van Mark") == .fine)
        // Told about another name before: this one is news.
        #expect(HotspotRules.judgeFailure(stored: known, reported: "Pixel 2", told: "Pixel") == .tell(.couldNotJoin))
    }

    @Test func aMissingPasswordIsNamedAsSuch() {
        #expect(HotspotRules.judgeFailure(stored: HotspotProfile(ssid: "Pixel", password: ""), told: nil) == .tell(.passwordMissing))
    }

    @Test func aNameIsOnlyRememberedWhenWifiCanHaveIt() {
        #expect(HotspotRules.usableName("Pixel van Mark") == "Pixel van Mark")
        #expect(HotspotRules.usableName("") == nil)
        #expect(HotspotRules.usableName(String(repeating: "x", count: 33)) == nil)
        // 32 bytes, not 32 characters.
        #expect(HotspotRules.usableName(String(repeating: "é", count: 17)) == nil)
    }

    @Test func aPhoneThatCanBeJoinedIsAskedFirstAndTheOthersFollow() {
        let filledIn = HotspotRules.Candidate(id: "filled-in", reachable: false, complete: true)
        let inRange = HotspotRules.Candidate(id: "in-range", reachable: true, complete: false)
        let ready = HotspotRules.Candidate(id: "ready", reachable: true, complete: true)
        // The train: the phone this Mac can join, its beacon not heard, goes before the one in range it has no password for.
        #expect(HotspotRules.choosePhone([inRange, filledIn]) == "filled-in")
        #expect(HotspotRules.choosePhone([inRange, filledIn, ready]) == "ready")
        // When that one fails, the next is asked: a new phone beside an old one out of range still gets its turn.
        #expect(HotspotRules.choosePhone([inRange, filledIn], except: ["filled-in"]) == "in-range")
        #expect(HotspotRules.choosePhone([inRange, filledIn], except: ["filled-in", "in-range"]) == nil)
        #expect(HotspotRules.choosePhone([filledIn]) == "filled-in")
        #expect(HotspotRules.choosePhone([]) == nil)
    }

    @Test func byItselfTheMacLeavesOutAPhoneItCanNeitherJoinNorReach() {
        let away = HotspotRules.Candidate(id: "away", reachable: false, complete: false)
        let inRange = HotspotRules.Candidate(id: "in-range", reachable: true, complete: false)
        let filledIn = HotspotRules.Candidate(id: "filled-in", reachable: false, complete: true)
        // The Sony: not filled in, not heard. Asked for by a person it is still asked; by itself the Mac leaves it be.
        #expect(HotspotRules.choosePhone([away]) == "away")
        #expect(HotspotRules.choosePhone([away], automatic: true) == nil)
        // A new phone in range still gets its setup, and a filled in one is still tried out of range.
        #expect(HotspotRules.choosePhone([away, inRange], automatic: true) == "in-range")
        #expect(HotspotRules.choosePhone([away, filledIn], automatic: true) == "filled-in")
        #expect(HotspotRules.choosePhone([away, filledIn], except: ["filled-in"], automatic: true) == nil)
    }

    @Test func theOldHotspotGoesToThePhoneThatWasAsked() {
        let legacy = HotspotProfile(ssid: "Old", password: "pw")
        let moved = HotspotRules.adopt(legacy: legacy, into: [:], phone: "a")
        #expect(moved == ["a": legacy])
        // A phone that has its own is left alone, and nothing is invented from nothing.
        #expect(HotspotRules.adopt(legacy: legacy, into: ["a": known], phone: "a") == ["a": known])
        #expect(HotspotRules.adopt(legacy: HotspotProfile(), into: [:], phone: "a").isEmpty)
        // Another phone does not take the one of the first.
        #expect(HotspotRules.adopt(legacy: legacy, into: ["a": known], phone: "b")["b"] == legacy)
    }

    @Test func noSavedNetworkInRangeEndsTheWaitAfterAWake() {
        #expect(HotspotRules.settleWait(left: 37, savedNearby: false) == 0)
    }

    @Test func aSavedNetworkInRangeKeepsTheWaitSoMacOSCanJoinIt() {
        #expect(HotspotRules.settleWait(left: 37, savedNearby: true) == 37)
    }

    @Test func aLookThatCannotTellKeepsTheWait() {
        #expect(HotspotRules.settleWait(left: 37, savedNearby: nil) == 37)
    }

    @Test func afterARestartOrAWakeAtHomeTheWaitIsLong() {
        let woke = Date()
        #expect(HotspotRules.settleSeconds(offlineSince: nil, wokeAt: woke) == 40)
        // The network went with the wake itself.
        #expect(HotspotRules.settleSeconds(offlineSince: woke.addingTimeInterval(-10), wokeAt: woke) == 40)
        #expect(HotspotRules.settleSeconds(offlineSince: woke.addingTimeInterval(2), wokeAt: woke) == 40)
    }

    @Test func gone11MinutesBeforeTheLidOpenedTheWaitIsShort() {
        let woke = Date()
        #expect(HotspotRules.settleSeconds(offlineSince: woke.addingTimeInterval(-11 * 60), wokeAt: woke) == 15)
    }

    @Test func aWaitThatIsOverStaysOver() {
        #expect(HotspotRules.settleWait(left: -5, savedNearby: nil) == 0)
        #expect(HotspotRules.settleWait(left: -5, savedNearby: true) == 0)
    }
}
