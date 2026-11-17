# Agenda — native design system

<!-- impeccable:design-schema 1 -->

## Scope and direction

Native Android phone application, Kotlin/Jetpack Compose/Material 3. Calendar-first
operator interface. The user pinned familiar Google Calendar-like navigation and
composition, not Google's logo, artwork or brand. This is a code-led first testing
version: no approved raster comp, random visual world or website design system was
substituted for that brief.

**Thesis:** make the date and appointments immediately usable while keeping their
source independent and private. No dashboard, hero, marketing cards or bottom-tab
navigation. The signature is a shared selected date across the month list and
actual timed grids, with an all-day lane and side-by-side overlaps.

## Structure

- Compact inset top bar: view/calendar drawer, month/date chooser, Today, Search.
- Separate previous/next date row, followed by the selected calendar view.
- Drawer: Planning/Day/3 days/Week/Month, calendar visibility and Settings.
- One create FAB, lower right, clear of system navigation.
- Month grid with event indicators and a selected-day list; chronological agenda;
  day/three-day/week grids with actual duration and overlap geometry.
- Full-page event editor, native date/time dialogs, explicit Save and Back, scrollable
  with the keyboard. Settings are native outlined fields, toggles and buttons.

## Color roles

The complete role definitions live in `MainActivity.kt`; use roles rather than
introducing one-off hex colors into controls.

| Role | Light | Dark |
| --- | --- | --- |
| Primary | `#1A73E8` | `#A8C7FA` |
| On primary | `#FFFFFF` | `#062E6F` |
| Primary container | `#D3E3FD` | `#0842A0` |
| On primary container | `#041E49` | `#D3E3FD` |
| Surface/background | `#FFFFFF` | `#131314` |
| On surface | `#1F1F1F` | `#E3E3E3` |
| Surface container | `#F1F3F4` | `#1E1F20` |

Calendar colors carry calendar identity, not decoration. Event blocks select
opaque black or white text by relative-luminance contrast, including time labels;
never apply a fixed white text rule to arbitrary server-supplied colors. Regression
tests cover grayscale and RGB samples. The current-time line uses the error role.

The app-local appearance setting controls system-bar icon contrast as well as
Compose colors, even when it differs from the device's system theme.

## Type, spacing and shapes

Use the native Material type scale and system sans-serif, not a downloaded display
font. Respect font scaling; do not shrink weekly text to squeeze in long titles.
Use compact single-line ellipsis in narrow or short event blocks and expose the
full title/time in semantics and detail view. Larger fonts allow horizontal
scrolling of timed columns. Main controls follow native 48dp interaction sizing;
dense event cells retain proportional geometry rather than stretching their time.

Layout spacing follows 4/8/12/16dp groupings. Calendar rules are fine, neutral and
subordinate to appointments. Today/selected-day markers are circular; event blocks
use restrained 4dp rounding; other shapes/elevation come from Material components.
All-day lanes use a compact sun symbol from Material Icons with an accessible
label, rather than wrapping the gutter into three short words.

## Behavior and truth

Honor native Back, IME/system insets and readable error/empty/pending states. Never
seed production with sample appointments. Never imply a failed sync succeeded or
silently allow destructive edits to unsupported recurrence/meeting structures.
Preserve the distinction between the local calendar and synchronized calendars.

## Evidence and review

Independent visual disposition: **ship**, limited to the first owner-testing alpha.
The review covered native month/day/week, dark day at 1.3× text, a keyboard-visible
editor and dark settings at 2.0×. The stable emulator was API 33 with a Pixel 9
hardware profile, not a physical Pixel. Static captures are not proof of gestures,
notification timing or NAS compatibility. The review noted a constrained system
clock in the API 33 large-font capture; physical system-bar behavior remains a
hardware check, not an attributed app defect.

No web detector ran: this is a native Android surface. There are no shipped raster
UI assets. The original launcher/notification icons are vector drawables and the
interface uses Material Icons. Review contact sheets contain only resized native
screenshots of synthetic fixtures, with labels outside the phone images; they are
not mockups. See `docs/VERIFICATION.md` for test scope and remaining checks.

## Do not dilute

Keep the compact calendar-first shell, neutral dark surfaces, meaningful event
colors, real time geometry, shared date selection, readable day/detail views and
single FAB. Do not add marketing panels, decorative imagery, gradients, glass,
extra floating controls or imitation Google branding.

## Owner-requested refinement (17 November 2026)

The owner provided a Google Calendar schedule screenshot and asked to keep the
working base while refining its presentation. This new explicit reference takes
precedence over the alpha's plain agenda rows: schedule cards use a dedicated
10–12dp corner role, while timed-grid event geometry stays compact. Dates move
into a left gutter with a prominent day number and circular Today marker. Colored
appointment surfaces, stronger title/time/location hierarchy and day spacing
replace repetitive rules and full-width day headings. Calendar colors remain
meaningful; Google artwork, profile photos and task controls are not reproduced.

Two native home-screen surfaces extend the same language: a scrollable Planning
widget and a month-grid widget. Both read the private cache, respect visibility,
open the relevant date/event, support light/dark and resizing, and expose actual
empty/error states. The Android launcher owns placement and user confirmation.
Their quality gate includes a real widget host, deep-link interaction and refresh
tests, not just a static layout preview. The implementation passed those native
checks and both widgets were placed through the actual Android launcher. At normal
height the month shows all six weeks; it omits the redundant Open footer unless an
error/limited-preview notice needs the space. Smaller sizes can scroll vertically.
The seven dense month cells may be narrower than 48dp at minimum width; primary
controls and date-cell heights remain 48dp, and non-overlap is tested down to 280dp.

The editor puts the title first and capability explanations beside locked fields.
Its compact detail toolbar keeps Modify visible. Widget navigation is consumed
once; changing text size/rotating does not reopen an earlier event or discard an
unsaved draft. Native screenshots and test scope are recorded in
`docs/VERIFICATION.md`; they are not physical-device certification.
