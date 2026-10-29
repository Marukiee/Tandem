import Foundation

// Runs the WebDAV client of the File Provider extension against a real server of the core. Started by the test
// `the_finder_extension_client_works_against_the_server` in crates/tandem-core/tests/webdav.rs, which sets up two engines,
// a shared folder with `a.txt` (holding "hello drive") and `sub/`, and passes the share in TANDEM_DAV_URL, _USER and _PASSWORD.

let env = ProcessInfo.processInfo.environment
let good = DavCredentials(url: env["TANDEM_DAV_URL"]!, user: env["TANDEM_DAV_USER"]!, password: env["TANDEM_DAV_PASSWORD"]!)
var failures = 0

func expect(_ ok: Bool, _ what: String) {
    print(ok ? "ok   \(what)" : "FAIL \(what)")
    if !ok { failures += 1 }
}

func fails<T>(_ what: String, with expected: DavError, _ body: () async throws -> T) async {
    do {
        _ = try await body()
        expect(false, "\(what): no error")
    } catch {
        expect((error as? DavError) == expected, "\(what): \(error)")
    }
}

let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-dav-check-\(UUID().uuidString)")
try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: scratch) }

let client = WebDAVClient { good }

let top = try await client.list("/")
expect(top.map(\.path) == ["/Docs"] && top[0].isDirectory, "the root lists the shared folder: \(top.map(\.path))")
let docs = try await client.list("/Docs")
let byName = Dictionary(uniqueKeysWithValues: docs.map { ($0.name, $0) })
expect(byName["a.txt"]?.size == 11 && byName["a.txt"]?.isDirectory == false, "a.txt with its size: \(docs)")
expect(byName["a.txt"]?.modified != nil, "a.txt has a date")
expect(byName["sub"]?.isDirectory == true, "sub is a folder")
let stat = try await client.stat("/Docs/a.txt")
expect(stat.path == "/Docs/a.txt" && stat.size == 11, "stat of a file: \(stat)")
let rootStat = try await client.stat("/")
expect(rootStat.isDirectory && rootStat.path == "/", "stat of the root: \(rootStat)")

let fetched = scratch.appendingPathComponent("a.txt")
try await client.download("/Docs/a.txt", to: fetched)
expect((try? String(contentsOf: fetched, encoding: .utf8)) == "hello drive", "download")

// A name with a space and letters outside ASCII, as phones have them.
let odd = "Één foto #1 (kopie).txt"
let source = scratch.appendingPathComponent("upload")
try Data("from the Mac".utf8).write(to: source)
try await client.makeFolder("/Docs/Nieuwe map")
await fails("a folder that is there already", with: .exists) { try await client.makeFolder("/Docs/Nieuwe map") }
try await client.upload(from: source, to: "/Docs/Nieuwe map/\(odd)")
let inside = try await client.list("/Docs/Nieuwe map")
expect(inside.map(\.name) == [odd] && inside[0].size == 12, "upload with an odd name: \(inside.map(\.name))")
try await client.move("/Docs/Nieuwe map/\(odd)", to: "/Docs/sub/renamed.txt")
await fails("the old place after a move", with: .notFound) { try await client.stat("/Docs/Nieuwe map/\(odd)") }
let back = scratch.appendingPathComponent("renamed.txt")
try await client.download("/Docs/sub/renamed.txt", to: back)
expect((try? String(contentsOf: back, encoding: .utf8)) == "from the Mac", "content survives the move")
try await client.upload(from: source, to: "/Docs/a.txt")
await fails("moving onto a file that is there without overwrite", with: .exists) { try await client.move("/Docs/sub/renamed.txt", to: "/Docs/a.txt") }
try await client.move("/Docs/Nieuwe map", to: "/Docs/Moved map")
expect((try await client.stat("/Docs/Moved map")).isDirectory, "a folder moves")
try await client.delete("/Docs/sub")
await fails("a deleted folder", with: .notFound) { try await client.list("/Docs/sub") }
await fails("a file that is not there", with: .notFound) { try await client.download("/Docs/none.txt", to: scratch.appendingPathComponent("none")) }

// The app started serving again with a new password: the first refusal makes the client read the credentials again.
var calls = 0
let rotating = WebDAVClient {
    calls += 1
    return calls == 1 ? DavCredentials(url: good.url, user: good.user, password: "old") : good
}
let again = try await rotating.list("/")
expect(again.count == 1 && calls >= 2, "a new password is picked up after a refusal (\(calls) reads)")
await fails("a password that stays wrong", with: .unauthorized) {
    try await WebDAVClient { DavCredentials(url: good.url, user: good.user, password: "wrong") }.list("/")
}

// No server: the app is not running, or stopped serving because the phone went away.
let port = URL(string: good.url)!.port!
let closed = good.url.replacingOccurrences(of: ":\(port)/", with: ":1/")
await fails("a port nobody listens on", with: .unreachable) {
    try await WebDAVClient { DavCredentials(url: closed, user: good.user, password: good.password) }.list("/")
}
await fails("no credentials at all", with: .unreachable) { try await WebDAVClient { nil }.list("/") }

print(failures == 0 ? "all good" : "\(failures) failed")
exit(failures == 0 ? 0 : 1)
