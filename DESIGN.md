# Design

## Source of truth
- Status: Active. Last refreshed: 2026-09-09.
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
