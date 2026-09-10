# Design

## Source of truth
- Status: Active. Last refreshed: 2026-09-10.
- Surfaces: native Android app, month widget, agenda widget.
- Evidence: `docs/reference.png`, supplied by the user and excluded from Git to keep the private reference local. White Samsung Calendar screens with compact month grid, color-coded events, agenda, event editor and account drawer. The second temporary screenshot path is no longer present.
- Interpretation: use the reference's quiet hierarchy and calendar-first navigation, with an original MOA identity. This is an inspired implementation, not a pixel-identical clone.

## Brand
- Name: 모아 / MOA. Bring a busy day together.
- Personality: calm, crisp, personal, carefully spaced.
- Trust: show actual account identity, source calendar, sync errors and last successful refresh.
- Avoid: fabricated connected status, dense dashboards, excessive gradients, decorative stock imagery.

## Product goals
- See Google and Naver CalDAV schedules together; create, edit and delete on the selected original calendar.
- Glance at upcoming events from the Android home screen.
- Automatic cross-service duplication is outside the initial scope unless requested.
- Success: Android APK builds; date navigation, real-provider CRUD, account selection UI and widgets work; protocol behavior has tests.

## Personas and jobs
- Korean Android users with work on Google and personal plans on Naver.
- Open the month, pick a day, see plans, add an event, check a widget.

## Information architecture
- Main navigation: 캘린더 / 일정 / 위젯 / 설정.
- Calendar hierarchy: brand + account state, month heading, day grid, selected-day agenda, add action.
- Settings: device Google calendars, Naver connection, visibility, sync details.

## Design principles
- Calendar before controls. A clear selected date and quiet grid.
- Source colors stay consistent across app and widgets.
- Empty, offline and permission states have a next action.

## Visual language
- Colors: warm white #F8F9FC, white #FFFFFF, ink #202637, secondary #737B90, indigo #5965D8; Google blue #4285F4, Naver green #03A86B, personal coral #EA8D77.
- Type: Android sans-serif; 30sp month, 22sp section, 15–16sp body, 11–13sp supporting text.
- Spacing: 4/8/12/16/20/24/32dp. Screen gutter 24dp.
- Shape: 24dp large cards, 16dp event cards, rounded pills, minimal elevation.
- Motion: short standard Android transitions. No essential animated information.
- Imagery: vector line icons and a small custom linked-calendar brand mark.

## Components
- Theme tokens in `ui/Theme.kt`; calendar grid, source chips, agenda row, navigation item, editor and connection dialogs.
- States: selected/today/outside-month; loading/empty/error/connected.

## Accessibility
- 48dp actions where space permits, content descriptions for icons, text labels beside source colors.
- Scrollable content and dialogs, system font scaling, sufficient ink/background contrast.
- Widgets have text alternatives and open the app on tap.

## Responsive behavior
- Android 8+; portrait phone first, centered content with a maximum width on tablets.
- Calendar scrolls with agenda on compact screens; system bars use insets.
- Widgets resize horizontally and vertically.

## Interaction states
- Loading keeps cached data visible.
- Empty gives a useful add/connect action.
- Errors retain cached schedules with explicit explanation and retry.
- Successful writes refresh app and widgets. Conflicting remote writes must fail visibly rather than overwrite newer data.
- Offline viewing uses the last cache. Remote writes require a connection.

## Content voice
- Korean, short and warm. Say 연결 필요 when not connected. Never seed or show example events in the production app.
- Distinguish calendar permission, Google device sync and Naver CalDAV authentication.

## Implementation constraints
- Kotlin, Jetpack Compose, Android Calendar Provider, HTTPS CalDAV, Android Keystore, platform AppWidgetProvider and JobScheduler.
- Use original source IDs; do not deduplicate unrelated events by title.
- No embedded credentials, no custom backend, no telemetry, no calendar data in logs.
- Verify APK build, Android lint, unit tests and emulator screenshots when available.

## Open questions
- User may later request cross-provider automatic replication; default is unified view and source-aware writes.
- Real Google/Naver account verification requires account setup on the user's device.

## September 9 update
- Real calendars only. Remove old demo storage during upgrade, without touching provider data.
- Google account picker distinguishes synced provider accounts from device account login. Selection applies to app, editor and widgets.
- Event detail: small service/account line and calendar name directly below the heading.
- Month widget: short titles with source-color backgrounds, size-dependent row count and an overflow count. Date opens that day in the app.
- Naver connection discloses the fixed SSL/443 server and automatic principal discovery.
- Sync wording: Google follows the device adapter; Naver checks on resume, every minute while active and approximately every 30 minutes in the background.

## Account and task correction · v0.1.2
- Settings groups calendars by selected Google account, NAVER, and other device calendars. A local calendar with an email account label is still a device calendar.
- Disabled Google calendars remain discoverable with an explicit enable action. Account selection requests a device sync; missing adapters and disabled automatic sync have visible explanations.
- The 일정 page offers 일정 / 할 일 chips. Tasks keep their real deadlines; undated tasks remain undated and completed tasks are optional in the list.
- Only incomplete tasks with deadlines appear in calendar grids and widgets, prefixed with □. Task detail shows the source account and is read-only, with a link to Naver for changes.
- Missing CalDAV task support, an empty result and a failed request have distinct notices. Event sync remains usable when task queries fail. Never imply that Naver web tasks or Google Tasks are all available through CalDAV.
- Verification screenshots use isolated emulator fixtures, which are removed after testing and never shipped as app data.

## Quiet refresh and readable dates · v0.1.3
- Read the last committed calendar cache without waiting for the remote write lock. Publish fetched events, tasks and sync status together. Preserve legitimate deletion results; do not retain every empty result forever.
- Initial cache loading must not pretend the user has zero connected calendars. A manual refresh may show a spinner within the existing 44dp action slot; periodic refresh and returning to the app must remain silent.
- Widget date clicks reuse an existing recent activity and apply the requested date through onNewIntent, returning from settings/editor to the calendar without dropping the current snapshot. Repeated clicks on the same date are distinct navigation requests.
- NAVER events and tasks use lavender #B39DDB. Google events use green #03A86B. Apply the mapping when reading older cached metadata too; other device calendars retain their own colors.
- The home widget shows at most three short titles followed by a separate +N line; the app displays every event (v0.1.6 override). The selected date has an explicit link to the full schedule with time and account labels.
- Widget side padding is 10dp, top/bottom 8dp; title text is 10sp. Give busy weeks enough room before distributing remaining height. At smaller sizes reduce title capacity while preserving the count.
- App week rows size to their actual title rows (minimum 48dp), reducing empty space while keeping date targets usable. Full event titles in the selected-day list may wrap.
- Private supplied screenshots remain outside Git; publish only isolated emulator fixtures as verification evidence.

## Account and calendar pickers · v0.1.4
- Use a consistent, wide selection dialog with a fixed title/close row and scrollable choices. Follow existing rounded surfaces, restrained accent colors and 48dp touch targets.
- Google email addresses remain on one line, with ellipsis only when necessary. Full text remains available to accessibility. Move the selected check to the trailing edge instead of consuming email width with a radio button.
- Calendar destination shows the name once, with service/account below. Omit a repeated account label when it is identical to the calendar name; keep distinct calendars with identical names selectable by ID and account.
- Calendar choices are grouped by service, use source colors and mark the current choice. Preserve the event draft when opening, choosing or dismissing the picker. Existing event ownership stays locked.
- Keep the v0.1.3 widget typography/padding, source colors, silent refresh and recent-activity navigation behavior.
- Keep the last complete device-calendar snapshot on query failure. Clear it when calendar permission is revoked, and accept successful empty results. Background refresh must not narrow previously cached dates.
- A partial CalDAV collection listing is an error, not evidence that calendars were deleted.

## Login and account connection · v0.1.5
- Close Naver credentials after verified calendar discovery. Schedule event/task loading independently of dialog composition; preserve existing data during same-account reconnect and display a quiet first-sync status.
- Add a matching Google connection dialog with two readable cards: an existing device account or a new Google account. Keep email selection in the existing one-line picker.
- Android account consent and Google sign-in remain provider-owned screens; never imitate Google credential entry inside MOA. Cancellation leaves the selected account unchanged.

## Full app calendar and connected date ranges · v0.1.6
- App month/week grids show all event entries without +N aggregation. Week rows grow with their schedules and the calendar remains scrollable. Short titles may ellipsize; the selected-day list retains full titles.
- A single event spanning multiple dates is one horizontal bar per visible week, with its title once per segment. Never merge distinct events with matching titles.
- Clip bars at week/month-view boundaries and mark continuation with chevrons. Timed events use local dates; all-day events use UTC dates. Exclusive midnight ends do not occupy the following day.
- Share event placement between Compose and the native home widget. Overlapping events occupy separate lanes. The home widget retains at most 3 title rows plus accurate per-day +N counts and may reduce rows at small sizes.
- Every date remains a click target, including inside a spanning bar. Widget entry must select the actual tapped date and retain cached events while syncing. Source colors and silent refresh remain unchanged.
- Plan: lock date-span/overlap/overflow semantics with unit tests, implement shared placement, render app and widget, then inspect emulator screenshots and test widget navigation before build/push.
- Foreground saves/deletes must not wait for remote refresh REPORTs. Serialize refreshes separately and reject responses/errors older than a successful local write, account change or disconnect. Google provider writes remain independent of Naver network work.

## Discoverable event editing · v0.1.7
- Plan: expose the existing update operation through app calendar bars and explicit list actions, keep save visible, then verify updates preserve the source identity and do not create duplicates.
- App event bars open the corresponding editor; date numbers and empty space select dates. This overrides the app-only whole-cell tap rule above. Native home widgets continue opening the actual tapped date.
- Day and agenda rows show 수정 for editable events and 보기 for restricted entries. The editor heading is 일정 수정 and the primary action is 변경사항 저장.
- Keep the header and save action outside the scrollable fields, including when the keyboard is open. Show account identity, preserve the original calendar, and keep the draft open when validation or saving fails.
- Explain the specific read-only reason near the heading. Recurring events, invitations and tasks retain existing source-app restrictions.
