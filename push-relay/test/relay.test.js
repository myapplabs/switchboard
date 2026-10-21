import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { grantFor, grantValid, parseRegister, parseSend, sendFcm, tokenSource } from "../lib/relay.js";

const TOKEN = "fcm-token-" + "x".repeat(140);

test("grants bind a token to the relay's secret", () => {
  const g = grantFor("s3cret", TOKEN);
  assert.ok(grantValid("s3cret", TOKEN, g));
  assert.ok(!grantValid("other", TOKEN, g));
  assert.ok(!grantValid("s3cret", TOKEN + "y", g));
  assert.ok(!grantValid("s3cret", TOKEN, undefined));
});

test("register accepts android tokens only", () => {
  assert.equal(parseRegister({ token: TOKEN, platform: "android" }).token, TOKEN);
  assert.throws(() => parseRegister({ token: TOKEN, platform: "ios" }), { status: 422 });
  assert.throws(() => parseRegister({ token: "short", platform: "android" }), { status: 400 });
});

test("notices carry a kind and opaque ids only", () => {
  const ok = parseSend({ token: TOKEN, grant: "g", kind: "approval_needed", hub: "hh_1k5e7ibc5e4kvy0jxtt", session_id: "ses_1k5ea1d1n0z09kktn4x", approval_id: "apr_1k5eb2x3" });
  assert.deepEqual(ok.data, { kind: "approval_needed", hub: "hh_1k5e7ibc5e4kvy0jxtt", session_id: "ses_1k5ea1d1n0z09kktn4x", approval_id: "apr_1k5eb2x3" });
  assert.throws(() => parseSend({ token: TOKEN, grant: "g", kind: "approval_needed", hub: "hh_1k5e7ibc", text: "rm -rf" }), { error: "unknown field text" });
  assert.throws(() => parseSend({ token: TOKEN, grant: "g", kind: "chat_message", hub: "hh_1k5e7ibc" }), { status: 400 });
  assert.throws(() => parseSend({ token: TOKEN, grant: "g", kind: "run_finished", hub: "hh_1k5e7ibc", session_id: "Paul's trip" }), { error: "bad session_id" });
  const card = parseSend({ token: TOKEN, grant: "g", kind: "task_review", hub: "hh_1k5e7ibc5e4kvy0jxtt", task_id: "tsk_1k5f0abc12" });
  assert.deepEqual(card.data, { kind: "task_review", hub: "hh_1k5e7ibc5e4kvy0jxtt", task_id: "tsk_1k5f0abc12" });
});

test("fcm send: sent, or gone for unregistered tokens", async () => {
  const calls = [];
  const ok = async (url, init) => { calls.push({ url, body: JSON.parse(init.body) }); return new Response("{}", { status: 200 }); };
  assert.equal(await sendFcm("applabs-switchboard", "at", TOKEN, { kind: "run_finished", hub: "hh_1" }, ok), "sent");
  assert.equal(calls[0].url, "https://fcm.googleapis.com/v1/projects/applabs-switchboard/messages:send");
  assert.deepEqual(calls[0].body.message.data, { kind: "run_finished", hub: "hh_1" });
  assert.equal(calls[0].body.message.notification, undefined, "data only: the app writes the text");
  const gone = async () => new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
  assert.equal(await sendFcm("p", "at", TOKEN, {}, gone), "gone");
  await assert.rejects(sendFcm("p", "at", TOKEN, {}, async () => new Response("", { status: 500 })));
});

test("google access tokens are signed with the service account and cached", async () => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048, privateKeyEncoding: { type: "pkcs8", format: "pem" }, publicKeyEncoding: { type: "spki", format: "pem" } });
  let n = 0, t = 1_000_000;
  const fetchImpl = async (url, init) => {
    n++;
    assert.equal(url, "https://oauth2.googleapis.com/token");
    const jwt = new URLSearchParams(init.body).get("assertion").split(".");
    const claim = JSON.parse(Buffer.from(jwt[1], "base64url"));
    assert.equal(claim.scope, "https://www.googleapis.com/auth/firebase.messaging");
    return new Response(JSON.stringify({ access_token: "ya29.x", expires_in: 3600 }), { status: 200 });
  };
  const source = tokenSource({ client_email: "relay@p.iam.gserviceaccount.com", private_key: privateKey }, fetchImpl, () => t);
  assert.equal(await source(), "ya29.x");
  await source();
  assert.equal(n, 1, "cached");
  t += 3_600_000;
  await source();
  assert.equal(n, 2, "refreshed after expiry");
});
