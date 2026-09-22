import SwiftUI
import UserNotifications

struct ProfileScreen: View {
  @EnvironmentObject var model: AppModel
  @State private var signout = false
  @State private var deleting = false
  @State private var confirmation = ""
  @State private var export: URL?
  @State private var permission = "Not enabled"
  @State private var autosave = false
  @State private var reminders = false
  var body: some View {
    ScrollView {
      VStack(spacing: 20) {
        Text("Profile").font(.system(.largeTitle, design: .serif)).frame(
          maxWidth: .infinity, alignment: .leading)
        if let person = model.person {
          Avatar(name: person.name, size: 80)
          Text(person.name).font(.title2)
          Text(person.email).font(.subheadline).foregroundStyle(Genoa.muted)
        }
        group("Notifications") {
          Toggle(
            "Message notifications",
            isOn: Binding(
              get: { model.person?.notifications == 1 },
              set: { enabled in Task { await preference(notifications: enabled) } }))
          Text("System permission: \(permission)").font(.footnote).foregroundStyle(Genoa.muted)
          if model.person?.capabilities?.apns != true {
            Text("Push delivery is not available on this server yet.").font(.footnote)
              .foregroundStyle(Genoa.muted)
          }
          Button("Enable notifications") {
            Task {
              await model.perform {
                _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [
                  .alert, .badge, .sound,
                ])
                UIApplication.shared.registerForRemoteNotifications()
                await permissions()
              }
            }
          }.frame(minHeight: 48)
          Button("Open system settings") {
            UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
          }.frame(minHeight: 48)
          Button("Reminders") { reminders = true }.frame(minHeight: 48)
        }
        group("Privacy") {
          Text(
            "Your conversations use server-authorized access. Recordings are not end-to-end encrypted."
          ).font(.subheadline)
        }
        group("Media") {
          Toggle("Save my video recordings", isOn: $autosave).onChange(of: autosave) { _, value in
            if let account = model.credentials?.account {
              UserDefaults.standard.set(value, forKey: "autosave:\(account)")
            }
          }
          Text(
            "Adds a separate copy to Photos. Supported imports: JPEG, PNG, H.264/AAC MP4 and M4A. Up to 8 GiB and 24 hours per message."
          ).font(.footnote).foregroundStyle(Genoa.muted)
        }
        group("Appearance") { Text("Follows your iPhone’s appearance and text size.") }
        group("Account") {
          Button("Export account data") {
            Task {
              await model.perform {
                guard let data = try await model.api?.data("/api/account/export") else { return }
                let file = FileManager.default.temporaryDirectory.appendingPathComponent(
                  "Genoa-account.json")
                try data.write(to: file, options: .atomic)
                export = file
              }
            }
          }.frame(minHeight: 48)
          Button("Delete account", role: .destructive) { deleting = true }.frame(minHeight: 48)
        }
        group("About Genoa") {
          HStack {
            Image("Brand").resizable().scaledToFit().frame(width: 32, height: 32)
            Text("Genoa for iOS · 0.1.0")
          }
          Link("talkgenoa.com", destination: URL(string: "https://talkgenoa.com")!).frame(
            minHeight: 48)
        }
        Button("Sign out") { signout = true }.frame(maxWidth: .infinity, minHeight: 48).overlay(
          RoundedRectangle(cornerRadius: 16).stroke(Genoa.teal)
        ).padding(.top, 8)
      }.padding(16).frame(maxWidth: 650).frame(maxWidth: .infinity)
    }.task {
      autosave = UserDefaults.standard.bool(forKey: "autosave:\(model.credentials?.account ?? "")")
      await permissions()
    }
    .confirmationDialog(
      "Sign out? Saved drafts stay on this iPhone and resume only when you sign back into this account.",
      isPresented: $signout, titleVisibility: .visible
    ) { Button("Sign out") { Task { await model.signOut() } } }
    .alert("Permanently delete your account?", isPresented: $deleting) {
      TextField("Type DELETE", text: $confirmation)
      Button("Cancel", role: .cancel) {}
      Button("Delete account", role: .destructive) {
        Task {
          await model.perform {
            try await model.api?.mutate("/api/account/delete", body: ["confirmation": confirmation])
            await model.outbox?.stop()
            await model.transfer.cancelAll()
            try await model.store?.eraseAccount()
            try SessionVault.clear()
            await model.clearSession()
          }
        }
      }.disabled(confirmation != "DELETE")
    } message: {
      Text(
        "Your messages will be deleted and access revoked. Independent exported and forwarded copies remain."
      )
    }
    .sheet(
      isPresented: Binding(
        get: { export != nil },
        set: {
          if !$0 {
            if let export { try? FileManager.default.removeItem(at: export) }
            export = nil
          }
        })
    ) { if let export { ShareSheet(items: [export]) } }
    .sheet(isPresented: $reminders) { RemindersScreen(conversation: nil) }
  }
  func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(title).font(.headline)
      content()
    }.frame(maxWidth: .infinity, alignment: .leading).padding(12).background(
      Genoa.surface, in: RoundedRectangle(cornerRadius: 16))
  }
  func preference(notifications: Bool) async {
    await model.perform {
      try await model.api?.mutate(
        "/api/me", method: "PATCH",
        body: ["ai_consent": model.person?.aiConsent == 1, "notifications": notifications])
      await model.refresh()
    }
  }
  func permissions() async {
    let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    permission =
      status == .authorized || status == .provisional
      ? "Enabled" : status == .denied ? "Blocked in Settings" : "Not enabled"
  }
}
