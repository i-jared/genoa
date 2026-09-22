import SwiftUI

struct ConversationsScreen: View {
  @EnvironmentObject var model: AppModel
  @State private var search = ""
  @State private var filter = "All"
  @State private var create = false
  @State private var join = false
  @State private var invite = ""
  var filtered: [Conversation] {
    model.conversations.filter { c in
      (search.isEmpty || c.title.localizedCaseInsensitiveContains(search))
        && (filter != "Unwatched" || c.unwatched > 0) && (filter != "Groups" || c.memberCount > 2)
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
            search.isEmpty ? "No conversations yet" : "No matches",
            systemImage: "bubble.left.and.bubble.right",
            description: Text(
              search.isEmpty
                ? "Start a conversation or join with an invitation."
                : "Search checks conversation names.")
          ).listRowBackground(Color.clear)
        }
        ForEach(filtered) { c in
          Button {
            model.path.append(c)
          } label: {
            ConversationRow(conversation: c)
          }.listRowBackground(Genoa.canvas).listRowInsets(
            EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
        }
      }.listStyle(.plain).scrollContentBackground(.hidden).refreshable { await model.refresh() }
    }.sheet(isPresented: $create) { CreateConversationScreen() }
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
  @State private var title = ""
  @State private var busy = false
  @State private var id = UUID().uuidString.lowercased()
  var body: some View {
    NavigationStack {
      Form {
        Section("People") {
          if people.isEmpty { Text("Create a conversation, then share its invitation.") }
          ForEach(people) { p in
            Button {
              if selected.contains(p.id) { selected.remove(p.id) } else { selected.insert(p.id) }
            } label: {
              HStack {
                Text(p.name)
                Spacer()
                if selected.contains(p.id) { GenoaIcon(name: "check") }
              }
            }.accessibilityAddTraits(selected.contains(p.id) ? .isSelected : [])
          }
        }
        Section("Conversation name") { TextField("Name", text: $title) }
        Button(busy ? "Creating…" : "Create conversation") {
          busy = true
          Task {
            await model.perform {
              guard let api = model.api else { return }
              let result: IDResponse = try await api.call(
                "/api/conversations", method: "POST",
                body: ["id": id, "title": title, "member_ids": Array(selected)])
              await model.refresh()
              if let c = model.conversations.first(where: { $0.id == result.id }) {
                model.path = [c]
              }
              dismiss()
            }
            busy = false
          }
        }.disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || title.count > 80 || busy)
      }.navigationTitle("New conversation").toolbar { Button("Cancel") { dismiss() } }.task {
        await model.perform { people = try await model.api?.call("/api/people") ?? [] }
      }
    }
  }
}
