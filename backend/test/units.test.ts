import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const here = dirname(fileURLToPath(import.meta.url));
import { inspectMp4, playable } from "../src/mp4";
import { sweepOrphanedMedia } from "../src/jobs";
import type { Env } from "../src/types";

const fixture = (name: string) =>
  new Uint8Array(readFileSync(join(here, "fixtures", name)));
const reader = (bytes: Uint8Array) => {
  let reads = 0;
  return {
    read: async (offset: number, length: number) => {
      reads++;
      return bytes.slice(offset, offset + length);
    },
    get reads() {
      return reads;
    },
  };
};

test("MP4 inspection accepts playable recordings wherever the movie box is", async () => {
  for (const name of ["tiny-video.mp4", "tiny-video-moov-last.mp4", "tiny-video-fragmented.mp4"]) {
    const bytes = fixture(name);
    const r = reader(bytes);
    const summary = await inspectMp4(r.read, bytes.length);
    assert.deepEqual(summary.video, ["avc1"], name);
    assert.deepEqual(summary.audio, ["mp4a"], name);
    assert.ok(playable(summary, "video"), name);
    assert.ok(!playable(summary, "audio"), `${name} has video, so it is not a voice message`);
    // Walking boxes, not downloading the file: a handful of small reads.
    assert.ok(r.reads <= 3, `${name} took ${r.reads} reads`);
  }
  const voice = fixture("synthetic-speech.m4a");
  const summary = await inspectMp4(reader(voice).read, voice.length);
  assert.ok(playable(summary, "audio"));
  assert.ok(!playable(summary, "video"));
  assert.ok((summary.durationMs ?? 0) > 1000);
});

test("MP4 inspection rejects files that only look like MP4", async () => {
  const video = fixture("tiny-video.mp4");
  // Magic bytes alone used to pass: "....ftyp" followed by nothing playable.
  const fake = new Uint8Array(64);
  fake.set([0, 0, 0, 16], 0);
  fake.set(new TextEncoder().encode("ftypisom"), 4);
  await assert.rejects(inspectMp4(reader(fake).read, fake.length));
  // Truncated upload: the declared box sizes run past the end of the data.
  const truncated = video.slice(0, Math.floor(video.length / 2));
  await assert.rejects(inspectMp4(reader(truncated).read, truncated.length));
  // An unsupported codec is refused even when the structure is valid.
  const renamed = video.slice();
  const text = new TextDecoder("latin1").decode(renamed);
  renamed.set(new TextEncoder().encode("vp09"), text.indexOf("avc1", text.indexOf("stsd")));
  const summary = await inspectMp4(reader(renamed).read, renamed.length);
  assert.ok(!playable(summary, "video"));
});

test("orphan sweep removes only unreferenced or deleted media old enough not to be in flight", async () => {
  const day = 86400000,
    now = Date.now();
  const objects = [
    { key: "media/live-msg/original", age: 2 * day }, // referenced asset: keep
    { key: "media/deleted-msg/original", age: 2 * day }, // message deleted: remove
    { key: "media/missing-msg/original", age: 2 * day }, // no message row: remove
    { key: "media/missing-new/original", age: 60000 }, // too new, may be in flight: keep
    { key: "media/live-msg/stray", age: 2 * day }, // unreferenced leftover: remove
    { key: "media/live-msg/live/init.mp4", age: 2 * day }, // live init has no table row: keep
  ];
  const deletedKeys: string[] = [];
  const states: Record<string, string> = { "live-msg": "ready", "deleted-msg": "deleted" };
  const referenced = new Set(["media/live-msg/original", "media/deleted-msg/original"]);
  const maintenance = new Map<string, string>();
  const statement = (sql: string) => ({
    bind: (...args: unknown[]) => ({
      first: async () => {
        if (sql.startsWith("SELECT value FROM maintenance"))
          return maintenance.has(String(args[0])) ? { value: maintenance.get(String(args[0])) } : null;
        if (sql.startsWith("SELECT state FROM messages"))
          return states[String(args[0])] ? { state: states[String(args[0])] } : null;
        return referenced.has(String(args[0])) ? { 1: 1 } : null;
      },
      run: async () => {
        maintenance.set(String(args[0]), String(args[1]));
      },
    }),
  });
  const env = {
    DB: { prepare: statement },
    MEDIA: {
      list: async () => ({
        objects: objects.map((o) => ({ key: o.key, uploaded: new Date(now - o.age) })),
        truncated: false,
      }),
      delete: async (key: string) => {
        deletedKeys.push(key);
      },
    },
  } as unknown as Env;
  assert.equal(await sweepOrphanedMedia(env, now), 3);
  assert.deepEqual(deletedKeys.sort(), [
    "media/deleted-msg/original",
    "media/live-msg/stray",
    "media/missing-msg/original",
  ]);
  assert.equal(maintenance.get("media_sweep_cursor"), "", "a finished pass restarts from the beginning");
});
