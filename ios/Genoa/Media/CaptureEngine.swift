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
            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(self, queue: self.queue)
            guard self.session.canAddOutput(output) else {
              throw APIError(status: 0, message: "Video recording is unavailable.")
            }
            self.session.addOutput(output)
            if let connection = output.connection(with: .video),
              connection.isVideoRotationAngleSupported(90)
            {
              connection.videoRotationAngle = 90
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
      } else {
        self.session.addInput(old)
      }
      self.session.commitConfiguration()
    }
  }
  func start(
    file: URL, live: Bool, angle: CGFloat = 90, segment: @escaping (Data, Int, Double) -> Void
  ) async throws {
    try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
      queue.async {
        do {
          guard self.session.isRunning, self.writer == nil else {
            throw APIError(status: 0, message: "Wait for the camera to be ready.")
          }
          let landscape = angle == 0 || angle == 180
          for output in self.session.outputs {
            if let connection = output.connection(with: .video),
              connection.isVideoRotationAngleSupported(angle)
            {
              connection.videoRotationAngle = angle
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
            if self.mode != "audio" {
              let input = AVAssetWriterInput(
                mediaType: .video,
                outputSettings: [
                  AVVideoCodecKey: AVVideoCodecType.h264,
                  AVVideoWidthKey: landscape ? (live ? 1280 : 1920) : (live ? 720 : 1080),
                  AVVideoHeightKey: landscape ? (live ? 720 : 1080) : (live ? 1280 : 1920),
                  AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: 3_000_000, AVVideoMaxKeyFrameIntervalDurationKey: 2,
                    AVVideoAllowFrameReorderingKey: false,
                  ],
                ])
              input.expectsMediaDataInRealTime = true
              writer.add(input)
              self.videoInputs.append(input)
            }
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
struct CameraSurface: UIViewRepresentable {
  let engine: CaptureEngine
  final class Surface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    override func layoutSubviews() {
      super.layoutSubviews()
      let angle = cameraAngle(window?.windowScene?.interfaceOrientation)
      if let c = preview.connection, c.isVideoRotationAngleSupported(angle) {
        c.videoRotationAngle = angle
      }
    }
  }
  func makeUIView(context: Context) -> Surface {
    let v = Surface()
    v.preview.session = engine.session
    v.preview.videoGravity = .resizeAspectFill
    return v
  }
  func updateUIView(_ view: Surface, context: Context) {}
}

func cameraAngle(_ orientation: UIInterfaceOrientation?) -> CGFloat {
  switch orientation {
  case .landscapeRight: return 0
  case .landscapeLeft: return 180
  case .portraitUpsideDown: return 270
  default: return 90
  }
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
