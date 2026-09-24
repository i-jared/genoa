import Foundation

struct Person: Codable, Identifiable, Equatable {
  let id: String
  var name: String
  let email: String
  var aiConsent: Int
  var notifications: Int
  var capabilities: Capabilities?
  struct Capabilities: Codable, Equatable {
    var ai: Bool
    var push: Bool?
    var apns: Bool?
    var liveFmp4: Bool?
  }
}
struct Conversation: Codable, Identifiable, Hashable {
  let id: String
  var title: String
  var memberCount: Int
  var unread: Int
  var unwatched: Int
  var favorite: Int
  var muted: Int
  var lastActivity: Int64?
  var preview: String?
  var previewData: String?
  var named: Int?
  var memberNames: [String]?
  /// A named conversation reads as a group even before a third person joins.
  var isGroup: Bool { memberCount > 2 || named == 1 }
  var memberSummary: String {
    let names = memberNames ?? []
    if names.isEmpty { return "Only you so far" }
    let hidden = memberCount - 1 - names.count
    // First names keep the "+N" visible on one line.
    let first = names.map { $0.split(separator: " ").first.map(String.init) ?? $0 }
    return first.joined(separator: ", ") + (hidden > 0 ? " +\(hidden)" : "")
  }
  var previewText: String {
    if let preview, !preview.isEmpty { return preview }
    struct Preview: Decodable {
      let kind: String
      let author: String
    }
    if let data = previewData?.data(using: .utf8),
      let value = try? JSONDecoder().decode(Preview.self, from: data)
    {
      return "\(value.author): \(value.kind.capitalized) message"
    }
    return "Start a conversation"
  }
}
struct Reaction: Codable, Identifiable, Equatable {
  let id: String
  let userId: String
  let userName: String
  let emoji: String
  let positionMs: Int64
  let createdAt: Int64
}
struct Message: Codable, Identifiable, Equatable {
  let id: String
  let seq: Int64
  let conversationId: String
  let authorId: String
  let authorName: String
  let kind: String
  let text: String?
  let state: String
  let assetId: String?
  let mime: String?
  let durationMs: Int64
  let createdAt: Int64
  var positionMs: Int64
  var watched: Int
  var bookmarked: Int
  let replyTo: String?
  let urgent: Int
  let reactions: [Reaction]
  let transcript: String?
  let summary: String?
  let aiStatus: String?
  let liveRotation: Int?
  var mediaPath: String? {
    if let assetId { return "/api/media/\(assetId)" }
    return kind == "video" ? "/api/live/\(id)/playlist.m3u8" : nil
  }
  var viewingLabel: String {
    state == "live"
      ? "Sharing live" : watched == 1 ? "Watched" : positionMs > 0 ? "Partially watched" : "New"
  }
}
struct WatchPage: Codable {
  var messages: [Message]
  let focusId: String?
  let startSeq: Int64
  let endSeq: Int64
  let hasOlder: Bool
  let hasNewer: Bool
}
struct Member: Codable, Identifiable {
  let id: String
  let name: String
  let role: String
}
struct Contact: Codable, Identifiable {
  let id: String
  let name: String
}
struct ConversationDetails: Codable {
  let members: [Member]
  var note: String
}
struct Reminder: Codable, Identifiable {
  let id: String
  let messageId: String
  let conversationId: String
  let title: String
  let dueAt: Int64
  let sent: Int
}
struct IDResponse: Codable { let id: String }
struct OK: Codable { let ok: Bool }
struct InviteResponse: Codable {
  let token: String
  let url: String
}
struct UploadSetup: Codable {
  let id: String
  let chunkSize: Int
  let state: String
}
struct UploadStatus: Codable {
  let state: String
  let parts: [Part]
  struct Part: Codable {
    let number: Int
    let bytes: Int64
  }
}

enum DraftState: String, Codable {
  case recording, saved, queued, uploading, processing, blocked, failed, sent
}
struct Draft: Codable, Identifiable, Equatable {
  var id = UUID().uuidString.lowercased()
  let conversationId: String
  let owner: String
  var filename: String
  var kind: String
  var mime: String
  var durationMs: Int64 = 0
  var replyTo: String?
  var state: DraftState = .saved
  var percent = 0
  var error: String?
  var text: String?
  var urgent = false
  var live = false
  var liveEnded = false
  var createdAt = Date()
  var label: String {
    switch state {
    case .recording: return "Recording interrupted · local file preserved"
    case .saved: return "Saved on this iPhone"
    case .queued: return "Waiting to send · saved on this iPhone"
    case .uploading: return "Uploading · \(percent)%"
    case .processing: return "Processing your message…"
    case .blocked: return "Access changed · recording saved"
    case .failed: return "Upload paused · recording saved"
    case .sent: return "Sent"
    }
  }
  var mayDiscard: Bool { [.saved, .failed, .blocked, .recording, .sent].contains(state) && !live }
}
struct WatchProgress: Codable, Equatable {
  let messageId: String
  let conversationId: String
  var positionMs: Int64
  var watched: Bool
  var revision = UUID().uuidString
  var dirty = true
}
struct LiveSegment: Codable, Identifiable {
  var id: String { "\(messageId):\(number)" }
  let messageId: String
  let number: Int
  let filename: String
  let duration: Double
  var uploaded = false
}
struct Credentials: Codable, Equatable {
  let endpoint: URL
  let userId: String
  let token: String
  var account: String { endpoint.absoluteString + "|" + userId }
}
