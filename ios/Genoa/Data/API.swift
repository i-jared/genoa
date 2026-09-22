import Foundation

struct APIError: LocalizedError {
  let status: Int
  let message: String
  var errorDescription: String? { message }
  var accessDenied: Bool { [401, 403, 404].contains(status) }
}
struct API: Sendable {
  let endpoint: URL
  let token: String?
  let session: URLSession
  init(endpoint: URL, token: String? = nil, session: URLSession? = nil) {
    self.endpoint = endpoint
    self.token = token
    let config = URLSessionConfiguration.ephemeral
    config.httpShouldSetCookies = false
    config.timeoutIntervalForRequest = 90
    self.session = session ?? URLSession(configuration: config)
  }
  static let decoder: JSONDecoder = {
    let d = JSONDecoder()
    d.keyDecodingStrategy = .convertFromSnakeCase
    return d
  }()
  func request(_ path: String, method: String = "GET", body: [String: Any]? = nil) throws
    -> URLRequest
  {
    guard path.hasPrefix("/"), !path.hasPrefix("//"),
      let url = URL(
        string: endpoint.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
          + path), url.host == endpoint.host
    else { throw APIError(status: 0, message: "Invalid server address.") }
    var request = URLRequest(url: url)
    request.httpMethod = method
    request.setValue(endpoint.absoluteString, forHTTPHeaderField: "Origin")
    if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    if method != "GET" && method != "HEAD" {
      request.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:])
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    }
    return request
  }
  func data(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> Data
  {
    let (data, response) = try await session.data(for: request(path, method: method, body: body))
    try Self.validate(response, data: data)
    return data
  }
  func call<T: Decodable>(_ path: String, method: String = "GET", body: [String: Any]? = nil)
    async throws -> T
  {
    try Self.decoder.decode(T.self, from: await data(path, method: method, body: body))
  }
  func mutate(_ path: String, method: String = "POST", body: [String: Any] = [:]) async throws {
    _ = try await data(path, method: method, body: body)
  }
  func put(_ path: String, file: URL) async throws {
    var req = try request(path, method: "PUT")
    req.httpBody = nil
    req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
    let (data, response) = try await session.upload(for: req, fromFile: file)
    try Self.validate(response, data: data)
  }
  static func validate(_ response: URLResponse, data: Data) throws {
    guard let http = response as? HTTPURLResponse else {
      throw APIError(status: 0, message: "Unable to connect. Try again.")
    }
    guard (200...299).contains(http.statusCode) else {
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      throw APIError(
        status: http.statusCode,
        message: json?["error"] as? String ?? json?["message"] as? String
          ?? "Request failed. Try again.")
    }
  }
  func signIn(email: String, password: String, name: String?, invite: String) async throws
    -> Credentials
  {
    var body: [String: Any] = ["email": email, "password": password]
    if let name { body["name"] = name }
    var req = try request(
      name == nil ? "/auth/sign-in/email" : "/auth/sign-up/email", method: "POST", body: body)
    req.setValue(invite, forHTTPHeaderField: "X-Invite-Code")
    let (data, response) = try await session.data(for: req)
    try Self.validate(response, data: data)
    struct Result: Decodable {
      struct User: Decodable { let id: String }
      let user: User
    }
    let result = try Self.decoder.decode(Result.self, from: data)
    guard let http = response as? HTTPURLResponse,
      let token = http.value(forHTTPHeaderField: "set-auth-token"), !token.isEmpty
    else {
      throw APIError(status: 0, message: "The server did not return a session. Sign in again.")
    }
    return Credentials(endpoint: endpoint, userId: result.user.id, token: token)
  }
  // Better Auth's bearer plugin exposes the signed session cookie as set-auth-token.
  // AVFoundation's public cookie option also applies to authorized HLS child requests.
  var mediaCookies: [HTTPCookie] {
    guard let token, let host = endpoint.host else { return [] }
    let secure = endpoint.scheme == "https"
    var properties: [HTTPCookiePropertyKey: Any] = [
      .name: (secure ? "__Secure-" : "") + "better-auth.session_token", .value: token,
      .domain: host, .path: "/", .originURL: endpoint,
    ]
    if secure { properties[.secure] = "TRUE" }
    return HTTPCookie(properties: properties).map { [$0] } ?? []
  }
}
