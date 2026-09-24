import Foundation
import MetricKit
import UIKit

/// Receives MetricKit crash, hang, CPU, and disk-write diagnostics. iOS delivers these on
/// physical devices, usually at the next launch after the event. Reports wait in memory until
/// an account is active and are then persisted in that account's store for upload.
final class DiagnosticsCollector: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
  private let lock = NSLock()
  private var held: [Data] = []
  private var sink: (@Sendable (Data) async -> Void)?
  func start() { MXMetricManager.shared.add(self) }
  func attach(_ sink: @escaping @Sendable (Data) async -> Void) {
    let waiting = lock.withLock {
      self.sink = sink
      defer { held = [] }
      return held
    }
    Task { for body in waiting { await sink(body) } }
  }
  func detach() { lock.withLock { sink = nil } }
  func didReceive(_ payloads: [MXDiagnosticPayload]) {
    let bodies = payloads.flatMap(DiagnosticReport.bodies(from:))
    let target: (@Sendable (Data) async -> Void)? = lock.withLock {
      if sink == nil { held = Array((held + bodies).suffix(DiagnosticReport.queueLimit)) }
      return sink
    }
    if let target { Task { for body in bodies { await target(body) } } }
  }
}

/// Builds `POST /api/diagnostics` bodies. Pure functions so bounds and redaction are testable.
enum DiagnosticReport {
  static let maxBody = 256 * 1024
  static let queueLimit = 20
  static func bodies(from payload: MXDiagnosticPayload) -> [Data] {
    var items: [(String, MXDiagnostic)] = []
    items += (payload.crashDiagnostics ?? []).map { ("crash", $0) }
    items += (payload.hangDiagnostics ?? []).map { ("hang", $0) }
    items += (payload.cpuExceptionDiagnostics ?? []).map { ("cpu", $0) }
    items += (payload.diskWriteExceptionDiagnostics ?? []).map { ("disk", $0) }
    if #available(iOS 16.0, *) {
      items += (payload.appLaunchDiagnostics ?? []).map { ("other", $0) }
    }
    return items.compactMap { kind, diagnostic in
      guard
        let json = try? JSONSerialization.jsonObject(with: diagnostic.jsonRepresentation())
          as? [String: Any]
      else { return nil }
      return body(
        kind: kind, payload: json, appVersion: diagnostic.applicationVersion,
        build: diagnostic.metaData.applicationBuildVersion, osVersion: diagnostic.metaData.osVersion)
    }
  }
  /// Returns nil when even the call-stack-free report cannot fit the server's body limit.
  static func body(
    kind: String, payload: [String: Any], appVersion: String, build: String, osVersion: String
  ) -> Data? {
    func encode(_ value: Any) -> Data? {
      let body: [String: Any] = [
        "platform": "ios", "kind": kind,
        "app_version": String(appVersion.prefix(32)), "build": String(build.prefix(32)),
        "os_version": String(osVersion.prefix(64)), "payload": value,
      ]
      guard JSONSerialization.isValidJSONObject(body) else { return nil }
      return try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
    }
    let clean = sanitize(payload)
    if let data = encode(clean), data.count <= maxBody { return data }
    // Call-stack trees dominate report size; keep the metadata that identifies the event.
    if let data = encode(omitting("callStackTree", from: clean)), data.count <= maxBody {
      return data
    }
    return nil
  }
  /// Exception messages and arguments can contain user text, URLs, or tokens; drop them and
  /// redact any other string that looks like a URL or credential.
  static func sanitize(_ value: Any) -> Any {
    if let dict = value as? [String: Any] {
      var out: [String: Any] = [:]
      for (key, item) in dict where !["composedMessage", "arguments"].contains(key) {
        out[key] = sanitize(item)
      }
      return out
    }
    if let list = value as? [Any] { return list.map(sanitize) }
    if let text = value as? String {
      let lower = text.lowercased()
      if lower.contains("://") || lower.contains("bearer") || lower.contains("token")
        || lower.contains("cookie")
      {
        return "[redacted]"
      }
      return String(text.prefix(2048))
    }
    return value
  }
  static func omitting(_ name: String, from value: Any) -> Any {
    if let dict = value as? [String: Any] {
      var out: [String: Any] = [:]
      for (key, item) in dict {
        out[key] = key == name ? ["omitted": "size"] : omitting(name, from: item)
      }
      return out
    }
    if let list = value as? [Any] { return list.map { omitting(name, from: $0) } }
    return value
  }
  /// Sends a few stored reports per opportunity; transient failures keep them for later.
  static func flush(store: LocalStore, api: API, limit: Int = 5) async {
    for file in await store.pendingDiagnostics().prefix(limit) {
      guard let body = try? Data(contentsOf: file) else {
        await store.removeDiagnostic(file)
        continue
      }
      do {
        try await api.post("/api/diagnostics", json: body)
      } catch let e as APIError where e.status != 404 && SendFailure(e) == .needsDecision {
        // Rejected as invalid or too large: retrying the same bytes cannot succeed.
      } catch {
        // Includes 404: a server without the diagnostics route yet; keep reports until it has one.
        return
      }
      await store.removeDiagnostic(file)
    }
  }
}
