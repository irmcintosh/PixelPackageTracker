# Approved Development Roadmap — October 24, 2026

## Stable baseline
- **v0.4.0-dev11**: Phase 2 complete, per user-reported physical Pixel verification (including upgrade/data retention and launcher icon).
- **View Tracking** remains in the external browser, unchanged.
- Preserve current order/shipment relationships, local data, application ID, and existing features.

## Phase 3 — Email integration and order discovery (NEXT)
1. Verify current Google OAuth, Gmail API, quota and billing requirements before connecting an account. Do not enable billing or paid services.
2. Implement Gmail sign-in using Google OAuth 2.0 and the minimum appropriate read-only permissions.
3. Add a user-initiated **Scan Email** action; no automatic/background scanning in the initial milestone.
4. Parse order confirmations and shipping notifications for retailer, order number, items, carrier and tracking numbers when available.
5. Match discovered information to existing records, preview proposed additions/changes, and require user confirmation before saving.
6. Avoid duplicate orders/shipments and protect email credentials. Investigate Outlook, Yahoo and other providers after Gmail works; do not assume universal compatibility.

**Initial development milestone:** v0.5.0-dev1 — Gmail authentication and connection. Planned, not implemented.

## Phase 4 — Email-driven shipment updates and tracking history
- Reuse existing tracking numbers and shipment relationships; do not require re-entry.
- Use authorized email messages to identify shipment status changes and tracking events.
- Review ambiguous matches, avoid duplicate events, preserve history and carrier handoffs.
- Email updates may be delayed or incomplete and do not replace live carrier tracking.

## Phase 5 — Background sync and notifications
- Introduce WorkManager-based periodic sync and configurable notifications only after Phase 3/4 are verified; respect Android execution limits.

## Phase 6 — Validation and release
- Regression tests, privacy/security review, signed APK and device verification.

## Cost and integration constraints
- Zero paid tracking APIs, subscriptions, required billing accounts or unexpected charges.
- No carrier website scraping or embedded tracking browser.
- No manual copy/paste tracking-import feature in the current plan.
- No backend/cloud hosting unless separately justified and approved.

## Verification status
- This roadmap is documentation only. No Gmail OAuth, email scanning, shipment updates, Gradle build, or new APK has been implemented or tested by this change.
