import { grantFor, parseRegister } from "../lib/relay.js";

// POST /api/register {token, platform} → {grant}. Called by the Switchboard app with its FCM token.
export default async function handler(req, res) {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  try {
    const { token } = parseRegister(req.body);
    return res.status(200).json({ grant: grantFor(process.env.RELAY_SECRET, token) });
  } catch (e) {
    return res.status(e.status ?? 500).json({ error: e.error ?? "relay error" });
  }
}
