# 인수인계: 진행 상황과 남은 절차

작성: 2026-11-10. 이전 PC와 계정에서 하던 작업을 개인 노트북으로 옮기면서 남깁니다.

## 1. 지금까지 한 것

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 주제 선정 | 여러 차례 독립 심사 끝에 「옆자리 친구」로 확정했습니다. 앞선 후보 '쉼표'는 벤츠 Power Nap, 현대 DAW, 도로공사 서비스와 겹쳐서 버렸습니다. | 완료 |
| 0. 기획서 v2 | 독립 평가 67점입니다(역대 대상작은 약 72점). 사본: [proposal.md](proposal.md) | 완료 |
| 1. core 판정 엔진 | 단위 테스트 약 50개, 코드 리뷰 2회, 20건 반영. PR #1을 main에 합쳤습니다. | 완료 |
| 2. Android 앱 | Pleos SDK 연결, 화면, 시연 패널을 만들었습니다. 리뷰 11건 반영. **PR #2는 아직 열려 있습니다(브랜치 `phase2-app`).** 빌드는 됩니다. | 에뮬레이터 확인 대기 |
| 3. 제출 자료 | 시연 영상, 설문 반영, 최종 기획서 | 시작 전 |

**아직 확인하지 못한 것**(에뮬레이터에서 처음 돌려 볼 때 확인할 것):
- Virtual Console에서 깜빡이, 브레이크, 조향을 넣었을 때 앱이 받는지
- Pleos TTS가 한국어로 말하는지, `speak(text, Boolean)`의 Boolean이 무슨 뜻인지
- 에뮬레이터에서 CAR_SPEED와 기어 값이 읽히는지
- NaviHelper에서 TBT가 오는지(내비의 SIMULATION_GUIDING 모드 등), 그리고 `TURN_DEVIDE_MERGE`가 실제로 무엇을 뜻하는지

이전 PC는 BIOS에서 VT-x가 꺼져 있고 하이퍼바이저 드라이버가 없어서 에뮬레이터를 실행하지 못했습니다.

## 2. 노트북 준비 (사용자가 직접)

1. **Android Studio**를 설치합니다.
2. **Pleos 시스템 이미지를 설치합니다.** 가이드: <https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator>
   1. Tools → SDK Manager → SDK Update Sites 탭에서 +를 누르고 아래처럼 입력합니다.
      - Name: `Pleos Connect System Image`
      - URL(Windows): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
      - URL(Mac, Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   2. SDK Platforms 탭에서 Show Package Details를 켜고, API 34 아래의 Pleos Connect 이미지를 체크한 뒤 OK를 누릅니다.
   3. 설치가 끝나면 Android Studio를 재시작합니다.
3. **AVD를 만듭니다.**
   1. Device Manager에서 Create device를 누르고, Automotive → **Pleos Connect v2.1.0**을 고릅니다.
   2. Advanced Settings에서 Default boot는 **Cold**, RAM은 **4 GB**로 합니다.
   3. API 34의 **Pleos Connect v2.1.0 APIs**를 고르고 Finish를 누릅니다.
4. **(Windows만) 가상화를 켭니다.**
   1. BIOS에서 Intel VT-x 또는 AMD SVM을 켭니다.
   2. SDK Manager → SDK Tools에서 **Android Emulator hypervisor driver**를 설치합니다.
   3. 확인: `emulator -accel-check`가 0으로 끝나야 합니다.
5. **경로를 확인합니다.** Windows 사용자 이름이 한글이면 SDK를 `C:\android-sdk`처럼 영문 경로로 옮기거나 junction을 만드세요.
6. **Git, GitHub CLI(`gh auth login`), Claude Code를 설치합니다.**
7. **레포를 받습니다.**
   ```
   git clone https://github.com/qkreodb/yeopjari-chingu.git C:\workspace\car
   cd C:\workspace\car
   git checkout phase2-app
   ```
8. 레포 루트에서 Claude Code를 실행하고 "handoff.md 보고 이어서 하자"라고 말합니다.

## 3. Claude가 이어서 할 일 (순서대로)

### A. 에뮬레이터 확인 (11/11~12)
1. `local.properties`에 `sdk.dir`를 설정합니다. 이 파일은 gitignore 대상이라 새로 만들어야 합니다.
2. `./gradlew :core:test :app:assembleDebug`로 빌드를 확인합니다.
3. 에뮬레이터를 실행하고 `adb install -r app/build/outputs/apk/debug/app-debug.apk`로 설치합니다.
4. `adb forward tcp:8088 tcp:8088`를 하고 Virtual Console을 엽니다. 설정 방법은 Pleos 문서의 "Pleos Connect Virtual Console 셋업 가이드"(`use-vehicle-emulator`) 페이지에 있습니다.
5. 다음을 확인합니다. 로그는 `adb logcat -s VehicleInputs NaviInputs Speaker`로 봅니다.
   - 깜빡이 왼쪽, 오른쪽, 끄기가 앱 화면의 "깜빡이: … (Virtual Console)"에 반영되는지
   - '합류 시연 시작'을 눌렀을 때 1km와 400m 안내 음성이 나오는지
   - 깜빡이를 미리 켜고 통과하면 "합류 구간 지나왔어요. 깜빡이도 미리 켰어요."가 나오는지
   - 브레이크와 조향 값이 들어오는지, 급제동 뒤 20초 동안 말을 멈추는지
6. 문제가 생기면 고치고, 독립 리뷰를 받은 뒤 **PR #2를 main에 합치고** README의 단계 표를 갱신합니다.

### B. 시연 영상 (11/13~14, 가산점)
- 합류 장면 하나를 1~2분으로 찍습니다. 녹화는 에뮬레이터 화면, Virtual Console, 음성을 함께 담습니다.
- 대본: [demo-transcript.md](demo-transcript.md). 'SIMULATION' 표시가 보여야 합니다.
- 이야기 순서: 문제(불안한 운전자의 인간 내비) → 합류 1km, 400m 안내 → 깜빡이 확인 → 사실대로 칭찬 → 재탐색 때 "괜찮아요, N분만 더 걸려요" → 주행 기록과 연습 단계.

### C. 설문 (사용자, 11/11~14)
- 설문지: [survey.md](survey.md). 목표는 30명 이상이고, 그중 40~50대가 15명 이상이면 좋습니다.
- 결과가 나오면 기획서의 MAU 설치율 가정(5~15%)과 문제 근거 부분에 반영합니다.

### D. 최종 제출 (11/15 마감)
- [proposal.md](proposal.md)를 최종 검토하고, 독립 에이전트에게 점수를 받습니다.
- 공식 제출 양식(DOCX)으로 옮깁니다.
- 설문 결과, 에뮬레이터 스크린샷, 시연 영상 링크를 넣습니다.

### E. 1차 통과 후 (11/23~12/2)
- P0 기능을 다듬습니다. 실제 NaviHelper TBT 연결, 화면 S1~S5 완성.
- 12/3~4 오프라인 시연을 리허설합니다.

## 4. 참고
- GitHub: <https://github.com/qkreodb/yeopjari-chingu> (private)
- 이전 계정의 Claude Docs 기획서는 노트북 계정에서 볼 수 없습니다. 이제 `docs/proposal.md`가 최신본입니다. 합류 흐름 그림 하나는 텍스트로만 남아 있습니다.
- 판정 규칙의 세부(PASSED / PASSED_NO_SIGNAL / MISSED / UNCERTAIN, 발화 예산, 안내 줄이기)는 [design.md](design.md)에 있습니다.
