// THE disk surface for captured chat photos. Only this file knows where the bytes physically live —
// db.ts stores a relative path, index.ts streams whatever this hands back. Same "one file owns the
// host resource" rule pg.ts follows for Postgres.
//
// Layout: one folder per chat bubble — see album.ts, which decides the names. This file only reads
// and writes whatever path it is handed.
//
// ⚠️ IMAGE_DIR must be a BIND-MOUNTED host directory in production, not a path inside the image.
// The database row holds only the path; an unmounted container writes into its own writable layer
// and every photo dies on the next `up --build` while its row survives — a gallery of 404s. On the
// home server it is /mnt/storage/tvl/iq-images mounted at /app/storage (see compose.yaml).
import { createHash } from "node:crypto";
import { mkdir, writeFile, stat } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";

const DEFAULT_DIR = "/app/storage";

export function imageDir(): string {
  return resolve(process.env.IMAGE_DIR || DEFAULT_DIR);
}

export const sha256 = (buf: Buffer): string => createHash("sha256").update(buf).digest("hex");

// A sha we are willing to touch the filesystem with. Anything else — "..", a slash, a shorter
// string — is rejected before it reaches a path, so a crafted GET can't read outside IMAGE_DIR.
export const isSha = (s: string): boolean => /^[0-9a-f]{64}$/.test(s);


// Absolute path for a stored relative path, refusing anything that escapes IMAGE_DIR even if a bad
// row somehow holds "../". Defence in depth behind isSha(), not instead of it.
export function absPathFor(rel: string): string {
  const root = imageDir();
  const abs = resolve(join(root, rel));
  if (abs !== root && !abs.startsWith(root + sep)) throw new Error("image path escapes IMAGE_DIR");
  return abs;
}

/**
 * Write the bytes at `rel` (a path relative to IMAGE_DIR), creating the folder as needed. Returns
 * the content hash and whether that exact file was already there — which is the normal case when the
 * phone re-uploads after dying mid-album, and is never an error.
 */
export async function storeImageAt(
  buf: Buffer, rel: string,
): Promise<{ hash: string; path: string; duplicate: boolean }> {
  const hash = sha256(buf);
  const abs = absPathFor(rel);
  try {
    const info = await stat(abs);
    // Same path AND same size: already written. Size alone is a weak check, but the name carries
    // the hash prefix and the caller derived the name from this very buffer.
    if (info.size === buf.length) return { hash, path: rel, duplicate: true };
  } catch { /* not there yet → write it */ }
  await mkdir(dirname(abs), { recursive: true });
  await writeFile(abs, buf);
  return { hash, path: rel, duplicate: false };
}

// Open a stored photo for streaming. Returns null when the row points at a file that is no longer on
// disk (the unmounted-volume symptom), so the caller can 404 instead of crashing the process.
export async function openImage(rel: string): Promise<{ stream: NodeJS.ReadableStream; size: number } | null> {
  let abs: string;
  try { abs = absPathFor(rel); } catch { return null; }
  try {
    const info = await stat(abs);
    if (!info.isFile()) return null;
    return { stream: createReadStream(abs), size: info.size };
  } catch {
    return null;
  }
}

// Cheap JPEG/PNG dimension read from the header — no image library, no decode. Returns nulls for
// anything it doesn't recognise; the panel only uses these to size a thumbnail box, so a miss costs
// layout polish, never the photo.
export function dimensions(buf: Buffer): { width: number | null; height: number | null } {
  // PNG: IHDR width/height are fixed offsets.
  if (buf.length > 24 && buf.readUInt32BE(0) === 0x89504e47) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  }
  // JPEG: walk the segment chain to the SOFn frame header.
  if (buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let i = 2;
    while (i + 9 < buf.length) {
      if (buf[i] !== 0xff) { i++; continue; }
      const marker = buf[i + 1];
      // SOF0..SOF15, skipping the non-frame markers in that range (DHT/JPG/DAC).
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
      }
      i += 2 + buf.readUInt16BE(i + 2);
    }
  }
  return { width: null, height: null };
}
