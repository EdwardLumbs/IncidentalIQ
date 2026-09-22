// How a bubble's photos are laid out ON DISK, and the PDF that sits beside them.
//
//   <IMAGE_DIR>/2026-09-22/TVL DOCUMENTATION - REPORTING/0925_Edcel-Prinsesa_8photos/
//       01_a3f9c1.jpg  02_7b2e44.jpg  …  08_5f0a77.jpg  album.pdf
//
// ONE FOLDER = ONE CHAT BUBBLE, because the folder is meant to be opened by a PERSON. The earlier
// layout stored everything flat under a month folder named by content hash, which is ideal for a
// machine and useless for anyone browsing: "0e7dbe9d….jpg" cannot tell you it is Edcel's 9:25 AM
// job sheet. Date → chat → bubble answers who/where/when without touching the database.
//
// The trade-off is deliberate: the same photo forwarded into two chats is now stored twice, once per
// bubble, where hash-naming stored it once. Each bubble's folder being self-contained (and its PDF
// complete) is worth more than the duplicate, at a few hundred KB a time.
import { PDFDocument } from "pdf-lib";
import { mkdir, writeFile, readFile, rename, stat } from "node:fs/promises";
import { join } from "node:path";
import { absPathFor } from "./images.js";

// Characters no filesystem should be asked to carry, plus the ones Windows refuses outright — the
// server is Linux, but these folders get copied to laptops and USB sticks.
const UNSAFE = /[\\/:*?"<>|\u0000-\u001f]/g;

/**
 * Make one path segment safe and still readable. Slashes become " - " rather than vanishing, so
 * "TVL DOCUMENTATION / REPORTING" stays legible instead of collapsing into one word.
 *
 * Note it does NOT try to tidy up the odd suffixes Messenger appends to some group names
 * ("…REPORTING6(p99"): they are part of how the chat is identified everywhere else in this system,
 * and guessing which characters are noise would eventually eat a real name.
 */
export function safeSegment(s: string, max = 60): string {
  const cleaned = (s || "")
    .replace(/\s*[\\/]\s*/g, " - ")   // path separators → a readable dash
    .replace(UNSAFE, "")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/[. ]+$/, "");           // a trailing dot or space breaks Windows copies
  const clipped = cleaned.slice(0, max).trim().replace(/[. ]+$/, "");
  return clipped || "unknown";
}

/** "Edcel Prinsesa" → "Edcel-Prinsesa", so the folder name reads as three distinct fields. */
const senderSegment = (s: string) => safeSegment(s, 40).replace(/\s+/g, "-") || "unknown";

/** PH date and time from a stored "2026-09-22T09:25:00+08:00" timestamp. */
function parts(ts: string): { date: string; hhmm: string } {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(ts || "");
  if (!m) {
    const d = new Date(Date.now() + 8 * 3600_000);
    const p = (n: number) => String(n).padStart(2, "0");
    return {
      date: `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`,
      hhmm: `${p(d.getUTCHours())}${p(d.getUTCMinutes())}`,
    };
  }
  return { date: `${m[1]}-${m[2]}-${m[3]}`, hhmm: `${m[4]}${m[5]}` };
}

/**
 * The folder for one bubble, relative to IMAGE_DIR. Single photos get the same shape as albums
 * ("1photo") so every bubble looks the same on disk and nothing has to be special-cased — by a
 * script, or by someone scrolling through a file manager.
 */
export function albumDir(meta: { group_name?: string; chat?: string; sender?: string | null; timestamp?: string; ts?: string }, count: number): string {
  const { date, hhmm } = parts(meta.timestamp ?? meta.ts ?? "");
  const chat = safeSegment(meta.group_name ?? meta.chat ?? "unknown chat");
  const who = senderSegment(meta.sender ?? "unknown");
  const n = Math.max(1, count);
  return `${date}/${chat}/${hhmm}_${who}_${n}photo${n === 1 ? "" : "s"}`;
}

/** "01_a3f9c1.jpg" — send order first so a plain alphabetical listing IS the right order. */
export const photoName = (seq: number, sha: string) =>
  `${String(Math.max(1, seq)).padStart(2, "0")}_${sha.slice(0, 6)}.jpg`;

export const PDF_NAME = "album.pdf";

/**
 * Move a bubble's folder when its photo count turns out to be higher than the first upload said
 * (the phone counts the tiles it can see, and a tall album needs scrolling). Renaming keeps the
 * label honest — a folder called "8photos" holding nine files is exactly the confusion this layout
 * exists to avoid.
 *
 * Returns the directory actually in use: the new one, or the old one if the move could not be done,
 * because a wrong label is better than a database row pointing at a folder that isn't there.
 */
export async function renameAlbumDir(oldRel: string, newRel: string): Promise<string> {
  if (oldRel === newRel) return newRel;
  try {
    await mkdir(absPathFor(newRel.split("/").slice(0, -1).join("/")), { recursive: true });
    await rename(absPathFor(oldRel), absPathFor(newRel));
    return newRel;
  } catch {
    return oldRel;
  }
}

/**
 * Build (or rebuild) the bubble's PDF from the photos currently on disk, one page per photo, pages
 * in send order. Rebuilt on EVERY upload for the bubble, so a photo that arrives late — a retry, a
 * phone that ran out of battery mid-album — is never missing from the document.
 *
 * Each page is exactly the image's own size, so nothing is scaled, cropped or re-encoded: the JPEG
 * bytes are embedded as they are. A job sheet photographed at 1536×2048 stays that sharp.
 */
export async function buildAlbumPdf(dirRel: string, fileNames: string[]): Promise<boolean> {
  if (!fileNames.length) return false;
  try {
    const pdf = await PDFDocument.create();
    let pages = 0;
    for (const name of fileNames) {
      const abs = absPathFor(join(dirRel, name));
      let bytes: Buffer;
      try {
        await stat(abs);
        bytes = await readFile(abs);
      } catch {
        continue;   // a photo whose file is missing must not sink the whole document
      }
      try {
        // JPEG is what both apps save; PNG is accepted because Messenger occasionally writes one.
        const img = bytes[0] === 0x89
          ? await pdf.embedPng(bytes)
          : await pdf.embedJpg(bytes);
        const page = pdf.addPage([img.width, img.height]);
        page.drawImage(img, { x: 0, y: 0, width: img.width, height: img.height });
        pages++;
      } catch {
        continue;   // unreadable/corrupt image — skip the page, keep the rest
      }
    }
    if (!pages) return false;
    await writeFile(absPathFor(join(dirRel, PDF_NAME)), await pdf.save());
    return true;
  } catch (e: any) {
    console.error(`could not build ${dirRel}/${PDF_NAME}:`, String(e?.message ?? e));
    return false;
  }
}
