# v1.0.2 Email Account Settings Restoration

- Restores Settings > Email Accounts with Gmail authorization, connection status, last Inbox scan, interval and session disconnect.
- Keeps Connect Gmail available on Orders > Scan Inbox / Import.
- Yahoo Mail, Outlook and other providers are labelled Coming Soon.
- Gmail authorization tokens remain in memory; Google can silently reauthorize on reopening.
- Disconnect session is local only and does not revoke Google permission.
- Updates About version and removes stale phase wording.

Validation: source inspection and ZIP integrity only. Android Studio build and emulator test not performed.
