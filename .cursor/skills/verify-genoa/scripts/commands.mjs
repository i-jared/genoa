import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import {
  cleanup,
  inviteCode,
  launch,
  loadInstance,
  nextAttemptDir,
  nextApiEvidencePath,
  refuseRemote,
  requireHealthy,
  saveAccounts,
  sleep,
  writeEvidence,
} from "./instance.mjs";
import { REGISTRY } from "./recipes.mjs";

export const VERIFY_PASSWORD = "verify-password-123";

function padded(magic, length) {
  const bytes = new Uint8Array(length);
  bytes.set(magic);
  return bytes;
}

export const FIXTURES = {
  jpeg: padded([0xff, 0xd8, 0xff], 123),
  png: padded([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 32),
  ftyp: padded([0x00, 0x00, 0x00, 0x1c, 0x66, 0x74, 0x79, 0x70], 32),
  ts: padded([0x47], 188),
};

const slotPattern = /\$([A-Za-z_][A-Za-z0-9_]*)/g;

export function expand(template, bindings) {
  if (typeof template === "string") {
    return template.replace(slotPattern, (whole, name) => {
      if (!Object.hasOwn(bindings, name)) throw new Error(`Unknown slot $${name}`);
      return String(bindings[name]);
    });
  }
  if (Array.isArray(template)) return template.map((item) => expand(item, bindings));
  if (template && typeof template === "object") {
    const out = {};
    for (const [key, value] of Object.entries(template)) out[key] = expand(value, bindings);
    return out;
  }
  return template;
}

export function readPath(body, path) {
  const parts = String(path).split(".").filter(Boolean);
  let current = body;
  for (const part of parts) {
    if (current == null) return undefined;
    current = current[part];
  }
  return current;
}

function collection(result, of) {
  if (of === "body") return Array.isArray(result.body) ? result.body : [];
  if (of === "texts") return (result.body?.texts ?? []).map((text) => ({ text }));
  if (of === "descriptions") return (result.body?.descriptions ?? []).map((desc) => ({ desc }));
  throw new Error(`Unknown count collection ${of}`);
}

function matches(item, match, bindings) {
  for (const [key, raw] of Object.entries(match)) {
    if (String(item?.[key]) !== String(expand(raw, bindings))) return false;
  }
  return true;
}

export function evaluateAssertion(result, assertion, bindings) {
  if (assertion.kind === "status") {
    if (result.status !== assertion.equals)
      throw new Error(`status ${result.status} != ${assertion.equals}`);
    return;
  }
  if (assertion.kind === "json-eq") {
    const actual = String(readPath(result, assertion.path));
    const expected = String(expand(assertion.equals, bindings));
    if (actual !== expected) throw new Error(`${assertion.path} ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
    return;
  }
  if (assertion.kind === "includes") {
    const actual = String(readPath(result, assertion.path) ?? "");
    const needle = String(expand(assertion.needle, bindings));
    if (!actual.includes(needle)) throw new Error(`${assertion.path} does not include ${JSON.stringify(needle)}`);
    return;
  }
  if (assertion.kind === "count") {
    const items = collection(result, assertion.of).filter((item) => matches(item, assertion.match, bindings));
    if (items.length !== assertion.equals)
      throw new Error(`count ${assertion.of} ${items.length} != ${assertion.equals}`);
    return;
  }
  throw new Error(`Unknown assertion kind ${assertion.kind}`);
}

function jsonBody(text) {
  try {
    return JSON.parse(text);
  } catch {
    return { text };
  }
}

export async function phoneRequest(instance, spec) {
  refuseRemote(instance.baseUrl);
  if (spec.path === "/health") throw new Error("/health is doctor-only.");
  const method = spec.method.toUpperCase();
  const mutating = ["POST", "PUT", "PATCH", "DELETE"].includes(method);
  const headers = {
    Origin: instance.baseUrl,
    ...(spec.token ? { Authorization: `Bearer ${spec.token}` } : {}),
    ...spec.headers,
  };
  let body = spec.raw;
  if (spec.raw === undefined) {
    if (mutating && spec.body === undefined) {
      headers["Content-Type"] = "application/json";
      body = "{}";
    } else if (spec.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(spec.body);
    }
  }
  const response = await fetch(instance.baseUrl + spec.path, { method, headers, body });
  if (spec.asBytes) {
    const bytes = Buffer.from(await response.arrayBuffer());
    return {
      status: response.status,
      body: {},
      bytes: bytes.length,
      contentRange: response.headers.get("content-range") ?? undefined,
    };
  }
  const text = await response.text();
  return {
    status: response.status,
    body: jsonBody(text),
    token: response.headers.get("set-auth-token"),
  };
}

function accountOf(instance, name) {
  const record = instance.accounts?.[name];
  if (!record) throw new Error(`Unknown account '${name}'. Run: control-genoa signup --as ${name}`);
  return record;
}

function sessionSecrets(instance, bindings = {}) {
  return [
    ...Object.values(instance.accounts ?? {}).map((account) => account.token),
    ...Object.entries(bindings)
      .filter(([key]) => /token/i.test(key))
      .map(([, value]) => value),
  ].filter(Boolean);
}

function flattenUser(json) {
  const user = json?.user ?? json ?? {};
  return { id: user.id, name: user.name, email: user.email };
}

async function performSignup(instance, { as: name, invite }) {
  const email = `${name}-${instance.runId}@example.test`;
  const header = invite === "wrong" ? "wrong" : inviteCode();
  const raw = await phoneRequest(instance, {
    method: "POST",
    path: "/auth/sign-up/email",
    body: { name, email, password: VERIFY_PASSWORD },
    headers: { "X-Invite-Code": header },
  });
  const token = raw.token ?? raw.body?.token;
  const body = raw.status === 200 ? flattenUser(raw.body) : raw.body;
  if (raw.status === 200 && token) {
    if (!instance.accounts) instance.accounts = {};
    const record = { name, email, id: body.id, token };
    saveAccounts(instance, { [name]: record });
    instance.accounts[name] = record;
  }
  return { status: raw.status, body };
}

async function performSignin(instance, { as: name }) {
  const who = accountOf(instance, name);
  const raw = await phoneRequest(instance, {
    method: "POST",
    path: "/auth/sign-in/email",
    body: { email: who.email, password: VERIFY_PASSWORD },
  });
  const token = raw.token ?? raw.body?.token;
  const body = raw.status === 200 ? flattenUser(raw.body) : raw.body;
  if (raw.status === 200 && token) {
    const record = { ...who, token };
    saveAccounts(instance, { [name]: record });
    instance.accounts[name] = record;
  }
  return { status: raw.status, body };
}

export async function signup(args) {
  const instance = requireHealthy();
  if (!args.as) throw new Error("signup requires --as <name>");
  const result = await performSignup(instance, { as: args.as, invite: args.invite });
  const proof = { name: args.as, email: result.body.email, id: result.body.id, status: result.status };
  writeEvidence(instance.evidenceDir, `accounts/${args.as}.json`, proof, sessionSecrets(instance));
  if (result.status !== 200) throw new Error(`Sign-up failed (${result.status})`);
  return proof;
}

export async function signin(args) {
  const instance = requireHealthy();
  if (!args.as) throw new Error("signin requires --as <name>");
  const result = await performSignin(instance, { as: args.as });
  const proof = { name: args.as, id: result.body.id, status: result.status };
  writeEvidence(instance.evidenceDir, `accounts/${args.as}-signin.json`, proof, sessionSecrets(instance));
  if (result.status !== 200) throw new Error(`Sign-in failed (${result.status})`);
  return proof;
}

async function performApi(instance, { method, path, auth, body }) {
  const token = auth.kind === "as" ? accountOf(instance, auth.name).token : undefined;
  return phoneRequest(instance, { method, path, token, body });
}

export async function api(args) {
  const instance = requireHealthy();
  const method = args.method.toUpperCase();
  const path = args.path;
  const result = await performApi(instance, { method, path, auth: args.auth, body: args.body });
  const as = args.auth.kind === "as" ? args.auth.name : "unauthenticated";
  const proof = { as, method, path, status: result.status, body: result.body };
  writeEvidence(
    instance.evidenceDir,
    nextApiEvidencePath(instance, as, method, path),
    proof,
    sessionSecrets(instance),
  );
  if (result.status >= 400) {
    const error = new Error(`api ${method} ${path} returned ${result.status}`);
    error.payload = proof;
    throw error;
  }
  return proof;
}

function loadBytes(source) {
  if (source.kind === "fixture") {
    const bytes = FIXTURES[source.id];
    if (!bytes) throw new Error(`Unknown fixture ${source.id}`);
    return bytes;
  }
  return new Uint8Array(readFileSync(source.path));
}

export async function putBytes(args) {
  const instance = requireHealthy();
  const who = accountOf(instance, args.as);
  const bytes = loadBytes(args.source);
  const result = await phoneRequest(instance, {
    method: "PUT",
    path: args.path,
    token: who.token,
    raw: bytes,
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Length": String(bytes.byteLength),
    },
  });
  const proof = { as: args.as, path: args.path, status: result.status, body: result.body, bytes: bytes.byteLength };
  writeEvidence(instance.evidenceDir, nextApiEvidencePath(instance, args.as, "PUT", args.path), proof, sessionSecrets(instance));
  if (result.status >= 400) throw new Error(`put-bytes ${args.path} returned ${result.status}`);
  return proof;
}

export async function rangeGet(args) {
  const instance = requireHealthy();
  const who = accountOf(instance, args.as);
  const result = await phoneRequest(instance, {
    method: "GET",
    path: args.path,
    token: who.token,
    asBytes: true,
    headers: { Range: args.range },
  });
  const proof = {
    as: args.as,
    path: args.path,
    range: args.range,
    status: result.status,
    bytes: result.bytes,
    contentRange: result.contentRange,
  };
  writeEvidence(instance.evidenceDir, nextApiEvidencePath(instance, args.as, "GET", args.path), proof, sessionSecrets(instance));
  if (result.status >= 400) throw new Error(`range-get ${args.path} returned ${result.status}`);
  return proof;
}

export function uuid() {
  const id = randomUUID();
  return { ok: true, uuid: id };
}

function adbSerial() {
  const serial = process.env.ANDROID_SERIAL;
  if (!serial)
    throw new Error(
      "ANDROID_SERIAL is unset. Point it at a dedicated emulator (emulator-5554), never a personal device.",
    );
  if (!serial.startsWith("emulator-"))
    throw new Error(`ANDROID_SERIAL=${serial} is not an emulator serial. Verification refuses physical devices.`);
  return serial;
}

function adb(serial, args, options = {}) {
  const result = spawnSync("adb", ["-s", serial, ...args], {
    encoding: "utf8",
    maxBuffer: 20_000_000,
    ...options,
  });
  if (result.status !== 0) throw new Error(`adb ${args.join(" ")} failed\n${result.stderr || result.stdout}`);
  return result;
}

function requireEmulator() {
  const serial = adbSerial();
  const qemu = spawnSync("adb", ["-s", serial, "shell", "getprop", "ro.kernel.qemu"], { encoding: "utf8" });
  if (qemu.stdout.trim() !== "1")
    throw new Error(`ANDROID_SERIAL=${serial} is not a qemu emulator. Refusing to drive it.`);
  const devices = spawnSync("adb", ["devices", "-l"], { encoding: "utf8" });
  if (!devices.stdout.includes(serial)) throw new Error(`adb does not list ${serial}\n${devices.stdout}`);
  return serial;
}

export function androidDoctor() {
  try {
    const serial = requireEmulator();
    const report = { ok: true, serial, qemu: true };
    try {
      const state = loadInstance();
      if (state.status === "healthy") writeEvidence(state.evidenceDir, "android-doctor.json", report);
    } catch {}
    return { status: 200, body: report };
  } catch (error) {
    return { status: 0, body: { ok: false, error: error.message }, gateFailed: true, reason: error.message };
  }
}

function dumpXml(serial) {
  adb(serial, ["shell", "uiautomator", "dump", "/sdcard/genoa-verify.xml"]);
  return adb(serial, ["exec-out", "cat", "/sdcard/genoa-verify.xml"]).stdout;
}

function parseNodes(xml) {
  return [...xml.matchAll(/<node\b[^>]*>/g)].map((match) => {
    const tag = match[0];
    const attr = (name) => {
      const found = tag.match(new RegExp(`${name}="([^"]*)"`));
      return found ? found[1] : "";
    };
    const bounds = attr("bounds").match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    return {
      text: attr("text"),
      desc: attr("content-desc"),
      x: bounds ? (Number(bounds[1]) + Number(bounds[3])) / 2 : 0,
      y: bounds ? (Number(bounds[2]) + Number(bounds[4])) / 2 : 0,
      bounds: attr("bounds"),
    };
  });
}

function dumpResult(xml) {
  const nodes = parseNodes(xml);
  return {
    status: 200,
    body: {
      xml,
      texts: nodes.map((node) => node.text).filter(Boolean),
      descriptions: nodes.map((node) => node.desc).filter(Boolean),
    },
  };
}

export function androidScreenshot(path) {
  const serial = requireEmulator();
  mkdirSync(dirname(resolve(path)), { recursive: true });
  const shot = spawnSync("adb", ["-s", serial, "exec-out", "screencap", "-p"], {
    encoding: "buffer",
    maxBuffer: 20_000_000,
  });
  if (shot.status !== 0) throw new Error("screencap failed");
  writeFileSync(path, shot.stdout);
  return { ok: true, path: resolve(path), bytes: shot.stdout.length, status: 200, body: { path: resolve(path), bytes: shot.stdout.length } };
}

export function androidDump(path) {
  const serial = requireEmulator();
  const xml = dumpXml(serial);
  mkdirSync(dirname(resolve(path)), { recursive: true });
  writeFileSync(path, xml);
  const result = dumpResult(xml);
  return { ok: true, path: resolve(path), nodes: result.body.texts.length, ...result };
}

export function androidTap(kind, value) {
  const serial = requireEmulator();
  const nodes = parseNodes(dumpXml(serial)).filter((node) =>
    kind === "desc" ? node.desc === value : node.text === value,
  );
  if (nodes.length !== 1)
    throw new Error(
      `Expected exactly one node with ${kind} ${JSON.stringify(value)}, found ${nodes.length}`,
    );
  adb(serial, ["shell", "input", "tap", String(nodes[0].x), String(nodes[0].y)]);
  return { ok: true, kind, value, x: nodes[0].x, y: nodes[0].y, status: 200, body: { x: nodes[0].x, y: nodes[0].y } };
}

export function androidType(text) {
  const serial = requireEmulator();
  adb(serial, ["shell", "input", "text", String(text).replaceAll(" ", "%s")]);
  return { ok: true, text, status: 200, body: { text } };
}

export async function wait(spec) {
  if (spec.kind === "ms") {
    sleep(spec.ms);
    return { ok: true, status: 200, body: { waited: spec.ms } };
  }
  const serial = requireEmulator();
  const deadline = Date.now() + spec.timeoutMs;
  let last = dumpResult("");
  while (Date.now() < deadline) {
    const xml = dumpXml(serial);
    last = dumpResult(xml);
    const hay = spec.kind === "until-text" ? last.body.texts : last.body.descriptions;
    const needle = spec.kind === "until-text" ? spec.text : spec.desc;
    if (hay.includes(needle)) return { ok: true, ...last };
    sleep(400);
  }
  throw new Error(`Timed out waiting for ${spec.kind === "until-text" ? spec.text : spec.desc}`);
}

function isReadOnly(command) {
  if (command.tag === "api" || command.tag === "unauth")
    return ["GET", "HEAD"].includes(command.method.toUpperCase());
  return ["range-get", "android-dump", "android-screenshot", "android-doctor", "wait"].includes(command.tag);
}

function validateClaims(recipe) {
  if (recipe.entry === "out-of-harness") return;
  if (!recipe.claims?.length) throw new Error(`${recipe.id} is missing proof claims.`);
  const index = new Map(recipe.steps.map((step, i) => [step.name, { step, i }]));
  for (const claim of recipe.claims) {
    const actionIndexes = claim.actions.map((id) => {
      const row = index.get(id);
      if (!row) throw new Error(`${recipe.id} claim action ${id} is not a step.`);
      return row.i;
    });
    const latestAction = Math.max(...actionIndexes);
    for (const id of claim.observations) {
      const row = index.get(id);
      if (!row) throw new Error(`${recipe.id} claim observation ${id} is not a step.`);
      if (!isReadOnly(row.step.command))
        throw new Error(`${recipe.id} claim observation ${id} is a mutation step.`);
      if (row.i <= latestAction)
        throw new Error(`${recipe.id} claim observation ${id} is not later than its actions.`);
    }
  }
}

async function runTagged(command, instance, bindings, attemptDir) {
  switch (command.tag) {
    case "signup":
      return performSignup(instance, { as: command.as, invite: command.invite });
    case "signin":
      return performSignin(instance, { as: command.as });
    case "api":
      return performApi(instance, {
        method: command.method,
        path: expand(command.path, bindings),
        auth: { kind: "as", name: command.as },
        body: command.body === undefined ? undefined : expand(command.body, bindings),
      });
    case "unauth":
      return performApi(instance, {
        method: command.method,
        path: expand(command.path, bindings),
        auth: { kind: "unauthenticated" },
        body: command.body === undefined ? undefined : expand(command.body, bindings),
      });
    case "put-bytes": {
      const path = expand(command.path, bindings);
      const bytes = loadBytes(command.source);
      const who = accountOf(instance, command.as);
      return phoneRequest(instance, {
        method: "PUT",
        path,
        token: who.token,
        raw: bytes,
        headers: {
          "Content-Type": "application/octet-stream",
          "Content-Length": String(bytes.byteLength),
        },
      });
    }
    case "range-get": {
      const who = accountOf(instance, command.as);
      return phoneRequest(instance, {
        method: "GET",
        path: expand(command.path, bindings),
        token: who.token,
        asBytes: true,
        headers: { Range: command.range },
      });
    }
    case "uuid": {
      const id = randomUUID();
      bindings[command.bind] = id;
      return { status: 200, body: { uuid: id } };
    }
    case "wait":
      return wait(command.spec);
    case "android-doctor":
      return androidDoctor();
    case "android-tap-desc":
      return androidTap("desc", expand(command.value, bindings));
    case "android-tap-text":
      return androidTap("text", expand(command.value, bindings));
    case "android-type":
      return androidType(expand(command.text, bindings));
    case "android-dump":
      return androidDump(join(attemptDir, command.name));
    case "android-screenshot":
      return androidScreenshot(join(attemptDir, command.name));
    default:
      throw new Error(`Unknown command tag ${command.tag}`);
  }
}

export async function runRecipe(recipe, instance, attemptDir, bindings) {
  for (const step of recipe.steps) {
    let result;
    try {
      result = await runTagged(step.command, instance, bindings, attemptDir);
      if (result.gateFailed) {
        return {
          ok: true,
          feature: recipe.id,
          verified: false,
          skipped: true,
          reason: result.reason,
          failedStep: step.name,
        };
      }
      if (step.bind) {
        for (const [slot, path] of Object.entries(step.bind)) {
          const value = readPath(result, path);
          if (value === undefined || value === null)
            throw new Error(`${step.name} did not bind ${slot} from ${path}`);
          bindings[slot] = String(value);
        }
      }
      for (const assertion of step.assert) evaluateAssertion(result, assertion, bindings);
      writeEvidence(
        attemptDir,
        `${step.name}.json`,
        {
          name: step.name,
          command: step.command,
          status: result.status,
          body: result.body,
          bytes: result.bytes,
          contentRange: result.contentRange,
        },
        sessionSecrets(instance, bindings),
      );
    } catch (error) {
      if (result)
        writeEvidence(
          attemptDir,
          `${step.name}.json`,
          {
            name: step.name,
            command: step.command,
            status: result.status,
            body: result.body,
            error: error.message,
          },
          sessionSecrets(instance, bindings),
        );
      return {
        ok: false,
        feature: recipe.id,
        verified: false,
        skipped: false,
        reason: error.message,
        failedStep: step.name,
      };
    }
  }
  return { ok: true, feature: recipe.id, verified: true, skipped: false };
}

export async function prove(featureId) {
  if (!featureId) return { ok: true, features: Object.keys(REGISTRY) };
  const recipe = REGISTRY[featureId];
  if (!recipe) throw new Error(`Unknown feature '${featureId}'`);
  if (recipe.entry === "out-of-harness") {
    return {
      ok: true,
      feature: recipe.id,
      verified: false,
      skipped: true,
      reason: recipe.reason,
    };
  }
  validateClaims(recipe);
  const current = loadInstance();
  if (current.status !== "absent") cleanup();
  let instance;
  try {
    instance = await launch();
  } catch (error) {
    cleanup();
    throw error;
  }
  if (recipe.entry === "android") {
    const gate = androidDoctor();
    if (gate.gateFailed) {
      return {
        ok: true,
        feature: recipe.id,
        verified: false,
        skipped: true,
        reason: gate.reason,
        runId: instance.runId,
      };
    }
  }
  const { attempt, dir } = nextAttemptDir(instance.runId, recipe.id);
  const bindings = {};
  try {
    const walked = await runRecipe(recipe, instance, dir, bindings);
    const report = {
      ...walked,
      feature: recipe.id,
      runId: instance.runId,
      attempt,
      evidenceDir: dir,
    };
    writeEvidence(dir, "summary.json", report, sessionSecrets(instance, bindings));
    return report;
  } catch (error) {
    const report = {
      ok: false,
      feature: recipe.id,
      verified: false,
      skipped: false,
      reason: error.message,
      runId: instance.runId,
      attempt,
      evidenceDir: dir,
    };
    writeEvidence(dir, "summary.json", report, sessionSecrets(instance, bindings));
    return report;
  }
}
