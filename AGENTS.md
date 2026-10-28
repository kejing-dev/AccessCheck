# Access Check (无障碍体检)

Home accessibility check for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.accesscheck`. You place a 1:1 virtual wheelchair, push it around your
bathroom by walking (it stays ahead of you), and everything it bumps turns red and leaves a scuff on the
wall. Stopped, you can turn it around (1.525 m turning disc, red where it is short), see the clear-floor
rectangle, read the door width and threshold at a doorway, sit or crouch to a seated / child eye height,
pin problem spots, and save a text report.

Design package (binding): `../spatial-app-factory/apps/access-check/design-package.json` (archetype D,
container STAGE_MIXED, window model single_panel_with_popup, 30 states S1 - S30, rules, copy). Mockup:
`../spatial-app-factory/apps/access-check/sample.html`. Reference project (same skeleton): `../spatial-ruler`
(its `AGENTS.md` holds the SDK findings this app relies on); the platform / scene plumbing was taken from
`../fire-plan`, the factory's previous build on this skeleton.

Built by the factory's build station on 2026-10-25 (v0.1.0, debug) and run on emulator-5560 (AVD
Pico_Emulator_6_1_fresh): two clean cold starts (no ANR), 30 design states captured with
`tools/shoot_all.sh` (see "QA hooks"). Not yet on a headset.

v0.1.1 (2026-10-26, polish after the user's headset feedback "placing the wheelchair is hard, it should
follow where my controller ray meets the floor"): placing with a ghost on the pointer's floor point,
trigger / pinch / console to put it down, trigger-drag of the parked chair, "bring here" / "place again",
0.8 m clearance around the head and a see-through chair around it (taste profile D1 / D14), the
console beside the push handle while pushing (design v2, QA q1-1) and clear of the chair otherwise, level
(yaw-only) panel follow 18° down (D9 / D10, UI sweep), hint on top of the console, mute and "bring the panel
back" keys (D15 / D9), virtual floor after 5 s without one (D7).

v0.1.2 (2026-10-27, the user on the headset: "after the first placing, pushing should start when the
controller trigger or grip is pressed, not follow the ray's floor point; hands too, gripping the chair to push
it; panels should follow the head and face it"): the parked chair is pushed while held (GripUseCase,
PushFollowUseCase.startHeld), the floor-point trigger drag is gone, the console no longer hangs beside the
push handle and faces the head every frame (Follower.facingYaw), the placing ghost uses a hand's aim ray when
no controller aims (taste profile D1: never the head unless nothing else is tracked). Also in v0.1.2: the
chair's size is adjustable (ChairModel = preset + width / length / height in cm, More menu sliders, remembered;
the door pass needs the chair width + 5 cm), the colour roles were fixed after "the UI colours are off" (dark
fills carry labelPrimaryLight, selected states the accent with ink; every text >= 4.5:1, measured in
build/ui-audit.md), the menu opens to the right of the console, the console slides aside before it lifts, and
the chair plate floats over the chair's front end.

## Structure

```
app/src/main/java/io/embodit/accesscheck/
  Main.kt                      DefaultStage { AccessCheckTheme { AccessScreen() } }
  platform/                    SpatialApplication (launch), LaunchActivity, AccessGraph (wiring), AcLog
    sense/PlaneTracker         plane detection -> PlaneSurface (Stage metres)            [from Spatial Ruler]
    sense/MeshTracker          spatial mesh anchors; vertices in Stage metres for the occupancy grid
    tracking/HeadTracker       HMD pose, lost-tracking sign, block reason                [from Spatial Ruler]
    input/ControllerInput      controller ray, trigger, thumbstick                       [from Fire Plan]
    report/ReportWriter        MediaStore write to Download/AccessCheck (fallback: app documents dir)
  domain/model                 Vec3 / Quat4, V2 / Obb2 + 2D tests (Geometry2), PlaneSurface, Access.kt
                               (AccessRules = every number of the design, ChairModel, ChairPose, Obstacle,
                               DoorInfo / DoorEntry / Verdict, Scuff, Pin, records, LocationName)
  domain/usecase               PushFollowUseCase (handle follows the hand; JVM tests), ObstacleField (walls with
                               doorways cut out, furniture blocks, 5 cm mesh grid, virtual obstacles; bump / turn /
                               clear-floor / nearest-wall queries), DoorUseCase (doorway from a plane, verdicts),
                               LocateUseCase (place names), HitSurface / ViewFrame / FacePanel / RestBody  [Ruler],
                               FloorAimUseCase (ray -> floor point, last good point, clearance, facing away,
                               ray vs chair / panel; v0.1.1), ConsoleLiftUseCase (the console lifted clear of the
                               chair), GripUseCase (handle bar, trigger / grip / fist / pinch hysteresis; v0.1.2)
  data/SettingsStore           SharedPreferences: chair model, language, units, coach seen
  ui/theme                     AcColors (the only colour literals), AccessCheckTheme (PicoTheme, 16 roles), typography
  ui/access                    AccessViewModel (the whole state machine), AccessUiState / AccessEvent, AccessScreen
  ui/access/components         AccessStage (SpatialView + panel pool), ConsolePanel, Plates (chair plate, door plate,
                               door card, pin tag), ReportPanel, MoreMenu, Overlays (dialog, hint, coach), Common,
                               Format (readings, names), ReportText (the saved file), Strings (runtime language)
  ui/access/scene              AccessScene (ECS: one frame System; push follow, bumps, drag, thumbstick, doors crossed,
                               panel placement), ChairView (procedural wheelchair + footprint + drag target), MarksView
                               (scuffs, pins, turning ring + arrow, clear-floor and snap frames, door gates, reach band,
                               eye line), PanelRig (pooled panels, Spatial Ruler findings), Follower (lazy follow),
                               PlaneColliders (tap targets + backstop), SceneKit (meshes), SceneMaterials
  ui/access/debug/AcProbe      seams for the debug receiver (no-ops in release)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
app/src/test/                  PushFollowUseCaseTest (forward, backward, sidestep, head turn, arc, behind sector),
                               FloorAimUseCaseTest, ConsoleLiftUseCaseTest, GripUseCaseTest, FollowerTest (yaw-only
                               follow, 10-20° down, facing the head); PushFollowUseCaseTest also covers held pushing
tools/make_icon.ps1            the 512 icon export + legacy mipmap (System.Drawing)
```

One direction of data: panel controls, scene callbacks (taps, bumps, chair pose and head ten times a
second, planes, tracking) and debug hooks -> `AccessViewModel.onEvent` -> `AccessUiState` ->
`AccessScene.render` (idempotent diff of entities) and the panels. Per-frame work (push follow, bump
test, drag, thumbstick, turn animation, reach band, eye line, panel placement) lives in
`AccessScene.onFrame` only and never goes through Compose. While the scene drives the chair (pushing,
dragging, thumbstick) it owns the pose and reports it at 10 Hz; otherwise the ViewModel's pose
(`poseSerial`) is authoritative (place, rotate keys, size change, snap).

## The floor plan (`ObstacleField`)

- WALL / VIRTUAL_WALL planes become 4 cm thick bars; DOOR / OPENING planes whose bottom edge is within
  10 cm of the floor are cut out of them.
- CABINET / WASHING_MACHINE / REFRIGERATOR / BED / SOFA / CHAIR top planes, and TABLE tops under 0.71 m,
  become solid blocks down to the floor; vertical planes of those kinds become bars.
- Mesh vertices 0.10 - 0.68 m above the floor and more than 5 cm from any wall fill a 5 cm grid (toilet,
  basin cabinet, bath rim). Rebuilt at most once a second. Whether the emulator reports any mesh is a
  design `needsCheck`; `obstacle_add` stands in for it.
- Only obstacles whose lower edge is under 0.68 m take part. Bumps = footprint OBB against them (SAT);
  the turning disc = 72 rays from the centre plus the floor polygon plus anything inside the disc;
  clear floor = the 0.76 x 1.22 rectangle against them (red first: a bumping footprint reports no parking).

## Interaction

- Place (v0.1.1, taste profile D1): the console's big button starts placing (S31). A see-through yellow
  ghost follows the point where the controller ray meets the floor (no controller: the gaze ray; a ray
  that goes level / up or lands farther than 6 m keeps the ghost on the last good point and shows "point
  at the floor"). The ghost faces away from you (the pushing direction), the thumbstick adds a turn; its
  centre stays 0.8 m from your head (D14, pushed out along the line from the head); it turns red where it
  would bump. The trigger puts it down (a ray resting on a panel moves and confirms nothing); a pinch on
  the floor puts it where the hand ray met the floor, a pinch elsewhere or "Put it here" on the console
  where the ghost stands; "Cancel" goes back (to the old spot when placing again). The chair is then parked;
  pushing is optional ("Push").
- Push it by holding it (v0.1.2): a controller whose trigger or grip goes down (0.6 on, 0.4 off, or the
  pressed flag) with its ray on the parked / waiting chair or itself within 25 cm of the push handle bar, or a
  hand that closes at the bar (fist: index / middle / ring tips within 6 cm of the palm, open past 8 cm; or a
  pinch 0.8 on / 0.5 off), takes hold: the chair is pushed (PUSHING) while any holder holds, and parks (door
  snap applies) when the last one lets go. The input is the held grip's floor point (the midpoint of two)
  instead of the head; the handle's offset from it, along and across the chair, is locked at the press (no
  ease); steering is the push rule's (forward travel turns towards the drift, backing up is straight); with
  a left and a right grip held the heading follows the line between them (offset by the turn at the moment
  both closed). The thumbstick of a holding controller turns it. More grips join at the handle; the system
  pinch drag of v0.1.0 stays for a far hand. "Bring here" puts it 1.2 m in front of you facing away; "Place
  again" goes back to the ghost. Standing inside the chair turns it see-through (22 %) so it never fills the view.
- Push (handle follows the hand): the head's floor point, 3 cm dead zone, 0.15 s low pass; forward
  travel steers at most 0.9 rad per metre, backing up is straight, head turns move nothing. "Push" from
  stopped only starts when you stand in the chair's tail sector (±60°, 0.2 - 1.0 m from the handle);
  otherwise "stand behind the wheelchair" waits for you. Standing still while pressing a wall for 2 s
  gives the stuck hint with "stop and drag".
- Stop: drag the chair over the floor (press and drag on it; emulator mouse; a controller or hand that
  holds it pushes it instead, above), turn ±15° with the keys,
  or turn with the thumbstick (taste rule D1, 90°/s at full deflection). Near a shown door a dashed
  yellow frame marks the approach pose 0.3 m before the door; within 25 cm / 15° the chair snaps to it on
  release.
- Turn around: 1 s spin, the ring and the plate show the result; red arrow towards the blocker.
- Doors: plates appear within 1.5 m (two at most), hide past 2.5 m. The plane estimate is "about" and
  can only say "too narrow" or "measure to confirm"; tap the plate for the card: threshold segments and
  the tape row (-5 / -1 / +1 / +5, confirm). Green needs a measurement of 81.5 cm or more and threshold
  "none". Pushing through a blocked door flashes the gate red; a 2 cm+ threshold flashes the sill.
- See from: seated 1.20 m / child 1.00 m; the eye line is drawn on the wall ahead, the console readout
  says how much lower / higher; ±5 cm = green.
- Flag issue: tap a surface, pick a tag (out of reach / can't see / hazard; child mode pre-highlights hazard).
- Report: card right of the console; save = UTF-8 text in Download/AccessCheck/access-check-YYYYMMDD-HHmm.txt.
- Tap on empty space = close the card, leave the pin tool.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels, fixed sizes: `console` 400 x 496 at 0.75 (fixed glass, rows anchored at the
top; v0.1.1 adds the mute / panel-back keys in the title row and the "Bring here / Place again" row),
`menu` 320 x 464 (v0.1.2: chair size sliders) and `report` 440 x 560 beside it on the right, tops aligned
(never open together; v0.1.1 had the menu above the console, too tall now for D10). The console family follows lazily 18° left,
18° down (20° while pushing), 0.6 m, leaning back 12°, in the head's level frame (yaw only, v0.1.1:
looking down at a threshold no longer drops it under the body), and wherever it rests it turns to face the
head's current position every frame (v0.1.2; v0.1.1 hung it beside the push handle while pushing, design v2
q1-1, dropped at the user's "panels follow the head and face it"). When the chair would hold or hide it, it
rises to 0.15 m above the chair's box, its top kept 8 cm under the eye, and what that capped lift cannot clear
a slide of up to 0.3 m to the viewer's left does (ConsoleLiftUseCase.sideShift); it faces the head from there. A pushed / waiting / placing chair takes no taps (the pointer passes through to the console).
`hint` 480 x 64 sits on top of the console at 0.75: below the view line.
`chairPlate` 280 x 96 over the chair's front end (v0.1.2; over the push handle it hung in the face of whoever
stood behind the chair) (hidden while pushing); `doorPlateA` / `doorPlateB` 300 x 112
and `doorCard` 300 x 320 on the door's centre line 20 cm under the top edge; `pinTag` 360 x 72 above a
fresh pin; `dialog` 440 x 240 1.0 m away, 8° right and 10° down in the level frame (clear of the console),
placed once the head pose is known; `coach` 360 x 96 ahead of the chair. Plates, hint and report use
an opaque ink root (design: no large glass in the room). Copy: `res/values/strings.xml` (en) and
`res/values-zh`, read through `rs()` so the in-app language switch works without a restart. Fonts: Noto
Sans + Noto Sans SC bundled under OFL (the SC font whole, 17 MB, as in Fire Plan).

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.accesscheck.DEBUG -p io.embodit.accesscheck --es cmd '<command>'"
adb -s <serial> shell "am broadcast -a io.embodit.accesscheck.DEBUG -p io.embodit.accesscheck --es cmd head --ef x 1.2 --ef z -0.4 --ef yaw 90"
```

| command | effect |
|---|---|
| `state S1` … `state S30` | straight to that designed state (places / parks the chair, injects a wall / cabinet / door / pins as needed) |
| `state S31` | placing (v0.1.1): the ghost 1.2 m ahead; move it with `aim` / `ray`, put it down with `trigger 1` then `trigger 0` |
| `aim x z` | stand-in controller 25 cm right of / 45 cm under the eye, pointing at the floor point (x, z) |
| `aim_ahead m [side]` | the same, at the floor point m ahead of the head (level) and side to its right |
| `ray ox oy oz dx dy dz`, `ray off` | stand-in controller ray in Stage metres (`ray off` = the real controller) |
| `trigger 1|0`, `stick x` | stand-in trigger and thumbstick x (-1..1; `stick 0` releases) |
| `chair manual|power|child`, `chair_size w l h` | chair preset / its size in cm (v0.1.2; clamped to 55-80 / 90-130 / 80-110) |
| `grip 1|0`, `move_ray dx dz` | stand-in grip; slide the stand-in controller level. Held push: `aim` at the chair, `trigger 1` (or `grip 1`), `move_ray 0 -0.5`, `trigger 0` |
| `hand x y z [open|fist|pinch] [left|right]`, `hand off` | stand-in tracked hand (palm, Stage metres) |
| `hand_handle [open|fist|pinch] [left|right]`, `hand_move dx dz` | the stand-in hand 5 cm behind a grip of the push handle; move it. Hand push: `hand_handle open`, `hand_handle fist`, `hand_move 0 -0.5`, `hand_handle open` (or `hand off`) |
| `replace`, `bring`, `place_here`, `place_cancel`, `mute`, `recall` | the new console keys |
| `place [x z yawDeg]` | place the chair ahead of the head, or at a Stage floor point |
| `place_ahead m [side]` | where `place` / `state Sxx` land the chair from now on (default 1.2 m ahead, 0 sideways). On the emulator room `place_ahead 1.7 -0.3` is free floor in front of the camera after `tools/shoot_all.sh` moved it 1.4 m back; 1.2 m puts the chair under the viewer's nose and on the platform edge |
| `push`, `park`, `turn`, `rotl`, `rotr` | the console keys |
| `head x z [yawDeg]`, `head off` | override the head's floor point (and heading): the push follow runs from it; send a few in a row to walk |
| `drag x z` | move the parked chair to a floor point (as a drag would) |
| `door width [measured] [thresholdCm]` | e.g. `door 0.90 1 0` = measured 90 cm, threshold none (S10); `door 0.78 1 0` (S11); `door 0.70 0 2` (S12); `door 0.86` (S14). Uses the nearest real door, else a virtual door 1.2 m ahead |
| `eye h`, `eye off` | override the eye height above the floor (S15 / S16 with `height seat`) |
| `height stand|seat|child` | the See-from segment |
| `pin [reach|see|hazard]` | a pin on the wall ahead (no tag = the tag card, S17) |
| `pin_fill n`, `scuff_fill n` | S29 / S30 |
| `obstacle_add x z w d h`, `obstacles [off]`, `obstacles_clear` | a virtual obstacle; draw the whole field; remove the virtual ones |
| `report`, `save`, `menu`, `clear`, `clear_now`, `exit` | cards and actions |
| `block [permission|fullspace|device|off]`, `lost 1|0`, `coach_reset` | S20, S24, S21 |
| `chair manual|power`, `lang system|zh|en`, `units cm|in`, `threshold cm` | settings |
| `ui <control>` | presses a panel control by name (`DebugCommands.kt` lists them) |
| `dump` | `DUMP {json}` (also files/dump.json): state name, chair, doors, pins, head, obstacle counts |
| `pose`, `bounds`, `planes`, `park_panel`, `fps on|off` | scene queries (`planes` also lists every obstacle) |

Screenshot list for QA (design `states[].qaCapture`): S1 (console disabled, spinner), S2 (`state S2`:
scanning hint), S3, S4 (`state S4`, then `head` a few times to walk), S5 (`state S5`: a wall is injected
at the chair's front), S6, S7 (`state S7`), S8, S9 (`state S9`: cabinet injected at the right), S10, S11,
S12, S13, S14, S15, S16, S17, S18 (seeds sample content), S19 (saves; `adb pull` the file), S20, S21,
S22, S23, S24, S25, S26 (spin held), S27, S28, S29, S30, S31 (placing ghost; `aim` a few points, one beyond
6 m / `ray 0 1.5 0 0 1 -1` for "point at the floor", one under your feet for the clearance push). `tools/shoot_all.sh` does all of them (relaunch,
`place_ahead 1.7 -0.3`, `eye` overrides for S15 / S16, the resets each state needs). In the emulator room the
pushing states (S4 / S21 / S28) show the chair red: pulled to 0.35 m ahead of the viewer it overlaps the low
platform (a 0.18 m BED block), which is the room, not a bug. Console screenshots: take two in a row (the
first often shows the previous frame).

Log tag `AccessCheck`: `STATE Sxx`, `PLANES`, `MESH`, `TAP`, `DRAG`, `PLACE`, `CHAIR mode`, `PUSH`, `BUMP start|end`,
`SCUFF add`, `TURN`, `CLEARFLOOR`, `DOOR`, `SNAP`, `PIN add`, `VIEW`, `HINT`, `COACH`, `REPORT saved`, `CLEAR`,
`FOLLOW`, `TRACK`, `CMD`, `DUMP`, `POSE`, `PANEL`, `OBSTACLE`, `FPS`; v0.1.1: `PLACE start|trigger|cancel`, `AIM floor=`,
`BRING`, `RECALL`, `SOUND muted=`, `FLOOR virtual|real`, `POINTER`; v0.1.2: `HOLD start|join|end by=ctl-l|ctl-r|hand-l|hand-r`,
`PUSH start by=` / `PUSH end by=release`, `TRACK hands support=`, `HAND`.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p io.embodit.accesscheck -c android.intent.category.LAUNCHER 1
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src`
must be empty. Icon: `powershell -ExecutionPolicy Bypass -File tools\make_icon.ps1 <out.png>`.

## Deviations from the design package (recorded in the factory's state.json)

- Dashed lines (footprint while pushing, clear-floor frame, snap frame) are drawn solid: no dashed
  material; the thickness and colours are the design's.
- The two scuff ellipses of a contact are the entry point and the exit point as the obstacle field
  reports them (nearest point of the obstacle to the chair centre), the scrape between them; the
  "same spot darkens" rule uses a 10 cm radius.
- Noto Sans SC is bundled whole (17 MB) instead of a subset (no fontTools on the build machine).
- Door plates face the viewer (auto-facing) rather than the chair's side strictly; they stand on the
  door centre line nudged 3 cm to the chair's side.
- The console is a fixed 400 x 496 glass (v0.1.0: 416) with its rows anchored at the top instead of a glass
  that grows and shrinks with the state (a hugging glass left the rest of the pooled panel black on the emulator).
- v0.1.1: placing no longer drops the chair 1.2 m ahead and starts pushing (design `interactions.place`): it is
  the pointer ghost of taste profile D1 and ends parked. New state S31 (placing). The console sits 18° down
  (design 26°; 20° while pushing, design 32°) for taste profile D10, and the hint bar on top of the console
  (design: 15° above the view centre). Virtual floor after 5 s without a FLOOR plane (design: the place button
  stays disabled), taste profile D7. Mute key (D15).

## Emulator findings (2026-10-25, emulator-5560)

- `MeshResource.createBox(size)` came out about twice the asked size on the emulator; every box-shaped part is now
  the unit cube from `MeshFactory.cube` (MeshModel, -0.5..0.5) scaled per entity, so the chair and the gates are
  built without creating a single mesh (the idle emulator takes about a second per mesh on the main thread).
- A pooled panel whose glass hugged its content left the rest of the panel black: the console glass is a fixed
  400 x 416 now, rows anchored at the top.
- The emulator room reports the floor at y = 0.40 with the head at 1.65 (eye height 1.25 m), 57 planes
  (one DOOR, far away), 48 mesh anchors (436 grid cells after the wall / furniture filter). Quad vertex lists
  are not in edge order: furniture blocks are fitted as the smallest rectangle over the triangle edges.
- Screenshots: `tools/qa_shot.sh Sxx` (state, `fast on`, settle, console capture) and `tools/shoot_all.sh`
  for all 30; `tools/launch_check.sh` watches a cold start for ANR dialogs; `tools/thumb.ps1` / `tools/sheet.ps1`
  make thumbnails and contact sheets for review.

## Not built / not verified

Headset-only:
centimetre-accurate bumps, mesh coverage of toilets / basins, door width versus the real clear width,
real pushing feel and real sitting / crouching eye height, permissions, whether the PICO file manager
shows the report file. Emulator: whether DOOR / OPENING planes are reported (else `door` injects one),
thumbstick in eye mode (controller DEVICE_NOT_SUPPORTED on the emulator per Theater Planner).
