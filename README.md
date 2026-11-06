# 차곡 · Chagok

*The noise the shop can never reproduce — your car remembers when it happened, and speaks up first.*

Chagok is a family-car service for Android Automotive (Hyundai Pleos). A driver who hears an intermittent squeak says **"record that"**, and the car stores the 30 seconds of vehicle state around that moment: brake pedal, steering angle, odometer, and the navigation guidance situation. When the same driving conditions come back, the car tells the driver *before* the symptom is likely to appear. Records are handed over to the next family member who drives the car, handed to the mechanic as a report, and kept as one case until it is marked resolved.

Chagok does not listen to sound. It remembers the vehicle state at the moment a person felt something, and compares that state later. All signal processing runs inside the vehicle app; no vehicle data is sent to an external AI.

This repository holds:

- [`chagok/core`](chagok/core) — the reproduction-alert engine, pure Kotlin/JVM, no Pleos SDK dependency. **Builds and passes 87 tests.**
- [`차곡_통합서비스_시각기획서_v3.3_제출본.html`](차곡_통합서비스_시각기획서_v3.3_제출본.html) — the product plan the code implements (Korean, v3.3 submission).
- `app` module (Pleos Vehicle / NaviHelper adapters, Room storage, in-car UI) — **not yet started**; see *Next steps*.

## Three pillars

| Pillar | What the driver sees | Signals used |
|---|---|---|
| 1. Moment capture | "Record that" → 20 s before + 10 s after saved as one record | Brake · SteeringWheel · Odometer · NaviHelper guidance type |
| 2. Reproduction alert | Entering the same conditions → "This is the condition from last time" | Same signals + a 3-stage rule-based match, in-vehicle |
| 3. Handover & resolution | Boarding briefing → repair → post-repair check → resolved, delivered to the original recorder | Odometer · user input |

Vehicle speed is deliberately not used: `VehicleSpeedListener` was removed in Vehicle SDK v2.3.2, and alternative AAOS access could not be confirmed from public material.

## How matching works (plan §02-1)

1. **Base filter** removes ordinary driving: brake held ≥ 1.5 s and |steering| ≤ 10°.
2. **Per-issue condition profile** narrows further: the min–max of recorded values for that issue ± a tolerance, plus the set of recorded navigation guidance types (`STRAIGHT`, `TURN_AHEAD`, `NO_GUIDANCE`). `DATA_UNAVAILABLE` (NaviHelper gave nothing) excludes the navigation item and raises the threshold (cooldown ×2) instead of blocking.
3. **The driver decides**, after the car stops: *felt / didn't feel / couldn't check*. Only these three counts are kept; no probability or score is computed. Three consecutive *didn't feel* answers ask the driver to reduce or stop alerts; ranges are never widened.

Which recordings belong to the same problem is a human decision: the user links each record to an existing issue or opens a new one. Conditions are compared only within one issue.

| Records on an issue | Profile state | Behaviour |
|---|---|---|
| 1 | candidate | Alerts only if the user turned on "tell me next time" |
| 2+ from different trips | provisional pattern | Alerts automatically |
| ongoing answers | counts accumulate | 2 alerts per issue per local day, 10 min cooldown; overlapping issues → only the first recorded one alerts |

Issue lifecycle (`new → acknowledged → in repair check → resolved ↩ reopened`) and condition-profile state (`none → candidate → provisional → active / reduced / stopped`) are kept separate on purpose: a symptom can be unresolved while its condition is well observed, and a repair can be done while the post-repair check is still running.

## Core module

### Build

```
cd chagok
gradle :core:test
```

Requires JDK 21, Gradle 8.x and Maven Central access. Verified 2026-11-06 with Temurin 21.0.13 and Gradle 8.10.2 (87 tests, no warnings). There is no Gradle wrapper yet.

### Pipeline

```
Producers (Pleos adapters / SimulationProducer)
   └─ SignalBus   SharedFlow, DROP_OLDEST      signals may be dropped under pressure
   └─ CommandBus  Channel.UNLIMITED            user commands must never be lost
Engine (one coroutine, no locks)
   ├─ StateDeriver     raw events → DerivedState (brake hold so far, stale sources, stopped)
   ├─ CaptureService   20 s hot + 10 min temporary ring buffer → Capture (30 s)
   │                   each Capture also carries a per-source baseline: the last value evicted from the window
   ├─ ConditionMatcher base filter → per-issue ConditionProfile ranges
   ├─ AlertGate        daily cap (persisted), cooldown, driving-restriction gate
   ├─ events           Alert / RecordCaptured / ProfileChanged / UserChoiceNeeded / IssueChanged / BriefingReady
   │                   (Channel.UNLIMITED, lossless, single consumer that fans out to UI/TTS)
   ├─ state            DerivedState as StateFlow (latest only)
   └─ sourceStatus     per source: AVAILABLE / STALE / PERMISSION_DENIED / UNSUPPORTED
Repository (in-memory now, encrypted Room later) — members, issues, records, profiles, maintenance entries, daily alert ledger
handover/BriefingBuilder   boarding briefing: resolved-for-you → unresolved → under post-repair check
report/ReportBuilder       maintenance report with per-column data provenance (vehicle vs. user input)
```

Design rules the code follows:

- Every SDK callback is normalised to one `SignalEvent` stamped with a **monotonic clock** at receipt. Wall-clock time is used only for display and the per-day alert cap.
- Brake hold time is measured ON→OFF (the plan's definition). A record takes the completed hold that was in progress when the driver spoke, waiting into the 10 s post-window for the release; if it does not end there, the in-progress value is kept as a lower bound (`brakeHoldCompleted=false`). Live matching uses the in-progress hold, so the alert fires as the hold crosses the recorded minimum and stops matching once it exceeds the recorded maximum. If any source goes silent for more than 2 s during a hold, that hold is **not** counted and the match is "data unavailable". Brake, steering and odometer have staleness limits; navigation guidance type is treated as a change-only value and never goes stale by time (a failing NaviHelper is reported through producer status instead).
- Signals and commands travel on separate buses because their back-pressure rules differ. `SignalBus` has no replay, so a producer that starts before the engine subscribes loses its first events; `SimulationProducer` waits for a subscriber (`SignalBus.awaitSubscriber`), real SDK adapters do not need to.
- The matcher only produces candidates. Whether an alert is actually raised is decided by `AlertGate` (policy + driving gate), so other alert types can share the channel later.
- `StateDeriver`, `ConditionMatcher`, `AlertGate`, `BriefingBuilder` and `ReportBuilder` have no SDK dependency. `SimulationProducer` replays `offsetMs,source,value` text, so logs recorded on the emulator in P0 become test fixtures and demo input unchanged.

### Plan → code map

| Plan | Code |
|---|---|
| §02-1 three-stage decision | `ConditionMatcher` (stages 1–2), `Command.Answer` (stage 3) |
| base filter + per-issue profile | `BaseFilter`, `ProfileBuilder` / `ConditionProfile` |
| 1 record = candidate, 2 trips = provisional pattern | `ProfileState.CANDIDATE` / `PROVISIONAL`, `tripId` |
| felt / didn't feel / couldn't check counts | `Counts`; answers are accepted only for an alert that is still open, so a late answer after `EndDrive` cannot double count |
| "felt" → reproduction confirmed, record linked to the same issue | `Engine.answer`: if no record exists within 30 s of the alert, the alert moment is cut from the 10 min buffer, saved as a record and linked |
| braking while standing still is not the condition | `ConditionMatcher`: `DerivedState.stopped` (odometer not increasing) → no match |
| 3 consecutive "didn't feel" → reduce / stop | `needsUserChoice`, `Command.AdjustAlert` (KEEP / REDUCE / STOP / RESUME; RESUME is the way back from stopped or reduced while the issue is open); `UserChoiceNeeded.repairCheck` tells the UI to ask "mark as resolved?" instead during a post-repair check |
| 2 per day · 10 min cooldown · vehicle local date | `AlertPolicy`, `AlertGate.today`, daily counts persisted via `Repository : AlertLedger` |
| overlapping issues → first recorded only | `Engine.evaluate` walks issues in creation order and stops at the first match that alerts or is in cooldown / at its daily cap; an issue that cannot alert at all (waiting for the 3× choice, stopped) does not shadow later ones |
| no answer → "couldn't check" | `Command.EndDrive` resolves open alerts as `UNKNOWN` |
| voice-less mode, 10 min temporary buffer | `CaptureService.captureAt` (marks `complete=false` when the 10 s after are not there yet), `Command.DiscardTemp` discards only the temporary buffer and saves a confirmed "record that" as an incomplete record, `Command.EndDrive` flushes incomplete captures; a late "record that" command also picks up post-window events already buffered |
| `NO_GUIDANCE` vs `DATA_UNAVAILABLE` | `NaviGuidance`, staleness rule in `StateDeriver` |
| missing permission → `onFailed(VehicleException)` | `SignalProducer.status` → `Engine.onProducerStatus` → `DerivedState.unavailable`, `Engine.sourceStatus` |
| no speed | absent from the model |
| §03 scenes 2 & 6: boarding briefing, resolution delivered as the first line | `handover/BriefingBuilder`, `Command.Board` → `EngineEvent.BriefingReady`, `Resolution.deliveredTo` |
| read / unresolved / checking / resolved / reopened | `IssueStatus` + `Issue.readBy`, `Command.AcknowledgeIssue` / `StartRepairCheck` / `ResolveIssue` / `ReopenIssue`; linking a record to a resolved issue reopens it and clears read marks; resolving writes a maintenance history line |
| §03 scene 4: maintenance report with provenance | `report/ReportBuilder`, `ObservationRow.sourceOf`, `MaintenanceEntry.source` |
| post-repair check ("passing the check condition") | `IssueStatus.IN_REPAIR_CHECK` → `EngineEvent.Alert.repairCheck` |
| describing a record after stopping | `Command.Describe` → `EngineEvent.RecordUpdated` (also emitted when a record is re-linked; the old issue's profile is recomputed) |
| habit badge line, share link expiry | not yet (TurnSignal unverified / app and server responsibility) |

### Values to calibrate in P0

`StateDeriver` (`staleNs`, `gapNs`, `stoppedAfterNs`), `BaseFilter`, `Tolerance` and `AlertPolicy` are all constructor arguments. The demo test uses a steering tolerance of ±6° so that −3° and +2° fall in one range; the default is ±3°. Which is right is decided after measuring real listener behaviour. Two open design questions are also left for the team: whether two records must actually overlap before forming a provisional pattern, and whether the recorded brake hold is the in-progress value at the moment of speech or the completed ON→OFF duration.

### Tests

| Test | Covers |
|---|---|
| `SmokeTest` | end-to-end through the real wiring, no direct engine calls: fixture CSV files (`core/src/test/resources/fixtures`, same format as P0 logs) → `SimulationProducer` → `SignalBus` → `Engine.start` with producer status → commands over `CommandBus` → `events`; capture across two replays, candidate → armed → alert on a later trip, `EndDrive` |
| `EngineScenarioTest` | the plan's demo: Minji records → candidate → Junho arms alerts → alert → Junho's record links → provisional pattern → auto alert → silent during turn guidance → 3× didn't feel → user choice → reduce; replay through `SimulationProducer` |
| `HandoverStoryTest` | all six demo scenes: capture and description, boarding briefing with read/acknowledged, report provenance, repair registration and post-repair alert, resolution delivered to the original recorder once, reopening on a new record |
| `EnginePolicyTest` | daily cap survives an engine restart, overlapping issues alert the first one only, `EndDrive` flushes captures and counts unanswered alerts as unknown, permission-denied sources block matching and show in `sourceStatus` |
| `StateDeriverTest`, `CaptureServiceTest`, `MatcherAndGateTest` | unit rules: hold measurement, gap invalidation, staleness, stopped detection, 20+10 s capture, 10 min window, baseline carry-over, base filter, profile narrowing, cooldown and cap |
| `PipelineUnitTest` | finer rules: hold validity resets on a new press, gap only counts while braking, producer status hides a source, ring event cap and baseline preference, overlapping captures, `discardTemp`, `captureAt` boundaries, feature fallback to the completed hold, baseline brake as lower bound, events after the trigger ignored, brake lower bound only, empty navi profile, driving gate, doubled cooldown without navi, reduced cap, per-issue ledger |
| `BusAndSimTest` | `SignalBus` sequence numbers and DROP_OLDEST, `CommandBus` lossless ordering before a subscriber exists, replay parser (comments, blanks, unknown sources, `baseTsNs`), real-time offsets and `stop()` |
| `DomainTest` | `Counts`, `alertsEnabled` per state, `needsUserChoice`, `Range`, `ProfileBuilder` states and ranges (null and `DATA_UNAVAILABLE` handling, keep reduced/stopped, carry counts and arming), `Issue` helpers, repository ordering |
| `EngineCommandTest` | each command on its own: `CaptureAt` failure, `Describe`, `DiscardTemp` cancels a pending capture, `LinkRecord` edge cases, `AdjustAlert` keep/stop, resolve twice and reopen-open are no-ops, acknowledge rules, vehicle-sourced maintenance, producer status merging, briefing ordering and delivery rules, report provenance and rendering |

## Next steps

1. **`app` module** (needs the Android SDK and the Pleos SDK): Pleos Vehicle / NaviHelper adapters implementing `SignalProducer` (stamp the monotonic clock in the callback, publish, report `onFailed` through `status`); a Room-backed `Repository` including `AlertLedger`; a stop-detection gate (`CarUxRestrictions`) for `DrivingGate` and for stop-only commands; a single consumer of `engine.events` that fans out to TTS and screen; send `Command.EndDrive` when a drive ends and `Command.DiscardTemp` when the stop screen is dismissed without choosing a moment.
2. **P0 measurements**: record emulator logs in `SimulationProducer` format and commit them as fixtures; set the calibration values above.
3. Habit badge line (turn signal before turning) and report sharing (QR / link with expiry and revocation).

## Data principles

Signal collection, condition analysis and case records stay in the vehicle app and are stored encrypted locally. Only guidance *type* is stored from navigation — no coordinates, road segments or route history. The voice-less mode keeps the last 10 minutes in memory only; after stopping, the user picks a moment and only that 30 s is encrypted and saved, the rest is discarded. Sharing a report sends only the items the user selects.
