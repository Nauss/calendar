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

## Local read-compatibility candidate: 0.2.0-alpha.2 (code 3)

This later, unpublished candidate addresses a reproduced parser refusal; it is
not a claim that the owner's physical-device failures have been diagnosed.

- A synthetic event with a vendor-qualified TZID and an exactly matching embedded
  VTIMEZONE failed both occurrence expansion and CalDAV resource admission with
  biweekly warning 43. Two regression tests first failed on that precise category.
  The parser now accepts that resolved, supplied definition. It does not strip a
  vendor prefix, guess an IANA zone, or accept unknown-zone/default-zone fallback
  warnings 5/38. A deliberately different embedded offset verifies the actual rules
  are used. Text-only editing and raw REPORT resource retention are also covered.
- Parser refusals now retain allowlisted categories through REPORT, direct GET and
  sync error handling, without exception causes, raw field values or parser text.
  Debug builds write only aggregate codes/counts into two app-private
  `no_backup/read-diagnostics-{display,sync}.txt` files. No titles, UIDs, account
  endpoints, credentials or ICS are included; release builds do not write them.
- Executed on this candidate: 95 core JVM tests, 28 app JVM tests, and a targeted
  17-test native API 33 run, all successful with none skipped. The native run covers
  Android timezone interpretation, private diagnostic storage, SQLite sync/cache,
  imported-event editing, store safety and instrumentation isolation. This is a
  targeted rerun, not a new execution of the full historical native/UI suite.
- Debug APK, instrumentation APK and unsigned release AAB built offline. Android
  lint reported 0 errors/fatal findings and 64 warnings. The release AAB has no JAR
  signature entries. `git diff --check` passed.
- Local APK: `artifacts/test-nas/Agenda-correctifs-lecture.apk`; package
  `fr.nauss.calendar`, min SDK 26, target SDK 37. SHA-256:
  `5523d95bd94b6e91e08ff522e3f4f8a96a9542bc2cceae2fdff77322db7552a8`.
  APK signature verified against the existing debug certificate; the original
  requested APK was not replaced.
- The physical phone was disconnected during the isolated checks. After the owner
  reconnected it, this exact APK was installed with update-only `install -r` on the
  verified Pixel 9a. The installed base APK was pulled through `pm path` and its
  SHA-256 matched the candidate above; the signing certificate matched the previous
  installation. The phone was locked, so interactive diagnosis was not started.
  The exact categories behind its two banners and successful real-account sync
  remain unverified. No personal calendar records, authenticated NAS responses or
  account credentials were inspected, and no phone cache was cleared. An attempted
  independent source review was unavailable because the review provider rejected
  the request; no independent review is claimed for this candidate.

## Confirmed offset-precision fix: 0.2.0-alpha.3 (code 4)

- Fresh, app-private aggregate diagnostics on the authorized phone isolated the
  remaining cached-event refusals to the offset-precision safety guard, not the
  previously reproduced warning43 case. No personal ICS, DB, credential or account
  endpoint was inspected. Removing cache would not correct this reader limitation.
- biweekly 0.6.8 retains offset milliseconds in its value model, but its default
  text offset parser/writer omit seconds. `SecondPrecisionOffsets` now registers
  exact property scribes for TZOFFSETFROM/TZOFFSETTO, including signed subminute
  offsets. The library still owns line unfolding, component parsing and timezone
  rules. Editing and conservative lost-response reconciliation both use the same
  precise serializer; malformed ranges and unknown-zone fallbacks remain blocked.
- Synthetic regressions first failed on valid second-precision imports, then
  passed with exact instants and preserved offset values. Coverage includes
  historical second offsets with modern events, negative offsets, folded lines,
  DST recurrence, metadata-only edits, rounded-object inequivalence, full REPORT
  admission, and rejected malformed responses retaining cache/pending operations.
- Executed: 104 core JVM tests, 28 app JVM tests, and 18 targeted isolated API 33
  native tests, all successful with none skipped. The native run includes exact
  Android occurrence instants and lossless pending SQLite edits. This is not a new
  run of the full historical UI suite. Debug APK, test APK and release AAB built
  offline; lint: 0 errors/fatal findings, 64 warnings. No remote CI/review is claimed.
- APK: `artifacts/test-nas/Agenda-correctifs-fuseaux.apk`, package
  `fr.nauss.calendar`, code4, version `0.2.0-alpha.3`. SHA-256:
  `7d6207b98fdd34a7b5bd6eb9bdb28152dd8985e31f2ad7219aaf0ec03691600d`.
  Installed update-only on the verified Pixel 9a; signer matched the previous APK
  and the installed base APK pulled through `pm path` matched this hash exactly.
- After installation, a fresh display diagnostic reported no read failures and
  zero pending operations. No cache reset, account reset or NAS mutation was made.
  The separate sync diagnostic file was still absent: a fresh real-account sync
  and the disappearance of the stored sync-error banner are not yet verified.
  The owner was asked to launch sync explicitly; the error was not cleared by hand.
  The owned emulator was stopped after testing.

## Confirmed manual-sync scheduling fix: 0.2.0-alpha.4 (code 5)

- Scoped JobScheduler metadata showed existing CalendarSyncWorker tasks deferred
  by timing constraints while connectivity was already satisfied. The manual
  button shared a KEEP queue with an old deferred automatic attempt, so a tap did
  not produce a fresh worker entry. Forcing the OS job alone did not establish a
  WorkManager execution; no network success was inferred from that command.
- Explicit sync and explicit account-connect actions now enqueue a separate
  single-flight user request, tagged independently from automatic retries. Manual
  failures finish visibly, allowing the next tap to start fresh; background retries
  keep their previous backoff. KEEP inside the user queue and the account lock
  preserve active operations instead of replacing/cancelling a running write.
- A native in-memory WorkManager regression first reproduced the deferred-queue
  refusal; the fixed tests prove fresh admission, retention of the old deferred job,
  no cancellation on a repeated tap, and a fresh request after a finished failure.
  Pure JVM tests cover manual versus background retry decisions. STARTED and early
  NO_ACCOUNT diagnostics distinguish worker entry from completed synchronization.
- Pinned test-only dependency: `androidx.work:work-testing:2.10.3`, resolved from
  the official repository. Dependency locks updated; no existing locked module
  version changed. Native scheduling tests use inert workers, an in-memory test DB
  and the isolated runner, never the phone's real account or production database.
- Executed offline after dependency resolution: 104 core JVM tests, 31 app JVM
  tests and 21 targeted native API 33 tests; all successful with none skipped.
  Debug APK, test APK and release AAB built; lint: 0 errors/fatal findings and 59
  warnings. This is not a new execution of the complete historical UI test suite.
- APK: `artifacts/test-nas/Agenda-correctifs-synchro.apk`; package
  `fr.nauss.calendar`, code5, version `0.2.0-alpha.4`. SHA-256:
  `aa725a7f022ac18b60d278c4d8121e7a04c8eb81491ca73f06b153c57fe3c91c`.
  Signature matched the existing installation; update-only installation on Pixel 9a
  was verified by pulling its installed base APK through `pm path` and comparing bytes.
- Physical-device acceptance: with fresh pending=0, only the known menu/settings/
  sync controls were activated. No raw UI tree, appointment fields or account values
  were exported. The actual button produced STARTED then a fresh completed OK;
  subsequent display diagnostics also reported OK and pending=0. The enabled sync
  control and absence of the exact previous REPORT error message were read back.
  No cache/account reset or NAS mutation was made. The owned emulator was stopped.

## Additional source hardening before commit

The pre-commit independent review identified a malformed REPORT case: multiple
direct DAV href elements could be reduced to the first address and accepted as a
snapshot. REPORT-only validation now requires one direct scalar href, unambiguous
status/property containers and exactly one binding for each required ETag and
calendar-data value. Conflicting/duplicate statuses, hidden binding selectors and
nested XML in required scalar values reject the whole snapshot without exposing
imported values in the exception. Discovery semantics remain unchanged, and valid
successful propstats may still split ETag and calendar-data across containers.

Core regressions cover missing/duplicate addresses, ambiguous status/property
bindings, and a valid split-property response, retaining cache and pending changes. An additional
isolated SQLite regression seeds two synthetic cached UIDs with strong ETags and
checks both rows and immutable pending operations remain unchanged on rejection.
It uses the real decoder offline through a fake transport, not a remote account.

This extra rejection guard is in the source being committed. The phone was not
reinstalled or otherwise altered during commit preparation: the alpha.4 APK hash
and physical-device acceptance recorded above describe the installed test build,
not a new phone validation of this additional malformed-response guard. No new
GitHub release, tag or Play submission is part of the commit/push operation.

## Automatic NAS synchronization candidate: 0.2.0-alpha.5 (code 6)

New-event creation requires an enabled, writable configured CalDAV calendar.
There is no local or Google destination fallback. A sole NAS calendar replaces a
legacy local default once; a missing previously selected NAS is not silently
replaced with another calendar. Without an eligible destination, Save is disabled
and the draft remains visible. Restoring an already committed submission remains
idempotent. Local-calendar creation is explicitly opted into only by isolated tests.

Each committed creation, edit or deletion queues a fresh network-constrained upload
wake-up, independent of an old retry delay. Work is serialized without cancelling
active writes. Upload-only synchronization drains durable conditional mutations
without requesting a REPORT; redundant wake-ups with an empty outbox perform no
network request. Offline failures retain operation identity and content, and
conflicts remain explicit. App startup also wakes pending changes.

The full NAS refresh is requested periodically every six hours; Android may defer
execution. Manual full synchronization remains available. Upload completion does
not advance the last full-refresh timestamp or clear an unresolved download error.
Debug-only upload diagnostics have a separate app-private, fixed-code file; no
appointment contents, account URLs or credentials are exported.

The worker delegates execution to the same orchestration exercised with isolated
SQLite and an inert transport. Confirmed durable acknowledgements are counted
independently of a later REPORT failure. A drained upload advances its clock while
preserving the failed full-refresh state; partial/offline sends and redundant
wake-ups do not invent progress. Explicit account detachment clears both progress
clocks and retained full-sync errors. Synthetic execution tests cover these
transitions, including reconnecting before the first full download.

Synthetic regressions cover upload-only PUT/DELETE, offline retry, conflicts,
fresh WorkManager admission during active work, actual periodic request constraints,
NAS-only editor/submission boundaries and persistent default selection. REPORT
container text and contradictory DELETE resource-type proofs are rejected before
cache replacement or mutation. The root envelope validator is shared by REPORT
and DELETE type proofs, including nested status bindings in descriptions and
recognized partial-response markers at any depth. HTTP
bodies use strict UTF-8 decoding: malformed bytes are rejected before XML/ICS
parsing, while valid Unicode remains intact. Raw-byte loopback regressions prove
cache/outbox retention and absence of destructive requests on these failures.
Hardware battery/Doze timing and this candidate's
interoperability with the owner's live account still require owner evaluation;
the alpha.4 physical acceptance above does not establish alpha.5 acceptance.

### XML depth and Planning widget consistency

All DAV XML receives a streaming SAX depth check before DOM allocation, with a
128-element maximum depth. Excessive nesting produces the existing safe data
error instead of an uncaught stack overflow. Metadata traversal is iterative;
DTD/entity rejection remains in place. Regressions cover REPORT cache/outbox
retention, no DELETE on excessive nesting, the exact depth boundary and harmless
element-looking text in comments/CDATA. The isolated Android decoder regression
also includes a deeply nested response.

DAV scalar statuses are validated as complete single lines before success or
authorization codes are interpreted; hidden second lines and forbidden controls
cannot authorize cache replacement or deletion. A full run with no enabled remote
targets is an explicit no-op, retaining prior errors and both clocks and emitting
only a fixed private NO_TARGETS diagnostic rather than false refresh progress.

Planning widget cards use the same stable UID-based color function as the app's
Planning rows, rather than the calendar-wide color. Their shared foreground
contrast policy is unchanged. A real AppWidgetHost regression samples rendered
card pixels and text colors for several event UIDs and recurrent occurrences,
in light/dark themes with both modern and legacy collection adapters. Native
screenshots contain only explicitly isolated test fixtures; no owner appointments
or production infrastructure are included in the repository.
