# Alpha verification

Scope: `0.1.0-alpha.1`, package `fr.nauss.calendar`. This record describes a local
owner-testing build, not Play Store approval or a physical-device certification.

## Executed checks

| Check | Result |
| --- | --- |
| Pure JVM calendar/CalDAV core | 37 tests, 0 failures/errors |
| App JVM tests, including calendar-color text contrast | 4 tests, 0 failures/errors |
| Native Android regressions on the owned API 33 emulator | 23 tests, successful |
| Python fixture/inspection tooling | 13 tests, successful |
| Debug APK and instrumentation APK | Built and installed on the emulator |
| Release AAB | Built; intentionally unsigned |
| Android lint | No errors; version/style/resource warnings remain |
| Gradle wrapper JAR | SHA-256 matched the official Gradle distribution metadata |

The native suite covers private storage, recurrence on ART, durable offline work,
conditional CalDAV writes/conflicts, lifecycle-safe creation, stale-detail deletion,
isolated instrumentation, silent notification posting/cancellation, overdue reminder
recovery, actual timed-block geometry, and application-theme system-bar contrast.
Additional UI runs exercised font scales 1.0, 1.3 and 2.0. Process-death persistence
was exercised with separate instrumentation invocations and an intervening force-stop.

The disposable CalDAV server was Radicale 3.5.7 on loopback. Discovery through the
actual account form, selection and synchronization were also exercised manually.
All eight original synthetic fixture resources remained present after testing.
No personal NAS account, real appointment, physical phone or Play Console was used.

## Native visual evidence

Captures are from `adb exec-out screencap -p`, not browser renders. The owned
emulator used a Pixel 9 hardware profile at 1080 × 2424 and Android API 33. French
month, agenda, day and week views; light/dark; enlarged text; the keyboard-visible
editor; and settings were inspected. Production first launch remains empty;
illustrated events come from explicitly synthetic development fixtures.

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
```

Then run:

```sh
adb -s emulator-NNNN shell am instrument -w -r \
  -e class fr.nauss.calendar.StoreInstrumentedTest,fr.nauss.calendar.CalendarUiTest,fr.nauss.calendar.NavigationUiTest,fr.nauss.calendar.DetailRevisionTest,fr.nauss.calendar.CreationLifecycleTest,fr.nauss.calendar.ReminderInstrumentedTest,fr.nauss.calendar.TimedGeometryTest,fr.nauss.calendar.IsolationInstrumentedTest,fr.nauss.calendar.ThemeSettingsTest \
  fr.nauss.calendar.test/fr.nauss.calendar.IsolatedTestRunner
```

For the additional CalDAV integration test, start the disposable server from
`tools/devserver.py`, map `tcp:15232` with `adb reverse`, add
`fr.nauss.calendar.DavInstrumentedTest` to the class list and pass
`-e testServer http://127.0.0.1:15232/tester/`. The test server's fixture account is
public, loopback-only and unsuitable for deployment. The isolated runner uses
separate storage/credentials and disables production background synchronization.
Keep the emulator muted; no sound playback is part of these tests.

## Remaining owner/hardware checks

Real Synology authentication/permissions/TLS, actual Pixel gestures and rendering,
Doze/battery restrictions, precise reminder timing, reboot/timezone behavior and
production-calendar interoperability remain unverified. Reminders are silent and
inexact by design in this alpha. Unsupported complex recurrence/meetings remain
protected rather than edited destructively. A narrow seven-day view necessarily
ellipsizes some titles; the day/detail views and accessibility descriptions expose
more information.

Automated tests and independent AI source/visual reviews are not an external
security audit. Preserve server-side backups and start with a separate test calendar.
The GitHub Actions template remains inactive at `ci/android.yml`: the publishing
credential did not have the workflow scope, and no permission expansion was made.
Remote CI has **not** run; local success is not a substitute for a future CI result.
