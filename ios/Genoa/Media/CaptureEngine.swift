import AVFoundation
import SwiftUI

// Capture and writer state have one serial owner; UI only receives immutable status.
final class CaptureEngine: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate,
  AVCaptureAudioDataOutputSampleBufferDelegate, AVAssetWriterDelegate
{
  let session = AVCaptureSession()
  private let queue = DispatchQueue(label: "genoa.capture")
  @Published private(set) var ready = false
  @Published private(set) var recording = false
  @Published private(set) var elapsed: Double = 0
  @Published var error: String?
  private var videoDevice: AVCaptureDeviceInput?
  private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
  @Published private(set) var camera: AVCaptureDevice?
  private var live = false
  private var writer: AVAssetWriter?
  private var liveWriter: AVAssetWriter?
  private var videoInputs: [AVAssetWriterInput] = []
  private var audioInputs: [AVAssetWriterInput] = []
  private var start: CMTime?
  private var segmentNumber = 0
  private var stopping = false
  private var mode = "video"
  private var lastElapsed: Double = 0
  private var publishedSecond = -1
  private var segmentHandler: ((Data, Int, Double) -> Void)?
  private var completion: ((Result<Double, Error>) -> Void)?

  func prepare(kind: String, microphone: Bool = true) async throws {
    let audio = microphone ? await AVCaptureDevice.requestAccess(for: .audio) : true
    let video = kind == "audio" ? true : await AVCaptureDevice.requestAccess(for: .video)
    guard audio && video else {
      throw APIError(
        status: 0,
        message:
          "Allow microphone\(kind == "audio" ? "" : " and camera") access in Settings to record.")
    }
    if microphone {
      try AVAudioSession.sharedInstance().setCategory(
        .playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetoothHFP])
      try AVAudioSession.sharedInstance().setActive(true)
    }
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      queue.async {
        do {
          self.mode = kind
          self.session.automaticallyConfiguresApplicationAudioSession = microphone
          self.session.beginConfiguration()
          defer { self.session.commitConfiguration() }
          self.session.sessionPreset =
            self.session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .hd1280x720
          if kind != "audio" {
            guard
              let camera = AVCaptureDevice.default(
                .builtInWideAngleCamera, for: .video, position: .front)
            else { throw APIError(status: 0, message: "No camera is available on this device.") }
            let input = try AVCaptureDeviceInput(device: camera)
            guard self.session.canAddInput(input) else {
              throw APIError(status: 0, message: "Camera is unavailable.")
            }
            self.session.addInput(input)
            self.videoDevice = input
            self.rotationCoordinator = AVCaptureDevice.RotationCoordinator(
              device: camera, previewLayer: nil)
            DispatchQueue.main.async { self.camera = camera }
            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(self, queue: self.queue)
            guard self.session.canAddOutput(output) else {
              throw APIError(status: 0, message: "Video recording is unavailable.")
            }
            self.session.addOutput(output)
            if let connection = output.connection(with: .video) {
              connection.automaticallyAdjustsVideoMirroring = false
              if connection.isVideoMirroringSupported { connection.isVideoMirrored = false }
            }
          }
          if microphone {
            guard let microphone = AVCaptureDevice.default(for: .audio) else {
              throw APIError(status: 0, message: "Microphone is unavailable.")
            }
            let input = try AVCaptureDeviceInput(device: microphone)
            guard self.session.canAddInput(input) else {
              throw APIError(status: 0, message: "Microphone is unavailable.")
            }
            self.session.addInput(input)
            let output = AVCaptureAudioDataOutput()
            output.setSampleBufferDelegate(self, queue: self.queue)
            guard self.session.canAddOutput(output) else {
              throw APIError(status: 0, message: "Audio capture is unavailable.")
            }
            self.session.addOutput(output)
          }
          self.queue.async {
            self.session.startRunning()
            DispatchQueue.main.async { self.ready = self.session.isRunning }
            if self.session.isRunning {
              continuation.resume()
            } else {
              continuation.resume(
                throwing: APIError(status: 0, message: "Camera could not start. Try again."))
            }
          }
        } catch { continuation.resume(throwing: error) }
      }
    }
  }
  func flip() {
    queue.async {
      guard self.writer == nil, let old = self.videoDevice else { return }
      let position: AVCaptureDevice.Position = old.device.position == .front ? .back : .front
      guard
        let device = AVCaptureDevice.default(
          .builtInWideAngleCamera, for: .video, position: position),
        let input = try? AVCaptureDeviceInput(device: device)
      else { return }
      self.session.beginConfiguration()
      self.session.removeInput(old)
      if self.session.canAddInput(input) {
        self.session.addInput(input)
        self.videoDevice = input
        self.rotationCoordinator = AVCaptureDevice.RotationCoordinator(
          device: device, previewLayer: nil)
        DispatchQueue.main.async { self.camera = device }
      } else {
        self.session.addInput(old)
      }
      self.session.commitConfiguration()
    }
  }
  func start(
    file: URL, live: Bool, segment: @escaping (Data, Int, Double) -> Void
  ) async throws {
    try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
      queue.async {
        do {
          guard self.session.isRunning, self.writer == nil else {
            throw APIError(status: 0, message: "Wait for the camera to be ready.")
          }
          self.live = live
          // Apply the camera's physical capture angle once, to pixels. No writer transform:
          // MP4 replay and fragmented live video then have identical upright frames.
          let angle = self.rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 0
          for output in self.session.outputs {
            if let connection = output.connection(with: .video),
              connection.isVideoRotationAngleSupported(angle)
            {
              connection.videoRotationAngle = angle
              connection.automaticallyAdjustsVideoMirroring = false
              if connection.isVideoMirroringSupported { connection.isVideoMirrored = false }
            }
          }
          self.writer = try AVAssetWriter(
            outputURL: file, fileType: self.mode == "audio" ? .m4a : .mp4)
          self.writer?.movieFragmentInterval = CMTime(seconds: 2, preferredTimescale: 600)
          self.videoInputs = []
          self.audioInputs = []
          self.start = nil
          self.stopping = false
          self.segmentNumber = 0
          self.lastElapsed = 0
          self.publishedSecond = -1
          if live {
            let writer = AVAssetWriter(contentType: .mpeg4Movie)
            writer.outputFileTypeProfile = .mpeg4AppleHLS
            writer.preferredOutputSegmentInterval = CMTime(seconds: 2, preferredTimescale: 600)
            writer.initialSegmentStartTime = .zero
            writer.delegate = self
            self.liveWriter = writer
            self.segmentHandler = segment
          }
          for writer in [self.writer, self.liveWriter].compactMap({ $0 }) {
            let audio = AVAssetWriterInput(
              mediaType: .audio,
              outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100,
                AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 96000,
              ])
            audio.expectsMediaDataInRealTime = true
            writer.add(audio)
            self.audioInputs.append(audio)
          }
          DispatchQueue.main.async {
            self.recording = true
            self.elapsed = 0
          }
          c.resume()
        } catch {
          self.writer = nil
          self.liveWriter = nil
          c.resume(throwing: error)
        }
      }
    }
  }
  func captureOutput(
    _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    guard let writer, !stopping else { return }
    let time = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
    if start == nil {
      // Starting on video avoids a black first GOP and establishes the shared clock.
      if mode != "audio" && !(output is AVCaptureVideoDataOutput) { return }
      if mode != "audio" {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let size = encodedVideoSize(
          width: CVPixelBufferGetWidth(pixels), height: CVPixelBufferGetHeight(pixels), live: live)
        for w in [writer, liveWriter].compactMap({ $0 }) {
          let input = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
              AVVideoCodecKey: AVVideoCodecType.h264,
              AVVideoWidthKey: size.width, AVVideoHeightKey: size.height,
              AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 3_000_000,
                AVVideoMaxKeyFrameIntervalDurationKey: 2, AVVideoAllowFrameReorderingKey: false,
              ],
            ])
          input.expectsMediaDataInRealTime = true
          guard w.canAdd(input) else {
            DispatchQueue.main.async { self.error = "This camera format could not be recorded." }
            return
          }
          w.add(input)
          videoInputs.append(input)
        }
      }
      start = time
      liveWriter?.initialSegmentStartTime = time
      for w in [writer, liveWriter].compactMap({ $0 }) {
        w.startWriting()
        w.startSession(atSourceTime: time)
      }
    }
    for input in output is AVCaptureVideoDataOutput ? videoInputs : audioInputs
    where input.isReadyForMoreMediaData {
      if !input.append(sampleBuffer) {
        DispatchQueue.main.async {
          self.error = "Recording stopped because media could not be saved."
        }
      }
    }
    let seconds = CMTimeSubtract(time, start ?? time).seconds
    if seconds.isFinite {
      lastElapsed = seconds
      if Int(seconds) != publishedSecond {
        publishedSecond = Int(seconds)
        DispatchQueue.main.async { self.elapsed = seconds }
      }
    }
  }
  func assetWriter(
    _ writer: AVAssetWriter, didOutputSegmentData segmentData: Data,
    segmentType: AVAssetSegmentType, segmentReport: AVAssetSegmentReport?
  ) {
    let data = Data(segmentData)
    let duration =
      segmentReport?.trackReports.map { $0.duration.seconds }.filter { $0.isFinite }.max() ?? 2
    let number = segmentType == .initialization ? -1 : segmentNumber
    if segmentType != .initialization { segmentNumber += 1 }
    segmentHandler?(data, number, max(0.001, duration))
  }
  func stop() async throws -> Double {
    try await withCheckedThrowingContinuation { c in
      queue.async {
        guard let writer = self.writer, self.start != nil else {
          c.resume(throwing: APIError(status: 0, message: "No media was recorded. Try again."))
          return
        }
        self.stopping = true
        for input in self.videoInputs { input.markAsFinished() }
        for input in self.audioInputs { input.markAsFinished() }
        let group = DispatchGroup()
        for w in [writer, self.liveWriter].compactMap({ $0 }) {
          group.enter()
          w.finishWriting { group.leave() }
        }
        group.notify(queue: self.queue) {
          let problem = writer.error ?? self.liveWriter?.error
          self.writer = nil
          self.liveWriter = nil
          DispatchQueue.main.async {
            self.recording = false
            if let problem {
              c.resume(throwing: problem)
            } else {
              c.resume(returning: self.lastElapsed)
            }
          }
        }
      }
    }
  }
  func shutdown() { queue.async { self.session.stopRunning() } }
}
// Match the dimensions of the already-rotated sample, rather than guessing from UI orientation.
func encodedVideoSize(width: Int, height: Int, live: Bool) -> (width: Int, height: Int) {
  let scale = min(1, Double(live ? 1280 : 1920) / Double(max(2, width, height)))
  return (max(2, Int(Double(width) * scale) / 2 * 2), max(2, Int(Double(height) * scale) / 2 * 2))
}
struct CameraSurface: UIViewRepresentable {
  @ObservedObject var engine: CaptureEngine
  final class Surface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    private var coordinator: AVCaptureDevice.RotationCoordinator?
    private var observation: NSKeyValueObservation?
    func configure(camera: AVCaptureDevice?) {
      guard let camera else { return }
      if coordinator?.device != camera {
        coordinator = AVCaptureDevice.RotationCoordinator(device: camera, previewLayer: preview)
        observation = coordinator?.observe(
          \.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]
        ) { [weak self] _, _ in
          DispatchQueue.main.async { self?.orient() }
        }
      }
      orient()
    }
    func orient() {
      guard let coordinator, let connection = preview.connection else { return }
      let angle = coordinator.videoRotationAngleForHorizonLevelPreview
      if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
      connection.automaticallyAdjustsVideoMirroring = false
      if connection.isVideoMirroringSupported {
        connection.isVideoMirrored = coordinator.device?.position == .front
      }
    }
    override func layoutSubviews() {
      super.layoutSubviews()
      orient()
    }
  }
  func makeUIView(context: Context) -> Surface {
    let view = Surface()
    view.preview.session = engine.session
    view.preview.videoGravity = .resizeAspectFill
    view.configure(camera: engine.camera)
    return view
  }
  func updateUIView(_ view: Surface, context: Context) { view.configure(camera: engine.camera) }
}

struct ReplyCameraPreview: View {
  @StateObject private var engine = CaptureEngine()
  var body: some View {
    ZStack {
      CameraSurface(engine: engine)
      if let error = engine.error {
        Text(error).font(.footnote).foregroundStyle(.white).padding().background(
          .black.opacity(0.6))
      }
    }
    .task {
      do { try await engine.prepare(kind: "video", microphone: false) } catch {
        engine.error = error.localizedDescription
      }
    }
    .onDisappear { engine.shutdown() }
  }
}
