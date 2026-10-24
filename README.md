# Pixel Package Tracker

A native Android application for managing retail orders, shipments, and package tracking in one place.

Designed for personal use on Google Pixel devices.

## Current Version

**v0.4.0-dev9 — Development Release**

Pixel Package Tracker is under active development. Features and integrations may change between development releases.

## Features

The project is designed to support:

- Unified order and shipment management
- Manual order and shipment entry
- Multiple products and shipments per order
- Standalone shipments without associated orders
- Separate order and shipment statuses
- Tracking numbers and carrier information
- Shipment tracking history
- Search, filtering, and sorting
- Order and shipment archiving
- Custom retailers and marketplaces
- Application settings and appearance preferences

Feature availability and testing status should be confirmed against the v0.4.0-dev9 source and release notes.

## Planned Integrations

Future development phases include:

- Carrier tracking APIs, including international shipments
- Gmail integration for automatic order discovery
- Automatic tracking-number extraction
- Background shipment synchronization
- Android notifications for shipment updates

These integrations should not be considered functional until implemented and verified.

## Technology

- Kotlin
- Jetpack Compose
- Material 3
- MVVM architecture
- Room database
- Local-first data storage

## Installation

Pixel Package Tracker is distributed as an Android APK through GitHub Releases.

1. Open the repository's **Releases** section.
2. Select the desired version.
3. Download the APK attached to that release.
4. Install the APK on your Android device.

Only install APKs from releases you trust.

## Updates with Obtainium

Pixel Package Tracker is intended to support updates through Obtainium.

Once GitHub Releases are configured:

1. Install Obtainium on your Android device.
2. Add the GitHub repository as an application source.
3. Configure authentication if the repository is private.
4. Select the appropriate APK release asset.
5. Use Obtainium to check for new versions and install updates.

Android updates require a compatible application ID, signing certificate, and increasing version code.

**Important:** Keep the signing key secure and backed up. Switching from a debug-signed APK to a differently signed release APK may require uninstalling the existing application, which can remove locally stored data.

## Version History

| Version | Description |
|---|---|
| v0.3.x | Initial Android foundation |
| v0.4.0-dev1–dev4 | Phase 2 data model and order/shipment management development |
| v0.4.0-dev5–dev9 | Subsequent development snapshots; details pending verification |

See `CHANGELOG.md` for documented changes.

## Development Roadmap

- **Phase 1:** Android foundation and local data management
- **Phase 2:** Order and shipment management
- **Phase 3:** Carrier tracking integrations
- **Phase 4:** Gmail order discovery
- **Phase 5:** Background synchronization and notifications
- **Phase 6:** Testing, security, optimization, and APK release

## Privacy and Security

Pixel Package Tracker is designed as a local-first application.

- No custom backend is required for the core local functionality.
- Credentials, API keys, and signing keys must not be committed to GitHub.
- External integrations require separate authorization.
- A private GitHub repository restricts source-code and release access to authorized users.

## Distribution

Personal-use Android application.

Not currently distributed through Google Play.

## Project Status

**Active development — v0.4.0-dev9**

Build verification, device testing, and release signing status should be documented for each published version.
