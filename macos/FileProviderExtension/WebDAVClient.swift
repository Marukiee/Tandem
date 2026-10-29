import Foundation

/// The address and password of the WebDAV server that the app runs for one phone (see `FilesInFinder` in the app).
struct DavCredentials: Codable, Equatable {
    var url: String
    var user: String
    var password: String
}

/// A file or folder as the server describes it. `path` is as the phone knows it: `/Docs/a.txt`, the root is `/`.
struct DavEntry: Equatable {
    var path: String
    var isDirectory: Bool
    var size: Int64
    var modified: Date?
    var etag: String?

    var name: String { path == "/" ? "" : String(path.split(separator: "/").last ?? "") }
}

enum DavError: Error, Equatable {
    /// No server answers: the app is not running, the phone is away, or the address is no longer served.
    case unreachable
    case unauthorized
    case notFound
    case exists
    case forbidden
    case insufficientStorage
    case status(Int)
    case badReply
}

/// Talks WebDAV to the core's server on the loopback address. Kept free of FileProvider so it can be run and tested
/// outside the extension.
///
/// The credentials are read again for every request: the app makes a new port and password each time it starts serving,
/// and a client that held on to the old ones would knock on a port that may be someone else's by then.
final class WebDAVClient {
    private let credentials: () -> DavCredentials?
    private let session: URLSession

    init(credentials: @escaping () -> DavCredentials?) {
        self.credentials = credentials
        let config = URLSessionConfiguration.ephemeral
        // The server is on this machine: a request that takes this long means the phone stopped answering, and Finder
        // should hear that instead of waiting.
        config.timeoutIntervalForRequest = 20
        config.urlCache = nil
        config.httpShouldSetCookies = false
        config.connectionProxyDictionary = [:]
        session = URLSession(configuration: config)
    }

    // MARK: Asking

    func stat(_ path: String) async throws -> DavEntry {
        let entries = try await propfind(path, depth: "0")
        guard let entry = entries.first(where: { $0.path == normalized(path) }) ?? entries.first else { throw DavError.badReply }
        return entry
    }

    /// What is in a folder, without the folder itself.
    func list(_ path: String) async throws -> [DavEntry] {
        let folder = normalized(path)
        return try await propfind(folder, depth: "1").filter { $0.path != folder }
    }

    /// Fetches a file into `destination`, which must not exist yet.
    func download(_ path: String, to destination: URL) async throws {
        let (file, response) = try await send(method: "GET", path: path) { request in
            try await self.session.download(for: request)
        }
        try check(response, ok: [200])
        try FileManager.default.moveItem(at: file, to: destination)
    }

    func upload(from file: URL, to path: String) async throws {
        let (_, response) = try await send(method: "PUT", path: path) { request in
            try await self.session.upload(for: request, fromFile: file)
        }
        try check(response, ok: [200, 201, 204])
    }

    func makeFolder(_ path: String) async throws {
        let (_, response) = try await data(method: "MKCOL", path: path)
        // A folder that is already there is answered with "not allowed".
        if response.statusCode == 405 { throw DavError.exists }
        try check(response, ok: [200, 201])
    }

    /// A folder goes with everything in it: the server walks it.
    func delete(_ path: String) async throws {
        let (_, response) = try await data(method: "DELETE", path: path)
        try check(response, ok: [200, 204])
    }

    func move(_ from: String, to: String, overwrite: Bool = false) async throws {
        guard let destination = url(for: to, credentials: credentials()) else { throw DavError.unreachable }
        let (_, response) = try await data(method: "MOVE", path: from, headers: [
            "Destination": destination.absoluteString,
            "Overwrite": overwrite ? "T" : "F",
        ])
        try check(response, ok: [200, 201, 204])
    }

    // MARK: Requests

    private static let propfindBody = Data("""
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:getetag/></D:prop></D:propfind>
        """.utf8)

    private func propfind(_ path: String, depth: String) async throws -> [DavEntry] {
        let (body, response) = try await data(method: "PROPFIND", path: path, headers: ["Depth": depth, "Content-Type": "application/xml"], body: Self.propfindBody)
        try check(response, ok: [207])
        guard let base = credentials().flatMap({ URL(string: $0.url) }) else { throw DavError.unreachable }
        return try MultiStatus.parse(body, basePath: base.path)
    }

    private func data(method: String, path: String, headers: [String: String] = [:], body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
        try await send(method: method, path: path, headers: headers) { request in
            var request = request
            request.httpBody = body
            return try await self.session.data(for: request)
        }
    }

    /// One request, and once more when the password was refused, because the app may have started serving again with a
    /// new one in between.
    private func send<T>(method: String, path: String, headers: [String: String] = [:], _ perform: (URLRequest) async throws -> (T, URLResponse)) async throws -> (T, HTTPURLResponse) {
        for attempt in 0..<2 {
            guard let creds = credentials(), let url = url(for: path, credentials: creds) else { throw DavError.unreachable }
            var request = URLRequest(url: url)
            request.httpMethod = method
            request.setValue("Basic " + Data("\(creds.user):\(creds.password)".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
            for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
            let result: (T, URLResponse)
            do {
                result = try await perform(request)
            } catch let error as URLError {
                if error.code == .cancelled { throw CancellationError() }
                throw DavError.unreachable
            }
            guard let response = result.1 as? HTTPURLResponse else { throw DavError.badReply }
            if response.statusCode == 401 && attempt == 0 { continue }
            return (result.0, response)
        }
        throw DavError.unauthorized
    }

    private func check(_ response: HTTPURLResponse, ok: Set<Int>) throws {
        let code = response.statusCode
        if ok.contains(code) { return }
        switch code {
        case 401: throw DavError.unauthorized
        case 404, 409: throw DavError.notFound
        case 403, 405: throw DavError.forbidden
        case 412: throw DavError.exists
        case 507: throw DavError.insufficientStorage
        case 502, 503, 504: throw DavError.unreachable
        default: throw DavError.status(code)
        }
    }

    // MARK: Paths

    func normalized(_ path: String) -> String {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true)
        return parts.isEmpty ? "/" : "/" + parts.joined(separator: "/")
    }

    private static let unreserved = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")

    func url(for path: String, credentials: DavCredentials?) -> URL? {
        guard let creds = credentials else { return nil }
        var text = creds.url.hasSuffix("/") ? String(creds.url.dropLast()) : creds.url
        for part in path.split(separator: "/", omittingEmptySubsequences: true) {
            text += "/" + (String(part).addingPercentEncoding(withAllowedCharacters: Self.unreserved) ?? String(part))
        }
        if normalized(path) == "/" { text += "/" }
        return URL(string: text)
    }
}

/// The answer to a PROPFIND: one `response` for each item, with what was asked about it.
private final class MultiStatus: NSObject, XMLParserDelegate {
    private let basePath: String
    private var entries: [DavEntry] = []
    private var text = ""
    private var href = ""
    private var isCollection = false
    private var length: Int64 = 0
    private var modified: Date?
    private var etag: String?
    private var propOK = true

    private init(basePath: String) {
        self.basePath = basePath.hasSuffix("/") ? String(basePath.dropLast()) : basePath
    }

    static func parse(_ data: Data, basePath: String) throws -> [DavEntry] {
        let reader = MultiStatus(basePath: basePath)
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.delegate = reader
        guard parser.parse() else { throw DavError.badReply }
        return reader.entries
    }

    private static let httpDate: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter
    }()

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        text = ""
        switch name {
        case "response":
            href = ""; isCollection = false; length = 0; modified = nil; etag = nil
        case "propstat":
            propOK = true
        case "collection":
            isCollection = true
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        text += string
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch name {
        case "href": href = value
        // A property the server does not have comes in a propstat of its own with a 404: its empty value means nothing.
        case "status": if !value.contains(" 200") { propOK = false }
        case "getcontentlength": if let n = Int64(value) { length = n }
        case "getlastmodified": modified = Self.httpDate.date(from: value)
        case "getetag": if !value.isEmpty { etag = value }
        case "response":
            if let path = path(of: href) {
                entries.append(DavEntry(path: path, isDirectory: isCollection, size: isCollection ? 0 : length, modified: modified, etag: etag))
            }
        default: break
        }
        text = ""
    }

    /// `/My%20Phone/Docs/a.txt` (or the whole address) to `/Docs/a.txt`.
    private func path(of href: String) -> String? {
        var raw = href
        if let url = URL(string: href), url.scheme != nil { raw = url.path(percentEncoded: true) }
        guard var decoded = raw.removingPercentEncoding else { return nil }
        guard decoded.hasPrefix(basePath) else { return nil }
        decoded.removeFirst(basePath.count)
        let parts = decoded.split(separator: "/", omittingEmptySubsequences: true)
        return parts.isEmpty ? "/" : "/" + parts.joined(separator: "/")
    }
}
