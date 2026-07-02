# Trip Ops Incidental Monitor — Backend

Cloudflare Worker + D1 + Groq. Receives captured messages from the phone, stores them, and every
6 hours classifies them per-chat with Groq (batched, with a rolling summary + trip registry for
cross-batch memory). All free-tier.

## Endpoints
| Method | Path | Purpose |
|---|---|---|
| `POST` | `/messages` | Phone uploads a batch: `{ "messages": [ ... ] }` (accepts the phone's `captured_messages.jsonl` shape directly). |
| `GET`  | `/incidentals` | Query classified incidentals. Filters: `?trip=`, `?status=confirmed\|possible`, `?limit=`. |
| `POST` | `/run` | Manually trigger the classifier (same work the cron does) — for testing. |
| `GET`  | `/health` | Liveness check. |

The cron (`0 0,6,12,18 * * *`) runs the classifier automatically.

## Local development (no Cloudflare account needed)
```bash
cd backend
npm install
cp .dev.vars.example .dev.vars       # then paste your Groq key into .dev.vars
npm run db:local                     # create the tables in the local D1 file
npm run seed:local                   # (optional) load sample messages
npm run dev                          # http://localhost:8787
```
Test it:
```bash
# upload messages
curl -X POST localhost:8787/messages -H "content-type: application/json" \
  -d '{"messages":[{"source":"VIBER","chat":"TVL X BEST","sender":"R. Ando","content":"nag lalamove kami ng selyo","ts":"2026-07-02T04:00:00"}]}'

# run the classifier now (instead of waiting for the cron)
curl -X POST localhost:8787/run

# see results
curl localhost:8787/incidentals
```

## Deploy to the cloud (needs a free Cloudflare account)
```bash
npx wrangler login                          # opens browser once
npx wrangler d1 create tripops              # copy the printed database_id into wrangler.toml
npm run db:remote                           # create tables in the cloud DB
npx wrangler secret put GROQ_API_KEY        # paste your Groq key (never in the repo)
npm run deploy                              # → https://tripops-monitor.<you>.workers.dev
```

## Notes
- `GROQ_MODEL` (in `wrangler.toml`) defaults to `llama-3.3-70b-versatile`. Switch to
  `openai/gpt-oss-120b` there if desired (separate rate-limit bucket).
- Dedup is durable: `content_hash = sha256(source|group|message)`, collapsed within a 10-min window.
- The classifier logic mirrors `test/classifier.mjs` (the proven reference), extended with the
  situation summary + trip registry.
- `seed.sql` is LOCAL-ONLY test data (`--local`); it never reaches the cloud.
