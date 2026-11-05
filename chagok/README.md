# chagok · Gradle root

Gradle multi-module root for Chagok. The project overview, architecture, plan-to-code map and next steps are in the [repository README](../README.md).

## Modules

| Module | Status | Description |
|---|---|---|
| `core` | builds, 23 tests pass | Reproduction-alert engine. Pure Kotlin/JVM, no Pleos SDK dependency. The Android app consumes this module and only plugs in SDK adapters as `SignalProducer` implementations. |
| `app` | not started | Android Automotive app: Pleos Vehicle / NaviHelper adapters, Room repository, in-car UI and TTS. Requires the Android SDK and the Pleos SDK. |

## Build

```
gradle :core:test
```

JDK 21, Gradle 8.x, Maven Central access. Verified 2026-11-06 with Temurin 21.0.13 and Gradle 8.10.2. No Gradle wrapper yet.

## Layout

```
core/src/main/kotlin/ai/chagok/core/
  model/      SignalEvent, SignalValue, Source, SourceStatus, Command
  pipeline/   SignalBus, CommandBus, SignalProducer
  derive/     StateDeriver, DerivedState
  buffer/     TimedRing, CaptureService, Capture
  domain/     Member, Issue, SymptomRecord, ConditionProfile, MaintenanceEntry, Repository, FeatureExtractor
  match/      BaseFilter, ConditionMatcher
  alert/      AlertPolicy, AlertGate, AlertLedger, DrivingGate
  handover/   BriefingBuilder
  report/     ReportBuilder
  sim/        SimulationProducer (offsetMs,source,value replay)
  Engine.kt   wiring: one coroutine, lossless events channel, state and sourceStatus flows
core/src/test/kotlin/ai/chagok/core/
  EngineScenarioTest, HandoverStoryTest, EnginePolicyTest, StateDeriverTest, CaptureServiceTest, MatcherAndGateTest, Fixtures
```
