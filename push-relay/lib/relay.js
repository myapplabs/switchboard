// Switchboard push relay — pure logic (no I/O except the injected fetch), tested in test/relay.test.js.
//
// Self-hosted hubs can't hold the app's FCM credentials (the hub is open source), so they send notices
// here. A notice carries no content: a kind, the hub, and opaque ids for the app to open; the app then
// fetches the details from its own hub. A phone first registers its token and gets a grant (an HMAC of
// the token); hubs must present it, so the relay can only reach phones that asked for it.
import { createHmac, createSign, timingSafeEqual } from "node:crypto";

export const KINDS = new Set(["approval_needed", "question_asked", "run_finished", "job_failed", "member_joined", "task_needs_you", "task_review"]);
const ID = /^[a-z]{2,6}_[a-z0-9]{6,40}$/;
const MAX_TOKEN = 4096;

export function grantFor(secret, token) {
  return createHmac("sha256", secret).update("v1:" + token).digest("base64url");
}

export function grantValid(secret, token, grant) {
  if (typeof grant !== "string" || grant.length > 128) return false;
  const want = Buffer.from(grantFor(secret, token));
  const got = Buffer.from(grant);
  return want.length === got.length && timingSafeEqual(want, got);
}

/** Validate a register body; returns {token, platform} or throws {status, error}. */
export function parseRegister(body) {
  const token = body?.token, platform = body?.platform;
  if (typeof token !== "string" || token.length < 20 || token.length > MAX_TOKEN) throw { status: 400, error: "token required" };
  if (platform !== "android") throw { status: 422, error: "only android is supported for now" };
  return { token, platform };
}

/** Validate a send body; returns the FCM data payload pieces or throws {status, error}. Unknown fields are rejected. */
export function parseSend(body) {
  if (!body || typeof body !== "object") throw { status: 400, error: "json body required" };
  const allowed = new Set(["token", "grant", "kind", "hub", "session_id", "run_id", "approval_id", "job_id", "agent_id", "task_id"]);
  for (const k of Object.keys(body)) if (!allowed.has(k)) throw { status: 400, error: `unknown field ${k}` };
  const { token, grant, kind, hub } = body;
  if (typeof token !== "string" || token.length < 20 || token.length > MAX_TOKEN) throw { status: 400, error: "token required" };
  if (!KINDS.has(kind)) throw { status: 400, error: "unknown kind" };
  if (typeof hub !== "string" || !/^[a-z]{2,6}_[a-z0-9]{6,40}$/.test(hub)) throw { status: 400, error: "hub id required" };
  const data = { kind, hub };
  for (const k of ["session_id", "run_id", "approval_id", "job_id", "agent_id", "task_id"]) {
    if (body[k] == null) continue;
    if (typeof body[k] !== "string" || !(ID.test(body[k]) || (k === "job_id" && /^[A-Za-z0-9_-]{1,64}$/.test(body[k])))) throw { status: 400, error: `bad ${k}` };
    data[k] = body[k];
  }
  return { token, grant, data };
}

/** A Google OAuth access token for FCM from a service account (JWT bearer flow), cached until near expiry. */
export function tokenSource(serviceAccount, fetchImpl = fetch, now = () => Date.now()) {
  let cached = null;
  return async () => {
    if (cached && cached.expires - 60_000 > now()) return cached.token;
    const iat = Math.floor(now() / 1000);
    const head = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
    const claim = Buffer.from(JSON.stringify({
      iss: serviceAccount.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token", iat, exp: iat + 3600,
    })).toString("base64url");
    const sig = createSign("RSA-SHA256").update(`${head}.${claim}`).sign(serviceAccount.private_key).toString("base64url");
    const r = await fetchImpl("https://oauth2.googleapis.com/token", {
      method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
      body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${head}.${claim}.${sig}`,
    });
    if (!r.ok) throw new Error(`google token: HTTP ${r.status}`);
    const j = await r.json();
    cached = { token: j.access_token, expires: now() + j.expires_in * 1000 };
    return cached.token;
  };
}

/** Send one data message. Returns "sent", "gone" (token no longer valid: the hub drops it) or throws. */
export async function sendFcm(projectId, accessToken, token, data, fetchImpl = fetch) {
  const r = await fetchImpl(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST", headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "high", ttl: "86400s" } } }),
  });
  if (r.ok) return "sent";
  const text = await r.text();
  if (r.status === 404 || text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) return "gone";
  throw new Error(`fcm: HTTP ${r.status}`);
}
