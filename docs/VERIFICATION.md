# Alpha verification

Scope: `0.2.0-alpha.1` (version code 2), package `fr.nauss.calendar`. This record describes a local
owner-testing build, not Play Store approval or a physical-device certification.

## Executed checks

| Check | Result |
| --- | --- |
| Pure JVM calendar/CalDAV core | 67 tests, 0 failures/errors |
| App JVM tests, including projection/routing and color contrast | 22 tests, 0 failures/errors |
| Native Android regressions on the owned API 33 emulator | 55 tests, successful, none skipped |
| Process-death persistence | Separate seed / force-stop / verify protocol passed (two invocations) |
| Python fixture/inspection tooling | 13 tests, successful |
| Debug APK and instrumentation APK | Built and installed on the emulator |
| Release AAB | Built; intentionally unsigned |
| Android lint | 0 errors, 61 warnings (including illustrative preview numerals and dependency/style notices) |
| Gradle wrapper JAR | SHA-256 matched the official Gradle distribution metadata |
| Update compatibility | Same APK signing-certificate SHA-256 as the first alpha; update-only install succeeded |
| Release manifest | No debug widget host, Calendar Provider permission or exact-alarm permission |

The native suite covers private storage, recurrence on ART, durable offline work,
conditional CalDAV writes/conflicts, lifecycle-safe creation, stale-detail deletion,
isolated instrumentation, silent notification posting/cancellation, overdue reminder
recovery, actual timed-block geometry, and application-theme system-bar contrast.
The second alpha adds real AppWidgetHost tests (including the legacy service path
on API 33), refresh invalidation, stale/hidden links, exact occurrence routing,
instance-specific month navigation, seven-column recycling, 280/320/384dp widths,
all six weeks at normal height and error visibility. A real PendingIntent reuses
the existing activity; recreation preserves an unsaved editor and the selected
occurrence without replaying an old widget launch. Imported-event ART tests cover
safe edits, capabilities, revisions and an actual disposable-server round-trip.

The first native widget run failed (5/10); those failures were corrected, not
discarded. Separate red regressions caught a redundant month footer hiding its last
week and a consumed widget intent reopening its old event after recreation. The
final complete native run passed all 55 tests, including the fixture-cleanup test.
Six incoming-navigation regressions cover dirty drafts, cancellation, fresh CREATE
sessions, notifications, recreation, Save concurrency and request-specific approval.
The full suite initially encountered a pending production-mode JobScheduler callback
inside the intentionally background-disabled test Application; the failed run is
retained. A force-stop and check for quiescent fixture-package jobs preceded the
successful complete repeat. This is test-harness isolation, not a production
WorkManager initialization failure.

Additional visual checks use font scales 1.0, 1.3 and 2.0. Process-death persistence
was rerun with separate seed/verify instrumentation invocations and an intervening
force-stop. Both phases passed on the final APK.

Timezone regressions cover a retained VTIMEZONE whose overlap/gap differs from IANA,
unchanged ambiguous endpoints, bounded observance enumeration, and non-hour offsets.
The low-level uniqueness helper retains second precision. However, biweekly 0.6.8
silently truncates nonzero seconds when parsing textual UTC-OFFSET properties.
A separate RED test reproduced that loss even on metadata edits. Custom property
scribes now reject such input before it can be rewritten, including folded values;
ordinary minute offsets and explicit zero seconds remain supported. This is a
documented parser boundary, not a claim of arbitrary historical-zone support.

The disposable CalDAV server was Radicale 3.5.7 on loopback. Discovery through the
actual account form, selection and synchronization were also exercised manually.
All eight original synthetic fixture resources remained present. Four additional
synthetic events in two extra calendars exercise real calendar colors and embedded
Europe/Paris zones. A time move through the native editor was read back from the
server with unchanged UID and retained VTIMEZONE. Conditional deletion checks an
exact successful Depth0 DAV resource type as well as the event revision.
No personal NAS account, real appointment, physical phone or Play Console was used
for development/testing of this iteration. The owner's successful first-alpha
CalDAV setup is feedback, not a substitute for these isolated checks.

## Native visual evidence

Captures are from `adb exec-out screencap -p`, not browser renders. The owned
emulator used a Pixel 9 hardware profile at 1080 × 2424 and Android API 33. The new
French Planning and the imported-event editor were inspected. Both widgets were
actually pinned through the launcher confirmation UI; month navigation, a date
link and an appointment link were exercised there, not just in a debug host.
Light/dark, enlarged text and the keyboard-visible editor are separate capture
states. Production first launch remains empty; displayed appointments come from
explicitly synthetic development fixtures. A resized/small month widget may scroll
vertically; its columns remain visible, and its normal launcher height shows six
weeks. Picker previews are explicitly illustrative.

The installed API 37 system image exhibited a graphics/system-server assertion
(`hasReadColorBufferDma`) that prevented valid screenshots. Changing host/software
rendering and disabling GLDMA did not repair it. Earlier core/database/CalDAV ART
checks succeeded there, but **API 37 visual and complete UI acceptance is not
claimed**. The final native regression and visual passes used the stable API 33
emulator instead. API 26 is the declared minimum, not a tested device in this run.

## Run the isolated native suite

Build both APKs and use an **explicitly owned emulator**, never a generic attached
device. Install the app and test APK. On Android 13 or newer, grant notification
permission on that muted test emulator before the notification suite:

```sh
adb -s emulator-NNNN shell pm grant fr.nauss.calendar android.permission.POST_NOTIFICATIONS
adb -s emulator-NNNN shell am force-stop fr.nauss.calendar
adb -s emulator-NNNN shell cmd jobscheduler cancel -u 0 fr.nauss.calendar
```

Use this only on the owned disposable emulator. Quiescing its app jobs prevents
old normal-mode jobs from entering the isolated test Application; it does not erase
calendar storage. Resume the normal app after tests to restore its scheduled work.
Then run:

```sh
adb -s emulator-NNNN shell am instrument -w -r \
  -e class fr.nauss.calendar.StoreInstrumentedTest,fr.nauss.calendar.CalendarUiTest,fr.nauss.calendar.NavigationUiTest,fr.nauss.calendar.DetailRevisionTest,fr.nauss.calendar.CreationLifecycleTest,fr.nauss.calendar.ReminderInstrumentedTest,fr.nauss.calendar.TimedGeometryTest,fr.nauss.calendar.IsolationInstrumentedTest,fr.nauss.calendar.ThemeSettingsTest,fr.nauss.calendar.ImportedEditingStoreTest,fr.nauss.calendar.ImportedEditingUiTest,fr.nauss.calendar.IncomingEditorNavigationTest,fr.nauss.calendar.PlanningUiTest,fr.nauss.calendar.WidgetHostTest,fr.nauss.calendar.WidgetInvalidationTest,fr.nauss.calendar.WidgetRoutingUiTest,fr.nauss.calendar.WidgetLifecycleTest,fr.nauss.calendar.FixtureCleanupTest \
  fr.nauss.calendar.test/fr.nauss.calendar.IsolatedTestRunner
```

For the additional CalDAV integration test, start the disposable server from
`tools/devserver.py`, map `tcp:15232` with `adb reverse`, add
`fr.nauss.calendar.DavInstrumentedTest` to the class list and pass
`-e testServer http://127.0.0.1:15232/tester/`. The test server's fixture account is
public, loopback-only and unsuitable for deployment. The isolated runner uses
separate storage/credentials and disables production background synchronization.
Keep the emulator muted; no sound playback is part of these tests.

Widget tests own separate host IDs and never publish instrumentation fixtures to
the launcher's widgets. If package-update callbacks were intercepted while the
isolated application was running, return to the normal app and refresh its cache
before launcher screenshots. A loading placeholder after instrumentation is not
accepted as a final widget screenshot. The normal cache was byte-identical across
the corrective 28-test no-reinstall repeat; the complete 12-event synthetic cache
was separately compared with server ICS/ETags. Database byte identity across APK
updates/background work is not claimed.

## Remaining owner/hardware checks

Real Synology authentication/permissions/TLS, actual Pixel gestures and rendering,
Doze/battery restrictions, precise reminder timing, reboot/timezone behavior and
production-calendar interoperability remain unverified for the new version.
Reminders are silent and inexact. Meetings and unsupported fields remain protected
rather than edited destructively; safe metadata editing is available for more
imports. Widgets are cached bounded previews, not a second live synchronization
engine; unusually large calendars explicitly show a limited-preview notice.
A narrow seven-day view necessarily
ellipsizes some titles; the day/detail views and accessibility descriptions expose
more information.

Automated tests and independent AI source/visual reviews are not an external
security audit. Preserve server-side backups and start with a separate test calendar.
The GitHub Actions template remains inactive at `ci/android.yml`: the publishing
credential did not have the workflow scope, and no permission expansion was made.
Remote CI has **not** run; local success is not a substitute for a future CI result.
