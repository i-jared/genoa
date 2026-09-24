import { APIError, betterAuth } from "better-auth";
import { bearer } from "better-auth/plugins";
import { sendEmail, type Email } from "./email";
import { suspension } from "./access";
import type { Env } from "./types";
export const SUSPENDED_MESSAGE =
  "This account is suspended. Contact support for help.";
// `ctx` lets email delivery run after the response, so response timing doesn't reveal which emails have accounts.
export function auth(env: Env, ctx?: ExecutionContext) {
  const deliver = (email: Email) => {
    const sending = sendEmail(env, email).catch((e: Error) =>
      console.error(JSON.stringify({ category: "EMAIL_FAILED", detail: e.message })),
    );
    if (ctx) ctx.waitUntil(sending);
    return ctx ? Promise.resolve() : sending;
  };
  if (!env.BETTER_AUTH_SECRET || env.BETTER_AUTH_SECRET.length < 32)
    throw new Error("AUTH_NOT_CONFIGURED");
  return betterAuth({
    appName: "Genoa",
    secret: env.BETTER_AUTH_SECRET,
    baseURL: env.APP_URL,
    basePath: "/auth",
    database: env.DB,
    emailAndPassword: {
      enabled: true,
      minPasswordLength: 8,
      revokeSessionsOnPasswordReset: true,
      resetPasswordTokenExpiresIn: 60 * 60,
      // Our own page, rather than Better Auth's redirect endpoint, so the link never depends on client input.
      sendResetPassword: ({ user, token }) =>
        deliver({
          to: user.email,
          subject: "Reset your Genoa password",
          text: `Someone asked to reset the password for your Genoa account.\n\nChoose a new password here (the link expires in one hour):\n${env.APP_URL}/reset-password?token=${encodeURIComponent(token)}\n\nIf this wasn't you, you can ignore this email.`,
        }),
    },
    emailVerification: {
      sendOnSignUp: true,
      expiresIn: 60 * 60 * 24,
      sendVerificationEmail: ({ user, token }) =>
        deliver({
          to: user.email,
          subject: "Confirm your email for Genoa",
          text: `Confirm this email address for your Genoa account:\n${env.APP_URL}/auth/verify-email?token=${encodeURIComponent(token)}&callbackURL=${encodeURIComponent(`${env.APP_URL}/email-verified`)}\n\nIf you didn't create a Genoa account, you can ignore this email.`,
        }),
    },
    databaseHooks: {
      session: {
        create: {
          before: async (session) => {
            if (await suspension(env, session.userId))
              throw new APIError("FORBIDDEN", {
                message: SUSPENDED_MESSAGE,
                code: "ACCOUNT_SUSPENDED",
              });
          },
        },
      },
    },
    plugins: [bearer()],
    socialProviders: {
      ...(env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET
        ? {
            google: {
              clientId: env.GOOGLE_CLIENT_ID,
              clientSecret: env.GOOGLE_CLIENT_SECRET,
            },
          }
        : {}),
      ...(env.APPLE_CLIENT_ID && env.APPLE_CLIENT_SECRET
        ? {
            apple: {
              clientId: env.APPLE_CLIENT_ID,
              clientSecret: env.APPLE_CLIENT_SECRET,
            },
          }
        : {}),
    },
    trustedOrigins: [
      env.APP_URL,
      "https://appleid.apple.com",
      ...(env.LEGACY_APP_URL ? [env.LEGACY_APP_URL] : []),
      ...(env.ADMIN_URL ? [env.ADMIN_URL] : []),
      ...(env.ENVIRONMENT === "development" ? ["http://10.0.2.2:8787"] : []),
    ],
    session: { expiresIn: 60 * 60 * 24 * 30, updateAge: 60 * 60 * 24 },
    rateLimit: { enabled: true, window: 60, max: 20 },
  });
}
