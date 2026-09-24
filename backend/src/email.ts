import type { Env } from "./types";

export interface Email {
  to: string;
  subject: string;
  text: string;
}
export const emailConfigured = (env: Env) =>
  !!(env.RESEND_API_KEY && env.EMAIL_FROM) || env.ENVIRONMENT === "development";

// Development has no provider: messages are kept in a local table so tests can follow links.
export async function sendEmail(env: Env, email: Email) {
  if (!env.RESEND_API_KEY || !env.EMAIL_FROM) {
    if (env.ENVIRONMENT !== "development") throw new Error("EMAIL_NOT_CONFIGURED");
    await env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS dev_emails (id INTEGER PRIMARY KEY, recipient TEXT, subject TEXT, text TEXT, created_at INTEGER)",
    ).run();
    await env.DB.prepare(
      "INSERT INTO dev_emails(recipient,subject,text,created_at) VALUES(?,?,?,?)",
    )
      .bind(email.to, email.subject, email.text, Date.now())
      .run();
    return;
  }
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.RESEND_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: env.EMAIL_FROM,
      to: [email.to],
      subject: email.subject,
      text: email.text,
    }),
  });
  if (!response.ok)
    throw new Error(`EMAIL_DELIVERY_FAILED_${response.status}`);
}
