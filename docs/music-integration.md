# 음악 서비스 연결 — 2026-11-12

빈틈의실은 음악을 별도 선택 활동으로 제공하고 충전 여유에 따라 피아노·어쿠스틱·로파이·재즈·앰비언트·산뜻한 음악의 추천 순서를 바꾼다. 이 목록은 앱이 편집한 분위기 제안이며 서비스가 반환한 곡·재생목록이나 그 재생시간이 아니다. 사용자는 추천 밖에서도 모든 분위기를 고를 수 있다.

## 현재 구현

- YouTube Music, Apple Music, Spotify 중 기본 서비스를 기기에 저장한다. 계정 연결 상태를 저장하거나 추정하지 않는다.
- 서비스 열기는 공식 Android 앱을 먼저 시도하고, 해당 앱이 링크를 처리하지 못하면 동일한 공식 HTTPS 주소를 설치된 브라우저로 연다. 브라우저는 HTTPS 처리기와 Android 표준 `CATEGORY_APP_BROWSER` 선언의 교집합에서 찾으며 기본 브라우저를 우선한다. 에뮬레이터 WebViewShell 및 WebView 패키지는 제외하고, 특정 브라우저 패키지로만 전달하여 다른 URL 처리기로 재선택되지 않게 한다. 외부 앱·적합한 브라우저가 없으면 휴대폰 인계 QR를 제공한다.
- 분위기를 고르면 해당 서비스의 검색 화면을 연다. 고정된 곡 ID나 인기 차트, 검증하지 않은 플레이리스트 길이를 표시하지 않는다.
- 로그인은 서비스 소유의 앱/브라우저에서 사용자가 직접 한다. 기존 로그인 세션을 재사용할 수 있지만 서비스의 세션 만료·로그아웃·보안 확인으로 재로그인이 필요할 수 있다. 빈틈의실은 비밀번호·쿠키·액세스 토큰을 받거나 저장하지 않는다.
- 외부 서비스 화면을 열었다는 것은 로그인·구독·재생 성공을 확인했다는 뜻이 아니다. 빈틈의실은 외부 음악의 재생/일시정지·재생시간·충전 완료 시 자동 정지를 제어하지 않는다. 외부 서비스의 계정·기기·DRM 지원을 실제 기기에서 검수해야 한다.
- 음악 선택 기록은 앱의 MUSIC 종류 및 선택 당시 충전 상황만 대상으로 한다. 개별 곡·검색어·서비스 계정·외부 청취 이력을 수집하지 않는다. 기본 서비스 변경은 활동 재생 기록이 아니다.

## 공식 연동 조사

| 서비스 | 공식 기능 | 현재 선택과 후속 조건 |
| --- | --- | --- |
| YouTube Music | 공식 웹 플레이어/PWA와 앱 | 공식 웹 또는 앱을 연다. 공개된 YouTube Music 전용 재생 API 계약은 이번 조사에서 확인하지 못했다. YouTube 동영상의 오디오 추출·숨김 플레이어·비공식 인증 API는 사용하지 않는다. |
| Spotify | Android App Remote, Web API, Web Playback SDK | 앱/웹 연결은 지금 제공한다. 인앱 SDK/재생 제어는 등록한 앱과 사용자 인증이 필요하다. Web Playback은 Premium이 필요하며 현재 Web API 개발 모드는 앱 소유자 Premium과 최대 5명의 허용 사용자 제약이 있다. |
| Apple Music | MusicKit Android/Web 및 Apple Music API | 앱/웹 연결은 지금 제공한다. 인앱 재생은 Apple 개발자 식별자·서명 키로 발급한 developer token과 사용자 인증/구독 확인이 필요하다. 개인 키를 APK에 넣지 않는다. |

YouTube Music의 공식 웹/PWA 및 Android Auto 지원 안내는 Pleos에 그 앱이 설치되어 있거나 DRM 재생이 지원된다는 근거가 아니다. Google OAuth 정책은 개발자가 제어하는 임베디드 user-agent로 인증 요청을 보내는 것을 금지하므로 Google 로그인 페이지를 자체 WebView에 넣지 않는다. [YouTube Music 공식 안내](https://support.google.com/youtubemusic/answer/9231765?co=GENIE.Platform%3DDesktop&hl=en), [Google OAuth 정책](https://developers.google.com/identity/protocols/oauth2/policies), [YouTube 개발자 정책 안내](https://developers.google.com/youtube/terms/developer-policies-guide)

Spotify Android SDK는 사용자의 Spotify 앱을 제어하며 앱 등록과 사용자 권한이 필요하다. Web Playback은 별도의 브라우저 스트리밍 SDK이며 Premium이 필요하다. 현재 개발 모드의 사용자 수·쿼터는 공모전 배포 계획에서도 검토해야 한다. [Android SDK](https://developer.spotify.com/documentation/android), [Content Linking](https://developer.spotify.com/documentation/android/tutorials/content-linking), [Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk), [개발 모드 제한](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)

Apple은 MusicKit for Android/Web를 제공하며 서비스 API 통신에 서명한 developer token을 사용한다. Android에서 Music User Token 관리는 직접 구현해야 한다. Apple Music 웹은 로그인 후 이용할 수 있고 일반 카탈로그 스트리밍에는 구독이 필요하다. [MusicKit](https://developer.apple.com/musickit/), [사용자 인증](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit), [웹 이용 안내](https://support.apple.com/en-gb/guide/music-web-review/apdm8de77edb/web)

낮잠용 백색소음과 기상음은 앱이 직접 생성하는 오디오로 분리한다. 외부 서비스 곡을 다운로드하거나 기상음으로 재사용하지 않는다. Spotify API/SDK를 연결하는 후속 단계에서는 알람·다른 오디오 혼합 등 제한도 확인한다. [Spotify 개발자 정책](https://developer.spotify.com/policy)

## 코드와 검증 범위

`music/MusicCatalog.kt`는 시간 구간에 따른 분위기 순서를 제공한다. `music/MusicServices.kt`는 공식 HTTPS 링크, 기본 서비스 설정, 외부 앱→브라우저 열기를 제공한다. 외부 열기 직전 호스트의 충전·안전 상태를 다시 검사하며 실패한 앱 시도 뒤 브라우저 시도 전에도 검사한다. 새 권한·브라우저 쿠키 저장소·네트워크 SDK 의존성을 추가하지 않는다.

`MusicIntegrationProbe`는 네트워크나 실제 UI/계정 접근 없이 시간 경계, 공식 URL 범위, 인텐트 대상, 안전 조건, 앱 미설치 시 fallback을 검사한다. 해당 검사는 실계정 로그인·음악 재생 성공을 대신하지 않는다. 빌드/기기 검사 결과는 상위 구현 상태 기록에 반영한다.

현재 Pleos 에뮬레이터의 기본 HTTPS 처리기는 `org.chromium.webview_shell`이었다. 이 테스트용 WebView 화면을 로그인 가능한 정식 브라우저로 취급하지 않는다. `MusicBrowserPolicy`는 이 경우 브라우저 후보를 비우며 실제 서비스 앱도 없으면 `NO_HANDLER`로 휴대폰 QR를 사용한다. 브라우저 후보 발견 자체도 OAuth·DRM·음악 재생 검증은 아니다. 패키지 가시성은 음악 서비스 3개와 필요한 intent 유형만 선언한다. [Android 패키지 가시성](https://developer.android.com/training/package-visibility/declaring)
