# Contributing

Keep the product a free, independent native calendar: no telemetry, ad or billing
SDKs, Google-account dependency or hidden network service.

## Before changing synchronization

Write a failing regression with synthetic data first. Preserve raw iCalendar
properties and explicit limitations; block unsupported edits rather than deleting
unknown data. Keep the calendar/href/UID identity stable, persist mutations before
network I/O, use conditional writes, and never treat a partial report as proof of
remote deletion. A failed request is not proof that the server did not accept it.

All tests must use isolated storage and disposable test accounts. Never attach
instrumentation to a personal configured application. Never log credentials or
full personal event content.

## Before changing the UI

Use native Material components, one create FAB, familiar calendar navigation,
semantic labels, system insets and accessible touch targets. Inspect actual
emulator screenshots in both themes and enlarged fonts. Screenshots in public
issues or documentation must contain only synthetic events. Do not substitute web
mockups for screenshots of the built Android application.

## Checks

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:bundleRelease
python -m unittest discover -s tools/tests -v
```

Native tests and a disposable CalDAV server are also required for storage, sync,
notification and lifecycle changes. See the isolated infrastructure in `tools/`
and instrumented tests in `app/src/androidTest/`.

Review all public changes for accidental credentials, private URLs, local paths
and personal screenshots. Never commit signing keys, local configuration, build
outputs or files in `.ops/` and `artifacts/`. Keep release signing under the
maintainer's control.
