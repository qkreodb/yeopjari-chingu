# 옆자리 친구 (가칭)

운전이 불안한 사람이 혼자 탈 때, 차가 옆자리의 '인간 내비'처럼 **어려운 지점에서만** 미리, 차분하게 말해 주는 Pleos Connect 차량용 앱입니다.

- 출품: 2026 현대자동차그룹 차량용 앱 개발 공모전 (대학생 부문, 개인) · 공모 분야: 안전 운전
- 접수 마감: 2026-11-15 · 2차 시연 심사: 2026-12-03~04
- 기획서: [docs/proposal.md](docs/proposal.md)
- 인수인계(진행 상황, 남은 절차): [docs/handoff.md](docs/handoff.md)
- 개발 기준 설계 요약: [docs/design.md](docs/design.md)

## 핵심 장면: 합류

1. 합류 1km 전: "곧 오른쪽 합류예요."
2. 400m 전, 깜빡이가 아직 꺼져 있을 때만: "400미터 뒤 오른쪽 합류예요. 깜빡이는 아직 꺼져 있어요."
3. 세 조건이 모두 확인될 때만 사실대로 말합니다: "합류 구간 지나왔어요. 깜빡이도 미리 켰어요."
   - 같은 방향 깜빡이를 합류 지점 전에 켰음
   - 재탐색 없이 다음 회전 안내로 넘어감
   - 통과 후 10초 동안 급제동 없음
4. 하나라도 불확실하면 아무 말도 하지 않습니다. 틀린 칭찬이 가장 해롭기 때문입니다.
5. 길을 놓치면: "괜찮아요, 3분만 더 걸려요."

장면별 전체 대본: [docs/demo-transcript.md](docs/demo-transcript.md)

## 시연 영상

- https://youtu.be/M1NmzVA1c2g (약 3분, Pleos Connect 에뮬레이터 화면 녹화. 회전 안내는 시뮬레이션 경로, 깜빡이는 Virtual Console 신호. 에뮬레이터 특성상 음성에 잡음이 있습니다)

## 제출용 APK

- 위치: [`apk/박대유_옆자리친구_release.apk`](apk/)
- release 빌드, 서명됨. 패키지 `com.yeopjari.chingu`, 버전 0.1.0

## 테스트 환경

| 항목 | 값 |
| --- | --- |
| 에뮬레이터 | Pleos Connect v2.1.0 (API 34, x86_64 시스템 이미지) |
| 화면 규격 | 2560×1440, 160dpi, 가로 |
| AVD 설정 | RAM 4GB, Cold boot, **CPU 8코어, GPU host** (기본값 4코어·auto에서는 Windows에서 부팅이 끝나지 않는 경우가 있었습니다) |
| 개발 PC | Windows 11, RAM 16GB, JDK 17, Gradle 8.10.2 |
| 차량 신호 | Pleos Connect Virtual Console (`adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`) |

## 실행 방법

1. 에뮬레이터를 켜고 APK를 설치합니다.
   ```bash
   adb install -r apk/박대유_옆자리친구_release.apk
   adb shell pm grant com.yeopjari.chingu android.car.permission.CAR_SPEED
   ```
2. 앱을 열고 안전 고지에 동의합니다.
3. Virtual Console을 엽니다: `adb forward tcp:8088 tcp:8088` → 브라우저에서 `http://127.0.0.1:8088/`
4. 앱의 시연 패널에서 **'합류 시연 시작'**을 누릅니다. 회전 안내는 앱 안의 시뮬레이션 경로(오른쪽 합류 → 좌회전 → 오른쪽 IC → 도착, 시속 80km)로 재생되며 화면에 'SIMULATION'이 표시됩니다. Virtual Console로는 회전 안내(TBT)를 넣을 수 없기 때문입니다.
5. 깜빡이는 Virtual Console의 **Convenience → Turn Signal State**로 넣습니다. **1km 안내("곧 오른쪽 합류예요.")를 들은 뒤** Blink right를 누르면, 통과 10초 뒤 "합류 구간 지나왔어요. 깜빡이도 미리 켰어요."가 나옵니다. 시작 전부터 켜 둔 깜빡이는 인정하지 않습니다(다른 이유로 켜 둔 깜빡이를 칭찬하지 않기 위해서입니다).
6. 깜빡이를 끈 채로 가면 400m에서 "깜빡이는 아직 꺼져 있어요."라고 사실만 말합니다. 시연 중 **'급제동'**을 누르면 20초 동안 말하지 않습니다.

촬영 순서는 [docs/video-script.md](docs/video-script.md)에 있습니다.

## 빌드

```bash
./gradlew :core:test          # 판정 로직 단위 테스트 (JDK 17)
./gradlew :core:run --args="docs/demo-transcript.md"   # 시연 장면 대본 다시 만들기
./gradlew :app:assembleDebug  # 디버그 APK (app/build/outputs/apk/debug/)
./gradlew :app:assembleRelease  # release APK (서명 정보 필요)
```

- `:app` 모듈은 `local.properties`에 `sdk.dir`가 있거나 `ANDROID_HOME`이 설정되어 있을 때만 포함됩니다.
- release 서명 정보는 `local.properties`에 넣습니다(git에 올리지 않음). 없으면 서명하지 않은 APK가 만들어집니다.
  ```properties
  release.storeFile=C:/경로/키파일.jks
  release.storePassword=...
  release.keyAlias=...
  release.keyPassword=...
  ```
  경로는 `/`로 씁니다(`\`는 이스케이프 문자로 읽혀 경로가 깨집니다). 상대 경로는 `app/` 폴더 기준입니다.
- 에뮬레이터와 Gradle 빌드를 동시에 돌리면 RAM 16GB에서 메모리가 모자랄 수 있습니다. 빌드할 때는 에뮬레이터를 끄는 편이 안전합니다.

## 구조

| 경로 | 내용 | 빌드 |
| --- | --- | --- |
| `core/` | 순수 Kotlin 판정 로직 (어려운 지점 판정, 발화 예산, 재탐색, 급제동, 안내 줄이기, 연습 사다리, 시뮬레이션 재생) | JDK 17만 있으면 됨 |
| `app/` | Pleos Connect 앱 (SDK 연결부, 화면, 신호 주입 패널) | Android Studio + Pleos SDK 필요 (2단계) |
| `docs/` | 설계 요약, 시연 시나리오 | — |

## 진행 단계

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 0 | 기획서 v2, 레포 생성 | 완료 (독립 평가 67점) |
| 1 | `core` 판정 로직 + 단위 테스트 46개 | 완료 (코드 리뷰 2회, 20건 반영) |
| 2 | Android 앱 (Pleos SDK, 화면, 신호 주입 패널) | 완료 (Pleos Connect v2.1.0 에뮬레이터에서 TTS·깜빡이·브레이크·조향 확인, PR #2) |
| 3 | 시연 영상, 제출 자료 | 완료 |

각 단계는 구현 → 독립 리뷰 → 반영 순서로 진행합니다.
