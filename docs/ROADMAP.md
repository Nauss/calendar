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

### Second testing version

- Schedule refinement based on the owner's visual reference, preserving the
  existing native navigation and timed views.
- Imported-event editing with field-specific capability guards and round-trip
  regression tests, rather than blanket protection for every embedded time zone.
- Native Schedule and Month home-screen widgets, private-cache refresh, exact
  occurrence/date links, and isolated real-host tests.

### Before a public store release

- Test on a physical Pixel, including background sync, timezone changes, restart,
  battery restrictions and notification behavior.
- Verify real Synology Calendar interoperability against a dedicated test calendar
  before using an important personal calendar.
- Review conflicts, account replacement, advanced recurrence and unsupported meeting
  editing with real-world fixtures.
- Coalesce redundant widget refresh work without dropping month-navigation clicks;
  exercise late load failures in legacy collection adapters and surface their status
  independently of the preceding outer widget render.
- Finalize product name, original icon, translations, accessibility and screenshots.
- Establish the final Android package identity, release signing and Play App Signing
  ownership; never reuse a debug certificate for a release.
- Review privacy/data-safety declarations, third-party notices and store metadata.
- Submit through the maintainer's account only after explicit approval.

### Not promised by the first alpha

Calendar migration, Google integration, invitations and attendee response handling,
server-side calendar creation/deletion, shared-resource booking, tasks,
wearable integration, complete tablet optimization or guaranteed exact alarms.

There are no plans for advertisements, in-app purchases, subscriptions, analytics
or a mandatory cloud account.
