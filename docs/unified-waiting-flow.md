# Unified waiting flow

Implementation branch: `codex/unified-waiting-flow`, based on friend dashboard commit `82eb2d2` plus the separate debug installation setup and system flat themes.

## Implemented

- Content can be browsed before starting a timer while vehicle safety gates allow it. Tapping the sidebar timer opens a larger animated time/target dial modal. Start applies the draft; dismissing leaves the existing plan unchanged. The sidebar dial then displays a countdown or live target-specific charge estimate. Reset in the modal clears the plan and cancels pending nap starts. The start action uses inverted background/text colors.
- Manual duration uses elapsed realtime, rounds up until the deadline, and is preserved across Activity recreation. Charge mode continues to use `ChargeTimePlanner`: OEM target-matched ETA, then recent SOC trend; unknown estimates stay unknown. No charge command is issued by starting a waiting plan.
- Recommendation time is the shortest available budget among the selected duration, the conservative charging estimate and an optional departure deadline. Time and charge estimates are separately labelled.
- Video, music, nearby places and nap occupy one vertical feed. An icon dock sits on the content side (right by default; independently configurable left). Scrolling expands/highlights the current section label. Touch/focus magnifies icons; activation scrolls to the section. Settings and panel placement stay in the sidebar. Settings replaces its entry with one centered Apply settings button. The wide layout has no horizontal app header or bottom tab bar. Narrow windows stack the sidebar above the content.
- New/default text scale is 130%; explicit previous selections are retained. Settings use one column and rows with a name, short explanation and control. System background is flat white/black; explicit black/white choices lead the list. Gradient options remain available.
- Departure uses a large themed digital wheel dialog (24-hour time; earlier times mean tomorrow), with touch, keyboard and accessibility adjustments.
- Music provider selection and external sign-in handoff share one dialog. An external service opening is not evidence of a connected account or successful playback.
- Sleep has only a remaining-time start action and a seat-settings action. Starting sleep shows a black app surface with a countdown and End button. End restores the content and brightness without clearing the waiting plan. No wake buffer, climate controls or sound choices remain in the current UI. Seat memory application remains explicit and parked-state guarded.
- In-memory waiting state is retained while backgrounded and during Activity recreation, but a fresh process starts at setup. Nap retains the existing foreground-only behavior.

## Earlier layout verification (2026-11-14; latest results in implementation-status.md)

- `:core:test`: 81 tests, zero failures/errors (including new deadline, budget and live-ETA tests).
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug`: successful; lint zero errors, 66 warnings.
- `WaitingFlowProbe`: 35 emulator checks using an in-memory simulated vehicle, covering content preview, dock placement and scroll highlighting, timer modal/cancel behavior, inverse start styling, single settings action, vertical section order, duration and OEM ETA linkage, flat themes and retained state/media object, single-column settings, departure/music dialogs, driving and non-charging gates. No vehicle writes, location requests or sign-ins.
- Nearby server: 16 tests passed; local health reports configured OSM dataset.
- Debug app remains `com.binteum.app.friend`, separately installed from `com.binteum.app`.

## Manual checks still needed

App-window PixelCopy captures were inspected for the current sleep layout. Swipe feel, actual YouTube picture/audio, external music sign-in/playback and physical vehicle behavior still require separate verification. Live SDK location results are recorded in implementation-status.md. Existing Pleos TTS engine/server permission limitations are unchanged. Test driving restrictions with simulated data; no real vehicle control was exercised by the layout probe.

## Dock and spacing refinement

- Borderless backdrop on the content dock: Android 12+ GPU blur uses a 3dp edge layer and a 22dp center layer. Each item has its own feathered footprint: the active item includes its full label, others cover their icon. DockContentLayout records the content once per frame and shares that GPU display list with the blur. No bitmap capture, software View.draw or periodic snapshot updates remain. Older devices without GPU blur show the dock without a blur layer.
- Section labels fade/translate in 90ms within stable measured bounds; the active icon stays at 1.28x scale. Selection does not trigger a bounds transition or resize the content.
- Music provider choices show the installed app icon when available, otherwise an offline vector identifier.
- Section padding, grid gaps, card padding and button margins increased. The settings/apply action occupies the bottom of a filled sidebar.
- Removed the requested nearby-location explanatory line; location permission and service behavior remain unchanged.
- Probe starts in a fresh activity task so an external settings activity with the same affinity cannot block startup. App data is not cleared.

## Scroll flicker regression

The former blur path redrew a live ScrollView onto a mutable software bitmap while the hardware renderer also consumed it. It has been replaced by GPU display-list replay. Dock label geometry is stable to avoid remeasuring the scroll host at section boundaries. A 24-frame emulator fixture checks that content is drawn on hardware canvases only, frame rendering continues, and source/dock layout sizes remain stable. These automated checks do not replace visual review of the reported flicker on the user's screen.

## Integration fixes — 2026-11-15

- Video choices remain directly accessible before, during and after the selected waiting duration; duration-fit advice and fresh charging/vehicle safety checks remain separate. A short or zero recommendation budget does not remove the catalog.
- Nearby **Other categories** and Back restore the nearby section after layout instead of jumping to the first video. An open video consumes Back before a retained nearby selection.
- Inactive dock labels keep their measured bounds for stable layout, but their invisible area passes touch through to the content. Active labels and inactive icons remain actionable, including keyboard/accessibility clicks.
- Waiting-plan saved state is bound to a process token. Activity recreation within that process keeps the original monotonic deadline. A fresh process, older saved state without a token, or invalid/reset monotonic clock returns to setup.
- Choice history v2 stores the effective available seconds separately from the raw charge ETA/source. Familiar-choice indicators use the available-time bucket, including a shorter timer or departure deadline; nearby indicators include food and walking choices. Existing rows retain their recorded charge context, and unknown historical limits are not reconstructed. The settings label describes preference indicators rather than claiming that the fixed section order is personalized.
