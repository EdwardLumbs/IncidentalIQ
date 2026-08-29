// THE host-port surface for Postgres. Only this file touches `pg` directly — db.ts calls the four
// helpers below and never sees a Pool or a Client.
//
// Same shape as tvl-trip-monitoring's server/database/pg.js (all/get/run/tx) on purpose — this repo
// and that one now read the database the same way.
//
// Everything this app owns lives under the `incidentaliq` schema in the shared `tvl`/`tvl_dev`
// database, qualified explicitly in every query (`incidentaliq.captured_messages`, etc.) rather than
// via search_path — same reasoning trip-monitoring used for the `mail` schema: what a query touches
// should be visible in the query, not implied by connection state.
import pg from "pg";
const { Pool } = pg;
const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
    console.error("DATABASE_URL is not set. The app cannot start without it.\n" +
        "Add it to backend/.env — points at the shared tvl/tvl_dev Postgres database.");
    process.exit(1);
}
const pool = new Pool({
    connectionString,
    // Small on purpose — one dispatch office's incidental feed, not a public site.
    max: 10,
    connectionTimeoutMillis: 10_000,
    idleTimeoutMillis: 30_000,
});
// A pooled connection can die while idle (server reboot, Tailscale drop). Without this listener that
// surfaces as an unhandled 'error' event and takes the whole process down.
pool.on("error", (err) => {
    console.error("[pg] idle client error:", err.message);
});
export async function all(sql, params = []) {
    const { rows } = await pool.query(sql, params);
    return rows;
}
// Single row, or undefined — matches D1's `.first()` behavior db.ts already assumes.
export async function get(sql, params = []) {
    const { rows } = await pool.query(sql, params);
    return rows[0];
}
export async function run(sql, params = []) {
    return pool.query(sql, params);
}
// Multi-statement atomic write, one checked-out client (matches trip-monitoring's tx()).
export async function tx(fn) {
    const client = await pool.connect();
    try {
        await client.query("BEGIN");
        const result = await fn({
            all: async (sql, params = []) => (await client.query(sql, params)).rows,
            get: async (sql, params = []) => (await client.query(sql, params)).rows[0],
            run: (sql, params = []) => client.query(sql, params),
        });
        await client.query("COMMIT");
        return result;
    }
    catch (err) {
        try {
            await client.query("ROLLBACK");
        }
        catch { /* connection already gone */ }
        throw err;
    }
    finally {
        client.release();
    }
}
export { pool };
