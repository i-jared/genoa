import SwiftUI

enum Genoa {
  static let teal = adaptive(0x206F60, 0x9AD4BE)
  static let canvas = adaptive(0xF5F3EE, 0x17251F)
  static let surface = adaptive(0xFFFDFA, 0x22332A)
  static let ink = adaptive(0x252925, 0xEDEFE9)
  static let muted = adaptive(0x686B64, 0xB2BDB4)
  static let soft = adaptive(0xDCEBE3, 0x304D40)
  static let coral = Color(hex: 0xFF593E)
  static func adaptive(_ light: UInt, _ dark: UInt) -> Color {
    Color(
      uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) }
    )
  }
}
extension UIColor {
  convenience init(hex: UInt) {
    self.init(
      red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255,
      blue: Double(hex & 255) / 255, alpha: 1)
  }
}
extension Color { init(hex: UInt) { self.init(uiColor: UIColor(hex: hex)) } }
struct GenoaIcon: View {
  let name: String
  var size: CGFloat = 24
  var body: some View {
    Image("icon-" + name).renderingMode(.template).resizable().scaledToFit().frame(
      width: size, height: size
    ).accessibilityHidden(true)
  }
}
struct IconButton: View {
  let icon: String
  let label: String
  let action: () -> Void
  var body: some View {
    Button(action: action) {
      GenoaIcon(name: icon).frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
    }.accessibilityLabel(label)
  }
}
struct Avatar: View {
  let name: String
  var size: CGFloat = 48
  var body: some View {
    let colors: [Color] = [
      Color(hex: 0xE7CDB9), Color(hex: 0xDCE6D7), Color(hex: 0xD9DDE8), Color(hex: 0xEBD9D7),
    ]
    let index = name.utf8.reduce(0) { ($0 + Int($1)) % colors.count }
    Text(
      name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined()
        .uppercased()
    )
    .font(.system(size: size * 0.34, weight: .medium, design: .serif)).foregroundStyle(
      Color(hex: 0x252925)
    )
    .frame(width: size, height: size).background(colors[index], in: Circle()).accessibilityHidden(
      true)
  }
}
struct PrimaryButton: ButtonStyle {
  var capture = false
  func makeBody(configuration: Configuration) -> some View {
    configuration.label.font(.headline).frame(maxWidth: .infinity, minHeight: 48).foregroundStyle(
      capture ? Color.white : Genoa.surface
    )
    .background(capture ? Genoa.coral : Genoa.teal, in: RoundedRectangle(cornerRadius: 16)).opacity(
      configuration.isPressed ? 0.7 : 1)
  }
}
struct BrandHeader: View {
  var body: some View {
    HStack(spacing: 8) {
      Image("Brand").resizable().scaledToFit().frame(width: 36, height: 36)
      Text("Genoa").font(.system(.largeTitle, design: .serif))
    }.foregroundStyle(Genoa.teal).accessibilityElement(children: .combine)
  }
}
extension View {
  func genoaPage() -> some View {
    self.background(Genoa.canvas).foregroundStyle(Genoa.ink).tint(Genoa.teal)
  }
}
func durationLabel(_ seconds: Double) -> String {
  let s = Int(max(0, seconds.isFinite ? seconds : 0))
  return String(format: "%d:%02d", s / 60, s % 60)
}
