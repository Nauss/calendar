# Agenda

A native Android calendar for your own CalDAV server. Familiar calendar views,
private offline storage, no Google account and no mandatory cloud service.

**Early alpha:** intended for testing on a separate calendar before trusting it
with important appointments. This is an independent project, not a Google product
or a pixel-for-pixel reproduction of Google Calendar.

## What it does

- Agenda, day, three-day, week and month views.
- Create, edit and delete timed or all-day events, with location and notes.
- Simple daily, weekly, monthly and yearly recurrence; guarded handling of imported
  complex events instead of destructive editing.
- Search, calendar visibility, a default calendar, French and English, light and
  dark appearance, and scalable text.
- Direct CalDAV discovery and synchronization for one account, with a private
  local cache and durable offline changes.
- Conditional server writes and visible conflicts; local-copy recovery without
  silently replacing the server's version.
- Native, silent, inexact reminders and individual iCalendar export.

No ads, purchases, subscriptions, trackers, analytics, Google account SDK or
Android Calendar Provider access. Android's open-source Jetpack libraries are
used for the native UI and background work; no Google Play Services is required.

## Try it

Use the APK attached to a tagged GitHub prerelease, or build the debug APK below.
Test builds use a debug certificate, **not the future Play Store signing identity**.
Changing to a differently signed release may require export and reinstall.

The app opens with an empty local calendar. In **Settings**, enter your CalDAV URL
and credentials, discover the calendars, then save the selected calendars. Choose
an explicit default calendar: **On this device** is local-only, whereas a CalDAV
calendar synchronizes to its server.

HTTPS is the default. Certificates and hostnames are verified. An explicitly
warned HTTP option is limited to literal private/loopback IP addresses for trusted
local testing; it sends credentials and events without transport encryption.
Never use it as a substitute for fixing a production TLS configuration.

See [the French test guide](docs/TESTER-FR.md), [privacy](PRIVACY.md),
[security](SECURITY.md) and [the roadmap](docs/ROADMAP.md).

## Build

JDK 21, Android SDK platform `android-37.0`, Build Tools `37.0.0` and the included
Gradle wrapper. Set `ANDROID_HOME` or create an untracked `local.properties` with
`sdk.dir=...`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows: use `gradlew.bat` from a native terminal, or `bash ./gradlew` from Git Bash.
The APK is `app/build/outputs/apk/debug/app-debug.apk`. `:app:bundleRelease`
produces an **unsigned** AAB; release signing is intentionally not configured.

The GitHub Actions template is in `ci/android.yml`, **inactive**. It is kept outside
`.github/workflows/` because workflow activation was not authorized by the available
publishing credential. After granting the appropriate workflow permission, move it
to `.github/workflows/android.yml`. No successful remote CI run is claimed.

- Package: `fr.nauss.calendar`
- Minimum Android API: 26; target/compile API: 37
- `core/`: JVM-testable iCalendar, recurrence, CalDAV and synchronization rules.
- `app/`: Kotlin/Compose UI, app-private SQLite/outbox, encrypted account storage,
  background sync and notifications.
- `tools/`: a loopback-only disposable Radicale fixture server and emulator-only
  inspection helpers. No personal account belongs in tests.

## Alpha boundaries

One account; no invitation transport, attendee responses, per-occurrence editing,
calendar administration, widgets or Google-calendar migration. Unsupported complex
recurrence/meeting edits are read-only, with export available. Full calendar
queries are used instead of incremental sync tokens. Recurrence expansion and
responses have explicit safety limits. Search covers the selected year.

Reminders are **silent and inexact**, and Android battery restrictions can delay
them. Background synchronization is not push. Physical-device battery/Doze
behavior and a real Synology server must be evaluated separately; disposable
Radicale success is not a Synology certification.

## License

Apache-2.0. Third-party library notices ship inside the app under Settings →
Library licenses. Names, logos and assets of other calendar products are not used.
