// Seals the Worker bearer token into an opaque cookie value with AES-256-GCM.
// GCM authenticates the ciphertext, so any tampering makes decryption fail.
// Kept free of Next.js imports so it runs in proxy, route handlers, and tests.

export const SESSION_MAX_AGE_SECONDS = 12 * 60 * 60;

const VERSION = "v1";
const IV_BYTES = 12;
// Binds ciphertext to this purpose so a value sealed elsewhere with the same
// secret cannot be replayed as a session.
const AAD = new TextEncoder().encode("genoa-admin-session");
const MAX_COOKIE_LENGTH = 4096;

export type SessionPayload = {
  token: string;
  /** Expiry as Unix milliseconds. */
  exp: number;
};

const keyCache = new Map<string, Promise<CryptoKey>>();

function deriveKey(secret: string): Promise<CryptoKey> {
  let key = keyCache.get(secret);
  if (!key) {
    key = (async () => {
      const material = await crypto.subtle.importKey(
        "raw",
        new TextEncoder().encode(secret),
        "HKDF",
        false,
        ["deriveKey"],
      );
      return crypto.subtle.deriveKey(
        {
          name: "HKDF",
          hash: "SHA-256",
          salt: new TextEncoder().encode("genoa-admin-session-salt-v1"),
          info: new TextEncoder().encode("cookie-encryption"),
        },
        material,
        { name: "AES-GCM", length: 256 },
        false,
        ["encrypt", "decrypt"],
      );
    })();
    keyCache.set(secret, key);
  }
  return key;
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(value: string): Uint8Array<ArrayBuffer> | null {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) return null;
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (value.length % 4)) % 4);
  try {
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

export async function sealSession(payload: SessionPayload, secret: string): Promise<string> {
  const key = await deriveKey(secret);
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const plaintext = new TextEncoder().encode(JSON.stringify(payload));
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: AAD }, key, plaintext),
  );
  const combined = new Uint8Array(iv.length + ciphertext.length);
  combined.set(iv, 0);
  combined.set(ciphertext, iv.length);
  return `${VERSION}.${toBase64Url(combined)}`;
}

/** Returns the payload, or null when the value is malformed, tampered with, or expired. */
export async function openSession(
  value: string | undefined,
  secret: string,
  now: number = Date.now(),
): Promise<SessionPayload | null> {
  if (!value || value.length > MAX_COOKIE_LENGTH) return null;
  const [version, body, ...rest] = value.split(".");
  if (version !== VERSION || !body || rest.length > 0) return null;
  const combined = fromBase64Url(body);
  if (!combined || combined.length <= IV_BYTES + 16) return null;

  let plaintext: ArrayBuffer;
  try {
    const key = await deriveKey(secret);
    plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: combined.slice(0, IV_BYTES), additionalData: AAD },
      key,
      combined.slice(IV_BYTES),
    );
  } catch {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(plaintext));
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const { token, exp } = parsed as Record<string, unknown>;
  if (typeof token !== "string" || token.length === 0 || typeof exp !== "number") return null;
  if (!Number.isFinite(exp) || exp <= now) return null;
  return { token, exp };
}
