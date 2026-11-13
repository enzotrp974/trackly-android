# Spec: public launch (Google Play first, iOS later)

Status: accepted by the maintainer, 13 November 2026. Tracking issue: see "Public launch" in the issue tracker.

## Goal

Anyone can install Trackly for free from Google Play, set it up for themselves in a few minutes, and use the AI
in one of three ways:

1. **Their ChatGPT plan** ("Continue with ChatGPT", plan usage). Costs the user nothing beyond their plan.
2. **Their own API key** (what exists today).
3. **A Trackly subscription** for people who have neither: they pay a monthly fee through Google Play, and a
   small Trackly gateway serves their AI requests with the project's own OpenAI key, within a monthly allowance.

The app is open source (AGPL-3.0) and keeps its core promise: every number is computed by the app, the journal is
stored on the phone, and only AI requests leave it.

## Non-goals (for this launch)

- iOS. It comes after Android proves people use and pay for it; tracked separately.
- A "ChatGPT app" (Apps SDK) mode. It needs the journal on a server; out of scope.
- Accounts, cloud sync or a web version. The journal stays on the phone.
- Ads, analytics or trackers.
- Other AI providers for paths 1 and 2. Path 3 may use any provider behind the gateway, invisible to the app.

## Product rules

- **No mixing of path 1 with paid features.** A user on their ChatGPT plan pays nothing to Trackly and meets no
  credit meter, paywall or feature gate. The subscription is a separate way to get AI access, not an upgrade.
  This is how the request to OpenAI was framed; keep it true in code, UI and store listing.
- **The app never stores or forwards a ChatGPT token or a user API key to the gateway.** Path 1 and path 2
  talk to OpenAI directly from the phone, as today.
- **Logging rule unchanged:** what the user says they ate is logged at once and can be undone in one tap; a
  suggestion from the assistant waits for a tap on "Log".
- **No personal defaults.** No weight, target date, expenditure, step count or session count is assumed for a
  new user. Until the user gives a value, the features that need it say so and stay off.
- **Privacy wording:** never "100 % local". The phone keeps the journal; each AI request carries the message or
  photo and the context needed for that turn; Drive backup is opt-in.
- **Health disclaimer**, at first run and in the store listing, in English and French: Trackly is not a medical
  device; it does not diagnose, treat, cure or prevent any disease; talk to a health professional before changing
  your diet (Google Play "Health Content and Services" policy).

## Design

### 1. One seam for AI access

Today five places attach `Authorization: Bearer <key>` themselves: the Responses client
(`OkHttpResponsesTransport`), key verification (`KeyVerification`), live dictation (`LiveTranscriber`), file
transcription (`FileTranscriber`) and the voice call (`WebRtcLiveTransport`).

Introduce a single `AiAccess` port in `assistant/` that every network caller uses:

```kotlin
sealed interface AiAccess {
    /** Base URL and auth header for HTTP and WebSocket calls. */
    suspend fun credentials(): AiCredentials          // may refresh (path 1) or fetch a short-lived token (path 3)
    val capabilities: AiCapabilities                  // models, vision, web search, live dictation, voice call
}
```

Implementations: `OwnKeyAccess` (path 2, current behaviour), `ChatGptPlanAccess` (path 1), `TracklyGatewayAccess`
(path 3). The UI reads `capabilities` to hide what the active path cannot do, with a one-line reason, rather than
failing at call time. The choice of path is a setting, changeable at any time; the journal does not depend on it.

### 2. Path 1: Continue with ChatGPT

Blocked on OpenAI's answer to the access request (sent 13 November 2026). Known from OpenAI's developer docs:
OAuth Authorization Code with PKCE and OpenID Connect, scope `chatgpt.tokens.use.direct`, Plus and Pro plans,
Responses API requests. Open questions, to settle from OpenAI's reply before any code:

- the redirect flow for a native Android app (the documented flow uses a loopback listener on `127.0.0.1`);
- which capabilities are available on plan usage (vision, tool calling, web search, realtime transcription,
  voice call). Anything missing is hidden on this path through `capabilities`;
- branding rules ("Continue with ChatGPT" button).

Plan usage is not a drop-in base URL: the documented preview limitations require streaming, reject
`max_tool_calls` and constrain how function tools are declared. Today the app sends `max_tool_calls`
(`TurnInterpreter`), declares tools directly in `tools` (`AssistantTool`) and retries an interrupted turn without
streaming (`ResponsesClient`). Path 1 therefore needs a protocol adapter (serialization, tools, retries), not only
other credentials. Re-check the preview limitations when the work starts; they may change.

Tokens are stored like the API key today: encrypted with the Android Keystore, excluded from backups.

### 3. Path 3: Trackly gateway and subscription

A small open-source service in `gateway/` (same repository, same licence), deployed on a free serverless tier
(Cloudflare Workers or equivalent).

- **Entitlement and purchase lifecycle:** the app sends the Google Play purchase token; the gateway verifies it
  server-side with the Google Play Developer API (product, state, expiry), acknowledges the purchase (an
  unacknowledged purchase is refunded after three days), grants idempotently, follows `linkedPurchaseToken` on
  upgrades and replacements so one subscription never yields two allowances, and reacts to renewals, refunds and
  revocations through Real-time Developer Notifications. The allowance renews on the verified Play billing
  period. The gateway then issues a short-lived session token. No email, no password, no account to create.
- **The gateway is the only authority on spending.** It never trusts usage reported by the phone. The client
  cannot choose models, output limits or tools freely: the gateway checks a model allow-list and enforces its own
  caps on input size, output tokens, tool calls and web search per request.
- **Text and vision:** before each call the gateway atomically reserves the maximum cost of the request (so
  parallel requests cannot overdraw), calls OpenAI with the project key, then settles against the usage OpenAI
  reports. An interrupted stream with no final usage is settled at the reserved amount. Request content is never
  logged.
- **Voice call (GPT-Live):** the current call posts an SDP offer to `/v1/live/sessions` with `gpt-live-1`. On
  path 3 the gateway creates the session server-side (the phone sends its SDP offer, the gateway returns the SDP
  answer and session id), with a maximum duration reserved up front and enforced by the gateway, which ends the
  session when the reservation runs out. Audio still flows directly between the phone and OpenAI.
- **Live dictation:** offered on path 3 only once the same server-side control exists for it (bounded session,
  reserved cost, forced end). Until then path 3 uses transcription after stop, proxied and capped like text.
  A short-lived client secret alone is not enough: it does not cap spending.
- **Allowance:** a monthly budget in internal credits. The app shows what is left. When it runs out: a clear
  message and the renewal date; no surprise charge.
- **Abuse limits:** per-session-token rate limit and concurrency limit.
- **Data kept by the gateway:** purchase token hash, billing period, credits reserved and used. Nothing else.
  This record is kept until the end of the billing period it covers (plus a short, documented grace period) so
  that deleting and re-registering cannot reset a used allowance; it is then deleted automatically. The privacy
  policy states this retention.
- **Manage and cancel:** the app has a "Manage subscription" entry that opens Google Play's subscription page,
  and explains that switching AI path or uninstalling does not cancel the subscription. After a cancellation the
  allowance stays usable until the paid period ends.

Price and allowance are a maintainer decision; target a margin that stays positive for a heavy user on the
cheapest adequate model.

### 4. First run (onboarding)

1. Short promise + "Not medical advice".
2. Explicit consent to process health-related data (GDPR art. 9), with a link to the privacy policy.
3. Profile: current weight (optional), weight target and date (optional), maintenance energy (optional, with a
   "don't know" choice), usual steps and sessions (optional). Replaces `WeightTarget.Default` and the personal
   reference values in `ReviewParams`; review features needing a missing value explain what to add.
4. Language (defaults to the phone's).
5. AI access: pick one of the three paths. Paths not yet available are shown as "coming soon" and cannot be
   selected.

### 5. Languages

English and French at launch. All UI strings move to Android resources (`values/`, `values-fr/`). The
assistant's instructions, the voice call instructions and the dictation language follow the app language. The
model answers in the app language. Screenshot tests run in both languages for the main screens.

### 6. Store readiness (Google Play)

- Name and package: decide the public name first (risk: an EU trademark "Trakkly" exists for a tracking app).
  The `applicationId` can never change after the first upload; keep `app.trackly` unless the name decision
  requires otherwise.
- Play App Signing, release build from CI with secrets stored in GitHub Actions secrets.
- Data safety form, health apps declaration, content rating, target audience (adults), privacy policy in English
  and French on the website. No user account exists, so the account-deletion requirement does not apply as long
  as that stays true; the privacy policy still explains what the gateway keeps and for how long.
- **Access for Play reviewers:** before any submission, a funded and capped review access (for example a
  review-only gateway allowance) with instructions in Play Console "App access", so reviewers can test text,
  photo and voice without buying credits or creating an OpenAI account. Check those flows with that access first.
- Closed test with at least 12 testers for 14 days (requirement for new personal developer accounts), then
  production.
- Store listing: short and long descriptions in English and French, screenshots with made-up data, the demo
  video.

## Delivery order

1. AI access seam (no behaviour change) and removal of personal defaults (onboarding profile).
2. Languages (English + French).
3. Gateway + Play Billing subscription (path 3). It also provides the capped review access Play reviewers need.
4. Store readiness, closed test (12 testers, 14 days) with paths 2 and 3, then production.
5. Path 1, as soon as OpenAI grants access.
6. iOS (separate spec).

Paths 2 and 3 do not depend on OpenAI's answer; nothing waits on it. Store preparation that needs no build
(name, listing texts, privacy policy, Play Console setup) can start during steps 1–3.

## Acceptance criteria for the launch

- A new user on a fresh phone installs from Play, completes onboarding in under three minutes, logs a meal by
  text, voice and photo on path 2 or 3, and sees totals computed by the app.
- No personal value of any real person appears in code, tests, screenshots or store assets.
- With the phone in English, no French string is visible in the main flows, and the assistant answers in
  English.
- On path 3, a modified client sending parallel requests, oversized requests or long voice sessions cannot spend
  more than its allowance (automated gateway tests), and the cost of a scripted heavy-use month stays below the net
  subscription revenue.
- Replaying, refunding or upgrading a purchase never yields more than one allowance per paid period (automated
  tests against Play Developer API fixtures).
- The app offers "Manage subscription" leading to Google Play, and the health disclaimer appears at first run and
  in the listing.
- Play reviewers can reach every main feature with the documented review access.
- Path 1, when enabled, shows no credit meter, paywall or paid feature.
- CI runs unit and screenshot tests on every pull request; the release build is reproducible from CI.

## Open decisions (maintainer)

| Decision | Needed before |
|---|---|
| Public name (keep "Trackly" or rename) | First Play upload |
| Subscription price and monthly allowance | Path 3 release |
| Hosting choice and spending cap for the gateway | Path 3 release |
| Legal form for selling (sole trader registration), terms of sale | Path 3 release |
