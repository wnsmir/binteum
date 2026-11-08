# 빈틈의실

전기차 충전 및 정차 대기 중 목표 배터리 잔량과 사용자가 정한 시간에 맞춰 짧은 활동을 선택하고 마무리하도록 돕는 Pleos Connect 앱의 개발 시작 프로젝트입니다.

현재 앱은 **첫 기능 구현 버전**입니다. Pleos Vehicle 2.3.2의 잔량 읽기, AAOS 실제 충전 상태·CarUxRestrictions, 시간별 자체 활동 추천, 카운트다운, 로컬 취향 저장, WebView 미디어 화면을 연결했습니다. 실제 차량 값 수신과 YouTube 재생은 아직 기기 검증 전입니다. 차량의 충전 한도를 바꾸거나 충전을 종료하지 않습니다. [구현·검증 상태](docs/implementation-status.md)

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

- `app/`: 차량 데이터 어댑터, 충전 상태·활동·설정 화면, WebView 콘텐츠
- `core/`: 차량 상태 모델, 목표 도달 판단, 시간 예산, 활동 추천·평가 중복 방지
- `scripts/`: 프로젝트 범위 환경 확인과 Gradle, Pleos AVD 및 Virtual Console 실행
- `.github/workflows/android.yml`: 단위 테스트, APK 빌드, lint
- `docs/product-plan.md`: 공동작업용 제품 기획과 MVP 완료 기준

## 콘텐츠와 개인화 확장 계획

2026년 11월 8일 작성한 확장 설계입니다. 자체 활동·로컬 개인화·웹 미디어의 첫 구현을 시작했으며, 장소·외출·서버 API는 후속 범위입니다. 현재 구현 여부는 구현·검증 상태 문서를 기준으로 확인합니다.

- [경험 확장 실행 계획](docs/experience-plan.md): 시간별 활동, YouTube·웹·주변 산책과 간식, 제출까지 일정
- [UI 명세](docs/ui-spec.md): 추천·진행·종료·휴대폰 인계 화면과 오류 상태
- [API 설계](docs/api-spec.md) 및 [OpenAPI 3.1 계약](docs/api/openapi.json): 앱 내부 경계와 BFF 4개 경로
- [추천과 개인화 설계](docs/recommendation-design.md): 시간·안전 필터, 직접 선호와 자체 평가, 이후 밴딧 실험
- [공식 차량 API 전체 목록](docs/vehicle-api-matrix.md): 19개 영역·배포물 확인 440개, 권한과 연결 여부
- [웹 미디어 구현](docs/web-media-integration.md): YouTube 공식 IFrame, 자체 웹 콘텐츠, 오류와 중단 처리

## 함께 개발하기

저장소를 clone한 뒤 Android Studio에서 루트 폴더를 열고 위 빌드 명령으로 환경을 확인합니다. 기능 작업은 별도 브랜치에서 진행하고, 테스트와 빌드를 확인한 후 `main`을 대상으로 Pull Request를 엽니다.

소스·테스트·Gradle Wrapper·CI·실행 스크립트와 개발 문서는 Git으로 공유합니다. APK/AAB, 빌드 캐시, 에뮬레이터 이미지, 로그와 조사 원문, 참가자 정보·서명 문서, 로컬 경로 설정과 서명키는 올리지 않습니다. 제출용 APK는 검증을 마친 뒤 별도 배포 자료로 관리합니다.

## 현재 확인한 결과

최종 검증 명령과 결과는 [구현·검증 상태](docs/implementation-status.md)에 기록합니다. core 테스트, debug APK 빌드와 lint를 함께 실행합니다.

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 테스트: `core/build/reports/tests/test/index.html`
- lint: `app/build/reports/lint-results-debug.html`

단위 테스트는 목표 도달·중단·제한·데이터 지연 상태와 시간 예산 경계, 선호 순위, 완료 확인과 평가 중복 방지를 확인합니다. 실제 기기에서의 권한·차량 값·화면·영상 재생 검증과 구분합니다.

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

Virtual Console은 `http://127.0.0.1:8088/`에서 엽니다. 이 Mac에서는 포워딩 후 HTTP 200과 Pleos Connect Virtual Console 응답을 확인했습니다. 앱의 기본 입력은 실제 SDK 어댑터이며, `나의 설정 → 차량 데이터 권한 허용`에서 Android 차량 에너지 읽기 권한을 요청합니다. 미지원·권한 거부·안전 상태 미확인일 때 활동을 차단합니다. Console에서 차량 상태를 바꿔 앱에 반영하는 통합 검증은 아직 수행하지 않았습니다. debug 빌드의 시연 모드는 명시적으로 켜야 하며 화면에 모의 데이터임을 표시합니다.

이 Mac에서는 에뮬레이터가 호스트의 IPv6 링크 로컬 DNS를 자동으로 사용하지 못했습니다. 기기를 종료한 뒤 `./scripts/start-pleos.sh -dns-server 8.8.8.8`로 시작해 YouTube 도메인 해석을 확인했습니다. 해당 실행에만 적용한 옵션이며, 네트워크 연결 확인이 영상 재생 검증을 뜻하지는 않습니다. [Android 에뮬레이터 DNS](https://developer.android.com/studio/run/emulator-networking-dns)

## 다음 구현

1. 기기에서 권한 허용·거부와 실제 SOC·충전 상태·UX 제한을 검증합니다.
2. 자체 활동·복구·시간 종료와 WebView 플레이어를 전체 화면 및 분할 창에서 검수합니다.
3. TTS와 오디오 포커스를 연결하고 목표 도달·중단·화면 이탈 시 중지 동작을 검증합니다.
4. 실제 장소·도보 경로 제공사와 휴대폰 인계를 연결한 뒤 외출 추천을 추가합니다.
5. 명시적 자체 활동 평가를 충분히 모은 뒤 시간·상황별 밴딧 실험을 검토합니다.
6. 승인과 실행 가능성이 확인되면 Gleo 입력 경로를 추가합니다.

배터리 데이터가 없을 때 수동 타이머를 제공할 수 있지만, 안전 상태가 불명확할 때 상세 조작을 허용하는 우회 수단으로 사용하지 않습니다. 차 밖 알림, 외출 추천, 정밀 충전 ETA, 시트 제어와 주문·결제는 후속 범위입니다.

## 공모전 자료

현재 debug APK는 개발용이며 제출용 release APK가 아닙니다. release 서명과 검증, 시연 녹화는 별도로 진행합니다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)에 맞춰 소스와 실행 가능한 APK, 실행 방법과 테스트 환경을 정리합니다.

연구 스냅샷, 참가자의 개인정보와 서명 문서, `local.properties`, 환경변수 파일, 인증정보와 서명키는 Git 제외 대상입니다. 최종 제출 자료는 공식 양식의 익명 안내에 맞춰 별도 점검합니다.
