import AVFoundation
import MediaPlayer
import SwiftUI

@MainActor
final class PlaybackController: ObservableObject {
  let player = AVPlayer()
  @Published var message: Message?
  @Published var playing = false
  @Published var position: Double = 0
  @Published var duration: Double = 0
  @Published var rate: Float = 1
  @Published var ready = false
  @Published var error: String?
  var onEnd: (() -> Void)?
  private var api: API?
  private var store: LocalStore?
  private var changed: (() async -> Void)?
  private var timer: Any?
  private var status: NSKeyValueObservation?
  private var endObserver: NSObjectProtocol?
  private var interrupted: NSObjectProtocol?
  private var routeObserver: NSObjectProtocol?
  private var lastSaved: Double = -5
  private var currentTask: Task<Void, Never>?
  private var progressTask: Task<Void, Never>?
  init() {
    timer = player.addPeriodicTimeObserver(
      forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main
    ) { [weak self] time in
      Task { @MainActor in
        guard let self else { return }
        self.position = time.seconds.isFinite ? time.seconds : 0
        self.playing = self.player.rate != 0
        if let d = self.player.currentItem?.duration.seconds, d.isFinite { self.duration = d }
        if abs(self.position - self.lastSaved) >= 5 { self.persist() }
        self.nowPlaying()
      }
    }
    endObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main
    ) { [weak self] notification in
      Task { @MainActor in
        guard let self, let item = notification.object as? AVPlayerItem,
          item === self.player.currentItem
        else { return }
        self.position = self.duration
        self.persist(watched: true)
        self.playing = false
        self.onEnd?()
      }
    }
    interrupted = NotificationCenter.default.addObserver(
      forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
    ) { [weak self] _ in Task { @MainActor in self?.pause() } }
    routeObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
    ) { [weak self] n in
      if n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
      {
        Task { @MainActor in self?.pause() }
      }
    }
    let commands = MPRemoteCommandCenter.shared()
    commands.playCommand.addTarget { [weak self] _ in
      Task { @MainActor in self?.play() }
      return .success
    }
    commands.pauseCommand.addTarget { [weak self] _ in
      Task { @MainActor in self?.pause() }
      return .success
    }
    commands.changePlaybackPositionCommand.addTarget { [weak self] event in
      guard let event = event as? MPChangePlaybackPositionCommandEvent else {
        return .commandFailed
      }
      Task { @MainActor in self?.seek(event.positionTime) }
      return .success
    }
  }
  func configure(api: API, store: LocalStore, changed: @escaping () async -> Void) {
    stop()
    self.api = api
    self.store = store
    self.changed = changed
  }
  func select(_ message: Message, autoplay: Bool = false, resume: Bool = false) {
    persist()
    currentTask?.cancel()
    player.pause()
    player.replaceCurrentItem(with: nil)
    self.message = message
    position = 0
    duration = Double(message.durationMs) / 1000
    ready = false
    error = nil
    lastSaved = -5
    guard ["video", "audio"].contains(message.kind), let path = message.mediaPath, let api else {
      return
    }
    currentTask = Task {
      do {
        // Verify current access before giving AVFoundation the private asset.
        var req = try api.request(path)
        req.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        if message.assetId != nil {
          let (_, response) = try await api.session.data(for: req)
          try API.validate(response, data: Data())
        } else {
          _ = try await api.data(path)
        }
        try Task.checkCancellation()
        guard self.message?.id == message.id, let url = try api.request(path).url else { return }
        let asset = AVURLAsset(url: url, options: [AVURLAssetHTTPCookiesKey: api.mediaCookies])
        let item = AVPlayerItem(asset: asset)
        item.audioTimePitchAlgorithm = .timeDomain
        player.replaceCurrentItem(with: item)
        status = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
          Task { @MainActor in
            guard let self, self.player.currentItem === item else { return }
            self.ready = item.status == .readyToPlay
            if item.status == .failed { self.error = "Couldn’t load this message. Try again." }
          }
        }
        if resume && message.positionMs > 0 && message.watched == 0 {
          await player.seek(to: CMTime(value: message.positionMs, timescale: 1000))
        }
        if autoplay { play() }
      } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
  }
  func play() {
    do {
      try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
      try AVAudioSession.sharedInstance().setActive(true)
      if duration > 0 && position >= duration { seek(0) }
      player.playImmediately(atRate: rate)
      playing = true
      nowPlaying()
    } catch { self.error = "Audio is unavailable. Try again." }
  }
  func pause() {
    player.pause()
    playing = false
    persist()
    nowPlaying()
  }
  func setRate(_ value: Float) {
    rate = value
    if playing { player.rate = value }
    nowPlaying()
  }
  func seek(_ value: Double) {
    position = max(0, min(duration, value))
    player.seek(to: CMTime(seconds: position, preferredTimescale: 1000))
    persist()
  }
  func persist(watched: Bool = false) {
    guard let message, ["video", "audio"].contains(message.kind), let store else { return }
    let position = Int64(max(0, position.isFinite ? position * 1000 : 0))
    lastSaved = Double(position) / 1000
    let previous = progressTask
    progressTask = Task { [weak self] in
      await previous?.value
      do {
        try await store.progress(message: message, position: position, watched: watched)
        await self?.changed?()
      } catch { self?.error = "Couldn’t save watch progress. Check available storage." }
    }
  }
  func stop() {
    pause()
    currentTask?.cancel()
    player.replaceCurrentItem(with: nil)
    message = nil
    ready = false
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
  }
  private func nowPlaying() {
    guard let message else { return }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = [
      MPMediaItemPropertyTitle: "Message from \(message.authorName)",
      MPMediaItemPropertyArtist: "Genoa", MPMediaItemPropertyPlaybackDuration: duration,
      MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
      MPNowPlayingInfoPropertyPlaybackRate: player.rate,
    ]
  }
}

struct PlayerSurface: UIViewRepresentable {
  let player: AVPlayer
  var onFrame: (Bool) -> Void = { _ in }
  final class Surface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var observation: NSKeyValueObservation?
    var onFrame: (Bool) -> Void = { _ in }
  }
  func makeUIView(context: Context) -> Surface {
    let view = Surface()
    view.backgroundColor = .black
    view.playerLayer.videoGravity = .resizeAspect
    view.onFrame = onFrame
    view.observation = view.playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) {
      [weak view] layer, _ in
      DispatchQueue.main.async { view?.onFrame(layer.isReadyForDisplay) }
    }
    return view
  }
  func updateUIView(_ view: Surface, context: Context) {
    view.onFrame = onFrame
    view.playerLayer.player = player
  }
}
