# 웹 미디어 연결 구현

## 현재 변경 — 2026-11-10

- 영상 탭은 충전 ETA와 무관하게 준비된 영상을 직접 선택할 수 있다. 짧은 시간은 안내하며 차량/UX 안전 차단은 유지한다. 대시보드만 길이와 마무리 여유에 맞춰 추천한다.
- 자동 사전 로드는 제거했다. 사용자 선택 시에만 WebView를 생성하고, HTML 준비 전 연속 선택은 마지막 요청을 보관한다. 닫힌 플레이어의 늦은 콜백은 폐기한다. 큐 동작을 JVM 테스트로 확인했다.
- 선택 시 앱의 VIDEO 종류 선택 이벤트만 로컬에 기록한다. 영상 ID·제목·iframe 재생 이벤트·시청 시간·완료는 선호 기록에 저장하지 않는다.
- ETA가 짧아져 추천에서 제외되어도 직접 선택한 영상은 시간만으로 중지하지 않는다. 안전 상태 유실·충전 중단·앱 이탈은 기존 파괴 경로를 유지한다.
- 2026-11-10의 실제 UI·음성 재생 검수는 대기 중이며 이전 사용자 확인은 해당 이전 버전의 결과다.

아래는 이전 구현 기록이며 현재 동작과 다른 사전 로드·타이머 설명은 이 절을 우선한다.

2026년 11월 8일 확인. `WebMediaPanel`은 Android 앱 안에 웹 화면을 실제로 띄우는 컴포넌트다. 인터넷 없이 실행되는 60초 웹 휴식과 공식 YouTube IFrame 플레이어 연결을 구현했다. YouTube는 사용자가 직접 플레이어를 눌러 재생한다. **사용자가 이 Mac의 Pleos 에뮬레이터 시연 모드에서 공식 예제 영상과 소리가 정상 재생됨을 확인했다.** 이 결과는 모든 영상·실차·제한 전환 시 중지 검증을 뜻하지 않는다.

## 선택한 방식

| 방식 | 확인한 범위 | 적용 |
| --- | --- | --- |
| APK 안의 WebView | Android가 공식 지원하는 HTML/JavaScript 화면 | 이번 구현. native 앱에서 안전 상태와 생명주기를 관리 |
| 공식 YouTube IFrame | 웹 플레이어와 재생·중단·오류 이벤트 제공 | 연결 시도 가능. API 키 없이 공식 문서의 예제 ID 사용 |
| 설치형 PWA / TWA | Pleos의 독립 PWA 설치·배포·차량 안전 신호 연동을 공식 문서에서 확인하지 못함 | 현재 선택하지 않음. WebView 구현을 PWA 설치 완료라고 부르지 않음 |
| 외부 앱 / 브라우저 | Android `ACTION_VIEW`가 설치된 처리 앱으로 HTTPS 링크 전달 | 사용자가 직접 누른 링크만 전달. 외부 앱의 재생·복귀·주행 안전 제어는 보장하지 않음 |
| Netflix 웹 재생 | Pleos WebView 인증·공개 재생 API를 확인하지 못함 | 구현하지 않음. DRM·기기 인증 우회 없음 |

Android는 `loadDataWithBaseURL`을 통해 HTML에 HTTPS origin을 지정하는 경로를 문서화한다. 파일 URL 대신 APK의 HTML을 문자열로 읽고 `https://<실제 applicationId>/media/`를 base URL로 사용한다. 이 주소는 호스팅한 웹사이트가 아니라 앱 식별용 origin이다. [Android 로컬 웹 콘텐츠](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)

YouTube는 WebView에서 로컬 HTML을 사용할 때 이 방식으로 `Referer`를 설정하도록 안내한다. 명시적 Referer의 호스트는 실제 배포·설치된 앱 ID여야 한다. 현재 기본값은 `com.binteum.app`이며 런타임 `context.packageName`으로 계산한다. IFrame의 `origin`도 같은 값이다. 이 설정만으로 Pleos 장치의 재생 가능성이나 인증을 보장하지 않는다. [YouTube 앱 식별 요구사항](https://developers.google.com/youtube/terms/required-minimum-functionality)

## 파일과 호스트 연결

- `app/src/main/java/com/binteum/app/media/WebMediaPanel.kt`: native 안전 게이트, 버튼, WebView 생성·파기, 오류 표시.
- `app/src/main/assets/media/youtube.html`: 공식 IFrame 플레이어. 예제 ID `M7lc1UVf-VE`는 [공식 시작 예제](https://developers.google.com/youtube/iframe_api_reference)의 값이다. 제목·영상 길이를 만들어 표시하지 않는다.
- `app/src/main/assets/media/rest.html`: 자체 작성한 오프라인 60초 눈 쉬기. 시작·멈춤·완료가 실제 동작하며 경과 시간은 `performance.now()`로 계산한다.

```kotlin
val media = WebMediaPanel(activity)
mediaContainer.addView(media)

// 호스트가 클릭 시 조건을 다시 확인하고, 첫 콘텐츠에서만 시간 예산을 시작한다.
media.onContentOpen = { canOpenNowAndStartWindowIfNeeded() }

// false가 기본이다. true는 화면을 열어 주기만 하며 자동 재생하지 않는다.
media.setPlaybackAllowed(
    allowed = verifiedFreshSafeParkedState,
    reason = "차량의 정차·안전 상태를 다시 확인해 주세요."
)

// onPause / onStop / 탭에서 다른 화면으로 이동 / 안전 제한 발생
media.pauseForHost()

// onDestroy
media.release()
```

위 변수와 함수는 설명을 위한 이름이며 Pleos API가 아니다. 현재 MainActivity는 실제 어댑터의 `CarUxRestrictions`, 충전 상태, 관측 시각과 foreground·awake 조건을 검사한다. 기본 제한 이외의 UX flag가 있거나 안전 상태가 미확인이면 차단한다. 기어 P만으로 허용을 추론하지 않는다. 또한 선택한 시간에 2분 이상의 여유가 있고 자체 활동·복구가 진행 중이지 않을 때만 연다. 실제 차량 신호 수신과 제한 변경 시 재생 중지는 기기 검증 대상이다. debug 시연 모드는 모의 상태임을 표시한다. [Pleos 주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)

매초 상태를 갱신할 때 패널을 새로 만들지 않는다. 기존 패널에 허용 여부만 전달한다. 탭을 `GONE`으로 숨기는 경우에도 `pauseForHost()`를 호출한다. 화면에서 사라진 Pleos 앱은 프로세스가 종료되지 않을 수 있다. [Pleos 화면·생명주기](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)

Manifest에는 `android.permission.INTERNET`과 `android:usesCleartextTraffic="false"`를 적용했다. WebView용 별도 Gradle 의존성은 사용하지 않는다. INTERNET은 런타임 권한 창을 띄우는 권한이 아니다. [Android WebView 구성](https://developer.android.com/develop/ui/views/layout/webapps/webview)

## 안전과 오류 처리

현재 WebView는 HTTPS만 사용하며 파일·content 접근과 파일 origin 간 접근을 끈다. 카메라·마이크 등의 웹 권한을 거절하고 위치 접근을 끈다. `addJavascriptInterface`나 웹에서 호출하는 native 메시지 bridge는 없다. 차량 정보·위치·키를 HTML에 보내지 않는다. native는 750ms 간격으로 자체 페이지의 제한된 상태 객체만 읽는다. 세대 토큰과 Handler 정리로 폐기한 화면의 늦은 응답이 UI를 다시 바꾸지 않게 한다. [WebView bridge 보안](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)

차단·화면 전환·닫기에서는 로딩 중단, `onPause`, 빈 페이지 전환, 뷰 제거와 `destroy`를 수행한다. 플레이어를 가림으로써 음성만 계속 재생하지 않는다. 허용 상태 복귀 시에는 사용자가 다시 콘텐츠를 선택해야 한다. 웹 휴식의 완료와 영상 종료는 차량 세션 완료로 기록하지 않는다.

YouTube 오류 5, 100, 101/150, 153, 스크립트 로딩 실패, 20초 연결 지연을 구별한다. 오류에는 다시 열기와 오프라인 웹 휴식 경로가 있다. 인증서 오류는 취소하며 우회하지 않는다. renderer 종료도 앱 전체의 성공으로 숨기지 않고 재시도 상태로 돌아간다. [공식 IFrame 오류·자동 재생 안내](https://developers.google.com/youtube/iframe_api_reference)

일반 사이트를 입력하는 주소창이나 범용 내부 브라우저는 없다. 사용자가 누른 HTTPS 링크는 시스템 처리 앱으로 넘기기 전에 현재 웹 화면을 파기한다. 처리 앱이 없으면 안내한다. 이 동작이 외부 앱의 정차 제한·SOC 알림·재생 완료·복귀를 통제한다는 뜻은 아니다. [Android 외부 앱 호출](https://developer.android.com/training/basics/intents/sending)

## 플레이어와 UI 경계

native 텍스트는 24sp 이상, 버튼은 최소64dp, 동작은 2열로 배치했다. 웹 자체 텍스트도 24px 이상이다. WebView 높이는 폭에 따라 400~620dp로 조절하며 HTML 플레이어는 현재 viewport에 맞춘다. 좁은 창에서 남는 본문은 스크롤할 수 있다. YouTube 플레이어 자체 컨트롤의 글자·버튼은 공급자가 렌더링하며 앱이 임의로 확대·가리지 않는다. 최종 가독성은 실제 Pleos 창 크기별로 검사한다.

플레이어는 `autoplay=0`, `controls=1`, `fs=0`으로 인라인 재생한다. `fs=0`은 공식 파라미터이며 별도의 차량 전체화면을 만들지 않는다. 광고·브랜딩·관련 링크를 가리거나 스트림·오디오를 추출하지 않는다. 영상 길이나 광고 시간을 근거 없이 만들어 대기 예산 안의 완료를 보장하지 않는다. [공식 플레이어 파라미터](https://developers.google.com/youtube/player_parameters), [YouTube 개발자 정책](https://developers.google.com/youtube/terms/developer-policies)

이 앱은 YouTube 로그인·시청 이력·시청시간을 개인화 저장소에 넣지 않는다. 외부 플레이어는 자체 서비스 운영에 필요한 데이터를 YouTube에 전송할 수 있다. 공개 배포 전 실제 데이터 흐름에 맞는 앱 개인정보 고지, YouTube 약관 링크·동의 UX와 장치별 플레이어 동작을 점검해야 한다. 이 컴포넌트가 배포 정책 승인을 받았다는 뜻은 아니다.

## 검증 상태

큰 글자·2열 버튼·적응형 높이 변경까지 반영한 뒤 Kotlin 컴파일과 두 HTML의 JavaScript 구문 검사가 통과했다. MainActivity 통합 후 사용자가 `개발용 모의 충전으로 둘러보기 → 웹·미디어 → 예제 영상 열기 → 플레이어 재생` 경로에서 영상과 소리 재생을 확인했다. 직접 화면 제어가 불가해 사용자 관찰로 기록하며, 나머지 항목은 별도 검수한다.

1. 패널을 열기 전 원격 요청이 없고 직접 누른 뒤에만 YouTube를 로드하는지.
2. 예제 연결·플레이어 사용자 재생·오류 fallback과 오프라인 웹 휴식 시작/중단/60초 완료.
3. 주행·stale·휴면·화면 이탈 시 영상/음성이 멈추고 복귀 뒤 자동으로 이어지지 않는지.
4. 2560×1440@160 및 Pleos 두 창에서 모든 버튼·상태·플레이어가 읽히고 접근 가능한지.
5. 외부 링크를 열 수 없는 기기에서도 앱이 종료되지 않으며 안전 제어 범위를 과장하지 않는지.

현재 확인 범위는 해당 에뮬레이터의 공식 예제 영상·소리 재생이다. 자체 웹 휴식의 60초 완료, 화면 이탈·주행 제한·stale·휴면 중 재생 중지, 실차 호환성과 모든 영상의 재생 가능성은 별도 검증 결과로 기록한다.
