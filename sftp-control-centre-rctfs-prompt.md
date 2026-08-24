# SFTP Control Centre — Implementation Prompt (RCTFS)

## Role
You are the frontend/full-stack engineer implementing a redesign of the SFTP Control Centre module inside iResolve (FastAPI + PostgreSQL + React/Vite + Celery/Redis, self-hosted on IIS). Act as a senior engineer who owns both the visual design system and the underlying data model — this is not a cosmetic reskin, several requirements require schema and pipeline changes.

## Context
This module lets ops and IT staff monitor and control SFTP endpoints for client placement files, and manage the reports generated from them. It currently has a metrics dashboard with all controls buried in sub-pages behind a menu bar — functional but reads as a generic admin panel, not a real-time console. Full design spec is attached in `sftp-control-centre-design-brief.md` — read it in full before writing code; it defines the color/type tokens, layout, motion rules, copy voice, and the following functional requirements in detail:
- One console shared by ops (status/reports level) and IT (connection-detail level), gated by existing per-user module permissions.
- Dashboard, endpoint rail, and sub-pages must read as one system: persistent status strip + collapsible endpoint rail (with per-endpoint uptime history bar) on every page, not just the dashboard.
- Config-driven, not hardcoded: every client is a data row against one generic Endpoint component — must scale from 5 clients to 500 without redesign.
- Every SFTP auth type (password, private key/PPK/PEM/OpenSSH with optional passphrase) must be supported through one conditional form, not separate flows.
- File pickup must never act on a partial/mid-upload file — filename convention validation, then multi-signal completion detection (size-stability, completion marker/atomic rename, checksum where available), with re-queue and full audit logging.
- Report email distribution: single "email report to HOD" send, and a bulk "one client per day" send, sharing one underlying send path so behavior never drifts between the two.

- Three-tier access control within the module: Ops (status/file movement/report download only), IT (adds full technical panel), Super Admin (adds config control — credentials, conventions, recipients, bulk-send). Enforced server-side per field/route, not frontend-only; reuses the existing per-user module permission pattern with a new role-assignment UI in Admin Settings.

## Task
1. Propose the schema changes needed (client/endpoint config table: auth type + credentials/key/passphrase fields, filename convention pattern, HOD recipient list, completion-check settings) before writing UI code — confirm the model with me if anything is ambiguous.
2. Build the shared layout shell first: status strip, collapsible endpoint rail with uptime history bar, and the menu-bar sub-pages reading from the same client list/state.
3. Implement the generic Endpoint detail view (ops summary + expandable IT technical panel) driven entirely by config — no per-client branching anywhere in the component tree.
4. Implement the auth-type-aware connection form and "test connection" flow with specific, non-generic failure messages per auth type.
5. Implement the file completion pipeline as a distinct, logged step ahead of existing file processing — do not let it silently swallow files; failed/pending files must be visible in the UI.
6. Implement single-send and bulk-send email actions sharing one send function; surface send status in the activity log/status strip.
7. Implement the three-tier access control: server-side field/route gating by role, Ops/IT view differences on the shared endpoint detail component, and the Admin Settings role-assignment UI.
8. Apply the visual design system (tokens, type, motion, copy voice) from the brief throughout — do not default to generic component-library styling.

## Format
- React/Vite functional components, Tailwind or CSS matching the existing iResolve frontend conventions — check the current codebase's styling approach before introducing a new one.
- FastAPI endpoints and Pydantic models for any new config/schema work; Celery task for the completion-check polling if it isn't already a scheduled job.
- Keep the design tokens (colors, type) in one shared theme file/constants module, not inlined per component, so they stay consistent as the module grows.
- Deliver in reviewable increments matching the task order above, not one giant diff.

## Style
Command-center, not dashboard-template: dense but legible, dark graphite-navy base, functional status color only, monospace for data/logs, minimal motion that always communicates a state change. No generic admin-panel defaults (cream/terracotta, floating gradient cards, numbered-step decoration where there's no real sequence). Copy is active-voice and specific — errors say what happened and what to do next, buttons and their resulting toasts use the same verb.
