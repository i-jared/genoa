import AVKit
import SwiftUI

struct CaptureDestination: Identifiable {
  let id = UUID()
  let conversation: Conversation
  let kind: String
  let live: Bool
  let reply: String?
  let immediate: Bool
}
struct RecorderScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  @Environment(\.scenePhase) private var phase
  let destination: CaptureDestination
  @StateObject private var engine = CaptureEngine()
  @State private var draft: Draft?
  @State private var saving = false
  @State private var starting = false
  @State private var sharing = false
  @State private var review = false
  @State private var discard = false
  @State private var notes = false
  @State private var segmentSink: LiveSegmentSink?
  @State private var liveFailure: String?
  @State private var preview: AVPlayer?
  @State private var queuedNotice = false
  var body: some View {
    ZStack {
      Color(hex: 0x17251F).ignoresSafeArea()
      if destination.kind != "audio" && !review { CameraSurface(engine: engine).ignoresSafeArea() }
      if review, let preview { VideoPlayer(player: preview).ignoresSafeArea() }
      VStack(spacing: 12) {
        HStack {
          IconButton(icon: "close", label: "Keep draft and close") {
            if engine.recording { Task { await finish(close: true) } } else { dismiss() }
          }.disabled(starting || saving)
          VStack {
            Text(destination.conversation.title).font(.headline)
            Text(status).font(.caption.bold()).accessibilityIdentifier("recorder.status")
          }
          Spacer()
          IconButton(icon: "more", label: "Private scratchpad") { notes = true }
        }.padding(.horizontal, 8).background(.black.opacity(0.65))
        if engine.recording {
          Text(durationLabel(engine.elapsed)).font(.title.monospacedDigit()).padding(8).background(
            .black.opacity(0.65), in: Capsule())
        }
        if let problem = liveFailure ?? engine.error {
          VStack {
            Text(problem)
            if !engine.recording {
              Button("Open Settings") {
                UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
              }
            }
          }.font(.callout).padding(12).background(
            .black.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
        }
        Spacer()
        if destination.kind == "audio" && !review {
          Avatar(name: destination.conversation.title, size: 100)
          Text(engine.recording ? "Recording voice" : "Voice message").font(
            .system(.title, design: .serif))
          Spacer()
        }
        if starting || saving {
          ProgressView(starting ? "Starting recording…" : "Saving recording…").tint(.white)
            .padding().background(.black.opacity(0.65), in: Capsule())
        } else if review {
          VStack(spacing: 12) {
            Text("Saved on this iPhone. Only you can see it.").font(.footnote)
            Button("Send") {
              Task {
                guard let draft else { return }
                preview?.pause()
                await model.perform {
                  try await model.outbox?.send(draft.id)
                  await readyForNextRecording()
                }
              }
            }.buttonStyle(PrimaryButton()).accessibilityIdentifier("recorder.send")
            HStack {
              Button("Keep draft") { dismiss() }.frame(maxWidth: .infinity, minHeight: 48)
              Button("Discard", role: .destructive) { discard = true }.frame(
                maxWidth: .infinity, minHeight: 48)
            }
          }.padding().background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 20))
        } else {
          if !engine.recording {
            if queuedNotice { Text("Message queued. Ready for another?").font(.footnote) }
            Text(
              destination.live
                ? "Start sharing live with \(destination.conversation.title). They can watch while you record."
                : "Private recording. Review before sending."
            ).font(.footnote).multilineTextAlignment(.center).padding(8).background(
              .black.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
          }
          HStack {
            IconButton(icon: "flip-camera", label: "Flip camera") { engine.flip() }.disabled(
              engine.recording || destination.kind == "audio")
            Spacer()
            Button {
              Task { engine.recording ? await finish() : await begin() }
            } label: {
              VStack(spacing: 4) {
                GenoaIcon(name: engine.recording ? "check" : "video", size: 32)
                Text(
                  engine.recording
                    ? (destination.live ? "End live sharing" : "Stop")
                    : destination.live ? "Share live" : "Record"
                ).font(.caption)
              }.frame(minWidth: 96, minHeight: 72).background(Genoa.coral, in: Capsule())
            }.disabled(!engine.ready).accessibilityIdentifier("recorder.record")
            Spacer()
            Color.clear.frame(width: 48, height: 48)
          }.padding(12).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 20))
        }
      }.padding(.bottom, 16).foregroundStyle(.white)
    }
    .interactiveDismissDisabled(engine.recording || starting || saving)
    .task {
      model.playback.stop()
      await model.playback.flushProgress()
      model.isCapturing = true
      do {
        try await engine.prepare(kind: destination.kind)
        if destination.immediate { await begin() }
      } catch { engine.error = error.localizedDescription }
    }
    .onDisappear {
      preview?.pause()
      engine.shutdown()
      model.isCapturing = false
      Task { await model.refresh() }
    }
    .onChange(of: phase) { _, p in
      if p == .background && engine.recording { Task { await finish(close: true) } }
    }
    .onChange(of: engine.elapsed) { _, elapsed in
      if elapsed >= 86400 && engine.recording { Task { await finish() } }
    }
    .onChange(of: engine.error) { _, value in
      if value != nil && engine.recording { Task { await finish() } }
    }
    .confirmationDialog("Discard this recording?", isPresented: $discard, titleVisibility: .visible)
    {
      Button("Discard recording", role: .destructive) {
        Task {
          await model.perform {
            if let draft { try await model.store?.remove(draft.id) }
            await model.reloadLocal()
            dismiss()
          }
        }
      }
    }
    .sheet(isPresented: $notes) { NoteScreen(conversation: destination.conversation) }
  }
  var status: String {
    if saving { return "SAVING" }
    if sharing && engine.recording {
      return liveFailure == nil ? "SHARING LIVE" : "LIVE CONNECTION PAUSED"
    }
    if engine.recording { return "RECORDING · PRIVATE" }
    if review { return "READY TO SEND" }
    return destination.live ? "LIVE PREVIEW" : "PRIVATE PREVIEW"
  }
  func begin() async {
    guard !starting, let store = model.store, let credentials = model.credentials,
      let api = model.api
    else { return }
    guard !destination.live || model.person?.capabilities?.liveFmp4 == true else {
      engine.error =
        "This server needs the iOS live update. Use Private video from Reply options until it is available."
      return
    }
    starting = true
    defer { starting = false }
    do {
      var d = Draft(
        conversationId: destination.conversation.id, owner: credentials.account, filename: "",
        kind: destination.kind, mime: destination.kind == "audio" ? "audio/mp4" : "video/mp4",
        replyTo: destination.reply, state: .recording, live: destination.live)
      d.filename = "\(d.id).\(destination.kind == "audio" ? "m4a" : "mp4")"
      try await store.save(d)
      draft = d
      if destination.live {
        var body: [String: Any] = ["id": d.id, "rotation": 0]
        if let reply = destination.reply { body["reply_to"] = reply }
        try await api.mutate("/api/conversations/\(destination.conversation.id)/live", body: body)
        sharing = true
      }
      guard let outbox = model.outbox else { throw CancellationError() }
      let sink = LiveSegmentSink(store: store, outbox: outbox, messageId: d.id) { value in
        await MainActor.run { liveFailure = value }
      }
      segmentSink = sink
      try await engine.start(
        file: store.file(d.filename), live: destination.live
      ) { data, number, duration in
        sink.append(data, number: number, duration: duration)
      }
    } catch {
      engine.error = error.localizedDescription
      if var d = draft {
        d.state = .failed
        d.error = error.localizedDescription
        try? await store.save(d)
      }
      if sharing, let d = draft {
        try? await api.mutate("/api/live/\(d.id)/end")
        sharing = false
      }
    }
  }
  func readyForNextRecording() async {
    preview?.pause()
    preview = nil
    draft = nil
    review = false
    sharing = false
    queuedNotice = true
    do {
      guard let api = model.api, let store = model.store else { return }
      let watch = WatchModel()
      watch.configure(conversation: destination.conversation.id, api: api, store: store)
      if await watch.afterRecording(userId: model.person?.id) != nil { dismiss() }
    } catch {
      // A connection loss never hides the saved draft or forces playback/navigation.
    }
  }
  func finish(close: Bool = false) async {
    guard !saving, let store = model.store, var d = draft else { return }
    saving = true
    defer { saving = false }
    do {
      d.durationMs = Int64(try await engine.stop() * 1000)
      await segmentSink?.drain()
      d.liveEnded = destination.live
      d.state = destination.live ? .queued : .saved
      try await store.save(d)
      draft = d
      if d.kind == "video" && UserDefaults.standard.bool(forKey: "autosave:\(d.owner)") {
        do { try await MediaLibrary.save(file: store.file(d.filename), kind: d.kind) } catch {
          model.notice = "Recording saved in Genoa. Saving to Photos failed; retry from the draft."
        }
      }
      await model.reloadLocal()
      if destination.live {
        await model.outbox?.run()
        await readyForNextRecording()
      } else if close {
        dismiss()
      } else {
        preview = AVPlayer(url: await store.file(d.filename))
        review = true
      }
    } catch {
      engine.error =
        "Couldn’t finish saving: \(error.localizedDescription). The local file is preserved."
      d.state = .failed
      d.error = engine.error
      try? await store.save(d)
      await model.reloadLocal()
    }
  }
}
struct DraftCard: View {
  @EnvironmentObject var model: AppModel
  let draft: Draft
  let conversation: Conversation
  @State private var review = false
  @State private var discard = false
  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Text(draft.kind.capitalized + " draft").font(.headline)
      Text(draft.label).font(.caption)
      if draft.state == .uploading { ProgressView(value: Double(draft.percent), total: 100) }
      if let error = draft.error { Text(error).font(.caption).lineLimit(3) }
      HStack {
        if draft.state == .saved { Button("Review") { review = true }.frame(minHeight: 48) }
        if [.saved, .failed, .blocked, .queued].contains(draft.state) {
          Button(draft.state == .saved ? "Send" : "Retry") {
            Task { await model.perform { try await model.outbox?.send(draft.id) } }
          }.frame(minHeight: 48)
        }
        if draft.mayDiscard {
          Button("Discard", role: .destructive) { discard = true }.frame(minHeight: 48)
        }
      }
    }.padding(12).frame(width: 270).background(
      Genoa.surface, in: RoundedRectangle(cornerRadius: 12)
    ).foregroundStyle(Genoa.ink)
      .sheet(isPresented: $review) { DraftReviewScreen(draft: draft) }
      .confirmationDialog("Discard saved draft?", isPresented: $discard, titleVisibility: .visible)
    {
      Button("Discard", role: .destructive) {
        Task {
          await model.perform {
            try await model.store?.remove(draft.id)
            await model.reloadLocal()
          }
        }
      }
    }
  }
}
struct DraftReviewScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let draft: Draft
  @State private var player: AVPlayer?
  @State private var photo: UIImage?
  @State private var shareFile: URL?
  var body: some View {
    NavigationStack {
      VStack(spacing: 16) {
        if let photo {
          Image(uiImage: photo).resizable().scaledToFit()
        } else if let player {
          VideoPlayer(player: player)
        } else {
          Text(draft.text ?? "Loading draft…")
        }
        Text(draft.label)
        Button("Send") {
          Task {
            await model.perform { try await model.outbox?.send(draft.id) }
            dismiss()
          }
        }.buttonStyle(PrimaryButton())
        Button("Save a copy") {
          Task {
            await model.perform {
              if let store = model.store {
                if draft.kind == "audio" {
                  shareFile = await store.file(draft.filename)
                } else {
                  try await MediaLibrary.save(file: store.file(draft.filename), kind: draft.kind)
                }
              }
            }
          }
        }.frame(minHeight: 48)
      }.padding().navigationTitle("Review").toolbar { Button("Keep draft") { dismiss() } }.task {
        guard let store = model.store else { return }
        let file = await store.file(draft.filename)
        if draft.kind == "photo" {
          photo = UIImage(contentsOfFile: file.path)
        } else if draft.kind != "text" {
          player = AVPlayer(url: file)
        }
      }.onDisappear { player?.pause() }
        .sheet(isPresented: Binding(get: { shareFile != nil }, set: { if !$0 { shareFile = nil } }))
      { if let shareFile { ShareSheet(items: [shareFile]) } }
    }
  }
}
