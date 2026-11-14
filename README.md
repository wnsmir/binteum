# 웨이팅 (Waiting)

**충전 예상시간 또는 직접 설정한 대기시간에 맞춰 영상·음악·주변 장소·수면을 선택하는 Pleos Connect 앱입니다.**

이 문서는 `codex/unified-waiting-flow` 브랜치의 **개발용 debug 앱**을 직접 시연하는 안내입니다. 기본 시연에는 YouTube API 키·Pleos 프로젝트 등록·별도 서버가 필요 없습니다. **주변 장소 검색만** 로컬 서버와 지도 데이터가 추가로 필요합니다.

- 한국어 이름: **웨이팅** / 영어 이름: **Waiting**
- 비교 설치용 debug 이름: **웨이팅 · 친구** / **Waiting · Friend**
- debug 패키지: `com.binteum.app.friend` / Activity: `com.binteum.app.MainActivity`
- 기존 `com.binteum.app` 앱과 함께 설치 가능. 이전 앱은 자동으로 삭제하지 않습니다.
- 검증 환경: macOS 26.3.1, Apple Silicon ARM64, Pleos Connect 2.1.0 / API 34 ARM64 revision 2.
- Windows/Linux의 빌드 안내도 아래에 있지만, 이 문서의 실제 실행 검증은 위 Mac에서 했습니다.

## 목차

1. [준비물과 저장소](#1-준비물과-저장소)
2. [개발 환경과 에뮬레이터](#2-개발-환경과-에뮬레이터)
3. [빌드·설치·실행](#3-빌드설치실행)
4. [기본 시연: 타이머와 수면](#4-기본-시연-타이머와-수면)
5. [영상·음악·Netflix 시연](#5-영상음악netflix-시연)
6. [주변 장소 시연](#6-주변-장소-시연)
7. [실제 차량 데이터와 시트](#7-실제-차량-데이터와-시트)
8. [검증 명령과 실제 결과](#8-검증-명령과-실제-결과)
9. [문제 해결](#9-문제-해결)
10. [종료·다시 실행·개발 자료](#10-종료다시-실행개발-자료)

## 1. 준비물과 저장소

| 구분 | 필요한 것 |
| --- | --- |
| 기본 앱 시연 | 저장소 접근 권한, Git, JDK 21, Android Studio/SDK, 호환 Pleos 에뮬레이터 |
| 웹 영상·외부 서비스 | 인터넷. 서비스 로그인·구독은 서비스별 조건에 따름 |
| 주변 장소 | Node.js 22 이상, Python 3 및 venv/pip, 인터넷, 지도 캐시를 저장할 여유 공간 |
| 실제 시트 동작 | 해당 제어를 지원하는 차량·권한·P단·정지 상태. 일반 화면 시연에는 불필요 |

저장소는 비공개입니다. GitHub 초대를 해당 계정에서 수락하고 GitHub Desktop, 브라우저 로그인 또는 `gh auth login`으로 인증하세요. 토큰을 명령이나 README에 붙여넣지 않습니다.

처음 받는 경우 원하는 작업 폴더에서:

```bash
git clone --branch codex/unified-waiting-flow https://github.com/wnsmir/binteum.git
cd binteum
git status --short --branch
```

이미 다른 브랜치에서 작업 중이라면 먼저 변경사항을 확인합니다. 기존 폴더를 reset/clean하지 않고 **별도 폴더**로 시연할 수 있습니다.

```bash
git status --short --branch
git fetch origin
git worktree add -b codex/local-demo ../waiting-demo origin/codex/unified-waiting-flow
cd ../waiting-demo
```

`codex/local-demo`나 `../waiting-demo`가 이미 있으면 새 이름을 사용하세요. `main`이나 `codex/ui-refinement`는 현재 화면과 다를 수 있습니다. 아래 명령은 모두 **이 README가 있는 저장소 루트**에서 실행합니다.

## 2. 개발 환경과 에뮬레이터

### 고정 버전

| 항목 | 버전 |
| --- | --- |
| JDK | 21 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Android SDK | compile/target 34, min 28 |
| Android Build Tools | 36.1.0 |
| Pleos Vehicle / FusedLocation / TextToSpeech | 2.3.2 / 1.1.3 / 3.3.1 |
| ZXing core | 3.5.3 |

Android Studio의 업그레이드 제안을 적용할 필요는 없습니다. Gradle은 저장소 Wrapper를 사용합니다. Pleos 의존성은 `settings.gradle.kts`에 설정된 Maven에서 받습니다.

### Mac 터미널 환경

JDK 21을 설치한 뒤 아래처럼 **현재 터미널에만** 경로를 지정합니다. SDK가 다른 폴더에 있으면 Android Studio → SDK Manager의 실제 SDK 경로로 바꾸세요.

```bash
export BINTEUM_JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export BINTEUM_ANDROID_SDK="$HOME/Library/Android/sdk"
export PATH="$BINTEUM_ANDROID_SDK/platform-tools:$PATH"
./scripts/check-environment.sh
```

Java 21, `platforms/android-34/android.jar`, `build-tools/36.1.0/aapt2`, `platform-tools/adb`가 확인되어야 합니다. `MISSING`이 있으면 SDK Manager에서 Android API 34, Build Tools 36.1.0, Platform-Tools, Emulator를 설치하세요. 스크립트가 `MISSING`을 출력해도 종료 코드는 0일 수 있으므로 출력 내용을 확인해야 합니다.

**Android SDK·Pleos 이미지 약관은 설치하는 본인이 원문을 확인하고 동의해야 합니다.** 다른 개발자의 동의를 재사용하거나 무조건 동의하는 명령은 사용하지 않습니다.

### Pleos 이미지와 AVD 만들기

[공식 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따릅니다. 공식 문서는 Mac ARM64 이미지와 Windows/Linux용 별도 이미지를 안내합니다. 호스트 CPU와 맞는 이미지를 선택하세요. Intel Mac 등 본 문서에서 검증하지 않은 조합의 실행을 보장하지 않습니다.

1. Android Studio → SDK Manager → SDK Update Sites에서 공식 문서의 해당 OS 이미지 저장소 추가.
2. API 34의 Pleos Connect 2.1.0 시스템 이미지 설치.
3. Device Manager → Create Device → Automotive → Pleos Connect v2.1.0 선택.
4. AVD 이름을 `Binteum_Pleos_2_1_API34`로 지정. RAM 4 GB, Cold boot 설정.
5. Mac에서 AVD가 꺼져 있을 때 다음 명령 실행. **이 터미널은 실행 상태로 둡니다.**

```bash
./scripts/start-pleos.sh
```

이 스크립트는 검증한 Mac의 그래픽 문제 대응으로 `-gpu host -feature -Vulkan`과 스냅샷 미사용을 적용합니다. 다른 OS에서는 Device Manager의 해당 호스트용 설정을 사용하세요. 이미 실행 중인 같은 AVD를 중복 실행하지 않습니다. 호환 이미지가 없으면 빌드까지 수행한 뒤 지원 호스트나 실제 Pleos 기기를 사용해야 합니다.

### Windows에서 빌드할 때

Mac용 `.sh` 환경 스크립트 대신 PowerShell에서 설치한 JDK/SDK 경로를 사용합니다.

```powershell
$env:JAVA_HOME = 'C:\실제\JDK-21-경로'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug --no-daemon
```

Windows/Linux 에뮬레이터는 공식 문서의 해당 OS 이미지를 사용합니다. 이후 `adb` 설치·실행 명령은 동일하되 셸 변수 문법을 해당 OS에 맞춰 바꾸세요. 주변 POI 준비 스크립트는 POSIX venv 경로를 사용하므로 Windows 네이티브 실행은 검증하지 않았습니다. 이 문서의 전체 시연 재현 경로는 Mac입니다.

## 3. 빌드·설치·실행

에뮬레이터와 별도 터미널을 열고 저장소 루트로 이동한 뒤, **2절의 환경변수 설정을 그 터미널에서도 실행**합니다.

```bash
./scripts/gradle.sh :core:test :app:assembleDebug :app:lintDebug --no-daemon
adb devices -l
```

`BUILD SUCCESSFUL`을 확인합니다. APK는 `app/build/outputs/apk/debug/app-debug.apk`입니다.

목록에서 시연할 에뮬레이터 serial을 확인해 지정합니다. 아래 `emulator-5554`는 예시이며 여러 기기 중 임의로 첫 기기를 선택하지 마세요.

```bash
export WAITING_SERIAL=emulator-5554
export WAITING_USER="$(adb -s "$WAITING_SERIAL" shell am get-current-user | tr -d '\r')"
printf 'serial=%s user=%s\n' "$WAITING_SERIAL" "$WAITING_USER"
adb -s "$WAITING_SERIAL" shell getprop sys.boot_completed
```

부팅 완료 값이 `1`인지, 운전자 번호가 숫자인지 확인합니다. 이 환경에서는 user 10이었지만 **직접 입력해 고정하지 않습니다.**

```bash
adb -s "$WAITING_SERIAL" install --user "$WAITING_USER" -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$WAITING_SERIAL" shell am start --user "$WAITING_USER" -W -f 0x10008000 -n com.binteum.app.friend/com.binteum.app.MainActivity
```

`Success`, `Status: ok`가 나오고 **웨이팅 · 친구**가 열리면 설치 완료입니다. `-r`은 기존 데이터를 보존하는 업데이트이며, 실행 플래그는 화면 스택만 새로 엽니다. 기존 별도 앱 아이콘을 열면 변경 전 화면이 보일 수 있습니다.

## 4. 기본 시연: 타이머와 수면

이 절은 **모의 충전 데이터**로 진행합니다. 실제 차량 API 성공을 증명하는 시연은 아닙니다. 모의 모드는 debug 앱에만 있습니다.

### 모의 충전 켜기

1. 최초 화면에서 **개발용 모의 충전으로 둘러보기**를 누릅니다. 또는 측면 아래 **설정 → 차량 연결 → 모의 충전**을 켭니다.
2. 설정을 열었다면 측면 아래 **설정 적용**을 누릅니다.
3. 배터리 **65%**, **모의 충전** 표시와 영상·음악·주변 장소·수면 콘텐츠를 확인합니다.
4. 화면이 잠겨 있다면 설정 → 차량 연결 → 개발용 진단 → 테스트 상황에서 **충전 중 65%**를 선택합니다.

타이머 설정 전에도 콘텐츠를 둘러볼 수 있습니다. 목차는 콘텐츠 옆 아이콘이며 스크롤 위치에 따라 해당 이름이 펼쳐집니다. 수면 시작은 남은 시간이 정해져야 활성화됩니다.

### 시간 설정과 다시 설정

| 조작 | 기대 결과 |
| --- | --- |
| 측면 다이얼 누르기 | 큰 대기 설정 모달 표시 |
| `시간` 선택 → 다이얼을 20분으로 조절 → `시작` | 모달이 닫히고 같은 다이얼에서 카운트다운 시작 |
| 다시 다이얼 누르기 → 값만 바꾼 뒤 닫기 | 기존 대기 설정 유지 |
| 다시 다이얼 누르기 → `초기화` | 대기 설정 해제. 재시연하려면 다시 `시작` |
| `목표 잔량` 선택 → 목표 설정 → `시작` | 사용 가능한 충전 ETA를 표시. 미확인 ETA를 임의 시간으로 표시하지 않음 |

목표 잔량 다이얼은 **추천 기준**입니다. 실제 차량 충전 목표를 자동 변경하거나 충전을 시작하지 않습니다. ETA는 차량 목표와 맞는 OEM 값 또는 충분한 SOC 증가 표본을 이용한 근사치입니다. 모의 SOC가 고정된 경우 모든 목표에 ETA가 생기는 것은 아닙니다. 안정적인 기본 시연에는 **시간 20분**을 사용하세요.

### 수면 시연

1. **시간 20분**으로 시작합니다.
2. 콘텐츠의 침대 모양 목차를 누르거나 아래로 스크롤해 **수면**으로 이동합니다.
3. 섹션에는 **남은 시간 동안 수면**과 **시트 위치 설정**, 두 버튼만 있습니다.
4. **남은 시간 동안 수면**을 누릅니다.
5. 앱 영역 전체가 검어지고 **남은 시간과 종료 버튼만** 보이는지 확인합니다. 시간이 실제로 줄어듭니다.
6. **종료**를 누르면 이전 콘텐츠·스크롤·밝기로 돌아갑니다. 메인 타이머는 계속됩니다.
7. 완료 경계를 보려면 다이얼에서 **시간 1분**으로 새로 시작한 뒤 수면으로 진입합니다.

별도 수면 시간이나 기상 준비 시간을 추가로 빼지 않습니다. 다만 충전 추정·출발 제한이 더 짧으면 그 이용 가능 시간까지만 사용합니다. 앱을 벗어나거나 안전 상태를 확인할 수 없으면 수면이 끝납니다. **앱 종료·기기 절전 뒤 알람은 보장하지 않습니다.** 기상음의 실제 청취와 Pleos TTS 음성은 별도의 검증 항목입니다.

### 설정과 반복 시연

- 측면 아래 **설정** → 한 열의 설정 목록 → **설정 적용**으로 복귀.
- 글자 크기 기본 **아주 크게(130%)**. 이전에 선택한 값은 업데이트해도 유지.
- 기본 배경은 시스템 밝기에 맞는 단색. 단색 블랙/화이트에서 배경 움직임은 비활성화.
- 그라데이션·시간별 배경은 선택 가능. 한국어/영어·목차 위치·조작 패널 위치 변경 가능.
- 기본값 검수를 위해 데이터를 지우지 마세요. 현재 선택을 직접 확인하고 원하는 값으로 바꾸면 됩니다.
- 개발용 진단의 **주행 제한**, **충전 중단**, **데이터 지연**으로 실행 차단을 확인하고, 시연 후 **충전 중 65%**로 복귀합니다. 안전 제한을 제거할 필요가 없습니다.

## 5. 영상·음악·Netflix 시연

| 기능 | 시연 방법 | 실제 구현 범위 |
| --- | --- | --- |
| YouTube | 영상 카드의 썸네일·길이 확인 → 카드 선택 → 같은 화면 플레이어에서 재생 | 샘플 목록 + 공식 IFrame 임베드. 별도 API 키 없음. 영상 제공 상태·네트워크에 따라 재생 실패 가능 |
| 음악 | 음악 섹션에서 서비스 선택 → YouTube Music / Apple Music / Spotify 선택 → 외부 앱/웹 열기 또는 휴대폰 QR | 외부 서비스 연결. 차량 안 OAuth 계정 연동·직접 스트리밍 SDK 아님 |
| Netflix | Netflix 카드의 열기 동작 선택 | 공식 앱/웹 외부 연결. 앱 내부 Netflix 임베드 재생 아님 |

이 Pleos 에뮬레이터에는 지원 음악 앱과 일반 브라우저가 없어 음악은 **휴대폰 QR 경로**로 확인했습니다. 테스트용 WebViewShell을 로그인 브라우저로 사용하지 않습니다. 로그인·구독은 해당 서비스에서 직접 진행해야 하며, 웨이팅이 자격증명을 받지 않습니다. 외부 음악의 실제 재생과 종료는 해당 서비스가 담당합니다.

영상의 화면·소리·테마 변경 중 재생 유지 여부는 사람이 직접 확인할 항목입니다. 코드 검사·빌드 성공을 재생 성공으로 대신하지 않습니다. 시간에 맞는 추천은 보유 샘플·규칙 기반이며 Netflix 전체 카탈로그나 모든 음악의 재생시간을 알고 추천하는 서비스가 아닙니다.

## 6. 주변 장소 시연

### 장소 데이터와 서버 준비

기본 시연과 달리 **서버가 필요**합니다. 별도의 지도 API 키 없이 대한민국 OSM 인덱스와 Valhalla 도보 경로를 사용합니다. 지도 원본은 약 280 MiB이고 로컬 캐시·인덱스·venv가 추가로 생성됩니다. 최신 지도 크기는 달라질 수 있습니다.

저장소 루트에서:

```bash
node --version
python3 --version
python3 scripts/prepare-nearby-pois.py
./scripts/start-nearby-server.sh
```

Node는 **22 이상**이어야 합니다. 준비 스크립트는 프로젝트의 `.local/maps/venv`에만 Python 의존성을 설치하고 지도 원본과 인덱스도 `.local/maps/`에 보관합니다. 처음에는 다운로드·추출 시간이 필요합니다. 완료 후 `south-korea-pois.json`이 생성됩니다. `npm install`은 필요 없습니다.

이미 다른 작업 폴더에 공식 인덱스가 있으면 준비 단계를 반복하지 않고 실제 파일 경로를 지정할 수도 있습니다.

```bash
export LOCAL_POI_FILE="/실제/지도캐시/south-korea-pois.json"
./scripts/start-nearby-server.sh
```

서버 터미널은 그대로 두고, 다른 터미널에서 저장소 루트·2절의 PATH·3절의 serial/user를 설정한 뒤:

```bash
curl -fsS http://127.0.0.1:3210/health
adb -s "$WAITING_SERIAL" reverse tcp:3210 tcp:3210
adb -s "$WAITING_SERIAL" reverse --list
```

health에서 `configured:true`, `provider:OPENSTREETMAP`, `coverage:SOUTH_KOREA`를 확인합니다. health는 설정 여부이며 실제 경로 서버의 연결 성공까지 보장하지는 않습니다. 이미 3210 서버가 실행 중이고 이 상태라면 중복 실행할 필요가 없습니다. `NOT_CONFIGURED`이면 인덱스 파일과 실행 경로를 확인합니다.

`server/.env`의 Kakao 키는 선택 사항이며 기본 시연에 필요 없습니다. 운영 HTTPS 서버는 아직 배포하지 않았습니다. 공개 경로 서버는 소규모 개발 검증용이므로 다수 이용자 대상 배포에는 별도 운영 구성이 필요합니다.

### 에뮬레이터 시험 위치와 권한

아래는 **에뮬레이터 GPS만** 공개된 강남역 시험 위치로 설정합니다. Mac의 실제 위치를 조회하지 않습니다. 순서는 **경도, 위도**입니다.

```bash
adb -s "$WAITING_SERIAL" emu geo fix 127.0276 37.4979
```

앱의 **주변 장소 → 차량 위치 권한 → 허용**에서 본인이 승인합니다. Pleos Connect 2.1.0의 일부 에뮬레이터는 custom 위치 권한이 설정 목록에 없고 OS 허용 창도 검게 표시되는 문제가 있습니다. 현재 앱은 해당 이미지에서 문제가 있는 창 대신 안내를 표시합니다.

**본인 소유의 개발용 에뮬레이터에서 위치 사용에 동의한 경우에만**, 다음 명령으로 현재 운전자의 이 앱에 해당 권한 하나를 부여할 수 있습니다. 실제 차량용 우회 절차로 사용하지 않습니다.

```bash
adb -s "$WAITING_SERIAL" shell pm grant --user "$WAITING_USER" com.binteum.app.friend pleos.car.permission.FUSED_LOCATION
adb -s "$WAITING_SERIAL" shell am start --user "$WAITING_USER" -W -f 0x10008000 -n com.binteum.app.friend/com.binteum.app.MainActivity
```

권한 부여 명령은 보통 성공 시 출력이 없습니다. 아래 SDK 검증에서 `permissionGranted=true`를 확인할 수 있습니다. 이 명령은 Mac 위치 권한·에너지 권한·시트 권한을 변경하지 않습니다.

### 검색과 기대 결과

1. 모의 충전은 켠 상태로 두고 **시간 30분** 설정.
2. 주변 장소 → **근처 카페** 선택.
3. 최대 3개의 실제 장소 카드와 가는 길·오는 길의 도보시간 확인.
4. 왕복 시간 + 체류시간 + 복귀 여유 5분이 남은 시간에 들어가는지 표시 확인.
5. 지도 연결/QR은 외부 지도 서비스로 이어짐. 휴대폰 스캔은 별도 확인.

**모의 충전 스위치는 위치를 모의로 만들지 않습니다.** 이 시연은 모의 충전 + 에뮬레이터 시험 GPS + 실제 SDK/장소 서버의 조합입니다. 실제 도로 위치에서의 검증과 구분합니다. 장소 데이터·경로 응답에 따라 결과와 개수는 바뀔 수 있습니다.

검색 간 최소 15초 간격이 있으므로 반복 탭 대신 기다리세요. 지원 지역 밖·위치 지연·경로 실패·통신 실패는 별도 안내되며, 가짜 장소나 직선거리 기반 보행시간으로 채우지 않습니다. 새 검색 직전에 `emu geo fix`를 다시 보내면 위치 신선도 확인에 도움이 됩니다.

## 7. 실제 차량 데이터와 시트

### Virtual Console과 읽기 확인

```bash
./scripts/open-virtual-console.sh "$WAITING_SERIAL"
```

출력된 `http://127.0.0.1:8088/`을 브라우저에서 엽니다. 실제 SDK 데이터를 보려면 앱 설정 → 차량 연결에서 모의 충전을 끄고 **배터리 상태 권한**을 앱 흐름에서 승인하세요.

- Console SOC를 변경했을 때 앱의 배터리 표시가 따라오는지 확인.
- 개발용 진단에서 SOC, Charge Switch, 실제 `EV_CHARGE_STATE`, UX 제한을 구분.
- 검증한 이미지에서는 SOC 65%·Charge Switch On이어도 실제 상태가 `STATE_FULLY_CHARGED`로 남았습니다. 이를 충전 중으로 오인하도록 수정하지 않습니다.
- 영상·수면 화면을 재시연하려면 모의 충전을 다시 켭니다. 전환하면 진행 중 대기 상태가 초기화될 수 있으므로 시간을 다시 설정하세요.

### 시트 위치 설정의 범위

- 수면 → **시트 위치 설정**에서 차량의 기존 앞좌석 왼쪽 메모리 번호를 선택·기억.
- 모의 충전에서는 시트 제어 비활성화. 실제 모드에서 권한·capability·P단·최신 속도 0을 확인해야 명시적 적용 요청 가능.
- 현재 Vehicle 2.3.2에는 연속 이동/STOP 및 기존 메모리 선택 API가 있습니다. **현재 자세를 새 메모리에 저장하는 API는 없습니다.**
- 앱은 메모리 번호를 기억할 뿐 새 자세를 저장하거나 수면 시작/종료 시 자동으로 움직이지 않습니다.
- 메모리 적용 완료 readback이 없어 ‘요청 전송’과 ‘실제 이동 완료’를 구분합니다. 현재 실제 모터 이동은 검증하지 않았습니다.

## 8. 검증 명령과 실제 결과

### 기본 검사

```bash
./scripts/gradle.sh :core:test :app:assembleDebug :app:lintDebug --no-daemon
node --test server/test/nearby.test.mjs
```

테스트 보고서는 `core/build/reports/tests/test/index.html`, lint는 `app/build/reports/lint-results-debug.html`입니다. lint 경고를 오류 0개와 혼동하지 마세요.

### README 시연 버튼 자동 확인

현재 화면을 닫고 앱을 새로 여는 검사입니다. 실제 debug 모의 충전 기능과 버튼·타이머를 사용하며 차량 객체/시간을 주입하지 않습니다. 시연용 수면 선택 기록 1건을 남기며 차량 제어·위치·외부 계정 접근은 하지 않습니다.

```bash
./scripts/gradle.sh :app:assembleDebugAndroidTest -PbinteumTestRunner=com.binteum.app.ReadmeDemoProbe --no-daemon
adb -s "$WAITING_SERIAL" install --user "$WAITING_USER" -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$WAITING_SERIAL" shell am instrument --user "$WAITING_USER" -w com.binteum.app.friend.test/com.binteum.app.ReadmeDemoProbe
```

`result=PASS`와 `INSTRUMENTATION_CODE: -1`을 확인합니다. `-1`은 이 검사에서 Android의 성공 결과입니다. 화면 픽셀·소리·실제 시트 이동까지 검사하는 것은 아닙니다.

### 실제 위치 SDK → 서버 확인

6절의 지도·서버·포트·권한 준비가 필요합니다. SDK가 공개 시험 위치를 반환할 때만 검색하며 좌표를 로그에 출력하지 않습니다.

```bash
./scripts/gradle.sh :app:assembleDebugAndroidTest -PbinteumTestRunner=com.binteum.app.NearbySdkIntegrationProbe --no-daemon
adb -s "$WAITING_SERIAL" install --user "$WAITING_USER" -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$WAITING_SERIAL" emu geo fix 127.0276 37.4979
adb -s "$WAITING_SERIAL" shell am instrument --user "$WAITING_USER" -w com.binteum.app.friend.test/com.binteum.app.NearbySdkIntegrationProbe
```

기대 값: `permissionGranted=true`, `locationResult=FRESH_SDK_FIX`, `result=PASS`. 장소 개수·도보 경로는 실제 공급자 응답에 따라 달라질 수 있습니다. 검사 후 앱은 3절의 `am start` 명령으로 다시 엽니다.

### 2026-11-14 이 Mac에서 확인한 결과

| 검사 | 결과와 범위 |
| --- | --- |
| core / debug 빌드 / lint | 87개 테스트 통과, APK 빌드 성공, lint 오류 0·경고 63 |
| WaitingFlowProbe | 모의 차량 주입 기반 화면·예산·안전 제한·수면 복귀 48개 통과 |
| ReadmeDemoProbe | 설정 → 모의 충전 → 시간 20분 → 실제 카운트다운 → 수면 → 종료 → 설정 복귀, 13개 통과 |
| 서버 테스트 | 16개 통과; 기본 경로 POI 준비 및 서버 재시작 확인 |
| NearbySdkIntegrationProbe | 실제 SDK로 시험 GPS 수신 → 실제 OSM 서버 검색 → 장소 3곳·왕복 경로 3건 |
| 화면 확인 | 실제 앱 PixelCopy 캡처에서 새 이름, 수면 2개 버튼, 검은 수면 화면·종료 버튼 확인. 데이터는 명시적 시연 값 |
| 시트 메모리 | 지원 번호 읽기와 가짜 Seat 경계 검사. 실제 이동 미검증 |
| 외부 영상/음악·음성 | 이 버전의 YouTube 실제 화면/소리, 음악 로그인/재생, Netflix 외부 재생, TTS 청취는 미완료 |

새 컴퓨터의 SDK 설치·약관 승인과 Windows/Linux 실행을 이 Mac 검증으로 대신하지 않습니다. 명령별 실제 수행 범위는 [README 실행 확인](docs/readme-verification.md)에 기록했습니다. 상세 변경 이력과 과거 결과는 [구현·검증 기록](docs/implementation-status.md), SDK별 경계는 [사용 SDK/API](docs/sdk-integration.md)를 참고하세요.

## 9. 문제 해결

| 증상 | 확인·조치 |
| --- | --- |
| GitHub `Repository not found` | 비공개 초대 수락, 인증 계정·저장소 권한 확인. 새 저장소를 만들 필요 없음 |
| JDK 21을 찾지 못함 | 실제 JDK 21 설치 경로를 `BINTEUM_JAVA_HOME`에 지정. Gradle 캐시 삭제보다 환경 확인 우선 |
| SDK `MISSING` 또는 license 오류 | SDK Manager에서 지정 패키지와 본인 약관 동의 확인 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 기존 설치와 debug 서명이 다름. 데이터를 보존할지 결정하기 전 앱을 삭제하지 말고 기존 서명/별도 패키지 사용 검토 |
| 바뀌기 전 이름·화면이 보임 | `웨이팅 · 친구` / `com.binteum.app.friend`를 열었는지 확인하고 3절의 설치·실행 반복 |
| 콘텐츠가 잠겨 있음 | 모의 충전 켜기, 테스트 상황 `충전 중 65%` 선택. 실제 모드에서는 에너지 권한과 UX 제한/충전 상태 확인 |
| 00:00 또는 수면 비활성화 | 다이얼 → 시간 재설정 → 시작. 출발 제한이 이미 지났는지도 설정에서 확인 |
| 위치 권한 목록이 비거나 검은 창 | 6절의 알려진 에뮬레이터 문제 및 동의 기반 개발용 권한 부여 절차 사용. 앱은 해당 이미지에서 빈 창 진입을 방지 |
| 위치 미수신/오래됨 | GPS 시험 위치를 다시 전송하고 앱 재조회. 권한을 다른 사용자에게 부여하지 않았는지 확인 |
| 주변 서버 미연결 | 서버 터미널, `/health`, `adb reverse`, 인덱스 경로 확인. 재부팅하면 포트 연결 재설정 |
| 주변 장소가 없음 | 대한민국 지원 범위인지 확인. 에뮬레이터 기본 위치는 다른 나라일 수 있음 |
| 경로 실패·재요청 제한 | 인터넷·외부 공급자 상태 확인, 최소 15초 대기. 제한 응답 후에는 더 기다려야 할 수 있음 |
| 음악이 차에서 열리지 않음 | 지원 앱/일반 브라우저가 없는 에뮬레이터에서는 QR 경로 사용. 선택 완료가 로그인/재생 성공은 아님 |
| YouTube 연결 실패 | 인터넷과 영상 임베드 허용 여부 확인. 실제 DNS 장애가 확인된 경우에만 네트워크 정책상 허용된 DNS를 해당 에뮬레이터 실행의 `-dns-server` 옵션에 지정. 시스템 DNS는 변경하지 않음 |
| 음성 미리 듣기가 안 들림 | 현재 미해결 검증 항목. Gleo 자동 음성 완료 기능으로 안내하지 않음 |
| 시트 적용 불가 | 모의 모드 여부, 지원 메모리, 권한, P단·최신 속도 확인. 지원 정보만으로 실제 이동 성공을 가정하지 않음 |

## 10. 종료·다시 실행·개발 자료

- 수면을 끝낼 때는 **종료**. 대기 타이머는 다이얼 → **초기화**.
- 서버 종료는 서버 터미널에서 `Ctrl+C`. 에뮬레이터는 창의 종료 조작 사용.
- 다음 시연에서 에뮬레이터 부팅 → 실제 serial/user 확인 → 앱 실행. 주변 장소를 사용할 때만 서버·reverse 연결 준비.
- 앱 업데이트는 `install -r`. 데이터 삭제·AVD 초기화·Git reset/clean은 정상 시연 절차에 필요하지 않습니다.

이 문서에서 연결한 개발용 포트를 제거하려면:

```bash
adb -s "$WAITING_SERIAL" reverse --remove tcp:3210
adb -s "$WAITING_SERIAL" forward --remove tcp:8088
```

연결하지 않은 포트를 제거하면 오류가 나올 수 있으며 앱 데이터에는 영향이 없습니다.

| 경로 | 내용 |
| --- | --- |
| `app/`, `core/` | Android 화면·SDK 어댑터 / 시간·추천·상태 계산 |
| `server/`, `server/README.md` | 주변 장소·도보 경로 서버와 계약 |
| `scripts/` | 프로젝트 범위 환경·빌드·AVD·지도 준비 |
| `docs/implementation-status.md` | 날짜별 구현·검증·미완료 사항 |
| `docs/sdk-integration.md` | 사용 SDK/API 전체와 검증 경계 |
| `docs/design/` | [제출용 소개문](docs/design/waiting-submission-outline.txt)과 [실제 화면 사용 안내 PDF](docs/design/waiting-screen-guide.pdf). 모의 충전 데이터로 촬영; 와이어프레임은 별도 참고 자료 |
| `.github/workflows/android.yml` | GitHub 테스트·APK 빌드·lint |

`docs/product-plan.md`, `docs/experience-plan.md` 등에는 미래 계획과 과거 UI가 포함되어 있습니다. **현재 기능은 이 README와 최신 구현·검증 기록을 우선**합니다. 공조·배경음 설정, 자체 호흡/명상/퀴즈, 새 시트 자세 저장, 인앱 Netflix 스트리밍, Gleo 자동 알림은 현재 완료 기능으로 소개하지 않습니다.

APK·빌드 캐시·지도 원본·로그·환경 파일·자격증명·서명키는 Git에 올리지 않습니다. 현재 debug APK는 시연용이며 서명·심사를 마친 배포용 release가 아닙니다.
