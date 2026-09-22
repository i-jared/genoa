import { betterAuth } from "better-auth";
import { bearer } from "better-auth/plugins";
import type { Env } from "./types";
export function auth(env: Env) {
  if (!env.BETTER_AUTH_SECRET || env.BETTER_AUTH_SECRET.length < 32)
    throw new Error("AUTH_NOT_CONFIGURED");
  return betterAuth({
    appName: "Genoa",
    secret: env.BETTER_AUTH_SECRET,
    baseURL: env.APP_URL,
    basePath: "/auth",
    database: env.DB,
    emailAndPassword: { enabled: true, minPasswordLength: 6 },
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
      ...(env.ENVIRONMENT === "development" ? ["http://10.0.2.2:8787"] : []),
    ],
    session: { expiresIn: 60 * 60 * 24 * 30, updateAge: 60 * 60 * 24 },
    rateLimit: { enabled: true, window: 60, max: 20 },
  });
}
