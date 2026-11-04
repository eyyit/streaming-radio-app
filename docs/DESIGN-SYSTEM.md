## Approved retail sizing - S8 revision 2 / P8 revision 3

Approved 4 November 2026. Native full-area Settings uses 36sp row labels,
equal minimum 112dp rows scaled for system font size, a 36sp/80dp heading,
and a themed 28sp/72dp Done control. The artwork switch is a 108x54dp themed
pill with a 44dp white circular thumb; it retains a minimum 72dp touch height.
Content scrolls when it exceeds the available app area. These are Settings
sizes, not a reason to change unrelated typography or image-picker geometry.

Playlist station rows retain 128dp height, 112dp artwork, existing text labels
and typography. Their heart/image actions use 80dp targets with 56dp glyphs,
centered vertically on the right. Caption and Remove-action widths must exclude
that action column, including on narrower windows. All Done controls retain
the current accent/onAccent colors. The media host still owns playback layout.
Mocks define app-owned geometry; exact physical vehicle scaling needs testing.

# Streaming Radio official design system

Effective2026-11-02. Applies to every app-owned screen, dialog, state, icon, mock and future feature. The latest explicitly approved mock/user instruction wins if a conflict arises. This codifies the established UI; the full-screen Settings/management flows and latest PL04/PL06 linked station actions were approved for implementation on 2 November 2026.

## Art direction

Modern dark media player: neutral charcoal, clear hierarchy, generous touch targets, rounded surfaces and restrained accents. Match the approved v11 image picker and established player rather than inventing adjacent layouts. No purple-tinted dialog backgrounds, white square panels, unnecessary branding headers or arbitrary gradients. Broadcaster artwork keeps its own colors. Native AAOS CarMedia and cluster layout are host-owned and outside this visual contract.

Use AutomotiveDesign constants for neutral colors: canvas#0d0e12; surface#20252c; control#333a43; field#15191e; text#f4f5f7; muted#bdc6d0; border#778390. Accent comes from AppearanceTheme, never a new hardcoded red/blue. Blue default; ROYGBIV previews (Red/Orange/Yellow/Green/Blue/Indigo/Violet) use representative play/pause, enabled toggle and heart; no slider samples. Blue is the default. Previews, with no color-name labels or disabled toggle. Semantic warning red is independent of the theme. Shape/wording also communicates state, not color alone.

## Preserve the approved composition

- Single bottom transport row: settings far left; previous/play-pause/next centered with approved gaps; shuffle then heart far right. Never move them onto another row incidentally.
- Expand at player top-right changes split→player-only; restore in same location returns to station list. No redundant Show playlist/Streaming Radio/Now playing header. No full-playlist mode or Android car player menu.
- Station row identity is always station name in larger type; current artist/track or useful stream description appears below. Do not replace station name with track/artist. Outline/filled hearts toggle Favorites. Explicit up/down reorder controls; disable movement at boundaries. No fake drag handles.
- Player prioritizes artwork and track/artist; station and bitrate/codec remain separate, readable metadata. Native host/cluster shows available service metadata but controls its own layout.
- Use the approved rounded-outline gear A and exact approved transport/refresh vectors. Reuse existing assets, never approximate with text glyphs/emoji. Shuffle selected state uses a dot, not a switch.

## Dialog and typography contract

Approved floating image picker keeps its large square preview, inline search, compact Previous/Next, generic-image choice, balanced Cancel/Use footer and clipped decoration. No decorative element escapes the preview bounds. Keep approved dimensions rather than silently scaling controls or moving rows. Existing management dialogs adopt the neutral palette now; the approved full-screen management area sits above the persistent playback dock.

Station/candidate names stay single-line with end ellipsis. Reserve stable heights for status/detail text so different results do not move the preview/actions. Input supports long queries without resizing the dialog. IME overlays image picker with ADJUST_NOTHING and stable pre-keyboard height; verify keyboard-open screenshot, focus and dismissal. App-level action buttons remain consistent rounded controls; avoid platform default rectangular/legacy styling.

Use established sizes/hierarchy and minimum48dp interactive targets; compact19dp artwork glyph may sit within a larger touch target. Content descriptions must name icon action and selected state. Test long station names, empty lists, font scale and keyboard; do not claim accessibility acceptance from static colors alone.

## Artwork, busy and failure states

- Never search Google Images implicitly: current station discovery is RadioBrowser directory logo matching. State that accurately. Do not report no results for network/download/quality errors.
- Only downloaded station originals at least512x512 on both axes qualify. Do not upscale favicons. Retain existing small originals without showing them at large size; use sharp generic initials fallback. Resolution does not establish copyright/license or subjective sharpness.
- Cache chosen artwork. Searching/previewing does not save; Cancel preserves saved state. Generic choice persists only on Use and suppresses automatic logo requests. Online-artwork off intercepts entry with approved warning popup/OK/Open Settings.
- Spinner appears only during actual image work, over artwork in refresh's corner/location. Native display-timed vector,19dp, approved partial-circle arc, translucent off-white with dark translucent keyline for white artwork; no gray backing disc. Refresh returns to the same position/path after completion. No misleading tiny fake busy flash for a skipped request.
- Never show Playing live as a status. Audio Connecting/Buffering describes playback only; image loading is independent. Save failure uses the approved red warning triangle. Disabled/off/no-result/outage/low-quality/download/save states remain distinct and preserve the existing image.
- Keep audio first: bounded image requests/decode/cache off Main/playback threads; no speculative broad image fanout or animation work when hidden.

## Change and acceptance process

For new layout/flow/art-direction changes, show identified mocks in actual AAOS dimensions and obtain explicit selection before implementation. Preserve options not mentioned by the user. Targeted approved correctness/style fixes do not authorize broader redesign. Update the implementation matrix/handoff to distinguish mock-only, source-only, built, installed and verified.

Compare the installed artifact to approved references and capture relevant states (not every unrelated screen repeatedly). Build/lint/unit tests cannot prove rendering, audible quality, driving safety or rights. Verify split/player-only, Favorites including empty, management, theme, picker ready/busy/results/empty/error/off/generic, long names and IME overlay. Record remaining gaps honestly.

Enforcement: AGENTS.md and .cursor/rules/streaming-radio-design.mdc point here; AutomotiveDesign supplies shared runtime tokens. Public source export must include this contract so future maintainers receive it. No commit/push/publication is implied by writing these rules.


PL04 station rows use112dp square transparent rounded logos within128dp rows; separate name/subtitle and one Remove… entry action. PL06 heading Remove from App? opens before mutation, two explanation cards for Hide from All stations and Remove from App, only top-right Cancel (no bottom duplicate). ROYGBIV/Blue default does not overwrite saved selections. RM01 retains exact refresh arc, withdraws arrow while rotation begins, returns arrow on real work completion; use display-timed animation, no fixed frame timer.


Button typography rule (2 November): Back/Done/Cancel and management action buttons share AutomotiveDesign.BUTTON_TEXT_SP=18sp, sans-serif-medium, consistent case/alignment. Artwork toolbar ART01–04 is pending approval, not implementation.

Artwork controls ART05–08 approved for implementation: icon-only vertical rail below expand/restore, Hide/Show then Refresh/loading; no artwork overlay or centered labelled toggle. Minimum48dp targets, accessible action names, no visible button discs; preserve artwork, metadata, dock and list controls. ART01–04 horizontal toolbar is superseded. (2 November 2026)


Player uses no Paused text; transport glyph indicates pause/play. Utility-column refresh renders28dp proportional to28dpneighbors, preservingpath/animation and56dptarget; otherloadingindicatorsretain19dp.

Empty library: show styled clickable Add a playlist instead of Choose a station; direct Add playlist form, no intervening manager. During playing with no artist/title show No track info received; clear when data arrives. No permanent station-support inference and no Paused label. Installed API33 verified, library remains0.


Missing-track notice:14sp dim footer above fixed dock,28dp reserved strip. Wait actualplayback max30s/twoICYintervals (cap5min) before factual No track info received; no stationpermanentunsupportedclaim. User keys generated only by user; do not generate/upload implicitly.

Settings sizing rule (3 November 2026): all Settings action and toggle cells are56dp high, including artwork and animation switches. Center captions and48dp switch touch targets within the cell; retain8dp row gaps. Screenshot documentation must say emulator/app-content captures, exclude generic emulator bars without depicting them as Volvo UI, and identify fictional demo content.

## Preserve approved design; consistency is mandatory

- A requested height/width/alignment fix changes only that property. Preserve font size, weight, line height, padding, colors, radii, icon geometry, labels, ordering and navigation unless explicitly requested or strictly necessary. State any necessary adjacent change before doing it. Never shrink text to make a row fit silently.
- Equivalent items on the same screen use the same label typography, row dimensions, alignment and spacing. A switch does not create a new heading level or justify larger text/cells. Differences must come from an approved hierarchy, not widget defaults.
- Use shared tokens/components rather than independent per-row literals. Settings labels are22sp; every Settings row56dp, switches48dp. Back/Done/Cancel remain18sp action typography; do not resize them with row captions. Preserve unrelated management views.
- Review the entire affected view, including plain rows, switches, long labels, disabled states and bottom controls. Compare before/after against approved references. Build success is not proof of visual correctness. Capture and inspect the installed result before claiming completion.
- Keep approved controls, touch targets, icons, content and artwork positions stable during unrelated fixes. Match established charcoal surfaces and theme semantics on every screen. Do not add headers, hide controls, redesign navigation or introduce a new font hierarchy as a side effect.
- New flows/structural redesigns require identified mocks and approval. Narrow corrections to these existing requirements do not require the user to repeat or reapprove them.
- Published screenshots must match the current source visually, identify emulator/app-content capture and fictional demo data, exclude misleading OS chrome without pretending it is Volvo UI, and never expose personal playlists or unlicensed broadcaster artwork.
- Update handoff docs and regression coverage for any discovered inconsistency. Record source/build/install/render/CI/publication status separately. Never describe an unpushed workflow fix as a passing GitHub run.

Settings caption font is shared sans-serif regular (weight400),22sp for both action and toggle rows. Done/Back/Cancel keep their existing18sp medium action style. The settings-typography instrumentation mode checks8captions,22sp,400weight and56dp rows and captures actual app content without replacing the user library.

Done-button rule (3 November): every app-owned Done action uses the selected theme accent background and on-accent text, matching Theme. Back and Cancel remain neutral. Preserve caption typography, dimensions and control positions. Shared button defaults enforce the Done treatment.

Done accent state must refresh when returning from Theme, not only when the Settings screen is created. Verify a nondefault theme roundtrip and restore the original user selection after tests.

Read-only management dismissal contract (3 November): Settings, Manage playlists, individual playlist views, hidden/removed listings and any station-image listing close with themed Done, not a neutral Back label. Existing dismiss/parent behavior is unchanged; Android Back remains navigation. Forms retain neutral Cancel and accented explicit Save/apply actions. The Station images Settings entry is removed; use per-station image controls in playlist management. Derived playlist names expose host and path/filename, omit credentials/query/fragment, are bounded safely, and use middle ellipsis to keep host/file ends visible; custom names remain intact.
