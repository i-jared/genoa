import SwiftUI

struct ReactionRail: View {
  @ObservedObject var playback: PlaybackController
  let message: Message
  let userId: String?
  @State private var seen: Set<String> = []
  @State private var visible: [(reaction: Reaction, expires: Date)] = []
  @State private var previous: Int64 = -1
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  var body: some View {
    VStack(alignment: .trailing, spacing: 4) {
      ForEach(visible.map(\.reaction)) { r in
        Text("\(r.userName) \(r.emoji)").font(.caption).padding(8).background(
          .black.opacity(0.65), in: Capsule()
        ).transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
      }
    }.foregroundStyle(.white).animation(
      reduceMotion ? nil : .easeOut(duration: 0.2), value: visible.map { $0.reaction.id }
    )
    .task(id: message.id) {
      seen = []
      visible = []
      previous = -1
      while !Task.isCancelled {
        tick()
        try? await Task.sleep(for: .milliseconds(200))
      }
    }
  }
  private func tick() {
    let position = Int64(playback.position * 1000)
    visible.removeAll { $0.expires < Date() }
    if position < previous {
      seen = Set(message.reactions.filter { $0.positionMs < position }.map(\.id))
      visible = []
    }
    if position - previous > 1500 {
      seen.formUnion(message.reactions.filter { $0.positionMs < position }.map(\.id))
    }
    for reaction in message.reactions
    where reaction.userId != userId && reaction.positionMs <= position
      && !seen.contains(reaction.id)
    {
      seen.insert(reaction.id)
      visible.append((reaction, Date(timeIntervalSinceNow: 3)))
      if visible.count > 3 { visible.removeFirst() }
    }
    previous = position
  }
}

struct ReactionAcknowledgment: View {
  let emoji: String
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var lifted = false
  var body: some View {
    Text(emoji).font(.largeTitle).offset(y: lifted && !reduceMotion ? -90 : 0).opacity(
      lifted && !reduceMotion ? 0 : 1
    )
    .accessibilityLabel("Reaction sent")
    .onAppear { withAnimation(.easeOut(duration: 2)) { lifted = true } }
    .allowsHitTesting(false)
  }
}
