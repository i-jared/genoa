import SwiftUI

struct AuthScreen: View {
  @EnvironmentObject var model: AppModel
  @State private var email = ""
  @State private var password = ""
  @State private var name = ""
  @StateObject private var social = SocialSignIn()
  @State private var joining = false
  @State private var resetting = false
  static let minimumPassword = 8
  // Built at runtime so the links use this build's server; interpolated markdown in a
  // localized key would not become links.
  var agreement: AttributedString {
    let text =
      "By creating an account you agree to the [Terms](\(model.page("terms").absoluteString)) and [Privacy Policy](\(model.page("privacy").absoluteString))."
    return (try? AttributedString(markdown: text))
      ?? AttributedString("By creating an account you agree to the Terms and Privacy Policy.")
  }
  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 20) {
        BrandHeader().padding(.top, 50)
        Text("Stay close, at your own pace.").font(.system(.title2, design: .serif)).padding(
          .bottom, 20)
        Text(joining ? "Join Genoa" : "Welcome back").font(.system(.title, design: .serif))
        ForEach(model.loginProviders, id: \.self) { provider in
          Button("Continue with \(provider == "apple" ? "Apple" : "Google")") {
            Task { await model.socialSignIn(provider: provider, flow: social) }
          }.buttonStyle(.bordered).controlSize(.large).frame(maxWidth: .infinity).disabled(
            model.busy)
        }
        if !model.loginProviders.isEmpty {
          Text("Or use your email").font(.footnote).foregroundStyle(Genoa.muted)
        }
        if joining {
          TextField("Your name (optional)", text: $name).textContentType(.name)
            .accessibilityIdentifier(
              "auth.name")
        }
        TextField("Email", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress)
          .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier(
            "auth.email")
        SecureField("Password", text: $password).textContentType(joining ? .newPassword : .password)
          .accessibilityIdentifier("auth.password")
        if joining {
          Text("Use at least \(Self.minimumPassword) characters for your password.").font(.footnote)
            .foregroundStyle(Genoa.muted)
        } else if model.passwordReset {
          Button("Forgot password?") { resetting = true }.frame(minHeight: 48)
            .accessibilityIdentifier("auth.forgot")
        }
        if model.pendingInvite != nil {
          Text("Your conversation invitation will be available after sign-in.").font(.footnote)
        }
        Button {
          Task {
            await model.signIn(
              email: email.trimmingCharacters(in: .whitespaces), password: password,
              name: joining
                ? (name.trimmingCharacters(in: .whitespaces).isEmpty
                  ? String(email.prefix { $0 != "@" }.prefix(80))
                  : name.trimmingCharacters(in: .whitespaces)) : nil, invite: "")
          }
        } label: {
          Text(model.busy ? "Signing in…" : joining ? "Join Genoa" : "Sign in")
        }.buttonStyle(PrimaryButton()).disabled(
          model.busy || email.isEmpty || password.isEmpty
            || joining && (password.count < Self.minimumPassword)
        ).accessibilityIdentifier("auth.submit")
        if joining {
          Text(agreement).font(.footnote).tint(Genoa.teal)
        }
        Button(joining ? "Already have an account? Sign in" : "New here? Create an account") {
          joining.toggle()
        }.frame(minHeight: 48)
        Link("talkgenoa.com", destination: URL(string: "https://talkgenoa.com")!).font(.footnote)
      }.textFieldStyle(.roundedBorder).padding(24).frame(maxWidth: 500)
    }.frame(maxWidth: .infinity).genoaPage().task { await model.loadLoginProviders() }
      .sheet(isPresented: $resetting) { PasswordResetScreen(email: email) }
  }
}
struct PasswordResetScreen: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  @State var email: String
  @State private var sending = false
  @State private var sent = false
  @State private var problem: String?
  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: 16) {
        if sent {
          Text("If an account exists for that email, we sent a reset link.").font(.body)
            .accessibilityIdentifier("reset.sent")
          Text("Open the link on any device to choose a new password, then sign in here.")
            .font(.footnote).foregroundStyle(Genoa.muted)
          Button("Done") { dismiss() }.buttonStyle(PrimaryButton())
        } else {
          Text("Enter the email you use for Genoa. We’ll send a link to choose a new password.")
          TextField("Email", text: $email).textContentType(.emailAddress)
            .keyboardType(.emailAddress).textInputAutocapitalization(.never)
            .autocorrectionDisabled().textFieldStyle(.roundedBorder)
          if let problem { Text(problem).font(.footnote).foregroundStyle(Genoa.coral) }
          Button(sending ? "Sending…" : "Send reset link") {
            Task { await send() }
          }.buttonStyle(PrimaryButton()).disabled(sending || !email.contains("@"))
        }
        Spacer()
      }.padding(24).navigationTitle("Reset password").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("Cancel") { dismiss() } }
    }
  }
  func send() async {
    sending = true
    problem = nil
    defer { sending = false }
    do {
      try await model.requestPasswordReset(
        email: email.trimmingCharacters(in: .whitespacesAndNewlines))
      sent = true
    } catch let e as APIError where e.status == 429 {
      problem = "Too many requests. Wait a few minutes, then try again."
    } catch let e as APIError where e.status > 0 {
      problem = e.message
    } catch {
      problem = "Couldn’t reach Genoa. Check your connection and try again."
    }
  }
}
