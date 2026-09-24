import { Hono } from "hono";
import { html } from "hono/html";
import type { HtmlEscapedString } from "hono/utils/html";
import { auth } from "./auth";
import { rateLimit } from "./access";
import type { AppEnv, Ctx } from "./types";

// Public pages the stores and emails link to. Plain HTML with no scripts, so the CSP can forbid them.
export const pages = new Hono<AppEnv>();
const OPERATOR = "Whatado, LLC";
const UPDATED = "September 24, 2026";

function page(c: Ctx, title: string, content: HtmlEscapedString | Promise<HtmlEscapedString>, status: 200 | 400 = 200) {
  c.header("Cache-Control", "no-store");
  c.header("Referrer-Policy", "no-referrer");
  c.header(
    "Content-Security-Policy",
    "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
  );
  return c.html(
    html`<!doctype html>
      <html lang="en">
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width,initial-scale=1" />
        <title>${title} · Genoa</title>
        <style>
          :root{color-scheme:light dark;--bg:#f5f3ee;--ink:#252925;--muted:#5d625d;--accent:#206f60;--line:#dcd8cf}
          @media (prefers-color-scheme:dark){:root{--bg:#171a18;--ink:#ecebe6;--muted:#a9ada8;--accent:#6cc2ad;--line:#343935}}
          body{background:var(--bg);color:var(--ink);font:17px/1.6 system-ui,sans-serif;margin:0;padding:40px 20px}
          main{max-width:680px;margin:0 auto}h1{font:36px/1.2 Georgia,serif;margin:0 0 8px}h2{font:22px Georgia,serif;margin-top:32px}
          a{color:var(--accent)}.muted{color:var(--muted)}nav{margin-bottom:32px;display:flex;gap:16px;flex-wrap:wrap}
          label{display:block;margin:16px 0 6px}input{font:inherit;width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid var(--line);background:transparent;color:inherit}
          button{font:inherit;margin-top:20px;background:var(--accent);color:#fff;border:0;padding:14px 20px;border-radius:14px;min-height:48px;cursor:pointer}
        </style>
        <main>
          <nav><a href="/privacy">Privacy</a><a href="/terms">Terms</a><a href="/support">Support</a><a href="/delete-account">Delete your account</a></nav>
          <h1>${title}</h1>
          ${content}
        </main>
      </html>`,
    status,
  );
}
const contact = (c: Ctx) =>
  c.env.SUPPORT_EMAIL
    ? html`<a href="mailto:${c.env.SUPPORT_EMAIL}">${c.env.SUPPORT_EMAIL}</a>`
    : html`the support address listed in the app store`;

pages.get("/privacy", (c) =>
  page(
    c,
    "Privacy Policy",
    html`<p class="muted">Last updated ${UPDATED}. Genoa is operated by ${OPERATOR}.</p>
      <p>Genoa is a private video messenger. This policy explains what we collect, why, and the choices you have.</p>
      <h2>What we collect</h2>
      <ul>
        <li><strong>Account details:</strong> your name, email address, and password (stored as a one-way hash), or the identifier from Google or Apple if you sign in with them.</li>
        <li><strong>What you send:</strong> videos, voice recordings, photos, text, reactions, and replies you choose to send, plus who you sent them to.</li>
        <li><strong>How you use conversations:</strong> memberships, watch progress, bookmarks, private notes, reminders, and blocked people, so the app works the same on every device.</li>
        <li><strong>Device information:</strong> a push-notification token and platform, so we can tell you when a message arrives.</li>
        <li><strong>Diagnostics:</strong> crash and performance reports from the app (app version, device OS version, and technical details of the failure). These never include your recordings, messages, or passwords.</li>
        <li><strong>Safety reports:</strong> reports you file about messages, and our moderation decisions.</li>
        <li><strong>Service logs:</strong> request identifiers and error categories kept briefly by our hosting provider for security and reliability.</li>
      </ul>
      <h2>How we use it</h2>
      <p>Only to provide Genoa: authenticating you, delivering messages to the people in your conversations, sending notifications, keeping the service secure, fixing problems, and enforcing our Terms. We do not sell your data, show ads, or use advertising or analytics trackers.</p>
      <h2>Who can see your messages</h2>
      <p>Messages are visible only to members of the conversation they were sent to, and the server checks this on every request. Messages are not end-to-end encrypted: our systems store them so they can be delivered, and authorized staff may review a message when it is reported to us for abuse.</p>
      <h2>Service providers</h2>
      <p>We use Cloudflare to host the service and store media, Google Firebase and Apple Push Notification service to deliver notifications, and an email delivery provider to send account emails such as password resets. They process data only on our behalf.</p>
      <h2>Retention and deletion</h2>
      <p>Messages you send stay available to the conversation until you delete them or delete your account. When you delete a message or your account, we revoke access immediately and delete the stored media shortly afterwards. Copies in backups are overwritten within 30 days. Diagnostics are deleted after 30 days. See <a href="/delete-account">how to delete your account</a>.</p>
      <h2>Your choices</h2>
      <p>You can export your data and delete your account in the app's settings, block people, and turn notifications off. Depending on where you live you may have rights to access, correct, or delete your data; contact us to exercise them.</p>
      <h2>Children</h2>
      <p>Genoa is not directed to children under 13, and we do not knowingly collect their data.</p>
      <h2>Contact</h2>
      <p>Questions about privacy: ${contact(c)}. If we change this policy, we will update the date above and, for significant changes, tell you in the app.</p>`,
  ),
);

pages.get("/terms", (c) =>
  page(
    c,
    "Terms of Use",
    html`<p class="muted">Last updated ${UPDATED}. These terms are an agreement between you and ${OPERATOR}.</p>
      <h2>Using Genoa</h2>
      <p>You must be at least 13 years old (or the minimum age in your country) to use Genoa. Keep your password secure; you are responsible for activity on your account.</p>
      <h2>No objectionable content or abuse</h2>
      <p>Genoa has zero tolerance for objectionable content and abusive users. You may not use Genoa to send or share:</p>
      <ul>
        <li>content that sexually exploits or endangers children;</li>
        <li>harassment, threats, bullying, hate speech, or incitement to violence;</li>
        <li>sexual content involving anyone who has not consented to it;</li>
        <li>content that promotes self-harm, terrorism, or illegal activity;</li>
        <li>spam, scams, malware, or impersonation;</li>
        <li>content that infringes someone else's rights, including recordings of people made or shared without their permission where that is required by law.</li>
      </ul>
      <p>You can block anyone and report any message from the message's menu. We review reports within 24 hours, remove content that breaks these terms, and suspend or remove accounts of people who post it. We may report illegal content to the authorities.</p>
      <h2>Your content</h2>
      <p>You own what you send. You give us permission to store, process, and deliver it to the people you send it to, only as needed to run Genoa. Only send recordings of other people if you have the right to do so.</p>
      <h2>Availability</h2>
      <p>Genoa is provided "as is". We work to keep it reliable but can't promise it will always be available or error-free. Current limits are 8 GB and 24 hours per message, and a per-account storage allowance.</p>
      <h2>Ending your use</h2>
      <p>You can stop using Genoa and delete your account at any time. We may suspend or end access for violations of these terms.</p>
      <h2>Liability</h2>
      <p>To the extent the law allows, ${OPERATOR} is not liable for indirect or consequential damages arising from your use of Genoa.</p>
      <h2>Contact</h2>
      <p>${contact(c)}</p>`,
  ),
);

pages.get("/support", (c) =>
  page(
    c,
    "Support",
    html`<p>We're here to help with Genoa.</p>
      <h2>Contact us</h2>
      <p>Email ${contact(c)}. Include the email address on your account, but never your password.</p>
      <h2>Forgot your password?</h2>
      <p>On the sign-in screen, choose <strong>Forgot password?</strong> and we'll email you a reset link.</p>
      <h2>Report abuse</h2>
      <p>In the app, open the message's menu and choose <strong>Report</strong>. You can also block the person. We review reports within 24 hours.</p>
      <h2>Delete your account</h2>
      <p>See <a href="/delete-account">how to delete your account and data</a>.</p>`,
  ),
);

pages.get("/delete-account", (c) =>
  page(
    c,
    "Delete your Genoa account",
    html`<h2>In the app</h2>
      <ol>
        <li>Open Genoa and sign in.</li>
        <li>Open <strong>Settings</strong> (Android) or your <strong>Profile</strong> (iPhone).</li>
        <li>Choose <strong>Delete account</strong> and confirm.</li>
      </ol>
      <h2>Without the app</h2>
      <p>Email ${contact(c)} from the address on your account and ask us to delete it. We'll confirm the request with you and complete it within 30 days.</p>
      <h2>What is deleted</h2>
      <p>Your profile, sign-in methods, sessions, devices, notes, bookmarks, reminders, reactions, watch progress, and blocks are deleted, and your messages are removed from every conversation. Stored media is deleted shortly afterwards and is overwritten in backups within 30 days. Your name is replaced with "Deleted account" in conversations other people are still in. Reports you filed and our moderation records may be kept for up to a year to keep Genoa safe.</p>`,
  ),
);

pages.get("/email-verified", (c) => {
  const failed = c.req.query("error");
  return page(
    c,
    failed ? "This link has expired" : "Email confirmed",
    failed
      ? html`<p>That confirmation link is invalid or has expired. Sign in to Genoa to get a new one.</p>`
      : html`<p>Thanks — your email address is confirmed. You can go back to Genoa.</p>`,
  );
});

const resetForm = (token: string, error?: string) =>
  html`${error ? html`<p role="alert"><strong>${error}</strong></p>` : ""}
    <form method="post" action="/reset-password">
      <input type="hidden" name="token" value="${token}" />
      <label for="password">New password (at least 8 characters)</label>
      <input id="password" name="password" type="password" autocomplete="new-password" minlength="8" maxlength="128" required />
      <label for="confirm">Confirm new password</label>
      <input id="confirm" name="confirm" type="password" autocomplete="new-password" minlength="8" maxlength="128" required />
      <button type="submit">Save new password</button>
    </form>`;
pages.get("/reset-password", (c) => {
  const token = c.req.query("token") ?? "";
  if (!token || c.req.query("error"))
    return page(c, "This link has expired", html`<p>Reset links work once and expire after an hour. In Genoa, choose <strong>Forgot password?</strong> to get a new one.</p>`);
  return page(c, "Choose a new password", resetForm(token));
});
pages.post("/reset-password", async (c) => {
  await rateLimit(c.env, `reset-form:${c.req.header("cf-connecting-ip") ?? "local"}`, 10);
  const form = await c.req.parseBody();
  const token = String(form.token ?? ""),
    password = String(form.password ?? ""),
    confirm = String(form.confirm ?? "");
  if (password !== confirm)
    return page(c, "Choose a new password", resetForm(token, "Those passwords don't match."), 400);
  if (password.length < 8 || password.length > 128)
    return page(c, "Choose a new password", resetForm(token, "Use 8 to 128 characters."), 400);
  try {
    await auth(c.env).api.resetPassword({ body: { token, newPassword: password } });
  } catch {
    return page(c, "This link has expired", html`<p>Reset links work once and expire after an hour. In Genoa, choose <strong>Forgot password?</strong> to get a new one.</p>`, 400);
  }
  return page(c, "Password changed", html`<p>Your password is changed and you've been signed out on your other devices. Go back to Genoa and sign in with your new password.</p>`);
});
