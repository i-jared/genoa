import { spawn, spawnSync } from "node:child_process";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const skillDir = resolve(scriptDir, "..");
export const repoRoot = resolve(skillDir, "../../..");
export const backend = join(repoRoot, "backend");
const wrangler = join(backend, "node_modules/.bin/wrangler");
export const defaultPort = Number(process.env.GENOA_VERIFY_PORT ?? 18787);
export const inspectorPort = Number(process.env.GENOA_VERIFY_INSPECTOR_PORT ?? 18788);
export const tmpRoot = process.env.GENOA_VERIFY_TMP ?? join(tmpdir(), "genoa-verify");
export const statePath = process.env.GENOA_VERIFY_STATE ?? join(tmpRoot, "current.json");
const productionHosts = ["genoa-api.hp-accounts.workers.dev", "talkgenoa.com"];
const secretKeyNames = new Set([
  "token",
  "set-auth-token",
  "setauthtoken",
  "authorization",
  "password",
  "invite",
  "invitecode",
  "beta_invite_code",
  "betainvitecode",
  "better_auth_secret",
  "betterauthsecret",
  "secret",
]);

export function persistAllowed(dir) {
  return typeof dir === "string" && dir.startsWith(tmpRoot);
}

export function inviteCode() {
  if (process.env.TEST_INVITE_CODE) return process.env.TEST_INVITE_CODE;
  if (process.env.BETA_INVITE_CODE) return process.env.BETA_INVITE_CODE;
  const varsPath = join(backend, ".dev.vars");
  if (!existsSync(varsPath)) return "local-genoa-beta";
  const match = readFileSync(varsPath, "utf8").match(/^BETA_INVITE_CODE=(.*)$/m);
  return match ? match[1].trim().replace(/^["']|["']$/g, "") : "local-genoa-beta";
}

export function refuseRemote(url) {
  let host;
  try {
    host = new URL(url).hostname;
  } catch {
    throw new Error(`Invalid URL: ${url}`);
  }
  if (productionHosts.includes(host) || host.endsWith(".workers.dev"))
    throw new Error(`Refusing to drive ${url}. Verification uses only an isolated local Worker.`);
  if (!["127.0.0.1", "localhost"].includes(host))
    throw new Error(`Refusing to drive ${url}. Host must be loopback.`);
}

export function isolatedUrl(port) {
  if (port === 8787) throw new Error("Refusing port 8787. That is the user's wrangler, not this harness.");
  const url = `http://127.0.0.1:${port}`;
  refuseRemote(url);
  return url;
}

export function pidAlive(pid) {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function childPids(pid) {
  const result = spawnSync("pgrep", ["-P", String(pid)], { encoding: "utf8" });
  if (result.status !== 0) return [];
  return result.stdout
    .trim()
    .split("\n")
    .map(Number)
    .filter(Boolean);
}

function descendants(pid) {
  const kids = childPids(pid);
  return kids.concat(kids.flatMap(descendants));
}

export function portOwner(port) {
  const result = spawnSync("lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"], {
    encoding: "utf8",
  });
  return result.stdout
    .trim()
    .split("\n")
    .map(Number)
    .filter(Boolean);
}

export function ownsPort(pid, port) {
  const owners = portOwner(port);
  const tree = new Set([pid, ...descendants(pid)]);
  return { owners, ours: owners.some((owner) => tree.has(owner)) };
}

export function killTree(pid, signal = "SIGTERM") {
  for (const child of childPids(pid)) killTree(child, signal);
  try {
    process.kill(pid, signal);
  } catch {}
}

export function sleep(ms) {
  spawnSync("sleep", [String(ms / 1000)]);
}

function keyName(key) {
  return String(key).toLowerCase().replaceAll(/[-_]/g, "");
}

function redactValue(value, secrets) {
  if (typeof value === "string") {
    let out = value;
    for (const secret of secrets) {
      if (secret && out.includes(secret)) out = out.split(secret).join("[redacted]");
    }
    return out.replace(/genoa:\/\/join\/[0-9a-fA-F-]{36}/g, "genoa://join/[redacted]");
  }
  if (Array.isArray(value)) return value.map((item) => redactValue(item, secrets));
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, nested] of Object.entries(value)) {
      out[key] = secretKeyNames.has(keyName(key)) ? "[redacted]" : redactValue(nested, secrets);
    }
    return out;
  }
  return value;
}

function persist(instance) {
  mkdirSync(dirname(statePath), { recursive: true });
  writeFileSync(statePath, JSON.stringify(instance, null, 2));
}

function rawState() {
  if (!existsSync(statePath)) return null;
  return JSON.parse(readFileSync(statePath, "utf8"));
}

function asFailed(row, error) {
  return {
    status: "failed",
    runId: row.runId,
    pid: row.pid ?? null,
    port: row.port,
    persistDir: row.persistDir,
    evidenceDir: row.evidenceDir,
    logPath: row.logPath,
    error,
  };
}

function validatePersisted(row) {
  if (!row || typeof row !== "object") throw new Error("Instance state is not an object.");
  const status = row.status ?? (row.pid && row.baseUrl ? "healthy" : null);
  if (!status || !["launching", "healthy", "failed", "cleaned"].includes(status))
    throw new Error("Instance state is missing a valid phase.");
  if (status === "cleaned") {
    if (!row.runId || !row.evidenceDir) throw new Error("cleaned state is missing evidenceDir.");
    return { status: "cleaned", runId: row.runId, evidenceDir: row.evidenceDir };
  }
  if (row.port === 8787) throw new Error("Refusing persisted state on port 8787.");
  if (row.baseUrl) refuseRemote(row.baseUrl);
  if (row.persistDir && !persistAllowed(row.persistDir))
    throw new Error(`persistDir is not under ${tmpRoot}.`);
  if (status === "launching") {
    if (row.pid != null && (typeof row.pid !== "number" || !Number.isInteger(row.pid)))
      throw new Error("launching.pid must be number or null.");
    return {
      status: "launching",
      runId: row.runId,
      pid: row.pid ?? null,
      port: row.port,
      inspectorPort: row.inspectorPort,
      persistDir: row.persistDir,
      evidenceDir: row.evidenceDir,
      logPath: row.logPath,
    };
  }
  if (status === "healthy") {
    if (typeof row.pid !== "number") throw new Error("healthy.pid must be a number.");
    refuseRemote(row.baseUrl);
    return {
      status: "healthy",
      runId: row.runId,
      pid: row.pid,
      port: row.port,
      inspectorPort: row.inspectorPort,
      baseUrl: row.baseUrl,
      persistDir: row.persistDir,
      evidenceDir: row.evidenceDir,
      logPath: row.logPath,
      accounts: row.accounts ?? {},
    };
  }
  return {
    status: "failed",
    runId: row.runId,
    pid: row.pid ?? null,
    port: row.port,
    persistDir: row.persistDir,
    evidenceDir: row.evidenceDir,
    logPath: row.logPath,
    error: row.error ?? "failed",
  };
}

export function loadInstance() {
  const row = rawState();
  if (!row) return { status: "absent" };
  let instance;
  try {
    instance = validatePersisted(row);
  } catch (error) {
    throw error;
  }
  if (instance.status === "launching" && instance.pid != null && !pidAlive(instance.pid)) {
    const failed = asFailed(instance, `pid ${instance.pid} is dead`);
    persist(failed);
    return failed;
  }
  if (instance.status === "healthy" && !pidAlive(instance.pid)) {
    const failed = asFailed(instance, `pid ${instance.pid} is dead`);
    persist(failed);
    return failed;
  }
  return instance;
}

export function transition(current, event) {
  if (event.type === "launch-started") {
    if (current.status !== "absent" && current.status !== "cleaned")
      throw new Error(`Cannot start launch from ${current.status}.`);
    return { ...event.draft, status: "launching" };
  }
  if (event.type === "ready") {
    if (current.status !== "launching") throw new Error(`ready is illegal from ${current.status}.`);
    if (typeof current.pid !== "number") throw new Error("ready requires a recorded pid.");
    return {
      status: "healthy",
      runId: current.runId,
      pid: current.pid,
      port: current.port,
      inspectorPort: current.inspectorPort,
      baseUrl: isolatedUrl(current.port),
      persistDir: current.persistDir,
      evidenceDir: current.evidenceDir,
      logPath: current.logPath,
      accounts: {},
    };
  }
  if (event.type === "launch-failed" || event.type === "doctor-failed") {
    if (current.status !== "launching" && current.status !== "healthy")
      throw new Error(`${event.type} is illegal from ${current.status}.`);
    return asFailed(current, event.error);
  }
  if (event.type === "cleanup-done") {
    if (current.status === "absent") return { status: "absent" };
    return { status: "cleaned", runId: current.runId, evidenceDir: current.evidenceDir };
  }
  throw new Error(`Unknown instance event ${event.type}.`);
}

function ensureWrangler() {
  if (!existsSync(wrangler)) throw new Error(`Wrangler is not installed. From ${backend} run: npm ci`);
  if (!existsSync(join(backend, "wrangler.jsonc")))
    throw new Error(`Missing ${join(backend, "wrangler.jsonc")}`);
}

function envFile(runDir, port) {
  const path = join(runDir, "verify.env");
  const secret =
    process.env.BETTER_AUTH_SECRET ||
    "verify-genoa-local-secret-do-not-use-in-production-32";
  writeFileSync(
    path,
    `BETTER_AUTH_SECRET=${secret}\nBETA_INVITE_CODE=${inviteCode()}\nAPP_URL=http://127.0.0.1:${port}\nLEGACY_APP_URL=http://10.0.2.2:${port}\nENVIRONMENT=development\n`,
  );
  return path;
}

async function healthRequest(baseUrl, path) {
  refuseRemote(baseUrl);
  const response = await fetch(baseUrl + path, { method: "GET", headers: { Origin: baseUrl } });
  const text = await response.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = { text };
  }
  return { status: response.status, json };
}

async function waitReady(baseUrl) {
  const deadline = Date.now() + 30000;
  let last = "";
  while (Date.now() < deadline) {
    try {
      const result = await healthRequest(baseUrl, "/health");
      if (result.status === 200 && result.json?.ok === true) return;
      last = JSON.stringify(result.json);
    } catch (error) {
      last = error.message;
    }
    sleep(400);
  }
  throw new Error(`Worker did not become ready at ${baseUrl}/health: ${last}`);
}

async function waitForLaunching(current) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const next = loadInstance();
    if (next.status === "healthy") return next;
    if (next.status === "failed") throw new Error(next.error);
    sleep(400);
  }
  const failed = transition(current, { type: "launch-failed", error: "Timed out waiting for a launching instance." });
  persist(failed);
  throw new Error(failed.error);
}

export async function launch() {
  ensureWrangler();
  if (defaultPort === 8787) throw new Error("Refusing port 8787. That is the user's wrangler, not this harness.");
  const current = loadInstance();
  if (current.status === "healthy") {
    const report = await doctorFrom(current);
    if (!report.ok) {
      persist(transition(current, { type: "doctor-failed", error: "Doctor failed on reuse." }));
      throw new Error("Doctor failed on reuse.");
    }
    return loadInstance();
  }
  if (current.status === "launching") return waitForLaunching(current);
  if (current.status === "failed")
    throw new Error(`Instance failed (${current.error}). Run cleanup before launch.`);
  const owners = portOwner(defaultPort);
  if (owners.length)
    throw new Error(
      `Port ${defaultPort} is already in use by pid ${owners.join(",")}. Set GENOA_VERIFY_PORT to another loopback port, or stop that process if it is a leftover verification Worker. Never kill the user's wrangler on 8787 by process name.`,
    );
  const runId = `${Date.now()}-${process.pid}`;
  const runDir = join(tmpRoot, runId);
  const persistDir = join(runDir, "wrangler");
  const logPath = join(runDir, "wrangler.log");
  const evidenceDir = join(repoRoot, "artifacts", "verify-genoa", runId);
  mkdirSync(persistDir, { recursive: true });
  mkdirSync(evidenceDir, { recursive: true });
  const vars = envFile(runDir, defaultPort);
  const migrate = spawnSync(
    wrangler,
    [
      "d1",
      "migrations",
      "apply",
      "genoa",
      "--local",
      "--persist-to",
      persistDir,
      "--env-file",
      vars,
    ],
    { cwd: backend, encoding: "utf8", env: { ...process.env, CI: "1" } },
  );
  if (migrate.status !== 0) throw new Error(`D1 migrations failed\n${migrate.stderr || migrate.stdout}`);
  const draft = {
    status: "launching",
    runId,
    pid: null,
    port: defaultPort,
    inspectorPort,
    persistDir,
    evidenceDir,
    logPath,
  };
  persist(transition({ status: "absent" }, { type: "launch-started", draft }));
  const logFd = openSync(logPath, "a");
  const child = spawn(
    wrangler,
    [
      "dev",
      "--ip",
      "127.0.0.1",
      "--port",
      String(defaultPort),
      "--inspector-port",
      String(inspectorPort),
      "--persist-to",
      persistDir,
      "--env-file",
      vars,
      "--show-interactive-dev-session",
      "false",
    ],
    {
      cwd: backend,
      detached: true,
      stdio: ["ignore", logFd, logFd],
      env: { ...process.env, CI: "1" },
    },
  );
  child.unref();
  closeSync(logFd);
  const launching = { ...draft, pid: child.pid };
  persist(launching);
  try {
    await waitReady(isolatedUrl(defaultPort));
  } catch (error) {
    persist(transition(launching, { type: "launch-failed", error: error.message }));
    throw new Error(
      `${error.message}${existsSync(logPath) ? `\n${readFileSync(logPath, "utf8").slice(-4000)}` : ""}`,
    );
  }
  const healthy = transition(launching, { type: "ready" });
  persist(healthy);
  writeEvidence(evidenceDir, "launch.json", {
    runId,
    baseUrl: healthy.baseUrl,
    persistDir,
    pid: child.pid,
  });
  return healthy;
}

async function doctorFrom(state) {
  refuseRemote(state.baseUrl);
  if (state.port === 8787) throw new Error("Refusing to doctor port 8787.");
  if (!pidAlive(state.pid)) throw new Error(`Verification Worker pid ${state.pid} is not running.`);
  const port = ownsPort(state.pid, state.port);
  if (!port.ours)
    throw new Error(
      `Port ${state.port} is not owned by verification pid ${state.pid} (owners: ${port.owners.join(",") || "none"}). Refusing to drive a shared instance.`,
    );
  const health = await healthRequest(state.baseUrl, "/health");
  const unauth = await healthRequest(state.baseUrl, "/api/conversations");
  const report = {
    ok:
      health.status === 200 &&
      health.json?.ok === true &&
      unauth.status === 401 &&
      persistAllowed(state.persistDir) &&
      pidAlive(state.pid),
    runId: state.runId,
    baseUrl: state.baseUrl,
    pid: state.pid,
    persistDir: state.persistDir,
    evidenceDir: state.evidenceDir,
    healthOk: health.status === 200 && health.json?.ok === true,
    health: health.json,
    unauthenticatedConversations: unauth.status,
    isolatedPersist: persistAllowed(state.persistDir),
  };
  writeEvidence(state.evidenceDir, "doctor.json", report);
  return report;
}

export async function doctor() {
  const state = requireHealthy();
  return doctorFrom(state);
}

export async function ensureHealthy() {
  const current = loadInstance();
  if (current.status === "absent" || current.status === "cleaned") return launch();
  if (current.status === "launching") return waitForLaunching(current);
  if (current.status === "failed")
    throw new Error(`Instance failed (${current.error}). Cleanup, then launch.`);
  const report = await doctorFrom(current);
  if (!report.ok) {
    persist(transition(current, { type: "doctor-failed", error: "Doctor failed." }));
    throw new Error("Doctor failed.");
  }
  return loadInstance();
}

export function requireHealthy() {
  const state = loadInstance();
  if (state.status !== "healthy")
    throw new Error(`No healthy verification instance (status=${state.status}). Run launch first.`);
  refuseRemote(state.baseUrl);
  if (state.port === 8787) throw new Error("Refusing to drive port 8787.");
  if (!pidAlive(state.pid))
    throw new Error(`Verification Worker pid ${state.pid} is not running. Launch again.`);
  const port = ownsPort(state.pid, state.port);
  if (!port.ours)
    throw new Error(
      `Port ${state.port} is not owned by verification pid ${state.pid} (owners: ${port.owners.join(",") || "none"}). Refusing to drive a shared instance.`,
    );
  return state;
}

export function cleanup() {
  let current;
  try {
    current = loadInstance();
  } catch (error) {
    const row = rawState();
    if (row?.port === 8787)
      return { ok: false, cleaned: false, error: "Refusing to cleanup port 8787." };
    if (!row) return { ok: true, cleaned: false, reason: "no state file" };
    throw error;
  }
  if (current.status === "absent")
    return { ok: true, cleaned: false, reason: "no state file" };
  if (current.port === 8787)
    return { ok: false, cleaned: false, error: "Refusing to cleanup port 8787." };
  if (typeof current.pid === "number" && pidAlive(current.pid)) {
    killTree(current.pid);
    sleep(800);
    if (pidAlive(current.pid)) killTree(current.pid, "SIGKILL");
  }
  if (persistAllowed(current.persistDir)) rmSync(dirname(current.persistDir), { recursive: true, force: true });
  rmSync(statePath, { force: true });
  return {
    ok: true,
    cleaned: true,
    pid: current.pid ?? null,
    persistRemoved: true,
    evidenceDir: current.evidenceDir,
    evidenceKept: current.evidenceDir ? existsSync(current.evidenceDir) : false,
  };
}

export function nextAttemptDir(runId, featureId) {
  const root = join(repoRoot, "artifacts", "verify-genoa", runId, featureId);
  mkdirSync(root, { recursive: true });
  let max = 0;
  for (const name of readdirSync(root)) {
    if (!/^\d{3}$/.test(name)) continue;
    if (!statSync(join(root, name)).isDirectory()) continue;
    max = Math.max(max, Number(name));
  }
  const attempt = max + 1;
  const dir = join(root, String(attempt).padStart(3, "0"));
  mkdirSync(dir, { recursive: true });
  return { attempt, dir };
}

export function writeEvidence(dir, relative, value, secrets = []) {
  const known = [inviteCode(), ...secrets].filter(Boolean);
  const redacted = typeof value === "string" ? redactValue(value, known) : redactValue(value, known);
  const path = join(dir, relative);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, typeof redacted === "string" ? redacted : JSON.stringify(redacted, null, 2) + "\n");
  return path;
}

export function saveAccounts(instance, accounts) {
  if (instance.status !== "healthy") throw new Error("Accounts can only be saved on a healthy instance.");
  const next = { ...instance, accounts: { ...instance.accounts, ...accounts } };
  persist(next);
  return next;
}

export function nextApiEvidencePath(instance, as, method, path) {
  const dir = join(instance.evidenceDir, "api");
  mkdirSync(dir, { recursive: true });
  let max = 0;
  for (const name of readdirSync(dir)) {
    const match = name.match(/^(\d+)-/);
    if (match) max = Math.max(max, Number(match[1]));
  }
  const seq = String(max + 1).padStart(3, "0");
  const slug = `${method.toLowerCase()}${path.replaceAll("/", "_") || "_"}`;
  return join("api", `${seq}-${as}-${slug}.json`);
}
