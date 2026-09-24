import AVFoundation
import SwiftUI
import UniformTypeIdentifiers

struct WatchScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.scenePhase) private var phase
  @Environment(\.dismiss) private var dismiss
  let conversation: Conversation
  @StateObject private var watch = WatchModel()
  @State private var capture: CaptureDestination?
  @State private var details = false
  @State private var note = false
  @State private var compose = false
  @State private var importing = false
  @State private var forward = false
  @State private var actions: Message?
  @State private var bookmarks = false
  @State private var dwell: Task<Void, Never>?
  @State private var image: UIImage?
  @State private var imageError: String?
  @State private var photoReady = false
  @State private var watchedStill = false
  @State private var stillPlaying = false
  @State private var outgoingReaction: String?
  @State private var reactionTask: Task<Void, Never>?
  @State private var autoplayPhoto = false
  var body: some View {
    ZStack {
      Color(hex: 0x17251F).ignoresSafeArea()
      VStack(spacing: 0) {
        HStack {
          IconButton(icon: "back", label: "Back") { dismiss() }
          Button {
            details = true
          } label: {
            VStack(alignment: .leading) {
              // Read the live row so a rename shows without leaving the conversation.
              Text(model.conversations.first { $0.id == conversation.id }?.title ?? conversation.title)
                .font(.headline)
              if let m = watch.current { Text("\(m.authorName) · \(m.viewingLabel)").font(.caption) }
            }.frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
          }.accessibilityHint("Opens conversation details and rename")
          IconButton(icon: "more", label: "Conversation details") { details = true }
        }.foregroundStyle(.white)
        if let error = watch.error {
          HStack {
            Text(error).font(.footnote)
            Button("Retry") { Task { await watch.load() } }
          }.foregroundStyle(.white).padding(8)
        }
        media.frame(maxWidth: .infinity, maxHeight: .infinity)
        if let m = watch.current, !watch.finished {
          HStack {
            if m.kind == "video" || m.kind == "audio" {
              PlaybackControls(playback: model.playback)
            } else {
              IconButton(
                icon: stillPlaying ? "pause" : "play",
                label: stillPlaying ? "Pause" : "Watch message"
              ) { stillPlaying ? stopDwell() : startDwell(m) }
              Text(watchedStill || m.watched == 1 ? "Watched" : "Tap to watch").font(.caption)
            }
            Spacer(minLength: 0)
            Menu {
              ForEach([Float(1), 1.5, 2, 2.5, 3], id: \.self) { speed in
                Button("\(speed.formatted())×") { model.playback.setRate(speed) }
              }
            } label: {
              Text("\(model.playback.rate.formatted())×").frame(minWidth: 48, minHeight: 48)
            }.accessibilityLabel("Playback speed")
            IconButton(icon: "more", label: "Message actions") { actions = m }
          }.foregroundStyle(.white).padding(.horizontal, 12)
          HStack(spacing: 4) {
            ForEach(["❤️", "😂", "🔥", "👏", "😊"], id: \.self) { emoji in
              Button(emoji) { Task { await react(emoji, to: m) } }.frame(
                minWidth: 48, minHeight: 48
              ).accessibilityLabel("React \(emoji)")
            }
            Spacer(minLength: 0)
          }.padding(.horizontal, 12)
        }
        filmstrip
        if watch.finished || watch.current == nil { composer }
        drafts
      }
    }
    .toolbar(.hidden, for: .navigationBar)
    .task {
      guard let api = model.api, let store = model.store else { return }
      watch.configure(conversation: conversation.id, api: api, store: store)
      let target =
        model.pendingTarget?.conversation == conversation.id ? model.pendingTarget?.message : nil
      if let target {
        await watch.load(target: target)
        model.pendingTarget = nil
        if let message = watch.current { select(message) }
      } else if let incoming = await watch.afterRecording(userId: model.person?.id) {
        select(incoming)
      }
      watch.connect()
      model.playback.onEnd = { Task { await advance() } }
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(5))
        if phase == .active && !model.isCapturing {
          await watch.load()
          if watch.selected == nil { model.playback.stop() }
        }
      }
    }
    .onChange(of: watch.selected) { _, value in
      if value == nil {
        model.playback.stop()
        stopDwell()
      }
    }
    .onDisappear {
      watch.disconnect()
      stopDwell()
      model.playback.onEnd = nil
      model.playback.persist()
    }
    .onChange(of: phase) { _, phase in if phase != .active { stopDwell() } }
    .onChange(of: model.pendingTarget?.message) { _, target in
      if let target, model.pendingTarget?.conversation == conversation.id {
        Task {
          await watch.load(target: target)
          if let m = watch.current { select(m) }
          model.pendingTarget = nil
        }
      }
    }
    .sheet(isPresented: $details) { ConversationDetailsScreen(conversation: conversation) }
    .sheet(isPresented: $note) { NoteScreen(conversation: conversation) }
    .sheet(isPresented: $compose) {
      ComposeScreen(conversation: conversation, reply: watch.current?.id)
    }
    .sheet(isPresented: $bookmarks) {
      BookmarkScreen(conversation: conversation) { m in
        Task {
          await watch.load(target: m.id)
          select(m)
          bookmarks = false
        }
      }
    }
    .sheet(item: $actions) {
      MessageActionsScreen(
        message: $0, conversation: conversation,
        reply: { live in
          actions = nil
          openCapture(live: live)
        },
        jump: { id in
          Task {
            await watch.load(target: id)
            if let m = watch.current { select(m) }
          }
        }, changed: { Task { await watch.load() } })
    }
    .fullScreenCover(
      item: $capture,
      onDismiss: {
        Task {
          await model.reloadLocal()
          if let next = await watch.afterRecording(userId: model.person?.id) {
            select(next, autoplay: true)
          }
        }
      }
    ) { RecorderScreen(destination: $0) }
    .fileImporter(
      isPresented: $importing, allowedContentTypes: [.jpeg, .png, .mpeg4Movie, .mpeg4Audio]
    ) { result in
      Task {
        await model.perform {
          try await MediaLibrary.importFile(
            result.get(), conversation: conversation.id, reply: watch.current?.id, model: model)
        }
      }
    }
  }
  @ViewBuilder var media: some View {
    if watch.finished {
      ZStack(alignment: .top) {
        ReplyCameraPreview()
        VStack(spacing: 8) {
          Text("You’re all caught up").font(.system(.title2, design: .serif))
          Button("Replay last message") { if let m = watch.current { select(m, autoplay: true) } }
            .frame(minHeight: 48)
        }.padding(12).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
          .foregroundStyle(.white).padding(12)
      }
    } else if let m = watch.current {
      ZStack(alignment: .topTrailing) {
        if m.kind == "video" || m.kind == "audio" {
          WatchPlayer(playback: model.playback, voice: m.kind == "audio", author: m.authorName)
        } else if m.kind == "photo" {
          if let image {
            Image(uiImage: image).resizable().scaledToFit()
          } else if let imageError {
            VStack {
              Text(imageError)
              Button("Retry photo") { loadImage(m) }
            }.foregroundStyle(.white)
          } else {
            ProgressView("Loading photo…").tint(.white).foregroundStyle(.white).task(id: m.id) {
              loadImage(m)
            }
          }
        } else {
          ScrollView {
            Text(m.text ?? "").font(.title2).padding(24).frame(
              maxWidth: .infinity, alignment: .leading)
          }.foregroundStyle(.white)
        }
        if let reply = m.replyTo {
          Button("Reply to message") {
            Task {
              await watch.load(target: reply)
              if let source = watch.current { select(source) }
            }
          }.font(.caption).padding(8).background(.black.opacity(0.6), in: Capsule())
            .foregroundStyle(.white).padding(8)
        }
        if let outgoingReaction {
          ReactionAcknowledgment(emoji: outgoingReaction).id(outgoingReaction).frame(
            maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing
          ).padding(20)
        }
        ReactionRail(playback: model.playback, message: m, userId: model.person?.id)
          .padding(.top, m.replyTo == nil ? 8 : 48).padding(.trailing, 8).allowsHitTesting(false)
      }
    } else if watch.loading {
      ProgressView("Loading messages…").foregroundStyle(.white).tint(.white)
    } else {
      ContentUnavailableView(
        "Start the conversation", systemImage: "video",
        description: Text("Record a message or send a note.")
      ).foregroundStyle(.white)
    }
  }
  var filmstrip: some View {
    ScrollViewReader { proxy in
      ScrollView(.horizontal) {
        HStack(spacing: 8) {
          if watch.hasOlder {
            Button("Earlier") { Task { await watch.page(older: true) } }.frame(
              minWidth: 60, minHeight: 64)
          }
          ForEach(watch.messages) { m in
            Button {
              select(m, autoplay: true)
            } label: {
              VStack(spacing: 4) {
                FilmstripThumbnail(
                  message: m,
                  active: !["video", "audio"].contains(watch.current?.kind ?? "")
                    || model.playback.ready)
                Text(m.authorName).font(.caption2).lineLimit(1)
                Text(m.viewingLabel).font(.caption2).lineLimit(1)
              }.frame(width: 80, height: 90).padding(4).background(
                m.id == watch.selected ? Genoa.teal.opacity(0.55) : .white.opacity(0.1),
                in: RoundedRectangle(cornerRadius: 12))
            }.id(m.id).accessibilityLabel("\(m.authorName), \(m.kind), \(m.viewingLabel)")
              .accessibilityAddTraits(m.id == watch.selected ? .isSelected : [])
          }
          if watch.hasNewer {
            Button("Later") { Task { await watch.page(older: false) } }.frame(
              minWidth: 60, minHeight: 64)
          }
        }.padding(12)
      }.foregroundStyle(.white).onChange(of: watch.selected) { _, value in
        if let value { proxy.scrollTo(value, anchor: .center) }
      }
    }
  }
  var composer: some View {
    HStack {
      Menu {
        Button("Private video") { openCapture(live: false) }
        Button("Voice message") { openCapture(live: false, kind: "audio") }
        Button("Photo or imported media") { importing = true }
        Button("Text or urgent note") { compose = true }
        Button("Private scratchpad") { note = true }
        Button("Bookmarks") { bookmarks = true }
      } label: {
        GenoaIcon(name: "add").frame(width: 56, height: 56)
      }.accessibilityLabel("Reply options")
      Spacer()
      Button {
        openCapture(live: true)
      } label: {
        GenoaIcon(name: "video", size: 32).frame(width: 72, height: 64).background(
          Genoa.coral, in: Capsule())
      }.accessibilityLabel("Record a live reply").accessibilityIdentifier("watch.record")
      Spacer()
      Color.clear.frame(width: 56, height: 56)
    }.foregroundStyle(.white).padding(.horizontal, 16).padding(.bottom, 8)
  }
  @ViewBuilder var drafts: some View {
    let rows = model.drafts.filter { $0.conversationId == conversation.id && $0.state != .sent }
    if !rows.isEmpty {
      ScrollView(.horizontal) {
        HStack { ForEach(rows) { draft in DraftCard(draft: draft, conversation: conversation) } }
          .padding(12)
      }.frame(maxHeight: 150).background(Genoa.canvas)
    }
  }
  func openCapture(live: Bool, kind: String = "video") {
    stopDwell()
    model.playback.stop()
    watch.finished = true
    capture = CaptureDestination(
      conversation: conversation, kind: kind, live: live, reply: watch.current?.id, immediate: live)
  }
  func select(_ m: Message, autoplay: Bool = false) {
    stopDwell()
    watch.select(m)
    image = nil
    imageError = nil
    photoReady = false
    watchedStill = false
    autoplayPhoto = autoplay
    model.playback.select(m, autoplay: autoplay, resume: true)
    if autoplay && m.kind == "text" { startDwell(m) }
  }
  func startDwell(_ m: Message) {
    guard phase == .active, m.kind != "photo" || photoReady else { return }
    stopDwell()
    stillPlaying = true
    dwell = Task {
      let seconds =
        m.kind == "photo"
        ? 4.0 : min(8, max(3, Double((m.text ?? "").split(separator: " ").count) / 3))
      do {
        try await Task.sleep(for: .seconds(seconds))
        try Task.checkCancellation()
        guard watch.selected == m.id else { return }
        try await model.store?.progress(message: m, position: 0, watched: true)
        watchedStill = true
        stillPlaying = false
        await model.reloadLocal()
        await advance()
      } catch {}
    }
  }
  func stopDwell() {
    dwell?.cancel()
    dwell = nil
    stillPlaying = false
  }
  func advance() async {
    if let next = await watch.advance(userId: model.person?.id) {
      select(next, autoplay: true)
    } else {
      model.playback.pause()
    }
  }
  func loadImage(_ m: Message) {
    imageError = nil
    Task {
      do {
        guard let path = m.mediaPath, let api = model.api else { return }
        let decoded = try await model.thumbnails.photo(path: path, api: api)
        guard watch.selected == m.id else { return }
        guard let decoded else {
          throw APIError(status: 0, message: "This photo could not be decoded.")
        }
        image = decoded
        photoReady = true
        if autoplayPhoto { startDwell(m) }
      } catch { imageError = error.localizedDescription }
    }
  }
  func react(_ emoji: String, to m: Message) async {
    await model.perform {
      try await model.api?.mutate(
        "/api/messages/\(m.id)/reaction", method: "PUT",
        body: [
          "id": UUID().uuidString.lowercased(), "emoji": emoji,
          "position_ms": Int64(model.playback.position * 1000),
        ])
      outgoingReaction = emoji
      reactionTask?.cancel()
      reactionTask = Task {
        do {
          try await Task.sleep(for: .seconds(2))
          outgoingReaction = nil
        } catch {}
      }
      await watch.load()
    }
  }
}
struct WatchPlayer: View {
  @ObservedObject var playback: PlaybackController
  let voice: Bool
  let author: String
  @State private var frameReady = false
  var body: some View {
    ZStack {
      if voice {
        VStack(spacing: 20) {
          Avatar(name: author, size: 100)
          Text("Voice message").font(.title2)
        }.foregroundStyle(.white)
      } else {
        GeometryReader { geometry in
          let angle = playback.message?.assetId == nil ? (playback.message?.liveRotation ?? 0) : 0
          let sideways = angle == 90 || angle == 270
          PlayerSurface(player: playback.player, onFrame: { frameReady = $0 }).id(
            playback.message?.id
          )
          .frame(
            width: sideways ? geometry.size.height : geometry.size.width,
            height: sideways ? geometry.size.width : geometry.size.height
          )
          .rotationEffect(.degrees(Double(angle)))
          .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
          .opacity(frameReady ? 1 : 0)
        }.clipped()
      }
      if let error = playback.error {
        VStack {
          Text(error)
          Button("Retry") { if let m = playback.message { playback.select(m) } }
        }.padding().foregroundStyle(.white)
      } else if !playback.ready || (!voice && !frameReady) {
        ProgressView("Loading message…").tint(.white).foregroundStyle(.white)
      }
    }
  }
}
struct PlaybackControls: View {
  @ObservedObject var playback: PlaybackController
  @State private var scrubbing = false
  @State private var seekPosition: Double = 0
  var body: some View {
    HStack(spacing: 4) {
      IconButton(
        icon: playback.playing ? "pause" : "play", label: playback.playing ? "Pause" : "Play"
      ) { playback.playing ? playback.pause() : playback.play() }
      VStack {
        Slider(
          value: Binding(
            get: { scrubbing ? seekPosition : min(playback.duration, playback.position) },
            set: { seekPosition = $0 }),
          in: 0...max(0.01, playback.duration),
          onEditingChanged: { active in
            scrubbing = active
            if active { seekPosition = playback.position } else { playback.seek(seekPosition) }
          }
        ).accessibilityLabel("Playback position")
        Text("\(durationLabel(playback.position)) / \(durationLabel(playback.duration))").font(
          .caption.monospacedDigit())
      }
    }
  }
}
