// Reads just enough of an ISO-BMFF file (via range reads) to confirm it has a track the apps can play.
// Sample tables for long recordings can be tens of megabytes, so boxes are walked, never loaded whole.
export type Reader = (offset: number, length: number) => Promise<Uint8Array>;
export interface Mp4Summary {
  video: string[];
  audio: string[];
  durationMs: number | null;
  fragmented: boolean;
}
interface Box {
  type: string;
  start: number;
  header: number;
  end: number;
}
const VIDEO = new Set(["avc1", "avc3", "hvc1", "hev1"]);
const AUDIO = new Set(["mp4a"]);
const MAX_BOXES = 512;

const fourcc = (b: Uint8Array, at: number) =>
  String.fromCharCode(b[at]!, b[at + 1]!, b[at + 2]!, b[at + 3]!);
const u32 = (b: Uint8Array, at: number) =>
  ((b[at]! << 24) >>> 0) + (b[at + 1]! << 16) + (b[at + 2]! << 8) + b[at + 3]!;
const u64 = (b: Uint8Array, at: number) => u32(b, at) * 2 ** 32 + u32(b, at + 4);

async function children(read: Reader, start: number, end: number, budget: { n: number }) {
  const boxes: Box[] = [];
  let offset = start;
  while (offset + 8 <= end) {
    if (--budget.n < 0) throw new Error("MP4_TOO_COMPLEX");
    const head = await read(offset, Math.min(16, end - offset));
    if (head.length < 8) break;
    let size = u32(head, 0),
      header = 8;
    if (size === 1) {
      if (head.length < 16) break;
      size = u64(head, 8);
      header = 16;
    } else if (size === 0) size = end - offset;
    if (size < header || offset + size > end) throw new Error("MP4_TRUNCATED");
    boxes.push({ type: fourcc(head, 4), start: offset, header, end: offset + size });
    offset += size;
  }
  return boxes;
}

// Each R2 range read is a billed operation with latency, so reads are served from 64 KiB blocks.
const BLOCK = 64 * 1024;
function blocks(read: Reader, size: number): Reader {
  const cache = new Map<number, Promise<Uint8Array>>();
  const block = (n: number) => {
    let hit = cache.get(n);
    if (!hit) {
      if (cache.size >= 16) cache.delete(cache.keys().next().value!);
      hit = read(n * BLOCK, Math.min(BLOCK, size - n * BLOCK));
      cache.set(n, hit);
    }
    return hit;
  };
  return async (offset, length) => {
    const end = Math.min(offset + length, size);
    if (end <= offset) return new Uint8Array(0);
    const out = new Uint8Array(end - offset);
    for (let n = Math.floor(offset / BLOCK); n * BLOCK < end; n++) {
      const data = await block(n);
      const from = Math.max(offset, n * BLOCK),
        to = Math.min(end, n * BLOCK + data.length);
      out.set(data.subarray(from - n * BLOCK, to - n * BLOCK), from - offset);
    }
    return out;
  };
}

export async function inspectMp4(source: Reader, size: number): Promise<Mp4Summary> {
  const read = blocks(source, size);
  const budget = { n: MAX_BOXES };
  const top = await children(read, 0, size, budget);
  if (top[0]?.type !== "ftyp") throw new Error("MP4_NO_FTYP");
  const moov = top.find((b) => b.type === "moov");
  if (!moov) throw new Error("MP4_NO_MOOV");
  const summary: Mp4Summary = { video: [], audio: [], durationMs: null, fragmented: false };
  for (const box of await children(read, moov.start + moov.header, moov.end, budget)) {
    if (box.type === "mvex") summary.fragmented = true;
    if (box.type === "mvhd") {
      const b = await read(box.start + box.header, 32);
      const [scale, duration] =
        b[0] === 1 ? [u32(b, 20), u64(b, 24)] : [u32(b, 12), u32(b, 16)];
      summary.durationMs = scale ? Math.round((duration / scale) * 1000) : null;
    }
    if (box.type !== "trak") continue;
    const mdia = (await children(read, box.start + box.header, box.end, budget)).find(
      (b) => b.type === "mdia",
    );
    if (!mdia) continue;
    const parts = await children(read, mdia.start + mdia.header, mdia.end, budget);
    const hdlr = parts.find((b) => b.type === "hdlr");
    const minf = parts.find((b) => b.type === "minf");
    if (!hdlr || !minf) continue;
    const handler = fourcc(await read(hdlr.start + hdlr.header + 8, 4), 0);
    const stbl = (await children(read, minf.start + minf.header, minf.end, budget)).find(
      (b) => b.type === "stbl",
    );
    const stsd =
      stbl &&
      (await children(read, stbl.start + stbl.header, stbl.end, budget)).find(
        (b) => b.type === "stsd",
      );
    if (!stsd) continue;
    // stsd: version/flags(4) entry_count(4), then the first sample entry's size(4) and format(4).
    const codec = fourcc(await read(stsd.start + stsd.header + 12, 4), 0);
    if (handler === "vide") summary.video.push(codec);
    if (handler === "soun") summary.audio.push(codec);
  }
  return summary;
}

export function playable(summary: Mp4Summary, kind: "video" | "audio") {
  const video = summary.video.some((c) => VIDEO.has(c));
  const audio = summary.audio.some((c) => AUDIO.has(c));
  const unknown = [...summary.video.filter((c) => !VIDEO.has(c)), ...summary.audio.filter((c) => !AUDIO.has(c))];
  if (unknown.length) return false;
  const timed = summary.fragmented || (summary.durationMs ?? 0) > 0;
  return timed && (kind === "video" ? video : audio && !summary.video.length);
}
