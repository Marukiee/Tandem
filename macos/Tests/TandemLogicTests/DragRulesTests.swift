import Testing
import TandemLogic

@Suite struct DragRulesTests {
    let promises: Set<String> = ["com.apple.NSFilePromiseItemMetaData", "com.apple.pasteboard.promised-file-url"]

    @Test func filesComeFirst() {
        #expect(DragKind.choose(types: ["public.utf8-plain-text", "public.file-url"], promiseTypes: promises) == .files)
    }

    @Test func aPhotoFromPhotosIsAPromiseNotAPicture() {
        #expect(DragKind.choose(types: ["public.tiff", "com.apple.NSFilePromiseItemMetaData"], promiseTypes: promises) == .promised)
    }

    @Test func aPictureFromABrowserIsNotItsAddress() {
        #expect(DragKind.choose(types: ["public.utf8-plain-text", "public.url", "public.tiff"], promiseTypes: promises) == .picture)
    }

    @Test func textAndLinks() {
        #expect(DragKind.choose(types: ["public.utf8-plain-text", "public.rtf"], promiseTypes: promises) == .text)
        #expect(DragKind.choose(types: ["public.url"], promiseTypes: promises) == .link)
        #expect(DragKind.choose(types: ["com.apple.finder.node"], promiseTypes: promises) == nil)
    }

    @Test func underThePointerOnlyRightAfterTheCrossing() {
        #expect(CarryRules.underPointer(buttonDown: true, sinceCrossing: nil))
        #expect(CarryRules.underPointer(buttonDown: false, sinceCrossing: 1.5))
        #expect(!CarryRules.underPointer(buttonDown: false, sinceCrossing: 8))
        #expect(!CarryRules.underPointer(buttonDown: false, sinceCrossing: nil))
    }

    @Test func pickUpWaitsForTheWholeOffer() {
        #expect(!CarryRules.readyToPickUp(arrived: 2, expected: 5, sinceLast: 0.5))
        #expect(CarryRules.readyToPickUp(arrived: 5, expected: 5, sinceLast: 0))
        #expect(CarryRules.readyToPickUp(arrived: 2, expected: 5, sinceLast: 6))
        #expect(!CarryRules.readyToPickUp(arrived: 1, expected: nil, sinceLast: 0.2))
    }
}
