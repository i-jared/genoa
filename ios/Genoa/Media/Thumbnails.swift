import AVFoundation
import ImageIO
import SwiftUI

actor ThumbnailStore {
  private let cache = NSCache<NSString, UIImage>()
  init() { cache.totalCostLimit = 4 * 1024 * 1024 }
  func image(message: Message, api: API) async throws -> UIImage? {
    guard let path = message.mediaPath, message.assetId != nil else { return nil }
    let key = (api.endpoint.absoluteString + ":" + message.id) as NSString
    if let image = cache.object(forKey: key) { return image }
    let image: UIImage
    if message.kind == "photo" {
      guard let decoded = try await photo(path: path, api: api, maximumSize: 320) else {
        return nil
      }
      image = decoded
    } else if message.kind == "video", let url = try api.request(path).url {
      let asset = AVURLAsset(url: url, options: [AVURLAssetHTTPCookiesKey: api.mediaCookies])
      let generator = AVAssetImageGenerator(asset: asset)
      generator.maximumSize = CGSize(width: 320, height: 320)
      generator.appliesPreferredTrackTransform = true
      let result = try await generator.image(at: .zero)
      image = UIImage(cgImage: result.image)
    } else {
      return nil
    }
    cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * 4))
    return image
  }
  func photo(path: String, api: API, maximumSize: Int = 2048) async throws -> UIImage? {
    let (file, response) = try await api.session.download(for: api.request(path))
    defer { try? FileManager.default.removeItem(at: file) }
    try API.validate(response, data: Data())
    guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
      let image = CGImageSourceCreateThumbnailAtIndex(
        source, 0,
        [
          kCGImageSourceCreateThumbnailFromImageAlways: true,
          kCGImageSourceThumbnailMaxPixelSize: maximumSize,
          kCGImageSourceCreateThumbnailWithTransform: true,
        ] as CFDictionary)
    else { return nil }
    return UIImage(cgImage: image)
  }
  func clear() { cache.removeAllObjects() }
}
struct FilmstripThumbnail: View {
  @EnvironmentObject var model: AppModel
  let message: Message
  let active: Bool
  @State private var image: UIImage?
  var body: some View {
    ZStack {
      if let image {
        Image(uiImage: image).resizable().scaledToFill()
      } else {
        GenoaIcon(
          name: message.kind == "video"
            ? "video"
            : message.kind == "photo" ? "media" : message.kind == "audio" ? "play" : "more")
      }
    }.frame(width: 72, height: 42).clipped().clipShape(RoundedRectangle(cornerRadius: 6))
      .task(id: active) {
        guard active, let api = model.api else { return }
        image = try? await model.thumbnails.image(message: message, api: api)
      }
  }
}
