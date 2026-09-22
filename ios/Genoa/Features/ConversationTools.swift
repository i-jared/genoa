import SwiftUI

struct NoteScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let conversation: Conversation
  @State private var text = ""
  @State private var loaded = false
  @State private var saving = false
  var body: some View {
    NavigationStack {
      VStack(alignment: .leading) {
        Text("Only you can see this note.").font(.footnote).foregroundStyle(Genoa.muted)
        TextEditor(text: $text).accessibilityLabel("Private note")
        Button(saving ? "Saving…" : "Save note") {
          saving = true
          Task {
            await model.perform {
              try await model.api?.mutate(
                "/api/conversations/\(conversation.id)/note", method: "PUT", body: ["text": text])
              dismiss()
            }
            saving = false
          }
        }.buttonStyle(PrimaryButton()).disabled(!loaded || saving || text.count > 20000)
      }.padding().navigationTitle("Private scratchpad").toolbar { Button("Cancel") { dismiss() } }
        .task {
          await model.perform {
            let details: ConversationDetails? = try await model.api?.call(
              "/api/conversations/\(conversation.id)")
            text = details?.note ?? ""
            loaded = true
          }
        }
    }
  }
}
struct ComposeScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let conversation: Conversation
  let reply: String?
  @State private var text = ""
  @State private var urgent = false
  @State private var busy = false
  var body: some View {
    NavigationStack {
      Form {
        Section("To \(conversation.title)") {
          TextEditor(text: $text).frame(minHeight: 140).accessibilityLabel("Message")
          Toggle("Urgent note", isOn: $urgent)
        }
        Text("Urgent notes still respect each person’s notification settings.").font(.footnote)
        Button(busy ? "Saving…" : "Send") {
          busy = true
          Task {
            await model.perform {
              try await model.text(
                text, conversation: conversation.id, reply: reply, urgent: urgent)
              dismiss()
            }
            busy = false
          }
        }.disabled(
          busy || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || text.count > 10000
        )
      }.navigationTitle("Send a note").toolbar { Button("Cancel") { dismiss() } }
    }
  }
}
struct ConversationDetailsScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let conversation: Conversation
  @State private var details: ConversationDetails?
  @State private var title = ""
  @State private var share: String?
  @State private var remove: Member?
  @State private var transfer: Member?
  @State private var leaving = false
  @State private var note = false
  @State private var reminders = false
  var owner: Bool { details?.members.first(where: { $0.id == model.person?.id })?.role == "owner" }
  var current: Conversation {
    model.conversations.first { $0.id == conversation.id } ?? conversation
  }
  var body: some View {
    NavigationStack {
      Form {
        Section("Conversation") {
          if owner {
            TextField("Name", text: $title)
            Button("Rename") { Task { await mutate(["title": title]) } }.disabled(
              title.isEmpty || title.count > 80)
          } else {
            Text(current.title)
          }
          Toggle(
            "Favorite",
            isOn: Binding(
              get: { current.favorite == 1 },
              set: { value in Task { await mutate(["favorite": value]) } }))
          Toggle(
            "Mute notifications",
            isOn: Binding(
              get: { current.muted == 1 }, set: { value in Task { await mutate(["muted": value]) } }
            ))
          if owner {
            Button("Share invitation") {
              Task {
                await model.perform {
                  let result: InviteResponse? = try await model.api?.call(
                    "/api/conversations/\(conversation.id)/invite", method: "POST")
                  share = result?.url
                }
              }
            }
          }
        }
        Section("People") {
          ForEach(details?.members ?? []) { member in
            HStack {
              Avatar(name: member.name, size: 36)
              VStack(alignment: .leading) {
                Text(member.name)
                Text(member.role.capitalized).font(.caption)
              }
              Spacer()
              if owner && member.id != model.person?.id {
                Menu("Manage") {
                  Button("Transfer ownership") { transfer = member }
                  Button("Remove member", role: .destructive) { remove = member }
                }
              }
            }
          }
        }
        Section {
          Button("Private scratchpad") { note = true }
          Button("Reminders") { reminders = true }
        }
        Section {
          Button("Leave conversation", role: .destructive) { leaving = true }
          if owner { Text("Transfer ownership before leaving.").font(.footnote) }
        }
      }.navigationTitle("Conversation details").toolbar { Button("Done") { dismiss() } }.task {
        title = current.title
        await reload()
      }
      .sheet(isPresented: Binding(get: { share != nil }, set: { if !$0 { share = nil } })) {
        if let share { ShareSheet(items: [share]) }
      }
      .sheet(isPresented: $note) { NoteScreen(conversation: conversation) }
      .sheet(isPresented: $reminders) { RemindersScreen(conversation: conversation.id) }
      .confirmationDialog(
        "Remove \(remove?.name ?? "member")? Their access will be revoked.",
        isPresented: Binding(get: { remove != nil }, set: { if !$0 { remove = nil } }),
        titleVisibility: .visible
      ) {
        Button("Remove member", role: .destructive) {
          if let member = remove {
            Task {
              await model.perform {
                try await model.api?.mutate(
                  "/api/conversations/\(conversation.id)/members/\(member.id)", method: "DELETE")
                await reload()
              }
            }
          }
        }
      }
      .confirmationDialog(
        "Transfer ownership to \(transfer?.name ?? "member")?",
        isPresented: Binding(get: { transfer != nil }, set: { if !$0 { transfer = nil } }),
        titleVisibility: .visible
      ) {
        Button("Transfer ownership") {
          if let member = transfer {
            Task {
              await model.perform {
                try await model.api?.mutate(
                  "/api/conversations/\(conversation.id)/owner", body: ["user_id": member.id])
                await reload()
              }
            }
          }
        }
      }
      .confirmationDialog(
        "Leave this conversation and lose access?", isPresented: $leaving, titleVisibility: .visible
      ) {
        Button("Leave", role: .destructive) {
          Task {
            await model.perform {
              guard let id = model.person?.id else { return }
              try await model.api?.mutate(
                "/api/conversations/\(conversation.id)/members/\(id)", method: "DELETE")
              model.playback.stop()
              model.path = []
              await model.refresh()
              dismiss()
            }
          }
        }
      }
    }
  }
  func reload() async {
    await model.perform {
      details = try await model.api?.call("/api/conversations/\(conversation.id)")
    }
  }
  func mutate(_ body: [String: Any]) async {
    await model.perform {
      try await model.api?.mutate(
        "/api/conversations/\(conversation.id)", method: "PATCH", body: body)
      await model.refresh()
    }
  }
}
struct MessageActionsScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let message: Message
  let conversation: Conversation
  let reply: (Bool) -> Void
  let jump: (String) -> Void
  let changed: () -> Void
  @State private var forwarding = false
  @State private var note = false
  @State private var reminders = false
  @State private var date = Date(timeIntervalSinceNow: 3600)
  @State private var deleting = false
  @State private var blocking = false
  @State private var reporting = false
  @State private var reason = ""
  @State private var file: URL?
  @State private var busy = false
  @State private var emoji = ""
  @State private var forwardID = UUID().uuidString.lowercased()
  @State private var reminderID = UUID().uuidString.lowercased()
  var body: some View {
    NavigationStack {
      Form {
        Section("\(message.authorName) · \(message.viewingLabel)") {
          Button("Record a live reply") { reply(true) }
          Button("Record a private reply") { reply(false) }
          if let source = message.replyTo {
            Button("View original message") {
              jump(source)
              dismiss()
            }
          }
          Button(message.bookmarked == 1 ? "Remove bookmark" : "Bookmark") {
            run {
              try await model.api?.mutate(
                "/api/messages/\(message.id)/bookmark", method: "PUT",
                body: ["enabled": message.bookmarked == 0])
              dismiss()
            }
          }
          Button("Forward to conversation") { forwarding = true }.disabled(message.state != "ready")
          if message.kind != "text" {
            Button(busy ? "Preparing file…" : "Save or share a copy") {
              run {
                if let api = model.api { file = try await MediaLibrary.download(message, api: api) }
              }
            }.disabled(busy)
          }
        }
        Section("React") {
          TextField("Emoji", text: $emoji)
          Button("Send reaction") {
            run {
              try await model.api?.mutate(
                "/api/messages/\(message.id)/reaction", method: "PUT",
                body: [
                  "id": UUID().uuidString.lowercased(), "emoji": emoji,
                  "position_ms": Int64(model.playback.position * 1000),
                ])
              emoji = ""
            }
          }.disabled(emoji.isEmpty || emoji.count > 32)
        }
        Section("Remind me") {
          DatePicker(
            "When", selection: $date,
            in: Date(timeIntervalSinceNow: 60)...Date(timeIntervalSinceNow: 365 * 86400))
          Button("Set reminder") {
            run {
              try await model.api?.mutate(
                "/api/messages/\(message.id)/reminder",
                body: ["id": reminderID, "due_at": Int64(date.timeIntervalSince1970 * 1000)])
              model.notice = "Reminder scheduled."
              dismiss()
            }
          }
          Button("Manage reminders") { reminders = true }
        }
        Section { Button("Private scratchpad") { note = true } }
        Section {
          if message.authorId == model.person?.id {
            Button("Delete message", role: .destructive) { deleting = true }
          } else {
            Button("Block \(message.authorName)", role: .destructive) { blocking = true }
            Button("Report message") { reporting = true }
          }
        }
      }.navigationTitle("Message").toolbar { Button("Done") { dismiss() } }
        .sheet(isPresented: $forwarding) {
          RecipientPicker(title: "Forward to…") { destination in
            run {
              try await model.api?.mutate(
                "/api/messages/\(message.id)/forward",
                body: ["id": forwardID, "conversation_id": destination.id])
              forwarding = false
              dismiss()
            }
          }
        }
        .sheet(isPresented: $note) { NoteScreen(conversation: conversation) }
        .sheet(isPresented: $reminders) { RemindersScreen(conversation: conversation.id) }
        .sheet(
          isPresented: Binding(
            get: { file != nil },
            set: {
              if !$0 {
                if let file { try? FileManager.default.removeItem(at: file) }
                file = nil
              }
            })
        ) { if let file { ShareSheet(items: [file]) } }
        .confirmationDialog(
          "Delete this message? Forwarded or exported copies cannot be recalled.",
          isPresented: $deleting, titleVisibility: .visible
        ) {
          Button("Delete message", role: .destructive) {
            run {
              try await model.api?.mutate("/api/messages/\(message.id)", method: "DELETE")
              if model.playback.message?.id == message.id { model.playback.stop() }
              dismiss()
            }
          }
        }
        .confirmationDialog(
          "Block \(message.authorName)? Their messages will be hidden.", isPresented: $blocking,
          titleVisibility: .visible
        ) {
          Button("Block", role: .destructive) {
            run {
              try await model.api?.mutate("/api/blocks", body: ["user_id": message.authorId])
              model.playback.stop()
              dismiss()
            }
          }
        }
        .alert("Report message", isPresented: $reporting) {
          TextField("Reason", text: $reason)
          Button("Cancel", role: .cancel) {}
          Button("Submit report") {
            run {
              try await model.api?.mutate(
                "/api/reports", body: ["message_id": message.id, "reason": reason])
              dismiss()
            }
          }.disabled(reason.isEmpty || reason.count > 2000)
        }
    }
  }
  func run(_ action: @escaping () async throws -> Void) {
    guard !busy else { return }
    busy = true
    Task {
      await model.perform {
        try await action()
        changed()
      }
      busy = false
    }
  }
}
struct BookmarkScreen: View {
  @EnvironmentObject var model: AppModel
  let conversation: Conversation
  let select: (Message) -> Void
  @State private var messages: [Message] = []
  @State private var more = true
  var body: some View {
    NavigationStack {
      List {
        ForEach(messages) { m in
          Button {
            select(m)
          } label: {
            VStack(alignment: .leading) {
              Text(m.authorName)
              Text(m.text?.isEmpty == false ? m.text! : m.kind.capitalized + " message").font(
                .caption)
            }
          }
        }
        if more { Button("Load bookmarks") { Task { await load() } } }
      }.navigationTitle("Bookmarks").task { await load() }
    }
  }
  func load() async {
    await model.perform {
      let query = messages.first.map { "&before=\($0.seq)" } ?? ""
      let rows: [Message] =
        try await model.api?.call(
          "/api/conversations/\(conversation.id)/messages?bookmarked=true\(query)") ?? []
      messages = rows + messages
      more = rows.count == 50
    }
  }
}
struct RemindersScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let conversation: String?
  @State private var reminders: [Reminder] = []
  var body: some View {
    NavigationStack {
      List {
        ForEach(reminders.filter { conversation == nil || $0.conversationId == conversation }) {
          reminder in
          VStack(alignment: .leading) {
            Button(reminder.title) {
              model.pendingTarget = (reminder.conversationId, reminder.messageId)
              Task {
                await model.refresh()
                dismiss()
              }
            }
            Text(Date(timeIntervalSince1970: Double(reminder.dueAt) / 1000), style: .date)
            Text(reminder.sent == 1 ? "Delivered to notification service" : "Scheduled").font(
              .caption)
            Button("Remove reminder", role: .destructive) {
              Task {
                await model.perform {
                  try await model.api?.mutate("/api/reminders/\(reminder.id)", method: "DELETE")
                  await load()
                }
              }
            }
          }
        }
      }.navigationTitle("Reminders").toolbar { Button("Done") { dismiss() } }.task { await load() }
    }
  }
  func load() async {
    await model.perform { reminders = try await model.api?.call("/api/reminders") ?? [] }
  }
}
