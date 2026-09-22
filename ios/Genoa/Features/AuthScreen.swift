import SwiftUI

struct AuthScreen: View {
  @EnvironmentObject var model: AppModel
  @State private var email = ""
  @State private var password = ""
  @State private var name = ""
  @State private var invite = ""
  @State private var joining = false
  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 20) {
        BrandHeader().padding(.top, 50)
        Text("Stay close, at your own pace.").font(.system(.title2, design: .serif)).padding(
          .bottom, 20)
        Text(joining ? "Join Genoa" : "Welcome back").font(.system(.title, design: .serif))
        if joining {
          TextField("Your name", text: $name).textContentType(.name).accessibilityIdentifier(
            "auth.name")
        }
        TextField("Email", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress)
          .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier(
            "auth.email")
        SecureField("Password", text: $password).textContentType(joining ? .newPassword : .password)
          .accessibilityIdentifier("auth.password")
        if joining {
          SecureField("Beta invitation code", text: $invite).accessibilityIdentifier("auth.invite")
          Text("Use at least 12 characters for your password.").font(.footnote).foregroundStyle(
            Genoa.muted)
        }
        if model.pendingInvite != nil {
          Text("Your conversation invitation will be available after sign-in.").font(.footnote)
        }
        Button {
          Task {
            await model.signIn(
              email: email.trimmingCharacters(in: .whitespaces), password: password,
              name: joining ? name : nil, invite: invite)
          }
        } label: {
          Text(model.busy ? "Signing in…" : joining ? "Join Genoa" : "Sign in")
        }.buttonStyle(PrimaryButton()).disabled(
          model.busy || email.isEmpty || password.isEmpty
            || joining && (name.isEmpty || invite.isEmpty || password.count < 12)
        ).accessibilityIdentifier("auth.submit")
        Button(joining ? "Already have an account? Sign in" : "Have a beta code? Join Genoa") {
          joining.toggle()
        }.frame(minHeight: 48)
        Link("talkgenoa.com", destination: URL(string: "https://talkgenoa.com")!).font(.footnote)
      }.textFieldStyle(.roundedBorder).padding(24).frame(maxWidth: 500)
    }.frame(maxWidth: .infinity).genoaPage()
  }
}
