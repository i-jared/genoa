// Reads and validates configuration once per process. Kept dependency-free so
// proxy.ts and instrumentation.ts can use it too.

export type Env = {
  apiUrl: string;
  adminOrigin: string;
  sessionSecret: string;
  secureCookies: boolean;
  /** `__Host-` pins the cookie to this exact origin, path `/`, and HTTPS. */
  cookieName: string;
};

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

function parseUrl(name: string, raw: string | undefined, problems: string[]): URL | null {
  if (!raw) {
    problems.push(`${name} is not set.`);
    return null;
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    problems.push(`${name} must be an absolute URL (got something unparseable).`);
    return null;
  }
  if (url.protocol !== "https:" && !(url.protocol === "http:" && LOCAL_HOSTS.has(url.hostname))) {
    problems.push(`${name} must use https (plain http is only allowed for localhost).`);
    return null;
  }
  if (url.username || url.password || url.search || url.hash) {
    problems.push(`${name} must not contain credentials, a query string, or a fragment.`);
    return null;
  }
  return url;
}

export function readEnv(source: Record<string, string | undefined> = process.env): Env {
  const problems: string[] = [];
  const api = parseUrl("GENOA_API_URL", source.GENOA_API_URL, problems);
  const admin = parseUrl("ADMIN_URL", source.ADMIN_URL, problems);
  if (admin && admin.pathname !== "/") {
    problems.push("ADMIN_URL must be an origin only, such as https://admin.example.com (no path).");
  }
  const secret = source.SESSION_SECRET ?? "";
  if (secret.length < 32) {
    problems.push("SESSION_SECRET must be at least 32 characters (try `openssl rand -base64 48`).");
  }
  if (problems.length > 0 || !api || !admin) {
    throw new Error(`Genoa admin is misconfigured:\n- ${problems.join("\n- ")}`);
  }
  return {
    apiUrl: api.href.replace(/\/+$/, ""),
    adminOrigin: admin.origin,
    sessionSecret: secret,
    secureCookies: admin.protocol === "https:",
    cookieName: admin.protocol === "https:" ? "__Host-genoa_admin" : "genoa_admin",
  };
}

let cached: Env | undefined;

export function env(): Env {
  cached ??= readEnv();
  return cached;
}
