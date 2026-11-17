# Roadmap

## First testing version

A native, account-independent Android calendar with a familiar Material calendar
interface, app-private offline storage, and a direct CalDAV connection. The
first delivery is an alpha APK for evaluation, not a store-ready replacement for
every calendar workflow.

### Implementation order

1. Date/time, recurrence and iCalendar handling with regression tests.
2. Safe CalDAV discovery, conditional writes, caching and persistent offline work.
3. Native agenda, month and timed views; event editing, search and calendar filters.
4. Private account setup, sync status and Android reminders.
5. Independent safety review, real disposable-server integration, native emulator
   inspection, and a reproducible APK.

### Before a public store release

- Test on a physical Pixel, including background sync, timezone changes, restart,
  battery restrictions and notification behavior.
- Verify real Synology Calendar interoperability against a dedicated test calendar
  before using an important personal calendar.
- Review conflicts, account replacement, advanced recurrence and unsupported meeting
  editing with real-world fixtures.
- Finalize product name, original icon, translations, accessibility and screenshots.
- Establish the final Android package identity, release signing and Play App Signing
  ownership; never reuse a debug certificate for a release.
- Review privacy/data-safety declarations, third-party notices and store metadata.
- Submit through the maintainer's account only after explicit approval.

### Not promised by the first alpha

Calendar migration, Google integration, invitations and attendee response handling,
server-side calendar creation/deletion, shared-resource booking, tasks, widgets,
wearable integration, complete tablet optimization or guaranteed exact alarms.

There are no plans for advertisements, in-app purchases, subscriptions, analytics
or a mandatory cloud account.
