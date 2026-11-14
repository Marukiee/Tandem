import Testing
import TandemLogic

@Suite struct SafeNameTests {
    @Test func onlyTheLastPartOfASuggestedName() {
        #expect(SafeName.file(suggested: "../../Library/LaunchAgents/x.plist", real: "tmp.plist") == "x.plist")
        #expect(SafeName.file(suggested: "/etc/passwd", real: "a.txt") == "passwd.txt")
        #expect(SafeName.file(suggested: "..\\..\\evil.png", real: "a.png") == "evil.png")
    }

    @Test func nothingUsableFallsBackToTheRealName() {
        #expect(SafeName.file(suggested: "..", real: "/tmp/x/IMG_1.heic") == "IMG_1.heic")
        #expect(SafeName.file(suggested: "", real: "IMG_1.heic") == "IMG_1.heic")
        #expect(SafeName.file(suggested: nil, real: "IMG_1.heic") == "IMG_1.heic")
    }

    @Test func theExtensionOfTheRealFileWhenTheNameHasNone() {
        #expect(SafeName.file(suggested: "Holiday", real: "x.jpeg") == "Holiday.jpeg")
        #expect(SafeName.file(suggested: "Holiday.png", real: "x.jpeg") == "Holiday.png")
    }
}
