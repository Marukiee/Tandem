import Foundation
import Testing
import TandemLogic

@Suite struct OutgoingFilesTests {
    let zip = URL(fileURLWithPath: "/tmp/tandem-x/Folder.zip")

    @Test func twoFilesWithTheSameNameKeepTheirOwnSource() {
        var files = OutgoingFiles()
        files.sent(offer: 1, sources: ["/a/photo.jpg", "/b/photo.jpg"], temporary: [], to: ["p"])
        #expect(files.source(offer: 1, index: 0) == "/a/photo.jpg")
        #expect(files.source(offer: 1, index: 1) == "/b/photo.jpg")
        #expect(files.source(offer: 1, index: 2) == nil)
        #expect(files.source(offer: 2, index: 0) == nil)
    }

    @Test func theZipGoesWhenEveryDeviceHasIt() {
        var files = OutgoingFiles()
        files.sent(offer: 1, sources: ["/f/Folder"], temporary: [zip], to: ["p", "q"])
        let first = files.finished(offer: 1, index: 0, peer: "p", ok: true)
        #expect(first.isEmpty)
        let again = files.finished(offer: 1, index: 0, peer: "p", ok: true)
        #expect(again.isEmpty)
        let last = files.finished(offer: 1, index: 0, peer: "q", ok: true)
        #expect(last == [zip])
        #expect(files.source(offer: 1, index: 0) == "/f/Folder")
    }

    @Test func afterAFailureTheZipStaysForAResume() {
        var files = OutgoingFiles()
        files.sent(offer: 1, sources: ["/f/Folder"], temporary: [zip], to: ["p", "q"])
        let failed = files.finished(offer: 1, index: 0, peer: "p", ok: false)
        let ok = files.finished(offer: 1, index: 0, peer: "q", ok: true)
        #expect(failed.isEmpty && ok.isEmpty)
    }

    @Test func oldOffersAreLetGoOf() {
        var files = OutgoingFiles(kept: 2)
        for offer in UInt64(1)...3 { files.sent(offer: offer, sources: ["/s\(offer)"], temporary: [], to: []) }
        #expect(files.source(offer: 1, index: 0) == nil)
        #expect(files.source(offer: 3, index: 0) == "/s3")
    }
}

@Suite struct OutgoingTransferIDTests {
    @Test func theSourceIsFoundByTheIdOfTheTransfer() {
        var files = OutgoingFiles()
        files.sent(offer: 42, sources: ["/a", "/b"], temporary: [], to: ["p"])
        #expect(files.source(transfer: "42-1-out") == "/b")
        #expect(files.source(transfer: "42-1-in") == nil)
        #expect(files.source(transfer: "nonsense") == nil)
    }
}
