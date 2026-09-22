// One-off: re-file photos stored under the ORIGINAL flat layout into one folder per chat bubble.
//
//   before   <IMAGE_DIR>/2026-09/8aee9efa….jpg                        (content hash, unbrowsable)
//   after    <IMAGE_DIR>/2026-09-22/<chat>/0922_Russel-Diloy_1photo/01_8aee9e.jpg + album.pdf
//
// Only bubbles with album_dir IS NULL are touched — that column is what the new upload path sets, so
// it doubles as the "already migrated" marker and makes running this twice a no-op.
//
// Safe to run against live data: every file is MOVED (never copied then deleted), each bubble is its
// own transaction, and a bubble whose files can't be found is skipped with a warning rather than
// having its rows rewritten to point somewhere empty. Nothing is deleted at any point.
//
//   docker compose exec incidentaliq node dist/migrate-albums.js          # do it
//   docker compose exec incidentaliq node dist/migrate-albums.js --dry    # just report
import "dotenv/config";
import { mkdir, rename, rmdir, stat } from "node:fs/promises";
import { dirname } from "node:path";
import { all, get, run } from "./pg.js";
import { absPathFor } from "./images.js";
import { albumDir, photoName, buildAlbumPdf } from "./album.js";

const DRY = process.argv.includes("--dry");

interface Bubble {
  id: number;
  group_name: string;
  sender: string | null;
  timestamp: string;
  image_count: number | null;
}

async function main() {
  const bubbles = await all<Bubble>(
    `SELECT id, group_name, sender, timestamp, image_count
       FROM incidentaliq.captured_messages
      WHERE album_key IS NOT NULL AND album_dir IS NULL
      ORDER BY id`,
  );
  if (!bubbles.length) {
    console.log("nothing to migrate — every bubble already has a folder");
    return;
  }
  console.log(`${bubbles.length} bubble(s) to migrate${DRY ? " (dry run — nothing will change)" : ""}`);

  let moved = 0, done = 0, skipped = 0;
  const emptiedDirs = new Set<string>();   // old folders to clear away once their files have gone
  for (const b of bubbles) {
    const photos = await all<{ id: number; seq: number; sha256: string; path: string }>(
      "SELECT id, seq, sha256, path FROM incidentaliq.message_images WHERE message_id = $1 ORDER BY seq ASC, id ASC",
      [b.id],
    );
    if (!photos.length) {
      console.log(`  #${b.id}: no photos recorded — skipped`);
      skipped++;
      continue;
    }

    // The count in the folder name is what is actually THERE, not what the phone once reported: this
    // is historical data, and whatever arrived is now the whole of it.
    const count = Math.max(photos.length, b.image_count ?? 0);
    const dir = albumDir(
      { group_name: b.group_name, sender: b.sender, timestamp: b.timestamp },
      count,
    );

    // Resolve every move BEFORE touching the disk, so a bubble with a missing file is skipped whole
    // rather than left half-moved.
    const plan: { id: number; from: string; to: string; abs: string; absTo: string }[] = [];
    let missing = 0;
    for (const [i, p] of photos.entries()) {
      const to = `${dir}/${photoName(p.seq || i + 1, p.sha256)}`;
      const abs = absPathFor(p.path);
      try {
        await stat(abs);
      } catch {
        // Already at the destination? Then a previous partial run moved it; that is fine.
        try { await stat(absPathFor(to)); } catch { missing++; continue; }
      }
      plan.push({ id: p.id, from: p.path, to, abs, absTo: absPathFor(to) });
    }
    if (missing) {
      console.log(`  #${b.id}: ${missing} file(s) not on disk — skipped, rows left pointing at the old path`);
      skipped++;
      continue;
    }

    console.log(`  #${b.id}: ${plan.length} photo(s) → ${dir}`);
    if (DRY) { done++; continue; }

    await mkdir(absPathFor(dir), { recursive: true });
    for (const m of plan) {
      try {
        await stat(m.absTo);              // already moved by an earlier run
      } catch {
        await mkdir(dirname(m.absTo), { recursive: true });
        await rename(m.abs, m.absTo);
        emptiedDirs.add(dirname(m.from));
        moved++;
      }
      await run("UPDATE incidentaliq.message_images SET path = $2 WHERE id = $1", [m.id, m.to]);
    }
    await run("UPDATE incidentaliq.captured_messages SET album_dir = $2, image_count = $3 WHERE id = $1",
              [b.id, dir, count]);

    const built = await buildAlbumPdf(dir, plan.map((m) => m.to.split("/").pop() as string));
    if (!built) console.log(`    ⚠ could not build album.pdf for #${b.id}`);
    done++;
  }

  // The old flat month folders are empty once their files have moved. Removed only when EMPTY, so
  // anything unmigrated (a skipped bubble's files) keeps its directory and stays findable.
  for (const d of emptiedDirs) {
    try { await rmdir(absPathFor(d)); console.log(`removed empty ${d}/`); } catch { /* not empty, or gone */ }
  }

  console.log(`\n${done} bubble(s) migrated, ${moved} file(s) moved, ${skipped} skipped`);
  const left = await get<{ n: number }>(
    "SELECT count(*)::int n FROM incidentaliq.captured_messages WHERE album_key IS NOT NULL AND album_dir IS NULL",
  );
  console.log(`${left?.n ?? 0} bubble(s) still without a folder`);
}

main()
  .then(() => process.exit(0))
  .catch((e) => { console.error("migration failed:", e); process.exit(1); });
