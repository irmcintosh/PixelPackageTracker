# v1.0.3 changes (source implemented; Android build and emulator not verified)

- Extract explicit purchase dates from order emails into Placed. Email received dates are not substituted.
- Extract labeled item, total, and shipping details into Notes, and an HTTPS order link when available.
- Backfill missing fields on existing orders without overwriting user notes or purchase dates.
- Require stronger past-tense evidence before automatically marking a shipment Delivered.
- Display date and time for order and shipment status updates; shipment update source is shown.
- Existing incorrectly marked Delivered records require review; Gmail alone cannot verify carrier truth.
- No database schema changes.
- Android build, emulator, Gmail integration and device behavior are NOT verified.

# Pixel Package Tracker v1.0.0

Source implemented: v1.0.0 version, periodic WorkManager Gmail sync, notification channel, and previous Orders-tab Gmail repair. Build verification and emulator testing remain pending. Known issue: Android 13+ notification runtime permission request UI not implemented.

# Phase 3 progress — v0.5.0-dev9

Source implemented: Inbox-only paginated scan, grouped preview, per-group Room import, Orders navigation to import, shipment summary in order cards.

Build verified: NO. Device tested: NO. Complete: NO.

Known gaps: Update buttons route to Inbox review rather than automated per-order sync; status updates are not automatically persisted from new email evidence; large Inbox scan has no cancellation.

### v0.5.0-dev9.2
Source implemented: individual import results and confirmable Import All with per-order outcomes. The result messages distinguish newly created orders from existing records and failures. Build verification and device testing pending.

### v0.5.0-dev9.3
- Source implemented: 6-hour default preference and accurate settings text.
- Planned/not implemented: WorkManager background Inbox rescan, persistent Gmail authorization, automatic saved-order status updates.
- Build verified: No. Device tested: No.

## dev9.4 status
Source implemented: Cancel Editing with unsaved-change confirmation; Beta/About roadmap; 8-hour preference choice.
Planned, not implemented: automatic Gmail sync, background OAuth handling, email-derived shipment status updates, and notifications.
Build verified: No. Device tested: No.


### v0.5.0-dev9.5 partial test candidate
Source implemented: new-install 8-hour preference default; skip saved orders in bulk import; Exit when no new orders.
Not implemented: working per-order and Update All email updates, background WorkManager checks, silent Gmail reconnection, notifications.
Build verified: No. Device tested: No. Complete: No.
Next phase after Gmail beta: other email providers (Yahoo Mail etc.), then authorized free carrier APIs.

## dev9.5 repair candidate
Source implemented: Orders-tab Gmail action routing; targeted manual order updates; silent token attempt and reconnect prompt; corrected Gradle version.
Planned/not implemented: periodic WorkManager sync, Gmail reconnect Android notification, shipment status notifications, full event history deduplication.
Build verified: No. Emulator tested: No. Device tested: No. Complete: No.

### v1.0.4 source update
Source implemented: Room 8→9 sync timestamp migration, always-visible order sync display, confirmation-date fallback, broader Notes parsing. Build verified: no. Emulator tested: no. Carrier API tracking remains deferred. Existing false Delivered records are not automatically corrected.
