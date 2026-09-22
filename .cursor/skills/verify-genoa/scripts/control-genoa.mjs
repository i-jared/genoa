#!/usr/bin/env node
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  androidDoctor,
  androidDump,
  androidScreenshot,
  androidTap,
  androidType,
  api,
  prove,
  putBytes,
  rangeGet,
  signin,
  signup,
  uuid,
  wait,
} from "./commands.mjs";
import { cleanup, doctor, launch, loadInstance } from "./instance.mjs";

const FLAG_VALUES = new Set([
  "--as",
  "--body",
  "--fixture",
  "--file",
  "--range",
  "--ms",
  "--until-text",
  "--until-desc",
  "--timeout-ms",
  "--invite",
]);
const FLAG_BOOL = new Set(["--unauthenticated"]);

export function parseArgs(argv) {
  const positional = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith("--")) {
      if (FLAG_BOOL.has(arg)) {
        flags[arg.slice(2)] = true;
        continue;
      }
      if (!FLAG_VALUES.has(arg)) throw new Error(`Unknown flag ${arg}`);
      const value = argv[++i];
      if (value === undefined) throw new Error(`${arg} requires a value`);
      flags[arg.slice(2)] = value;
      continue;
    }
    positional.push(arg);
  }
  if (flags.as && flags.unauthenticated)
    throw new Error("--as and --unauthenticated cannot be combined.");
  const name = positional.shift();
  if (!name) throw new Error(usageText());
  if (name === "launch" || name === "doctor" || name === "cleanup" || name === "android-doctor" || name === "uuid")
    return { name };
  if (name === "signup") {
    if (!flags.as) throw new Error("signup requires --as <name>");
    return { name, as: flags.as, invite: flags.invite };
  }
  if (name === "signin") {
    if (!flags.as) throw new Error("signin requires --as <name>");
    return { name, as: flags.as };
  }
  if (name === "api") {
    const method = positional.shift();
    const path = positional.shift();
    if (!method || !path) throw new Error("api requires METHOD PATH");
    if (!flags.as && !flags.unauthenticated) throw new Error("api requires --as <name> or --unauthenticated");
    let body;
    if (flags.body !== undefined) {
      try {
        body = JSON.parse(flags.body);
      } catch {
        throw new Error(`Invalid JSON body: ${flags.body}`);
      }
    }
    return {
      name,
      method,
      path,
      auth: flags.unauthenticated ? { kind: "unauthenticated" } : { kind: "as", name: flags.as },
      body,
    };
  }
  if (name === "put-bytes") {
    const path = positional.shift();
    if (!path) throw new Error("put-bytes requires PATH");
    if (!flags.as) throw new Error("put-bytes requires --as <name>");
    if (flags.fixture && flags.file) throw new Error("put-bytes takes --fixture or --file, not both");
    if (!flags.fixture && !flags.file) throw new Error("put-bytes requires --fixture or --file");
    if (flags.fixture && !["jpeg", "png", "ftyp", "ts"].includes(flags.fixture))
      throw new Error("put-bytes --fixture must be jpeg, png, ftyp, or ts");
    return {
      name,
      path,
      as: flags.as,
      source: flags.fixture ? { kind: "fixture", id: flags.fixture } : { kind: "file", path: flags.file },
    };
  }
  if (name === "range-get") {
    const path = positional.shift();
    if (!path) throw new Error("range-get requires PATH");
    if (!flags.as) throw new Error("range-get requires --as <name>");
    if (!flags.range) throw new Error("range-get requires --range");
    return { name, path, as: flags.as, range: flags.range };
  }
  if (name === "wait") {
    const timeoutMs = Number(flags["timeout-ms"] ?? 8000);
    if (flags.ms) return { name, spec: { kind: "ms", ms: Number(flags.ms) } };
    if (flags["until-text"])
      return { name, spec: { kind: "until-text", text: flags["until-text"], timeoutMs } };
    if (flags["until-desc"])
      return { name, spec: { kind: "until-desc", desc: flags["until-desc"], timeoutMs } };
    throw new Error("wait requires --ms N or --until-text/--until-desc");
  }
  if (name === "android-screenshot" || name === "android-dump") {
    const path = positional.shift();
    if (!path) throw new Error(`${name} requires a path`);
    return { name, path };
  }
  if (name === "android-tap-desc" || name === "android-tap-text") {
    const value = positional.join(" ");
    if (!value) throw new Error(`${name} requires a value`);
    return { name, value };
  }
  if (name === "android-type") {
    const text = positional.join(" ");
    if (!text) throw new Error("android-type requires text");
    return { name, text };
  }
  if (name === "prove") return { name, feature: positional.shift() };
  throw new Error(usageText());
}

function usageText() {
  return `Usage: control-genoa <command>
  launch
  doctor
  signup --as <name>
  signin --as <name>
  api <METHOD> <PATH> --as <name> [--body <json>]
  api <METHOD> <PATH> --unauthenticated [--body <json>]
  put-bytes <PATH> --as <name> --fixture jpeg|png|ftyp|ts | --file <path>
  range-get <PATH> --as <name> --range bytes=N-M
  uuid
  wait --ms N | --until-text T | --until-desc D [--timeout-ms N]
  android-doctor
  android-screenshot <path>
  android-dump <path>
  android-tap-desc <content-description>
  android-tap-text <visible-text>
  android-type <text>
  prove [<feature-id>]
  cleanup`;
}

function print(value, pretty = false) {
  console.log(JSON.stringify(value, null, pretty ? 2 : 0));
}

export async function main(argv) {
  const command = parseArgs(argv);
  let result;
  switch (command.name) {
    case "launch": {
      const before = (() => {
        try {
          return loadInstance();
        } catch {
          return { status: "absent" };
        }
      })();
      const instance = await launch();
      result = {
        ok: true,
        reused: before.status === "healthy",
        runId: instance.runId,
        baseUrl: instance.baseUrl,
        evidenceDir: instance.evidenceDir,
        pid: instance.pid,
      };
      break;
    }
    case "doctor":
      result = await doctor();
      print(result, true);
      if (!result.ok) process.exit(1);
      return;
    case "cleanup":
      result = cleanup();
      break;
    case "signup":
      result = await signup(command);
      print(result, true);
      return;
    case "signin":
      result = await signin(command);
      print(result, true);
      return;
    case "api":
      result = await api(command);
      print(result, true);
      return;
    case "put-bytes":
      result = await putBytes(command);
      break;
    case "range-get":
      result = await rangeGet(command);
      break;
    case "uuid":
      result = uuid();
      break;
    case "wait":
      result = await wait(command.spec);
      break;
    case "android-doctor":
      result = androidDoctor();
      if (result.gateFailed) {
        print({ ok: false, error: result.reason });
        process.exit(1);
      }
      print(result.body, true);
      return;
    case "android-screenshot":
      result = androidScreenshot(command.path);
      break;
    case "android-dump":
      result = androidDump(command.path);
      break;
    case "android-tap-desc":
      result = androidTap("desc", command.value);
      break;
    case "android-tap-text":
      result = androidTap("text", command.value);
      break;
    case "android-type":
      result = androidType(command.text);
      break;
    case "prove":
      result = await prove(command.feature);
      break;
    default:
      throw new Error(usageText());
  }
  print(result, command.name === "cleanup");
  if (result?.skipped) process.exit(2);
  if (result?.ok === false) process.exit(1);
}

const invoked =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invoked) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    const payload = error.payload ?? { ok: false, error: error.message };
    if (payload.ok === undefined) payload.ok = false;
    print(payload, true);
    process.exit(1);
  }
}
