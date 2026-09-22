import AuthenticationServices
import CryptoKit
import SwiftUI

@MainActor
final class SocialSignIn: NSObject, ObservableObject,
  ASWebAuthenticationPresentationContextProviding
{
  private var session: ASWebAuthenticationSession?
  func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
  }
  func signIn(provider: String, endpoint: URL) async throws -> Credentials {
    let verifier = UUID().uuidString + UUID().uuidString
    let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
      .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
    let api = API(endpoint: endpoint)
    struct Start: Decodable {
      let url: URL
      let id: String
    }
    let start: Start = try await api.call(
      "/auth/mobile/start", method: "POST", body: ["provider": provider, "challenge": challenge])
    guard start.url.scheme == endpoint.scheme, start.url.host == endpoint.host,
      start.url.port == endpoint.port
    else {
      throw APIError(status: 0, message: "The sign-in address was invalid. Try again.")
    }
    let callback: URL = try await withCheckedThrowingContinuation { continuation in
      let session = ASWebAuthenticationSession(url: start.url, callbackURLScheme: "genoa") {
        url, error in
        if let error {
          continuation.resume(throwing: error)
        } else if let url {
          continuation.resume(returning: url)
        } else {
          continuation.resume(throwing: APIError(status: 0, message: "Sign-in was cancelled."))
        }
      }
      session.presentationContextProvider = self
      self.session = session
      if !session.start() {
        continuation.resume(
          throwing: APIError(status: 0, message: "Could not open sign-in. Try again."))
      }
    }
    defer { session = nil }
    let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?.first {
      $0.name == "code"
    }?.value
    guard callback.scheme == "genoa", callback.host == "auth", code == start.id else {
      throw APIError(status: 0, message: "Sign-in was cancelled. Please try again.")
    }
    struct Exchange: Decodable {
      struct User: Decodable { let id: String }
      let user: User
      let token: String
    }
    let result: Exchange = try await api.call(
      "/auth/mobile/exchange", method: "POST", body: ["code": start.id, "verifier": verifier])
    return Credentials(endpoint: endpoint, userId: result.user.id, token: result.token)
  }
}
