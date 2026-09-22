import SwiftUI

struct AuthScreen: View {
  @EnvironmentObject var model: AppModel
  @State private var email = ""
  @State private var password = ""
  @State private var name = ""
  @StateObject private var social = SocialSignIn()
  @State private var joining = false
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
          Text("Use at least 6 characters for your password.").font(.footnote).foregroundStyle(
            Genoa.muted)
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
            || joining && (password.count < 6)
        ).accessibilityIdentifier("auth.submit")
        Button(joining ? "Already have an account? Sign in" : "New here? Create an account") {
          joining.toggle()
        }.frame(minHeight: 48)
        Link("talkgenoa.com", destination: URL(string: "https://talkgenoa.com")!).font(.footnote)
      }.textFieldStyle(.roundedBorder).padding(24).frame(maxWidth: 500)
    }.frame(maxWidth: .infinity).genoaPage().task { await model.loadLoginProviders() }
  }
}
