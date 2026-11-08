# 빈틈의실

전기차 충전 및 정차 대기 중 목표 배터리 잔량과 사용자가 정한 시간에 맞춰 짧은 활동을 선택하고 마무리하도록 돕는 Pleos Connect 앱의 개발 시작 프로젝트입니다.

현재 앱은 **모의 차량 데이터 시연 버전**입니다. 실제 Vehicle SDK, CarUxRestrictions, TTS, Gleo, 휴대폰 알림, 자동 카운트다운은 아직 연결하지 않았습니다. 차량의 충전 한도를 바꾸거나 충전을 종료하지 않습니다.

## 개발 환경

| 항목 | 프로젝트 설정 |
| --- | --- |
| JDK | 21 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Android SDK | compile/target 34, min 28 |
| Build Tools | 36.1.0 |
| 패키지 | com.binteum.app (개발용 기본값) |

Mac에서는 설치된 Android Studio의 JBR와 `$HOME/Library/Android/sdk`를 사용합니다. 전역 셸 설정을 변경하지 않습니다.

```bash
./scripts/check-environment.sh
./scripts/gradle.sh :core:test :app:assembleDebug :app:lintDebug --no-daemon
```

다른 환경에서는 실제 설치 경로를 지정합니다.

```bash
BINTEUM_JAVA_HOME=/path/to/jdk21 \
BINTEUM_ANDROID_SDK=/path/to/android-sdk \
./scripts/gradle.sh :core:test :app:assembleDebug :app:lintDebug
```

JDK와 Android SDK 환경변수가 이미 설정되어 있다면 `./gradlew`를 직접 사용해도 됩니다. GitHub CI도 같은 테스트·빌드·lint 명령을 실행하며 결과는 저장소의 Actions 화면에서 확인합니다.

## 프로젝트 구성

- `app/`: 모의 상태를 조작해 보는 네이티브 Android 화면
- `core/`: 차량 상태 모델, 목표 도달 판단, 데이터 유효성, 중복 방지, 시간 예산 계산
- `scripts/`: 프로젝트 범위 환경 확인과 Gradle, Pleos AVD 및 Virtual Console 실행
- `.github/workflows/android.yml`: 단위 테스트, APK 빌드, lint
- `docs/product-plan.md`: 공동작업용 제품 기획과 MVP 완료 기준

## 함께 개발하기

저장소를 clone한 뒤 Android Studio에서 루트 폴더를 열고 위 빌드 명령으로 환경을 확인합니다. 기능 작업은 별도 브랜치에서 진행하고, 테스트와 빌드를 확인한 후 `main`을 대상으로 Pull Request를 엽니다.

소스·테스트·Gradle Wrapper·CI·실행 스크립트와 개발 문서는 Git으로 공유합니다. APK/AAB, 빌드 캐시, 에뮬레이터 이미지, 로그와 조사 원문, 참가자 정보·서명 문서, 로컬 경로 설정과 서명키는 올리지 않습니다. 제출용 APK는 검증을 마친 뒤 별도 배포 자료로 관리합니다.

## 현재 확인한 결과

2026년 11월 8일 기준, 로컬 JBR 21.0.9에서 core 테스트 9개와 debug APK 빌드가 성공했습니다. lint는 오류 0개, 고정한 SDK와 Gradle의 최신버전 안내 경고 3개입니다.

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 테스트: `core/build/reports/tests/test/index.html`
- lint: `app/build/reports/lint-results-debug.html`

목표 도달 후 상태 유지, 동일 목표 중복 방지, 목표 전 충전 중단, 데이터 지연과 누락, 주행·휴면 시 알림 억제, 회전 후 상태 보존, 시간 예산 경계값을 점검했습니다. 이 결과는 실제 차량 또는 Pleos SDK 통합 검증이 아닙니다.

## Pleos 연결

2026년 11월 8일 사용자 약관 동의 후 이 Mac에 공식 Pleos Connect 2.1.0/API 34 ARM64 이미지 revision 2를 설치했습니다. 전용 AVD 이름은 `Binteum_Pleos_2_1_API34`이며 RAM 4GB, Cold boot로 구성했습니다. Android 14 부팅과 debug APK 설치 및 실행을 확인했습니다. 다른 컴퓨터에서는 공식 가이드에 따라 이미지와 같은 이름의 AVD를 먼저 준비합니다.

AVD가 종료된 상태에서 다음 명령으로 시작합니다.

```bash
./scripts/start-pleos.sh
```

이 Mac에서는 기본 Vulkan 렌더링에 호환성 문제가 있어 `-gpu host -feature -Vulkan`로 실행합니다. 스크립트는 이 옵션과 스냅샷 미사용을 적용하며 전역 에뮬레이터 설정은 변경하지 않습니다. Android Studio의 일반 시작 버튼은 같은 옵션을 적용하지 않을 수 있으므로, 기기를 종료한 뒤 재시작할 때 이 스크립트를 사용합니다. [Android 공식 그래픽 문제 해결](https://developer.android.com/studio/run/emulator-troubleshooting)

이 설정에서도 Pleos 기본 주행 화면의 부팅 초기 크래시 1회는 기록되었습니다. 이후 41초 비교 구간에서 추가 크래시와 빈틈의실 앱 크래시는 관찰되지 않았습니다. 장시간 안정성과 앱 화면·조작 흐름의 시각 검수는 별도로 필요합니다.

- [SDK와 에뮬레이터 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Vehicle 공식 샘플](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
- [주행 제한 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)

에뮬레이터 부팅 후 `adb devices`에서 실제 장치 번호를 확인하고 아래의 `VERIFIED_SERIAL`을 바꿉니다.

```bash
adb -s VERIFIED_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s VERIFIED_SERIAL shell am start -n com.binteum.app/.MainActivity
adb -s VERIFIED_SERIAL forward tcp:8088 tcp:8088
```

Virtual Console은 `http://127.0.0.1:8088/`에서 엽니다. 이 Mac에서는 포워딩 후 HTTP 200과 Pleos Connect Virtual Console 응답을 확인했습니다. 현재 mock 앱은 Console의 실제 SDK 값을 읽지 않으며, Console에서 차량 상태를 바꿔 앱에 반영하는 통합 검증은 아직 수행하지 않았습니다.

## 다음 구현

1. 실제 SDK 패키지와 메서드 서명, 권한, capability를 확인하고 `VehicleDataSource`를 구현합니다.
2. CarUxRestrictions와 최신 안전 신호를 연결합니다. 기어 P만으로 안전 조건을 통과시키지 않습니다.
3. 목표와 대기시간 입력, 자체 활동 콘텐츠, 실제 타이머와 세션 저장을 구현합니다.
4. 목표 도달·충전 중단·시간 종료·감시 불가를 구분하고 TTS와 오디오 포커스를 연결합니다.
5. 기기에서 재시작·데이터 끊김·주행 제한·Suspend를 검증합니다.
6. 승인과 실행 가능성이 확인되면 Gleo 입력 경로를 추가합니다.

배터리 데이터가 없을 때 수동 타이머를 제공할 수 있지만, 안전 상태가 불명확할 때 상세 조작을 허용하는 우회 수단으로 사용하지 않습니다. 차 밖 알림, 외출 추천, 정밀 충전 ETA, 시트 제어와 주문·결제는 후속 범위입니다.

## 공모전 자료

현재 debug APK는 개발용이며 제출용 release APK가 아닙니다. release 서명과 검증, 시연 녹화는 별도로 진행합니다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)에 맞춰 소스와 실행 가능한 APK, 실행 방법과 테스트 환경을 정리합니다.

연구 스냅샷, 참가자의 개인정보와 서명 문서, `local.properties`, 환경변수 파일, 인증정보와 서명키는 Git 제외 대상입니다. 최종 제출 자료는 공식 양식의 익명 안내에 맞춰 별도 점검합니다.
