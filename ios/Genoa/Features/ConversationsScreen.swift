import SwiftUI

struct ConversationsScreen: View {
  @EnvironmentObject var model: AppModel
  @State private var search = ""
  @State private var filter = "All"
  @State private var create = false
  @State private var join = false
  @State private var invite = ""
  @State private var renaming: Conversation?
  @State private var newName = ""
  var filtered: [Conversation] {
    model.conversations.filter { c in
      (search.isEmpty || c.title.localizedCaseInsensitiveContains(search))
        && (filter != "Unwatched" || c.unwatched > 0) && (filter != "Groups" || c.isGroup)
    }
  }
  var body: some View {
    VStack(spacing: 12) {
      HStack {
        BrandHeader()
        Spacer()
        Menu {
          Button("New conversation") { create = true }
          Button("Join with invitation") { join = true }
        } label: {
          GenoaIcon(name: "add").frame(width: 48, height: 48)
        }.accessibilityLabel("New conversation or join")
      }.padding(.horizontal, 16)
      HStack {
        GenoaIcon(name: "search")
        TextField("Search conversations", text: $search)
      }.padding(12).background(Genoa.surface, in: RoundedRectangle(cornerRadius: 16)).padding(
        .horizontal, 16)
      Picker("Conversations", selection: $filter) {
        ForEach(["All", "Unwatched", "Groups"], id: \.self) { Text($0) }
      }.pickerStyle(.segmented).padding(.horizontal, 16)
      if let notice = model.notice {
        Text(notice).font(.footnote).foregroundStyle(Genoa.muted).padding(.horizontal, 16)
      }
      if model.pendingInvite != nil {
        Button("Join invited conversation") {
          invite = model.pendingInvite ?? ""
          join = true
        }.buttonStyle(.bordered).frame(minHeight: 48)
      }
      List {
        if filtered.isEmpty {
          ContentUnavailableView(
            !search.isEmpty
              ? "No matches" : filter == "Groups" ? "No groups yet" : "No conversations yet",
            systemImage: "bubble.left.and.bubble.right",
            description: Text(
              !search.isEmpty
                ? "Search checks conversation names."
                : filter == "Groups"
                  ? "Conversations with more than one other person, or that you name, appear here."
                  : "Start a conversation or join with an invitation.")
          ).listRowBackground(Color.clear)
        }
        ForEach(filtered) { c in
          Button {
            model.path.append(c)
          } label: {
            ConversationRow(conversation: c)
          }.contextMenu {
            Button("Rename") {
              newName = c.named == 1 ? c.title : ""
              renaming = c
            }
          }.listRowBackground(Genoa.canvas).listRowInsets(
            EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
        }
      }.listStyle(.plain).scrollContentBackground(.hidden).refreshable { await model.refresh() }
    }.sheet(isPresented: $create) { CreateConversationScreen() }
      .alert(
        "Rename conversation",
        isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
        presenting: renaming
      ) { c in
        TextField(c.title, text: $newName)
        Button("Cancel", role: .cancel) {}
        Button("Save") { Task { await model.perform { try await model.rename(c.id, to: newName) } } }
          .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || newName.count > 80)
      } message: { _ in
        Text("Everyone in the conversation will see the new name.")
      }
      .alert("Join conversation", isPresented: $join) {
        TextField("Invitation link or code", text: $invite)
        Button("Cancel", role: .cancel) {}
        Button("Join") { Task { await model.perform { try await model.join(invite) } } }
      } message: {
        Text(
          "Join the conversation shared with you. The server checks whether the invitation is still valid."
        )
      }
  }
}
struct ConversationRow: View {
  let conversation: Conversation
  var body: some View {
    HStack(spacing: 12) {
      Avatar(name: conversation.title)
      VStack(alignment: .leading, spacing: 4) {
        HStack {
          Text(conversation.title).font(.headline)
          if conversation.favorite == 1 { Text("★").accessibilityLabel("Favorite") }
        }
        if conversation.isGroup { MemberPreview(conversation: conversation) }
        Text(conversation.previewText).font(.subheadline).foregroundStyle(Genoa.muted).lineLimit(2)
        if conversation.unwatched > 0 {
          Text("\(conversation.unwatched) unwatched").font(.caption).foregroundStyle(Genoa.teal)
        } else if conversation.unread > 0 {
          Text("\(conversation.unread) new").font(.caption).foregroundStyle(Genoa.teal)
        }
        if conversation.muted == 1 { Text("Muted").font(.caption).foregroundStyle(Genoa.muted) }
      }
      Spacer()
      GenoaIcon(name: "chevron", size: 20)
    }.padding(.vertical, 6).contentShape(Rectangle()).foregroundStyle(Genoa.ink)
      .accessibilityElement(children: .combine)
  }
}
/// Shows who is in a group at a glance, beneath its name.
struct MemberPreview: View {
  let conversation: Conversation
  var body: some View {
    let names = conversation.memberNames ?? []
    HStack(spacing: 6) {
      HStack(spacing: -6) {
        ForEach(Array(names.prefix(3).enumerated()), id: \.offset) { _, name in
          Avatar(name: name, size: 22).overlay(Circle().stroke(Genoa.canvas, lineWidth: 2))
        }
      }
      // Unnamed groups already list names in the title, so show the headcount instead.
      Text(
        conversation.named == 1 || names.isEmpty
          ? conversation.memberSummary : "\(conversation.memberCount) people"
      ).font(.caption).foregroundStyle(Genoa.muted).lineLimit(1)
    }.accessibilityElement(children: .ignore).accessibilityLabel(
      "Members: \(conversation.memberSummary)")
  }
}
struct RecipientPicker: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let title: String
  let choose: (Conversation) -> Void
  var body: some View {
    NavigationStack {
      List(model.conversations) { c in
        Button {
          choose(c)
        } label: {
          ConversationRow(conversation: c)
        }.accessibilityIdentifier("recipient.\(c.id)")
      }.navigationTitle(title).navigationBarTitleDisplayMode(.inline).toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
      }.genoaPage()
    }
  }
}
struct CreateConversationScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  @State private var people: [Contact] = []
  @State private var selected: Set<String> = []
  @State private var busy = false
  @State private var loading = true
  @State private var id = UUID().uuidString.lowercased()
  @State private var invite = UUID().uuidString.lowercased()
  @State private var link: URL?
  @State private var share = false
  @State private var copied = false
  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: 20) {
        Text("Choose your people, or send a link. You can name it later.").foregroundStyle(
          Genoa.muted)
        HStack {
          Button(copied ? "Link copied" : "Copy invite link") { start(action: "copy") }
          Spacer()
          Button("Share link") { start(action: "share") }
        }.buttonStyle(.bordered).controlSize(.large).disabled(busy)
        Text("Your contacts").font(.headline)
        if loading {
          ProgressView("Loading contacts…")
        } else if people.isEmpty {
          Text("People you talk with will appear here. Share a link to invite someone new.")
            .foregroundStyle(Genoa.muted)
        }
        ScrollView {
          LazyVStack(spacing: 8) {
            ForEach(people) { p in
              Button {
                if selected.contains(p.id) { selected.remove(p.id) } else { selected.insert(p.id) }
              } label: {
                HStack(spacing: 12) {
                  Avatar(name: p.name, size: 40)
                  Text(p.name).font(.headline)
                  Spacer()
                  GenoaIcon(name: selected.contains(p.id) ? "check" : "add")
                }.frame(minHeight: 56).contentShape(Rectangle())
              }.disabled(busy || link != nil).accessibilityAddTraits(
                selected.contains(p.id) ? .isSelected : [])
            }
          }
        }
        if link != nil {
          Text("Your conversation is ready. The invitation link works for 7 days.").font(.footnote)
            .foregroundStyle(Genoa.muted)
        }
        Button(busy ? "Preparing…" : link != nil ? "Open conversation" : "Start conversation") {
          start(action: "open")
        }
        .buttonStyle(PrimaryButton()).disabled(busy)
      }.padding(24).navigationTitle("Start a conversation").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("Cancel") { dismiss() }.disabled(busy) }.genoaPage()
        .task {
          await model.perform { people = try await model.contacts() }
          loading = false
        }
        .sheet(isPresented: $share) { if let link { ShareSheet(items: [link]) } }
        .interactiveDismissDisabled(busy)
    }
  }
  func start(action: String) {
    guard !busy else { return }
    busy = true
    Task {
      await model.perform {
        let url = try await model.startConversation(
          id: id, members: Array(selected), invite: invite)
        link = url
        if action == "copy" {
          UIPasteboard.general.url = url
          copied = true
        } else if action == "share" {
          share = true
        } else if let conversation = model.conversations.first(where: { $0.id == id }) {
          model.path = [conversation]
          dismiss()
        }
      }
      busy = false
    }
  }
}
