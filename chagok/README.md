# chagok core

차곡 재현 알림 엔진. Pleos SDK 의존 없음 (순수 Kotlin/JVM). 안드로이드 앱은 이 모듈을 가져다 쓰고, SDK 어댑터만 `SignalProducer`로 붙인다.

## 빌드·테스트
```
gradle :core:test
```
JDK 21, Gradle 8.x, Maven Central 접근 필요. (2026-11-06 Temurin 21.0.13 + Gradle 8.10.2로 컴파일·테스트 통과 확인.)

## 구조
```
Producers (Pleos 어댑터 / SimulationProducer)
   └─ SignalBus (SharedFlow, DROP_OLDEST)        신호: 밀려도 됨
   └─ CommandBus (Channel.UNLIMITED)             명령: 손실 금지
Engine (단일 코루틴, 락 없음)
   ├─ StateDeriver   원시 → DerivedState (브레이크 유지 시간, stale, 정차)
   ├─ CaptureService 20초 핫 + 10분 임시 링버퍼, Capture(30초) 생성
   │                 창 밖으로 밀려난 소스별 마지막 값(baseline)을 Capture에 함께 담음
   ├─ ConditionMatcher 기본 필터 → Issue별 ConditionProfile 범위
   ├─ AlertGate      하루 상한·쿨다운·주행 게이트
   ├─ events         Alert / RecordCaptured / ProfileChanged / UserChoiceNeeded (Channel.UNLIMITED, 손실 금지, 단일 소비자)
   ├─ state          DerivedState StateFlow (최신 값만)
   └─ sourceStatus   소스별 AVAILABLE / STALE / PERMISSION_DENIED / UNSUPPORTED
Repository (InMemory → Room 암호화) — Member·Issue·Record·Profile·MaintenanceEntry·하루 알림 횟수
handover/BriefingBuilder  탑승 브리핑 (해결 전달 → 미해결 → 확인 중)
report/ReportBuilder      정비 리포트 (출처 구분, 평문 렌더)
```

## 기획서 대응
| 기획서 | 코드 |
|---|---|
| 02-1 3단계 판정 | ConditionMatcher (1·2단계), Command.Answer (3단계) |
| 기본 필터 + Issue별 프로파일 | BaseFilter, ProfileBuilder/ConditionProfile |
| 1건 후보 / 다른 주행 2건 잠정 패턴 | ProfileState.CANDIDATE / PROVISIONAL, tripId |
| 느낌·못 느낌·미확인 카운트 | Counts |
| 3회 연속 못 느낌 → 축소·중지 선택 | needsUserChoice, Command.AdjustAlert |
| 하루 2회·쿨다운 10분·현지 날짜 | AlertPolicy, AlertGate.today, 하루 횟수는 Repository(AlertLedger)에 저장 |
| 다른 문제가 겹치면 먼저 기록된 것만 | Engine.evaluate: Issue 생성 순, 첫 매치에서 멈춤 |
| 답이 없으면 미확인 | Command.EndDrive → 열린 알림을 UNKNOWN으로 |
| 음성 없는 모드 10분 임시 보관 | CaptureService.captureAt, Command.DiscardTemp(폐기), Command.EndDrive(미완성 Capture 마감) |
| NO_GUIDANCE / DATA_UNAVAILABLE | NaviGuidance, StateDeriver stale 규칙 |
| 권한 없음 → onFailed(VehicleException) | SignalProducer.status → Engine.onProducerStatus → DerivedState.unavailable, Engine.sourceStatus |
| 속도 없음 | 모델에 없음 |
| 03 장면 2·6 탑승 브리핑, 해결 전달(다음 탑승 첫 줄) | handover/BriefingBuilder, Command.Board → EngineEvent.BriefingReady, Resolution.deliveredTo |
| 읽음·미해결·확인 중·해결·재발 상태 | IssueStatus + Issue.readBy, Command.AcknowledgeIssue/StartRepairCheck/ResolveIssue/ReopenIssue, 해결된 Issue에 기록 연결 시 자동 재발 |
| 03 장면 4 정비 리포트, 출처(사용자 입력/차량 수신) 구분 | report/ReportBuilder, ObservationRow.sourceOf, MaintenanceEntry.source |
| 수리 후 확인 ("확인 조건을 지나고 있어요") | IssueStatus.IN_REPAIR_CHECK → EngineEvent.Alert.repairCheck |
| 기록 묘사 (정차 후) | Command.Describe |
| 습관 배지 한 줄, 공유 QR·링크 만료 | 아직 없음 (TurnSignal 검증 전 / 앱·서버 몫) |

## P0에서 채울 값
StateDeriver.staleNs/gapNs/stoppedAfterNs, BaseFilter, Tolerance, AlertPolicy — 전부 생성자 인자. 실측 후 바꾼다.

## 다음 단계
1. `app` 모듈: Pleos Vehicle/NaviHelper 어댑터 (`SignalProducer` 구현, onFailed → status), Room 리포지토리(AlertLedger 포함), 정차 게이트(CarUxRestrictions), `events` 단일 소비자 디스패처 → TTS/화면, 주행 종료 시 `Command.EndDrive`, 정차 화면 닫힘 시 `Command.DiscardTemp`.
2. P0 실측 로그를 `SimulationProducer` 형식으로 저장 → 테스트 fixture로.
