import Foundation
import Testing
import TandemLogic

@Suite struct SudoRuleTests {
    @Test func onlyPlainUserNames() {
        #expect(SudoRule.line(user: "mark") != nil)
        #expect(SudoRule.line(user: "") == nil)
        #expect(SudoRule.line(user: "mark' ; rm -rf /") == nil)
        #expect(SudoRule.line(user: "märk") == nil)
    }

    @Test func theScriptFitsInAnAppleScriptStringAndUsesNoFileOfTheUser() throws {
        let script = try #require(SudoRule.script(user: "mark", rule: "/etc/sudoers.d/tandem-pmset", disabled: true))
        #expect(!script.contains("\""))
        #expect(!script.contains("\\"))
        #expect(!script.contains(NSHomeDirectory()))
        #expect(!script.contains(FileManager.default.temporaryDirectory.path))
        #expect(script.contains("/usr/bin/mktemp"))
        #expect(script.hasSuffix("disablesleep 1"))
    }

    @Test func theShellCanReadIt() throws {
        let script = try #require(SudoRule.script(user: "mark", rule: "/etc/sudoers.d/tandem-pmset", disabled: false))
        let shell = Process()
        shell.executableURL = URL(fileURLWithPath: "/bin/sh")
        shell.arguments = ["-n", "-c", script]
        try shell.run()
        shell.waitUntilExit()
        #expect(shell.terminationStatus == 0)
    }
}
