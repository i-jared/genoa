# Google and Apple sign-in

Both native apps use a system browser authentication flow through Better Auth on the Worker. Email signup is open, needs no beta code, and accepts passwords of at least six characters. Names are optional in the app. Google/Apple buttons are hidden unless the corresponding pair of Worker secrets is configured.

## Configure when ready

1. Apply backend migration `0007.sql` using the normal production migration command before deploying this update. It adds short-lived mobile sign-in handoffs.
2. Google: create a **Web application** OAuth client in the Genoa Google Cloud project. Configure the consent screen, support details and permitted/test users as appropriate. Add this authorized redirect URI: `https://genoa-api.hp-accounts.workers.dev/auth/callback/google`. Store `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` using `npx wrangler secret put NAME --config wrangler.production.jsonc` from `backend/`.
3. Apple: enable Sign in with Apple for the primary app ID `app.genoa` in team **Whatado, LLC / DCWP7J7692**. Create a Services ID associated with that app for the browser flow. Register the API domain `genoa-api.hp-accounts.workers.dev` and return URL `https://genoa-api.hp-accounts.workers.dev/auth/callback/apple`. Store the Services ID as `APPLE_CLIENT_ID`. Create a Sign in with Apple key and generate the client-secret JWT with that Services ID as subject; store the JWT as `APPLE_CLIENT_SECRET`. Keep the private signing key outside this repository. Apple client-secret JWTs expire; arrange renewal before their expiration (maximum six months).
4. Deploy the Worker, reopen the sign-in screen, and test each enabled provider on Android and iOS. No mobile rebuild is needed just to enable configured providers. If the API hostname changes, update APP_URL and both provider consoles together.

Do not paste secrets into chat or commit `.dev.vars`. Existing email accounts must retain their data when safely linked by the authentication provider; test verified-email linking, Apple private-relay accounts, new accounts, canceled consent, and sign-out/sign-in with real credentials before advertising social login as verified.

## Security and verification

Native apps generate a verifier and send only its SHA-256 challenge to the Worker. The browser callback is bound to a short-lived HttpOnly cookie. Completion returns only an opaque code in `genoa://auth`; exchange requires the native verifier and consumes the code once. Session credentials do not appear in callback URLs. Android persists the pending verifier in encrypted storage to survive process recreation. iOS uses ASWebAuthenticationSession. Existing authorized media/session behavior is preserved.

The local backend suite verifies browser binding, incorrect proofs, single use, expiry and disabled providers. It also verifies open signup and rejection of five-character passwords. Provider-console integration cannot be tested until credentials are supplied. The current implementation does not add password recovery/email delivery.

Primary configuration references: [Better Auth Google](https://www.better-auth.com/docs/authentication/google), [Better Auth Apple](https://www.better-auth.com/docs/authentication/apple), [Apple web configuration](https://developer.apple.com/help/account/capabilities/configure-sign-in-with-apple-for-the-web/).
