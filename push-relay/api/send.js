import { grantValid, parseSend, sendFcm, tokenSource } from "../lib/relay.js";

// POST /api/send {token, grant, kind, hub, …ids} → 202 sent | 410 gone (drop the token) | 4xx invalid.
// Called by Switchboard hubs. Nothing is stored or logged beyond Vercel's request metadata.
let source = null;

export default async function handler(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  try {
    const { token, grant, data } = parseSend(req.body);
    if (!grantValid(process.env.RELAY_SECRET, token, grant)) return res.status(403).json({ error: "grant does not match token" });
    const account = JSON.parse(process.env.FCM_SERVICE_ACCOUNT);
    source ??= tokenSource(account);
    const result = await sendFcm(account.project_id, await source(), token, data);
    return result === "gone" ? res.status(410).json({ error: "token unregistered" }) : res.status(202).json({ ok: true });
  } catch (e) {
    return res.status(e.status ?? 502).json({ error: e.error ?? "could not deliver" });
  }
}
