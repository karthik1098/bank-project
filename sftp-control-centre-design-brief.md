# iResolve — SFTP Control Centre: Design Brief

## Subject & audience
Internal ops **and IT** staff at CRM monitoring and controlling client SFTP endpoints for placement file transfers, and downloading/holding generated reports. Two audiences, different depth needs from the same screens:
- **Ops** mostly cares about status and reports — is the client's file in, did it sync, send the report to the HOD.
- **IT** needs the connection-level detail — host/port, auth method, key fingerprints, error codes, retry/backoff behavior — to actually diagnose a failed endpoint.
Don't split this into two separate UIs. Keep one console, but layer the detail: the endpoint card/rail shows the ops-level summary by default, with a "connection details" expand (or a details tab within the endpoint detail view) that surfaces the IT-level technical fields without cluttering the default view. Existing module permissions (per-user module toggles) already gate who sees what — use that to decide whether the technical panel and raw log/error fields are visible to a given user, rather than building a second app.

This is not a marketing page — it's a working console people from both teams will have open all day. The job: instant situational awareness (is this endpoint alive, did the last transfer succeed, where's my report) plus fast control actions, with technical depth available on demand rather than always on screen.

## Color tokens
| Token | Hex | Use |
|---|---|---|
| Base | `#0B0F14` | App background — graphite-navy, not pure black |
| Surface | `#131A22` | Cards, panels |
| Surface raised | `#1A2330` | Active/hovered cards |
| Hairline | `#1E2A35` | Borders, dividers |
| Text primary | `#E6EDF3` | Headers, key values |
| Text secondary | `#8B9BAA` | Labels, metadata |
| Accent | `#00D9C8` | Selection, links, primary actions |
| Status: connected | `#3DDC84` | Live endpoint |
| Status: degraded | `#FFB020` | Slow/retrying |
| Status: down | `#FF5470` | Connection failed |

Status colors are functional, never decorative — don't reuse them for anything that isn't a live state.

## Typography
- **Display/UI face:** IBM Plex Sans or Space Grotesk, restrained weights (500/600). Avoid Inter — too generic for this brief.
- **Data/mono face:** JetBrains Mono or IBM Plex Mono for IPs, hostnames, timestamps, file names, byte counts, and the activity log. The sans/mono contrast is what makes the UI read as a console instead of a form.
- Caption/utility: same mono face at smaller size, `text-secondary` color.
- **Licensing constraint:** all four fonts above are free and open-source (SIL Open Font License) — IBM Plex Sans/Mono via IBM, Space Grotesk and JetBrains Mono via Google Fonts. Self-host the font files (don't rely on a runtime Google Fonts CDN call, given the HIPAA-controlled server environment) rather than link to an external font service.

## Layout concept
```
┌─────────────────────────────────────────────────────────────┐
│ System health strip: X/Y endpoints up · active transfers ·  │
│ alerts count                                                 │
├───────────────┬───────────────────────────────────────────┤
│ Endpoint rail  │ Selected endpoint detail                    │
│ (persistent)   │  - connection info (host, auth, last sync) │
│ status dot +   │  - recent files / reports, with actions    │
│ uptime history │  - manual actions: test, sync now, hold    │
│ bar per row    │                                              │
│                ├───────────────────────────────────────────┤
│                │ Activity log (monospace, live-scrolling)    │
└───────────────┴───────────────────────────────────────────┘
```
The left rail stays visible at all times — a persistent list of every client endpoint with live status is what makes this a command center rather than a settings page buried in tabs.

## Signature element
A **segmented uptime history bar** per endpoint — a row of small blocks, one per sync cycle over a fixed recent window (e.g. last 30 runs), each colored by outcome (connected/success, degraded, failed, no-data-yet). This is the pattern enterprise infra monitoring already uses (Statuspage, Datadog, PagerDuty-style history bars) — it reads as serious ops tooling rather than a decorative widget, and it encodes real information at a glance: an operator can see *when* an endpoint started failing and whether it's recovering, not just its current state. Sits inline next to the status dot in the rail and dashboard card; hovering a segment shows that run's timestamp and detail.

## Motion (use sparingly)
- Status dot: subtle pulse animation only while connected — stops or changes character on degraded/down, so motion itself is informative.
- Uptime history bar: new segment slides in from the right as each sync cycle completes; no bounce/spring.
- Activity log: live-scrolls new entries in; no bounce/spring.
- Endpoint selection: instant, no page transition — this is a console, latency reads as broken.
- No decorative motion (no floating gradients, parallax, hover-tilt cards). Every animation must communicate a state change.

## Copy voice
- Name things by what the operator controls: "Sync now," "Hold report," "Retry connection" — not "Trigger job," "Invoke webhook."
- Errors state what happened and the next action, in the system's voice: "Connection timed out after 30s — check firewall allowlist," not "Something went wrong."
- Button label and resulting toast use the same verb: "Sync now" → "Synced."
- Empty states are an invitation to act: "No reports held for this client yet" rather than a blank table.

## Build quality floor
- Responsive down to a reasonable minimum width (this is likely desktop-only for ops staff, but don't hard-break below ~1280px).
- Visible keyboard focus states on every interactive element.
- Respect `prefers-reduced-motion` — disable the pulse animation and log auto-scroll transition.
- Keep CSS specificity clean — avoid stacking type-selectors and utility classes that fight each other (a known failure mode when generating panel/section spacing).

## Structure: dashboard + rail + menu (not dashboard vs. sub-pages)
- Add a persistent status strip (endpoints up/down, active transfers, alerts) above the menu bar on **every** page, not just the dashboard — this is what keeps the "console" feeling alive when the operator is inside Reports or Settings.
- The client/endpoint list becomes a persistent, collapsible left rail (with uptime history bar per row) rather than a sub-page — it stays visible everywhere and filters whatever sub-page is open to the selected client, instead of forcing navigation away to check status.
- The Dashboard is the zoomed-out view of the same rail data (a card grid), not a separate app — so Dashboard and Endpoint Detail read as one system.
- Reports, logs, and settings remain behind the menu bar as genuinely secondary pages — that's correct, they're not live state.

## Scalability: client architecture must be config-driven, not hardcoded
Every client-specific control (SFTP creds, schedule, report format, held-report rules, HOD email routing) should be driven by a per-client config record, not per-client code or per-client UI branches. Concretely:
- One generic "Endpoint" component/page renders for any client from its config — adding a new client is a data row (new endpoint record), never a new UI screen or route.
- The rail and dashboard grid both just iterate over the client list — no hardcoded client count or fixed grid size; layout must handle 5 clients or 500 without redesign (virtualized list/grid once the count gets large).
- Per-client controls (test connection, sync now, hold/release report, pause client, edit schedule, edit HOD recipients) live in one reusable action panel keyed off the config, so every client automatically gets every control — no client silently missing a button because it was added after the UI was built.
- Client onboarding becomes a form that writes the config record — not a dev task.

## Report email distribution
Two send modes, same underlying action so behavior stays predictable:
- **Single send:** from an endpoint's detail view, "Email report to HOD" — sends the current/selected report as an attachment plus a short plain-text summary (client name, file date, record count, any flagged exceptions) in the email body. Recipient list comes from that client's config (editable there), not hardcoded per send.
- **Bulk send:** a scheduled or manual "Send today's batch" action that queues one email per client, one client per day per the existing schedule — reuses the same single-send logic per client rather than a separate bulk code path, so the email content and recipient logic never drift between the two modes.
- Surface bulk-send status in the activity log/status strip like any other job: queued → sending → sent/failed per client, so a failed HOD email is as visible as a failed SFTP sync, not silent.
- Give the operator a preview of the email body before a manual bulk send fires, and a per-client send history (last sent date/time, to whom) visible from that client's detail panel.

## Auth compatibility: every SFTP auth type must be first-class
Client endpoints won't all authenticate the same way — the config schema and UI must support all of these without special-casing:
- **Password auth** — username/password stored encrypted at rest.
- **Private key (PPK/PEM/OpenSSH)** — key file upload or paste, with optional passphrase field.
- Endpoint config form shows/hides the relevant fields based on a selected auth type (password vs. key), not separate forms per type — one schema, one component, conditional fields.
- "Test connection" must validate against whichever auth type is configured and surface a clear, specific failure (wrong password, bad passphrase, malformed key, host key mismatch) rather than a generic "connection failed."
- Store key material and passphrases with the same encryption/handling standard as passwords — never log either in plaintext, including in the activity log/error messages.

## File integrity: never act on a partial/mid-upload file
This is the highest-risk failure mode for the whole module — picking up a file while the client is still writing it corrupts downstream processing. Build detection as a real pipeline step, not a delay hack:
- **Filename convention check first** — validate the incoming filename against the expected pattern for that client (naming convention is per-client config, same as auth) before anything else runs; mismatches go to an exceptions view instead of silently failing.
- **Completion detection**, using more than one signal so a single weak check doesn't cause a false positive:
  - Size-stability check: file size unchanged across two checks spaced a short interval apart.
  - Prefer a completion marker where the client supports it: a `.done`/`.ok` sentinel file written after the real file, or an atomic rename pattern (client uploads as `.tmp`/`.part` then renames to final name) — treat these as authoritative over size-stability alone when available.
  - Where the client's own SFTP process supports it, checksum/hash verification (e.g. a paired `.md5`/`.sha256` file) is the strongest signal and should be used when present.
- Files that fail the completion check are re-queued for the next poll cycle, not skipped — surface an "awaiting completion" state per file in the endpoint detail view rather than leaving the operator guessing why a visible file hasn't been picked up.
- Every completion decision (which signal passed, timestamps checked) is written to the activity log so a "why wasn't this picked up" question is always answerable after the fact.

## Access control: three-tier visibility (Ops / IT / Super Admin)
Builds on the existing per-user module permission system (PostgreSQL-backed, `require_module()` on the backend) — this is a new permission tier *within* the SFTP Control Centre module, not a new auth system.
- **Ops:** SFTP status (connected/degraded/down + pulse trace), file movement/activity log, and report download only. No visibility into connection credentials, auth type/key material, retry/backoff internals, or raw error codes — failures show the plain-language state ("connection down since 9:14am"), not the technical detail.
- **IT:** everything Ops sees, plus the full technical panel — host/port, auth type, key fingerprints, error codes, retry/backoff behavior, completion-check signal detail in the log.
- **Super Admin:** everything IT sees, plus config-level control — add/edit/remove client endpoints, change auth credentials, edit filename conventions and completion-check settings, manage HOD recipient lists, and the bulk-send controls.
- Enforce this server-side (per-endpoint API field/route gating keyed to role), not just conditional rendering in the frontend — the same failure mode as the letter-approval HOD gate: a hidden button is not access control.
- Admin Settings gets a role-assignment UI (reusing the existing per-user module toggle pattern) so Super Admins can set each user's tier for this module without a dev task.
- Where the ops-level and IT-level views share a component (e.g. endpoint detail), the technical panel is conditionally rendered *and* the underlying API response itself omits the restricted fields for Ops-tier requests — don't rely on the frontend to hide data the backend already sent.

## What to avoid
- No cream background + terracotta accent, no pure-black + single neon accent, no numbered-marker (01/02/03) decoration unless it's an actual ordered process.
- No gradient hero banners — this page has no "hero," it has a status strip.
- Don't let every card have the same visual weight — the endpoint currently having a problem should visually stand out from the 40 that are fine.
