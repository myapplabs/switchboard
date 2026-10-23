# switchboard-push-relay

Self-hosted Switchboard hubs can't hold the app's push credentials, since anyone can run a hub. They send
**content-free notices** here instead, and this function delivers them through Firebase Cloud Messaging
(Android; iOS through APNs is planned). AppLabs runs the official relay for the official app at
`https://switchboard-push-relay.vercel.app`; a fork with its own app deploys its own.

- **No content.** A notice is a kind (`approval_needed`, `question_asked`, `run_finished`, `job_failed`,
  `job_finished`, `member_joined`, `task_needs_you`, `task_review`), the hub's id, and opaque ids to open (session, run,
  approval, job, agent, task). Text, names
  and any unknown field are rejected. The app shows a generic notification ("Approval needed") and gets
  the details from its own hub.
- **Only to phones that asked.** The app registers its FCM token (`POST /api/register`) and receives a
  *grant*: an HMAC of the token under `RELAY_SECRET`. It gives the grant to its hub, and the hub must
  present it with each notice (`POST /api/send`). Without it the relay refuses (403), so it can't be used
  to reach arbitrary devices.
- **Nothing is stored.** A token the FCM service reports as gone answers 410, and the hub forgets it.

## Deploy your own (Vercel free plan)

1. **Firebase:** your app's project → Project settings → Service accounts → *Generate new private key*.
   Keep the file safe: it's the only secret.
2. **Vercel:**
   ```bash
   cd push-relay
   npx vercel link                                  # new project, e.g. switchboard-push-relay
   npx vercel env add FCM_SERVICE_ACCOUNT production < ~/Downloads/<project>-firebase-adminsdk-*.json
   openssl rand -base64 32 | npx vercel env add RELAY_SECRET production
   npx vercel deploy --prod
   rm ~/Downloads/<project>-firebase-adminsdk-*.json   # the key now lives only in Vercel
   ```
3. Set the relay URL in each hub's `config.json` (`pushRelayUrl`).

Changing `RELAY_SECRET` makes every existing grant invalid; apps register again the next time they start.

## Test

`npm test` (Node 20+, no dependencies).
