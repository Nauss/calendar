# Security

Agenda is a calendar client, not a hosted service. Do not attach calendar exports,
server passwords, private URLs, notification content or personal screenshots to a
public issue. Reproduce bugs with synthetic data first.

## Development boundaries

- Tests must never mutate a personal CalDAV server.
- `tools/devserver.py` targets a disposable server bound to loopback only. Its
  deliberately public test account is not suitable for a deployment.
- Debug APKs use test signing and are not Play Store releases.
- Never disable TLS certificate or hostname verification to fix a connection.
- Do not add analytics, ad, billing, Google account or remote logging SDKs.
- Keep credentials, local signing material, `.ops/`, `artifacts/` and
  `local.properties` out of commits.

## Reporting

Use GitHub's private security advisory reporting for a reproducible vulnerability
when available. For an ordinary bug, open an issue with the app version, Android
version and a sanitized reproduction. Do not disclose a working credential or a
private calendar even in a draft public issue.

This early alpha has not received a third-party security audit. Keep another
client available, and export a server-side calendar backup before first evaluating
writes to an important calendar.
