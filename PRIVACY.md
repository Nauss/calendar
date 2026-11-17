# Privacy

Agenda is an open-source calendar client. It has no hosted account service,
advertising, analytics, purchase system or remote crash-reporting SDK.

## Data on the device

The app stores calendar names, event content, synchronization metadata, pending
changes and preferences in its private application storage. Local-only events stay
there unless you explicitly export them. Calendar data is not separately encrypted
by the app; it relies on Android's application sandbox and device protection.

CalDAV credentials are encrypted using an Android Keystore-backed key. The app
opts out of Android cloud backup and excludes its private data from the configured
device-transfer rules. Platform/OEM backup behavior is outside the app's control.
Treat app removal, storage clearing or device loss as potentially destructive to
local-only events and pending changes. An individual event can be exported as an
ICS file to a location you choose; this file is not encrypted by Agenda.

## Data sent over the network

After you configure a CalDAV account, the app sends authentication information and
calendar requests directly to that server. Saving, editing and deleting in a
synchronized calendar sends the corresponding changes. Server operators, reverse
proxies and DNS/network providers may process normal connection metadata according
to their own policies. Agenda does not send events to the project maintainer.

HTTPS uses normal certificate and hostname verification. The optional local HTTP
mode is unencrypted and warns before use. No automatic cross-origin authenticated
redirects are followed.

## Permissions

- Internet/network state: connect to the server you configure and schedule work.
- Notifications: optional calendar reminders, requested in context.
- Boot/background-work permissions: refresh reminders and scheduled sync.

The app does **not** request read/write access to Android's shared calendar database,
contacts, location, microphone or camera. An event's location is text you enter,
not a device-position lookup. Reminders may display event titles on the device;
Android notification visibility settings also apply.

## Other calendar apps

Agenda does not read or write Google Calendar's Android account or the shared
Calendar Provider. Two other apps can still display the same underlying calendar,
and multiple clients connected to the same CalDAV server will naturally display
its events. Installing Agenda neither deletes existing duplicate records nor
migrates or disables another app's account.

## Deletion and support

Removing a local event deletes it locally. Deleting a synchronized event queues a
server deletion that is sent when synchronization succeeds. Detaching an account
retains local cached copies and does not delete a server calendar. Do not detach,
clear data or uninstall while important changes remain pending without exporting
or otherwise preserving them.

Use the repository's issue tracker for sanitized bugs, or private security
reporting for vulnerabilities. Never include credentials, private server URLs,
calendar exports or personal screenshots in a public report. This alpha has not
received an external security audit or store privacy review.
