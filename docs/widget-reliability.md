# Widget reliability changes

Based on `boudywho/codex-quota-android` at `11315161acdc8839972dbaeb0c910a0afdd36d9f`.

## Correctness

- Subscriber HTTP errors no longer produce a fabricated 100% remaining reading or use access-token expiry as a quota reset.
- Authentication expiry no longer produces a fabricated 0% reading.
- Missing or invalid subscriber percentages are treated as a failed reading.
- Failed refreshes retain the last successful snapshot, including both windows, reset times, and the original fetch timestamp. Account authentication/error status still changes.
- A successful API authentication check without quota headers leaves the quota unknown.
- A relative `reset_after_seconds` is accepted when an absolute reset timestamp is missing.
- Optional subscription-details failures do not invalidate a valid quota response.
- Requests have a 30-second overall HTTP timeout. Refreshes of the same account are serialized.

## Widgets

- Glance content observes the database and preferences while its composition is alive, instead of capturing an immutable reading before `provideContent`.
- Database changes from foreground refresh, account edits/removals, and theme changes trigger widget updates.
- Each widget size can refresh through a persistent, unique WorkManager request. Rapid repeated taps are coalesced while work is pending/running.
- Small and medium widgets persist the selected account in Glance preferences. Deleting that account shows setup rather than silently switching to a different account.
- All sizes show the last successful update time and distinguish offline, retry, stale, and sign-in states. Cached percentages remain visible when authentication expires.
- Medium progress bars now represent the remaining percentage; previously any positive value filled the entire bar.
- Newly created preferences default to 15-minute periodic sync. Existing interval choices remain unchanged. Application startup respects disabled background sync.
- Transient background errors trigger bounded exponential retries. Updating one widget size cannot block the other sizes after a Glance error.

## Two-account layouts (widget-fix.2)

- The multi-account provider now shows exactly two accounts. Its default size is 4x2, with a 48dp minimum resize height to permit a 4x1 placement.
- Below 108dp actual content height, accounts appear as two compact horizontal rows: name, weekly remaining/reset, 5-hour remaining/reset, refresh. There is no title row or progress bar.
- At 108dp or higher, accounts occupy equal left/right panels. Each panel contains separate weekly and 5-hour remaining rings, their reset countdowns, and the last successful sync status/time.
- The configuration activity stores both account IDs, independent display-name aliases, and account order in widget preferences. Swapping accounts swaps their aliases too. Blank aliases follow the account nickname.
- Empty selections and unknown quota windows remain visibly empty. An expired reset timestamp is labeled `due`; it does not fabricate restored quota.
- Only the ring artwork is drawn to a small transparent bitmap. Numbers, window labels and countdowns use native widget text. No image-generation service, account access, or network call is involved in drawing the rings.
- Robolectric native rendering tests inflate the actual Glance RemoteViews at eight launcher sizes, check text visibility and clipping, and write PNG previews to `outputs/widget-previews/`. Separate tests cover account selection, aliases, deletions, missing data and ring fill.

## Test build

The debug package is `com.codex.quota.debug`, labeled **Codex Quota Test**, version `1.2.0-widget-fix.2` (version code 21). It installs alongside the upstream release and updates the first local test build using the same signing key. Add accounts and widgets in the test app separately if starting from the upstream release.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug` (Windows: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`) from the repository root. See the [README build instructions](../README.md#build-from-source) for SDK requirements and an optional configuration that keeps tools, caches, temporary files, and the debug key on D:.

## Phone verification

1. Sign into the test app on the phone using its normal device-code flow. Compare weekly and 5-hour values against the official usage display at approximately the same time.
2. Add each widget size. Configure two single-account widgets with different accounts, then reorder accounts in the app: each widget must keep its selection.
3. Tap the widget refresh control. Confirm the update time advances after a successful read without opening the main activity.
4. Disable connectivity and refresh from the app. The last percentages and update time must remain; status should indicate offline. A widget refresh made while offline queues until connectivity returns.
5. Restore connectivity, refresh, and confirm the time and percentages recover. A genuine server reading of 0% or 100% remains valid.
6. Check the widgets after screen-off/overnight idle and after a normal process restart. Compare the shown update time before assuming the reading is current.
7. If a session expires, confirm that cached values remain labeled **Sign in**; sign in again through the app.

## Limits

WorkManager periodic sync has a 15-minute minimum, and Android battery/Doze/OEM scheduling can delay it. This build does not guarantee continuous or second-by-second background readings. Manual refresh requests are subject to network availability and OS scheduling too.

The subscriber REST endpoint used by upstream (`chatgpt.com/backend-api/wham/usage`) is separate from the documented Codex app-server `account/rateLimits/read` interface. Official app-server field semantics are useful for checking window percentages and reset timestamps; they do not guarantee this third-party REST endpoint's availability. An expired token still requires sign-in; this change does not add automatic OAuth token renewal.

References:

- [Upstream source](https://github.com/boudywho/codex-quota-android)
- [OpenAI rate-limit field semantics](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt)
- [Android Glance state and updates](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)
