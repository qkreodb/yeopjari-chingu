# CLAUDE.md — 옆자리 친구 (Pleos Connect 차량용 앱)

이 레포에서 작업하는 Claude를 위한 안내입니다. **먼저 [docs/handoff.md](docs/handoff.md)를 읽으세요.** 진행 상황과 남은 절차가 정리되어 있습니다.

## 프로젝트
- 2026 현대자동차그룹 차량용 앱 개발 공모전, 대학생 부문, 개인 출품. 분야: 안전 운전.
- 마감: **2026-11-15 접수**. 시연 영상 가산점은 11/14까지 준비합니다. 1차 결과는 11/23, 2차 오프라인 시연은 12/3~4입니다.
- 앱: 운전이 불안한 사람을 위한 차분한 동승 내비입니다. 합류, IC, 분기, 회전교차로 같은 어려운 지점에서만 미리 말하고, 차량 신호로 확인된 사실만 말합니다.
- 기획서: [docs/proposal.md](docs/proposal.md). 원본은 이전 계정의 Claude Docs에 있었고, 이 파일이 그 사본입니다. 이제 이 파일을 최신본으로 관리합니다.
- 개발 기준 설계: [docs/design.md](docs/design.md)

## 사용자와 일하는 방식
- 사용자는 대학생입니다. 한국어로, 짧고 쉽게 설명하세요.
- 작업은 단계(phase)로 나눕니다. 단계마다 구현 → 독립 리뷰(다른 에이전트) → 반영 → 재검토 순서로 진행하고, 단계마다 브랜치와 PR을 만듭니다.
- "점수 올릴 수 있는 것"은 알아서 넣어도 됩니다. 단, 심사 기준(차량 특화성, 창의성, 기술 활용성, 사업성)에 근거해야 합니다.
- **경쟁 제품이 "없다"고 말하기 전에 반드시 확인하세요.** 확인하지 않고 그렇게 말했다가 크게 신뢰를 잃은 적이 있습니다. 확인할 대상은 OEM 기능(벤츠, 현대 등), 국내 공공 서비스, 역대 수상작입니다.
- 자체 점수를 부풀리지 마세요. 평가는 독립 에이전트에게 맡깁니다. 기준: 역대 대상작 '창밖동화'는 약 72점, 다른 수상작은 53~60점입니다. 이 기획서 v2는 67점을 받았습니다.

## 빌드
- JDK 17, Gradle 8.10.2(wrapper), Kotlin 2.0.0, AGP 8.8.2, Compose BOM 2024.09.03, compileSdk와 targetSdk는 35, minSdk는 28 이상(Pleos 요구)입니다.
- `./gradlew :core:test`: 판정 로직 단위 테스트입니다. JDK만 있으면 됩니다.
- `./gradlew :core:run --args="<절대경로>/docs/demo-transcript.md"`: 시연 대본을 다시 만듭니다.
- `./gradlew :app:assembleDebug`: 결과물은 `app/build/outputs/apk/debug/app-debug.apk`입니다.
- `:app` 모듈은 `local.properties`에 `sdk.dir`가 있거나 `ANDROID_HOME`이 설정되어 있을 때만 포함됩니다. 설정 위치는 settings.gradle.kts입니다.
- **Windows에서 경로에 한글이 있으면** 문제가 납니다. SDK, AVD, 프로젝트는 ASCII 경로(`C:\android-sdk`, `C:\workspace\car` 등)에 두세요. `org.gradle.jvmargs`에 `-Dfile.encoding`을 넣으면 테스트 워커가 깨지니 넣지 마세요.

## Pleos SDK (javap로 검증한 내용)
- Maven 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`이며 인증이 필요 없습니다.
- 사용하는 모듈:
  - `ai.pleos.playground:Vehicle:2.3.2`
  - `ai.pleos.playground:NaviHelper:3.0.5`
  - `ai.pleos.playground:TextToSpeech:3.3.1`
- Vehicle: `Vehicle(ctx).initialize()`를 부른 뒤 `getTurnSignal()`, `getBrake()`, `getSteeringWheel()`의 `register*` 리스너를 씁니다. 리스너 인자는 nullable입니다(`TurnSignalValue?`). `onFailed`는 Unit을 반환하는 블록 본문으로 써야 합니다.
- NaviHelper: `initialize(onConnected, onFailed)` → `addListener`. 쓰는 콜백은 `onTBTInfo(List<TBTInfo>)`, `onDrivingInfo`, `onReRouteEnded`, `onDestinationArrived`, `onRouteCancelled`, `onCautionPointInfo`입니다.
- TTS: `TextToSpeech(ctx, Mode.ON_DEVICE).initialize(...)`를 부르고 `setLocale`, `setAudioAttributes`, `speak(text, Boolean)`을 씁니다.
- 차속과 기어는 AAOS `CarPropertyManager`에서 읽습니다(`PERF_VEHICLE_SPEED`, `GEAR_SELECTION`). `CAR_SPEED`는 런타임 권한입니다.
- **Virtual Console**(`adb forward tcp:8088 tcp:8088`)로 주입할 수 있는 것: 기어, 브레이크, 조향, 방향지시등, 시트, 무드등.
- **Virtual Console로 주입할 수 없는 것: 회전 안내(TBT).** 그래서 시연에서는 앱 안의 RouteSimulator로 경로를 재생하고, 화면에 'SIMULATION'을 항상 표시합니다.

## 구조
- `core/`: 순수 Kotlin 판정 엔진입니다. CoachEngine, VoiceBudget, HarshBrakeDetector, Fading, Phrases, TripLog, PracticeLadder, TbtAdapter, sim/*가 있고 테스트 약 50개가 붙어 있습니다.
- `app/`: AAOS 앱입니다. 패키지는 com.yeopjari.app, applicationId는 com.yeopjari.chingu입니다.
  - VehicleInputs, NaviInputs, Speaker, CoachController, MainActivity, ui/MainScreen이 있습니다.
