# Privacy Policy

Effective date: October 22, 2026

This Privacy Policy explains what information the Switchboard mobile app (the "App"), the website at switchboard.applabs.llc (the "Website") and the notification relay that AppLabs runs for the App (the "Relay") process, who processes it, and the choices you have. "AppLabs", "we" and "us" mean AppLabs, LLC.

## The short version

- Switchboard is self-hosted. Your conversations, account and settings live on a hub that you, or the person who invited you, run. AppLabs doesn't receive them and can't see them.
- The App has no analytics, advertising, tracking or crash-reporting tools. We don't sell or share personal information, and we don't track you across apps or websites.
- AppLabs handles only what's needed to deliver notifications to your phone. Notifications carry no message content, and the Relay doesn't store them.

## 1. How Switchboard is built

Switchboard has three parts. The **hub** is open-source server software run by its owner (the "hub owner"), on their own computer. The **App** connects to one hub. The **agents** are AI software, such as Hermes Agent, that the hub owner connects to the hub; they use AI models from providers the hub owner chooses.

Because the hub owner runs the hub, the hub owner decides what is kept on it and is responsible for that information, not AppLabs. If you were invited to someone else's hub, ask its owner about how they handle your information.

## 2. Information kept on your hub (not by AppLabs)

The hub stores the following on the hub owner's computer. AppLabs has no access to it.

- **Account**: your display name, your role, and the public part of your passkey. The hub never receives your passkey's private key.
- **Signed-in devices**: each device's name (such as "Pixel 10"), platform, when it was last used, and its notification token and grant if notifications are on. Sign-in tokens are stored only as hashes.
- **Conversations**: the messages you and agents send, their titles, the model used, approvals and answers, and results of scheduled jobs.
- **Files and photos you send**: stored on the hub with their conversation, and deleted with it. Photos are made smaller on your device before they're sent. Files you add but don't send are deleted after a day.
- **Agents and permissions**: the agents added, who may use them, with which profiles, models and daily limits. Agent keys are encrypted (AES-256-GCM).
- **Scheduled jobs and the task board**: jobs, their run history, and cards and their steps.
- **Activity log**: who did what and when (for example, who approved a request), without message content. It's kept for 90 days.

The hub also sends your messages to the agents you use, together with the files and photos you send: photos inside the message, and every file as a private download link that works for 7 days. Agents, and the AI providers they use, process them under their own terms and privacy policies, which the hub owner chose.

**How long.** Information stays on the hub until you or the hub owner delete it, except the activity log (90 days).

## 3. Information kept on your device

- The hub's address, your sign-in tokens, and a copy of your profile on the hub (name and role), encrypted with the Android Keystore or the iOS Keychain.
- Your passkey, kept by your device's password manager (for example, Google Password Manager or iCloud Keychain) under that provider's privacy policy.

When you open a file from a conversation, the App keeps one copy in its private cache so another app can show it; opening the next file replaces it. Signing out or uninstalling the App removes what the App stores. Your passkey stays in your password manager until you delete it there.

## 4. Information AppLabs processes

**Notifications (the Relay).** If you allow notifications, the App registers your device's notification token with the Relay and receives a "grant" that only works for that token. Your hub stores both, and sends a notice to the Relay when something needs you. Each notice contains only: the token and grant, a random identifier for the hub, the kind of notice (such as "approval needed" or "job failed"), and random identifiers of the items to open. It never contains message text, names, or other content; the Relay rejects anything else. The Relay passes the notice to Google's Firebase Cloud Messaging (and, on iPhone, to Apple's push notification service once supported), which delivers it to your device. The App then gets the details from your own hub.

The Relay doesn't store notices or tokens. Our hosting provider, Vercel, keeps standard request logs (which include IP addresses) for a short period for security and operations. Google and Apple process notification data under their own privacy policies.

**The Website.** The Website is static and hosted on Firebase Hosting. It uses no cookies and no analytics. Google keeps standard server logs, including IP addresses, for security and operations. The installer downloads the hub from GitHub, under GitHub's privacy policy.

**Contacting us.** If you email us, we keep your email address and what you send us to answer you, and delete it when it's no longer needed.

We use this information only to deliver notifications, run and secure the Services, answer you, and meet legal obligations. Where the GDPR or UK GDPR applies, we rely on our legitimate interests in providing and securing the Services you choose to use, on your consent for notifications (which you can withdraw at any time), and on legal obligations where they apply.

## 5. Device permissions

- **Notifications**: optional. Turn them off at any time in the App (More, Notifications) or in your device's settings.
- **Camera**: used only to scan an invite or pairing QR code, and only when you choose to. On Android, Google Play services' code scanner handles the camera, and the App receives only the text in the code. No photos or video are kept or sent.
- **Photos and files**: the App uses your device's own photo picker and file picker. It gets only the photo or file you choose, never access to your photo library or storage.
- **Network**: to reach your hub and the Relay.

## 6. Sharing

We don't sell personal information, share it for cross-context behavioral advertising, or use it for advertising at all. We share information only with the service providers named in this policy (Google, Apple, Vercel), to deliver the Services; when the law requires it; to protect the safety, rights or property of people or AppLabs; or as part of a merger, sale or reorganization, under this policy.

## 7. Your choices and rights

**Deleting your account.** You can delete your account in the App at any time: More, Account, Delete my account. This deletes the conversations you own (on the hub and on its agents) and the files in them, your access to agents, your quick commands, your passkey registration, devices and notification tokens on the hub, and signs you out everywhere. Your name is replaced with "Deleted member" wherever it still appears, such as the activity log and board cards you created (the cards stay, for the people you worked with). If you own the hub, your account can't exist without it, so deleting it deletes everything on the hub: every member's account and conversations, the agents' settings and keys, jobs, cards and settings. A hub owner can also remove members. If you can't use the App, see switchboard.applabs.llc/delete-account.html.

**Other choices.** You can delete single conversations in the App, sign out other devices (More, Signed-in devices), and turn notifications off. A hub owner can also delete everything by uninstalling the hub and deleting its data folder.

**Information AppLabs processes.** Uninstalling the App or turning notifications off stops the Relay from delivering to your device. Email us to ask about, correct or delete anything we hold about you.

**Legal rights.** Depending on where you live (for example, in the European Union, the United Kingdom, California or other U.S. states), you may have the right to access, correct, delete, or receive a copy of your personal information, to object to or restrict processing, to withdraw consent, and to not be discriminated against for using these rights. For information on a hub, the hub owner is responsible for answering these requests. For information AppLabs holds, email us and we'll answer within the time the law requires (usually 30 to 45 days); we may need to confirm your identity. You may also complain to your local data protection authority.

## 8. Security

Switchboard is designed to protect your information: passkey sign-in, short-lived tokens stored only as hashes, encrypted agent keys, private network access through Tailscale, and content-free notifications. The hub owner is responsible for keeping the hub and its computer secure (see SECURITY.md in the source code). No system is perfectly secure, and we can't guarantee security.

## 9. Children

The Services are for people 18 and older. They are not directed to children, and we don't knowingly collect information from anyone under 18. If you believe a child has given us information, email us and we'll delete it.

## 10. International transfers

AppLabs and its service providers are in the United States, and notification data may be processed there and in other countries where they operate. Where the law requires, these transfers are protected by the service providers' standard contractual clauses or similar safeguards. Information on a hub stays wherever the hub owner runs it.

## 11. Changes to this policy

We may update this policy. We'll change the effective date above and, for important changes, tell you in the App or on the Website before they take effect.

## 12. Contact

AppLabs, LLC. Privacy questions and requests: mail@applabs.llc
