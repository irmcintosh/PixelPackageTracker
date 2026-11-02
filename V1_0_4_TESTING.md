# v1.0.4 Emulator acceptance checklist

Source implemented; Android build and emulator tests have NOT been run in this environment.

1. Upgrade from v1.0.3 without clearing app data; confirm Room 8→9 migration retains orders, shipments and settings.
2. Orders cards: verify Last synced appears top-right collapsed and expanded; before scan reads Not available.
3. Scan Inbox / Import: matching order confirmation fills Placed and Notes if supported by email.
4. Individual Update: matching original confirmation fills missing fields on an existing order.
5. Update All: fills missing details without duplicating orders; timestamps advance after a successful scan even if nothing changes.
6. Verify manually entered Placed/Notes are not overwritten.
7. Verify messages saying 'expected to be delivered tomorrow' do not set Delivered.
8. Verify Gmail Settings connection and Orders scanning both remain available.

Limitations: Only Inbox messages are scanned. The email confirmation's Date header is a fallback when an explicit purchase date is absent; a missing confirmation yields Not available. Notes extraction remains heuristic and depends on retailer email format. No direct carrier API verification. Incorrect previously stored Delivered statuses require review. Last synced is a successful Gmail scan timestamp, not a carrier tracking refresh.
