import BackgroundTasks
import SwiftUI
import UserNotifications

@main
struct GenoaApp: App {
  @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
  @StateObject private var model = AppModel()
  @Environment(\.scenePhase) private var phase
  var body: some Scene {
    WindowGroup {
      RootView().environmentObject(model)
        .task {
          delegate.model = model
          await model.restore()
          model.setActive(true)
        }
        .onOpenURL { model.receive(url: $0) }
        .onChange(of: phase) { _, phase in model.setActive(phase == .active) }
    }
  }
}
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
  weak var model: AppModel?
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    UNUserNotificationCenter.current().delegate = self
    BGTaskScheduler.shared.register(forTaskWithIdentifier: "app.genoa.sync", using: nil) {
      task in
      let work = Task { @MainActor [weak self] in
        await self?.model?.refresh()
        task.setTaskCompleted(success: !Task.isCancelled)
        Self.scheduleSync()
      }
      task.expirationHandler = { work.cancel() }
    }
    return true
  }
  func application(
    _ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    guard identifier == BackgroundTransfer.identifier, let model else {
      completionHandler()
      return
    }
    model.transfer.restore {
      Task { @MainActor in
        await model.refresh()
        completionHandler()
      }
    }
  }
  static func scheduleSync() {
    let request = BGProcessingTaskRequest(identifier: "app.genoa.sync")
    request.requiresNetworkConnectivity = true
    request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
    try? BGTaskScheduler.shared.submit(request)
  }
  func application(
    _ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
  ) {
    UserDefaults.standard.set(
      deviceToken.map { String(format: "%02x", $0) }.joined(), forKey: "apnsToken")
    Task { @MainActor in await model?.registerDeviceIfNeeded() }
  }
  func application(
    _ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error
  ) {
    Task { @MainActor in model?.notice = "Push registration failed. You can retry in Profile." }
  }
  func userNotificationCenter(
    _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
  ) async {
    let info = response.notification.request.content.userInfo
    guard let conversation = info["conversation_id"] as? String,
      let message = info["message_id"] as? String, UUID(uuidString: conversation) != nil,
      UUID(uuidString: message) != nil
    else { return }
    await MainActor.run {
      UserDefaults.standard.set(conversation, forKey: "notificationConversation")
      UserDefaults.standard.set(message, forKey: "notificationMessage")
      model?.pendingTarget = (conversation, message)
    }
    await model?.refresh()
  }
  func userNotificationCenter(
    _ center: UNUserNotificationCenter, willPresent notification: UNNotification
  ) async -> UNNotificationPresentationOptions {
    await model?.refresh()
    return [.banner, .sound]
  }
}
struct RootView: View {
  @EnvironmentObject var model: AppModel
  @State private var chooseRecipient = false
  @State private var capture: CaptureDestination?
  var body: some View {
    Group {
      if model.credentials == nil {
        AuthScreen()
      } else {
        NavigationStack(path: $model.path) {
          VStack(spacing: 0) {
            if model.tab == 0 { ConversationsScreen() } else { ProfileScreen() }
            if let message = model.playback.message, ["video", "audio"].contains(message.kind) {
              MiniPlayer(playback: model.playback, message: message) {
                if let c = model.conversations.first(where: { $0.id == message.conversationId }) {
                  model.path = [c]
                }
              }
            }
            HStack {
              nav("home", "Home", 0)
              Spacer()
              Button {
                chooseRecipient = true
              } label: {
                VStack(spacing: 2) {
                  GenoaIcon(name: "video", size: 32)
                  Text("Record").font(.caption)
                }.frame(minWidth: 70, minHeight: 56)
              }.accessibilityIdentifier("home.record")
              Spacer()
              nav("profile", "Profile", 1)
            }.padding(.horizontal, 30).padding(.vertical, 4).background(Genoa.surface)
          }.background(Genoa.canvas)
            .navigationDestination(for: Conversation.self) { c in WatchScreen(conversation: c) }
            .toolbar(.hidden, for: .navigationBar)
        }
      }
    }.genoaPage()
      .alert(
        "Genoa",
        isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })
      ) {
        Button("OK") { model.error = nil }
      } message: {
        Text(model.error ?? "")
      }
      .sheet(isPresented: $chooseRecipient) {
        RecipientPicker(title: "Record for…") { c in
          chooseRecipient = false
          capture = CaptureDestination(
            conversation: c, kind: "video", live: true, reply: nil, immediate: false)
        }
      }
      .fullScreenCover(item: $capture) { RecorderScreen(destination: $0) }
  }
  func nav(_ icon: String, _ title: String, _ tab: Int) -> some View {
    Button {
      model.tab = tab
    } label: {
      VStack(spacing: 2) {
        GenoaIcon(name: icon, size: 32)
        Text(title).font(.caption)
      }.frame(minWidth: 70, minHeight: 56).background(
        model.tab == tab ? Genoa.soft : .clear, in: RoundedRectangle(cornerRadius: 16))
    }.foregroundStyle(model.tab == tab ? Genoa.teal : Genoa.muted).accessibilityAddTraits(
      model.tab == tab ? .isSelected : [])
  }
}
struct MiniPlayer: View {
  @ObservedObject var playback: PlaybackController
  let message: Message
  let open: () -> Void
  var body: some View {
    HStack {
      Button(action: open) {
        VStack(alignment: .leading) {
          Text(message.authorName).font(.headline)
          Text(durationLabel(playback.position)).font(.caption)
        }.frame(maxWidth: .infinity, alignment: .leading)
      }
      IconButton(
        icon: playback.playing ? "pause" : "play", label: playback.playing ? "Pause" : "Play"
      ) { playback.playing ? playback.pause() : playback.play() }
      IconButton(icon: "close", label: "Close player") { playback.stop() }
    }.padding(.horizontal, 16).background(Genoa.soft)
  }
}
