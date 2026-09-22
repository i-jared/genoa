import AVFoundation
import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

@MainActor
enum MediaLibrary {
  static func importFile(_ file: URL, conversation: String, reply: String?, model: AppModel)
    async throws
  {
    guard let store = model.store, let credentials = model.credentials else { return }
    let access = file.startAccessingSecurityScopedResource()
    defer { if access { file.stopAccessingSecurityScopedResource() } }
    let ext = file.pathExtension.lowercased()
    let mapping: [String: (String, String)] = [
      "jpg": ("photo", "image/jpeg"), "jpeg": ("photo", "image/jpeg"),
      "png": ("photo", "image/png"), "mp4": ("video", "video/mp4"), "m4a": ("audio", "audio/mp4"),
    ]
    guard let (kind, mime) = mapping[ext] else {
      throw APIError(status: 0, message: "Choose a JPEG, PNG, H.264/AAC MP4, or M4A file.")
    }
    let bytes = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
    guard bytes > 0 && Int64(bytes) <= 8 * 1024 * 1024 * 1024 else {
      throw APIError(status: 0, message: "Choose a nonempty file up to 8 GiB.")
    }
    var draft = Draft(
      conversationId: conversation, owner: credentials.account, filename: "", kind: kind,
      mime: mime, replyTo: reply)
    draft.filename = "\(draft.id).\(ext)"
    if kind != "photo" {
      let asset = AVURLAsset(url: file)
      guard try await asset.load(.isPlayable) else {
        throw APIError(
          status: 0, message: "This media cannot be played. Choose H.264/AAC MP4 or M4A.")
      }
      let videos = try await asset.loadTracks(withMediaType: .video)
      let audios = try await asset.loadTracks(withMediaType: .audio)
      if kind == "video" && videos.isEmpty || kind == "audio" && audios.isEmpty {
        throw APIError(status: 0, message: "This file does not contain the expected media.")
      }
      for track in videos + audios {
        let descriptions = try await track.load(.formatDescriptions)
        for description in descriptions {
          let codec = CMFormatDescriptionGetMediaSubType(description)
          guard codec == kCMVideoCodecType_H264 || codec == kAudioFormatMPEG4AAC else {
            throw APIError(
              status: 0,
              message: "Choose H.264 video with AAC audio, or an AAC M4A voice recording.")
          }
        }
      }
      let duration = try await asset.load(.duration).seconds
      guard duration.isFinite && duration > 0 && duration <= 86400 else {
        throw APIError(status: 0, message: "Choose media up to 24 hours long.")
      }
      draft.durationMs = Int64(duration * 1000)
    }
    try await store.importFile(file, draft: draft)
    await model.reloadLocal()
  }
  static func save(file: URL, kind: String) async throws {
    if kind == "audio" {
      throw APIError(status: 0, message: "Use Share to save voice recordings to Files.")
    }
    let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
    guard status == .authorized || status == .limited else {
      throw APIError(status: 0, message: "Allow Photos access in Settings, then try again.")
    }
    try await PHPhotoLibrary.shared().performChanges {
      if kind == "photo" {
        PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: file)
      } else {
        PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: file)
      }
    }
  }
  static func download(_ message: Message, api: API) async throws -> URL {
    guard let asset = message.assetId else {
      throw APIError(
        status: 0, message: "The completed replay is still processing. Try again shortly.")
    }
    let (temp, response) = try await api.session.download(for: api.request("/api/media/\(asset)"))
    try API.validate(response, data: Data())
    let ext =
      message.kind == "video"
      ? "mp4" : message.kind == "audio" ? "m4a" : message.mime == "image/png" ? "png" : "jpg"
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(
      "Genoa-\(message.id).\(ext)")
    try? FileManager.default.removeItem(at: file)
    try FileManager.default.moveItem(at: temp, to: file)
    return file
  }
}
struct ShareSheet: UIViewControllerRepresentable {
  let items: [Any]
  func makeUIViewController(context: Context) -> UIActivityViewController {
    UIActivityViewController(activityItems: items, applicationActivities: nil)
  }
  func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
