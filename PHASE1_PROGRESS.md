# Phase 1 v0.3 progress

## Source implemented (not compiled)
- Unified orders and tracking list.
- Orders can be saved before tracking exists.
- Separate order status and shipment status.
- Editable official retailer order URL and carrier tracking URL.
- Separate View Order / View Tracking actions, only shown when corresponding HTTPS URL exists.
- Room database migration v2 -> v3 for orderUrl, trackingUrl and orderStatus.
- Existing archive, delete, AliExpress, settings retained.

## Still pending
- Android Studio compile/device validation.
- Automatic retailer order status retrieval; automatic link discovery.
- Carrier API and tracking history sync.
- Gmail scanning and push notifications.
- Full one-to-many order -> shipments normalization (current v0.3 stores one primary shipment per record).
- Link verification against known official domains and carrier URL builders.

## v0.3.2 (source implemented; not build verified)
- Order form scrolling, conditional tracking fields, field help, expanded US marketplaces, custom retailer name.
- Room v4 migration allows multiple orders without tracking numbers.
- Still pending: Gradle sync/build and emulator verification of keyboard scrolling, save, edit, and data persistence.
