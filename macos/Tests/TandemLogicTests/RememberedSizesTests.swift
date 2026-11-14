import Foundation
import Testing
import TandemLogic

@Suite struct RememberedSizesTests {
    @Test func aNewSizeWinsOverTheKeptOne() {
        var sizes = RememberedSizes(["a": .init(width: 1920, height: 1080)])
        let changed = sizes.heard("a", width: 2560, height: 1440)
        #expect(changed)
        #expect(sizes.sizes["a"] == .init(width: 2560, height: 1440))
    }

    @Test func theSameSizeAgainChangesNothing() {
        var sizes = RememberedSizes()
        let first = sizes.heard("a", width: 1920, height: 1080)
        let again = sizes.heard("a", width: 1920, height: 1080)
        let empty = sizes.heard("b", width: 0, height: 1080)
        #expect(first && !again && !empty)
        #expect(sizes.sizes["b"] == nil)
    }

    @Test func aDeviceThatLeftTheCircleIsForgotten() {
        var sizes = RememberedSizes(["a": .init(width: 1, height: 1), "b": .init(width: 2, height: 2)])
        let first = sizes.keep(only: ["a", "c"])
        #expect(first)
        #expect(sizes.sizes.keys.sorted() == ["a"])
        let again = sizes.keep(only: ["a"])
        #expect(!again)
    }

    @Test func anEmptyCircleForgetsNothing() {
        var sizes = RememberedSizes(["a": .init(width: 1, height: 1)])
        let changed = sizes.keep(only: [])
        #expect(!changed)
        #expect(sizes.sizes.count == 1)
    }

    @Test func itSurvivesARestart() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sizes-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        var sizes = RememberedSizes()
        sizes.heard("a", width: 1728, height: 1117)
        sizes.save(to: url)
        #expect(RememberedSizes.load(from: url) == sizes)
        #expect(RememberedSizes.load(from: url.appendingPathExtension("missing")) == RememberedSizes())
    }
}
