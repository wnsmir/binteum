# 빈틈의실

전기차 충전 중 **현재 충전량과 목표까지 남은 예상시간**을 바탕으로 활동을 추천하고, 사용자가 영상·활동 탭에서 원하는 것을 직접 선택할 수 있는 Pleos Connect 앱입니다.

2026-11-12 기준 `codex/charge-aware-dashboard`에서 충전 정보 중심 흐름을 구현했습니다. 차량의 목표와 일치하는 AAOS 잔여 충전시간을 우선 사용하며, 지원되지 않으면 충분한 SOC 관측이 쌓인 뒤 최근 충전 속도로 근사합니다. 출발 시각은 별도 선택 제약이며 충전 완료 예상시간으로 표시하지 않습니다. [현재 구현·검증 상태](docs/implementation-status.md)

하단은 **대시보드·영상·음악·활동·낮잠** 다섯 탭입니다. 활동에는 현재 위치 주변의 카페·식사·간식·산책을 통합했고, 숨쉬기·명상·퀴즈 등의 자체 활동과 별도 주변 탭은 제거했습니다. 예상시간이 없거나 짧아 추천이 없어도 안전 조건이 충족되면 원하는 콘텐츠를 직접 선택할 수 있습니다. 선택 시점의 충전 예상시간·SOC·목표·종류·직접/추천 경로를 기기의 SQLite에 누적합니다. 낮잠의 완료·중단·시간을 기록하며, 영상·음악 재생 이력·지도 검색어·위치·방문 이력은 수집하지 않습니다. 현재 개인화는 선택 빈도와 명시적 평가를 사용하는 규칙이며 강화학습이나 개인화 서버 기능이 아닙니다.

**음악 탭**에서 YouTube Music·Apple Music·Spotify를 기본 서비스로 지정하고, 남은 시간에 맞춰 피아노·어쿠스틱·로파이·재즈 등의 분위기를 추천합니다. 공식 앱 또는 일반 브라우저를 열어 해당 서비스의 로그인 상태를 재사용합니다. 세션 만료 때는 재로그인이 필요할 수 있으며 빈틈의실이 계정·토큰을 저장하지 않습니다. 외부 음악 재생이나 충전 종료 시 자동 정지는 제어하지 않습니다. 적합한 앱/브라우저가 없으면 휴대폰 연결 QR을 제공합니다. 에뮬레이터의 테스트용 WebViewShell은 로그인 경로로 사용하지 않습니다. [공식 API 조사와 연결 범위](docs/music-integration.md)

**활동 탭**에서 카페·식사·간식·산책을 누르면 차량 현재 위치를 가져와 실제 주변 장소와 도보 왕복시간을 자동 조회합니다. 처음에는 위치 권한을 요청하며, 충전소 이름이나 이동시간을 입력하지 않습니다. 왕복 경로 시간＋종류별 체류시간＋복귀 여유 5분을 현재 충전 예산과 비교해 장소 카드를 표시하고, 가는 길·돌아오는 길은 QR로 휴대폰에 전달합니다. 위치나 경로를 확인하지 못하면 임의 장소·시간으로 채우지 않습니다. [지도 구현과 공식 API 근거](docs/nearby-maps.md)

장소·보행 조회는 현재 **개발용 로컬 BFF**를 통해 실제 대한민국 OpenStreetMap 추출 데이터와 Valhalla 보행 경로를 사용합니다. 장소 목록은 Geofabrik 공식 데이터로 이 Mac에 준비하며 한국 지원 범위 밖은 별도로 안내합니다. Kakao 로그인은 진행하지 않았으며 `server/.env`의 선택적 REST 키가 있을 때만 Kakao 공급자로 바뀝니다. 키는 APK에 넣지 않습니다. 운영용 HTTPS 서버는 아직 배포하지 않았고, 휴대폰 복귀 알림도 별도 범위입니다.

**낮잠 탭**은 5·10·15·20·30분 휴식, 기상 여유 2·3·5분, 화면 밝기, 오프라인 화이트·핑크·브라운 노이즈와 음량을 저장합니다. 실제 충전 여유가 줄면 기상을 앞당기며 화면 이탈·안전 상태 상실·다른 오디오의 포커스 획득에 맞춰 소리를 멈춥니다. 공조는 P단·속도 0·지원 구역·권한 확인 뒤 명시적으로 적용/복원하며, ‘온도 적용 후 낮잠 시작’은 getter로 변경을 확인한 후에만 휴식을 시작합니다. 실내 센서 온도·설정 온도·공조 전원과 시트 지원 상태도 구분합니다. 실제 시트 이동·공조 전원 변경은 하지 않으며 앱이 닫히거나 차량이 꺼진 뒤 기상음을 보장하지 않습니다. [시트·공조 API와 검증 경계](docs/nap-vehicle-controls.md)

차량 충전 목표 변경도 별도 경로로 구현했습니다. Pleos 제어 권한·쓰기 capability·신선한 안전 상태를 확인한 뒤 사용자가 명시적으로 선택한 값을 보내고 다시 읽어 일치해야 성공으로 처리합니다. **실제 목표 쓰기는 아직 검증하지 않았습니다.** 앱 추천 목표 선택과 실제 차량 목표 변경은 다른 조작이며, 충전 시작·중지 명령은 보내지 않습니다. 자동 위치 추천 변경의 검증 결과는 [구현·검증 상태](docs/implementation-status.md)를 따릅니다. 실제 차량 위치 수신·QR·화면·기상음·차량 쓰기의 검수는 코드 및 공급자 응답 검사와 구분합니다.

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

## 주변 검색 개발 실행

Python 3으로 실제 대한민국 장소 인덱스를 먼저 준비한 뒤 Node.js 22 이상으로 로컬 서버를 실행합니다. 원본 지도 약 277MiB와 생성 인덱스·Python 의존성은 Git에서 제외되는 `.local/maps/`에 저장합니다. 실제 에뮬레이터 serial을 확인해 포트를 연결합니다.

```bash
python3 scripts/prepare-nearby-pois.py
./scripts/start-nearby-server.sh
# 별도 터미널: VERIFIED_SERIAL을 adb devices에서 확인한 값으로 바꿉니다.
adb -s VERIFIED_SERIAL reverse tcp:3210 tcp:3210
```

Debug 앱의 기본 주소는 `http://127.0.0.1:3210`이며 HTTP 허용은 이 loopback 주소로 한정합니다. 서버도 Mac의 `127.0.0.1`에만 바인딩합니다. Release에는 로컬 주소를 넣지 않으며 별도 HTTPS 서버 주소를 `-PbinteumNearbyApiBaseUrl=https://...`로 설정해야 합니다. 공개 OSM/Valhalla 서버 연결은 소규모 개발용으로 제한하며 운영 배포에는 제공사 사용 조건에 맞는 서버 구성이 필요합니다.

## 프로젝트 구성

- `app/`: 차량 데이터 어댑터, 충전 상태·활동·설정 화면, WebView 콘텐츠
- `core/`: 차량 상태 모델, 목표 도달 판단, 시간 예산, 활동 추천·평가 중복 방지
- `server/`: 현재 위치 기반 장소·왕복 보행 조회를 위한 개발용 로컬 BFF
- `scripts/`: 프로젝트 범위 환경 확인과 Gradle, Pleos AVD 및 Virtual Console 실행
- `.github/workflows/android.yml`: 단위 테스트, APK 빌드, lint
- `docs/product-plan.md`: 공동작업용 제품 기획과 MVP 완료 기준

## 콘텐츠와 개인화 문서

아래 문서에는 2026-11-08의 확장 설계와 이후 구현 이력이 함께 있습니다. 2026-11-10 현재 상태는 각 문서 상단과 구현·검증 상태 문서를 우선합니다. 자동 장소·도보 경로 조회는 개발용 로컬 BFF에 연결했으며 운영 서버 배포는 후속 범위입니다.

- [경험 확장 실행 계획](docs/experience-plan.md): 시간별 활동, YouTube·웹·주변 산책과 간식, 제출까지 일정
- [UI 명세](docs/ui-spec.md): 추천·진행·종료·휴대폰 인계 화면과 오류 상태
- [API 설계](docs/api-spec.md): 현재 `/v1/nearby` 계약과 데이터 경계. [OpenAPI 3.1 제안](docs/api/openapi.json)의 기존 4개 경로는 후속 설계
- [추천과 개인화 설계](docs/recommendation-design.md): 시간·안전 필터, 직접 선호와 자체 평가, 이후 밴딧 실험
- [공식 차량 API 전체 목록](docs/vehicle-api-matrix.md): 19개 영역·배포물 확인 440개, 권한과 연결 여부
- [웹 미디어 구현](docs/web-media-integration.md): YouTube 공식 IFrame, 자체 웹 콘텐츠, 오류와 중단 처리
- [UI 고도화](docs/ui-refinement.md): 자동·라이트·다크 모드, 공통 아이콘, 충전 게이지, 반응형 화면

## 함께 개발하기

저장소를 clone한 뒤 Android Studio에서 루트 폴더를 열고 위 빌드 명령으로 환경을 확인합니다. 기능 작업은 별도 브랜치에서 진행하고, 테스트와 빌드를 확인한 후 `main`을 대상으로 Pull Request를 엽니다.

소스·테스트·Gradle Wrapper·CI·실행 스크립트와 개발 문서는 Git으로 공유합니다. APK/AAB, 빌드 캐시, 에뮬레이터 이미지, 로그와 조사 원문, 참가자 정보·서명 문서, 로컬 경로 설정과 서명키는 올리지 않습니다. 제출용 APK는 검증을 마친 뒤 별도 배포 자료로 관리합니다.

## 현재 확인한 결과

최종 검증 명령과 결과는 [구현·검증 상태](docs/implementation-status.md)에 기록합니다. core 테스트, debug APK 빌드와 lint를 함께 실행합니다.

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 테스트: `core/build/reports/tests/test/index.html`
- lint: `app/build/reports/lint-results-debug.html`

단위 테스트는 목표 도달·중단·제한·데이터 지연 상태와 시간 예산 경계, 선호 순위, 완료 확인과 평가 중복 방지를 확인합니다. 실제 기기에서의 권한·차량 값·화면·영상 재생 검증과 구분합니다.

## Pleos 설치 및 기존 연결 기록

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

Virtual Console은 `http://127.0.0.1:8088/`에서 엽니다. 앱의 기본 입력은 실제 SDK 어댑터이며, 권한이 없으면 활동·미디어 화면에 `충전 상태 읽기 허용` 버튼을 표시합니다. 거부 후에는 앱 설정에서 다시 허용할 수 있고, 연결 실패 시 재확인 버튼을 제공합니다. 기술 진단은 debug 설정 화면에 표시합니다.

이 에뮬레이터의 운전자 프로필은 user 10입니다. 현재 사용자의 에너지 읽기 권한을 허용한 뒤 SOC 100%, `STATE_FULLY_CHARGED`, UX 제한 없음 수신을 확인했습니다. Console에서 SOC를 65%로, Charge Switch를 On으로 바꾸면 두 값은 앱에 반영되지만 `EV_CHARGE_STATE`는 계속 `STATE_FULLY_CHARGED`였습니다. 충전 스위치만으로 충전 중이라고 판단하지 않습니다. 화면·영상 시연은 debug의 `개발용 모의 충전으로 둘러보기`로 진행하며 모의 상태임을 표시합니다.

이 Mac에서는 에뮬레이터가 호스트의 IPv6 링크 로컬 DNS를 자동으로 사용하지 못했습니다. 기기를 종료한 뒤 `./scripts/start-pleos.sh -dns-server 8.8.8.8`로 시작해 YouTube 도메인 해석을 확인했습니다. 해당 실행에만 적용한 옵션이며, 네트워크 연결 확인이 영상 재생 검증을 뜻하지는 않습니다. [Android 에뮬레이터 DNS](https://developer.android.com/studio/run/emulator-networking-dns)

## 다음 검수와 확장

1. 실제 충전 중 ETA의 단위·갱신·목표 정합성을 확인합니다. 미지원·0·오래된 값은 실제 예상시간으로 쓰지 않습니다.
2. 권한·capability가 충족된 기기에서 사용자가 선택한 목표 쓰기와 readback을 검수합니다. 현재는 구현만 완료한 상태입니다.
3. 대시보드·직접 선택 탭, 충전 중단·주행 제한·이탈, 자체 활동 복구, SQLite 기록과 삭제를 기기에서 확인합니다.
4. 기존 음성 기능은 실험용 미리 듣기로 유지합니다. SDK 엔진·프로젝트 권한 문제를 해결하고 검증한 뒤 충전 완료 안내를 연결합니다.
5. 주변 탭에서 카테고리 선택 후 차량 현재 위치·실제 장소·자동 도보시간·QR 연결을 확인합니다. 낮잠 화면·기상음·공조 적용/복원도 검수하며, 운영 HTTPS 서버와 휴대폰 복귀 알림은 후속으로 연결합니다.
6. 충분한 허용 데이터를 확보한 뒤 별도의 밴딧 실험과 Gleo 입력 경로를 검토합니다.

안전 상태가 불명확하거나 충전 중이 아니면 콘텐츠 실행을 차단합니다. 예상시간이 없다는 것과 안전 상태가 불명확하다는 것은 구분합니다. 차 밖 알림, 주변 검색 운영 서버 배포, 정밀한 충전 곡선 예측, 시트 제어와 주문·결제는 후속 범위입니다.

## 공모전 자료

현재 debug APK는 개발용이며 제출용 release APK가 아닙니다. release 서명과 검증, 시연 녹화는 별도로 진행합니다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)에 맞춰 소스와 실행 가능한 APK, 실행 방법과 테스트 환경을 정리합니다.

연구 스냅샷, 참가자의 개인정보와 서명 문서, `local.properties`, 환경변수 파일, 인증정보와 서명키는 Git 제외 대상입니다. 최종 제출 자료는 공식 양식의 익명 안내에 맞춰 별도 점검합니다.
