import Foundation

// File-backed URLSession tasks may finish while suspended. On relaunch, the
// server's part list is authoritative; no local callback is required for safety.
final class BackgroundTransfer: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  private let lock = NSLock()
  private var continuations: [Int: CheckedContinuation<Void, Error>] = [:]
  private var completion: (() -> Void)?
  private var storedSession: URLSession?
  static let identifier = "app.genoa.media-transfer"
  var session: URLSession {
    lock.lock()
    defer { lock.unlock() }
    if let storedSession { return storedSession }
    let config = URLSessionConfiguration.background(withIdentifier: Self.identifier)
    config.httpShouldSetCookies = false
    config.sessionSendsLaunchEvents = true
    config.isDiscretionary = false
    config.timeoutIntervalForResource = 24 * 60 * 60
    let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    storedSession = session
    return session
  }
  func upload(request: URLRequest, file: URL) async throws {
    try await withCheckedThrowingContinuation { continuation in
      let task = session.uploadTask(with: request, fromFile: file)
      lock.lock()
      continuations[task.taskIdentifier] = continuation
      lock.unlock()
      task.resume()
    }
  }
  func restore(completion: @escaping () -> Void) {
    lock.lock()
    self.completion = completion
    lock.unlock()
    _ = session
  }
  func cancelAll() async {
    for task in await session.allTasks { task.cancel() }
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    lock.lock()
    let continuation = continuations.removeValue(forKey: task.taskIdentifier)
    lock.unlock()
    if let error {
      continuation?.resume(throwing: error)
    } else {
      do {
        guard let response = task.response else { throw URLError(.badServerResponse) }
        try API.validate(response, data: Data())
        continuation?.resume()
      } catch { continuation?.resume(throwing: error) }
    }
  }
  func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    lock.lock()
    let done = completion
    completion = nil
    lock.unlock()
    DispatchQueue.main.async { done?() }
  }
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    // Authenticated media requests must never forward credentials to another host.
    completionHandler(nil)
  }
}
