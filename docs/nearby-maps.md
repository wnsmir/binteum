# 주변 지도 연결 — 2026-11-10

현재 앱은 외부 지도의 검색 결과와 차량 위치 핀을 여는 링크를 만든다. 자체 장소 DB, 영업 여부 판정, 도보 경로 계산 API를 호출하지 않는다. 실제 보행 시간은 외부 지도에서 확인하고 사용자가 입력한다. 카페·음식점·편의점·공원은 검색 종류이며, 검증된 특정 가게 추천 목록이 아니다.

## 구현

- `NearbyMapLinks`: Kakao 웹 검색, 좌표 중심 모바일 검색, 차량 위치 핀과 Naver 앱 검색 URI 생성. API 키가 필요 없는 공개 링크 기능만 사용한다.
- `NearbyMapLauncher`: 사용자가 누를 때만 외부 Activity를 연다. 지정 지도 호스트와 스킴만 허용하고, 호출 직전 호스트의 차량 안전 조건을 재확인한다. 앱이나 브라우저가 없으면 실패를 반환하므로 QR 전달 화면을 계속 사용할 수 있다.
- `VehicleLocationReader`: 사용자가 선택한 때만 Pleos FusedLocation 1.1.3을 호출한다. 지원 여부와 런타임 권한을 확인하고 12초 이내에 마지막 위치 또는 새 위치 한 번을 받으면 구독과 SDK를 해제한다. 화면 이탈 때 취소하고 이전 요청의 늦은 응답을 버린다.
- 위치는 메모리에만 둔다. 유효 좌표, 120초 이내 원본 timestamp, 유한한 nonnegative confidence를 확인한다. SDK가 confidence의 단위를 명시하지 않아 이를 미터 정확도로 표시하거나 보행 안전의 근거로 사용하지 않는다.
- 지역명을 입력하면 그 지역명으로 검색하며 좌표를 URL에 넣지 않는다. Kakao에서 지역명이 없고 유효 차량 좌표가 있을 때만 좌표 중심 검색을 만든다. Naver 검색 문서에는 좌표 중심 파라미터가 없으므로 지역명이 필요하다.
- 지도 검색과 실제 방문·복귀는 구분한다. 지도 열기를 방문 완료나 도보 활동 완료로 기록하지 않는다. 차량 좌표는 차 밖 사용자의 위치가 아니며 휴대폰 복귀 알림을 제공하지 않는다.

## 공개 API 조사

| 제공사 | 확인한 기능 | 다음 통합에 필요한 것 |
|---|---|---|
| Kakao | 장소 키워드·카테고리 검색, 도보 경로 `GET /v2/routing/walk` | 개발자 앱/API 활성화와 REST 키, 키를 APK에 넣지 않는 서버, 실제 왕복 경로 및 실패 응답 검증 |
| Naver | 지역 검색 REST API, 지도 앱 검색·도보 길찾기 URL scheme | 지역 검색용 ID/secret과 서버. 공개 Directions 5 API는 자동차 경로이므로 보행 ETA로 사용하지 않음 |
| Pleos | 차량 FusedLocation, 차량 Navigation 연동 NaviHelper | 차량 위치 지원·권한·유효 데이터. NaviHelper를 도보 경로 제공 서비스로 해석하지 않음 |

차량에 네이버 지도 앱이 없을 수 있다. Naver QR는 `nmap://` 스킴이므로 휴대폰에도 네이버 지도 앱이 필요하다. Kakao QR는 HTTPS 링크여서 모바일 웹으로 연결할 수 있다. 외부 지도에서 장소를 고른 뒤 왕복 각각의 보행 시간, 체류 시간과 최소 5분 복귀 여유를 확인한다. 자체 자동 추천은 계정/API 연동과 휴대폰 복귀 흐름 검증 후 확장한다.

## 근거

- [Kakao 지도 웹 URL 가이드](https://apis.map.kakao.com/web/guide/): 검색·위치 핀 URL.
- [Kakao 모바일 URL scheme](https://apis.map.kakao.com/ios_v2/docs/getting-started/urlscheme/): `search?q=...&p=lat,lng`. HTTPS 주소의 공식 서버 응답도 확인했다(2026-11-10, 모바일 applink로 302).
- [Kakao REST API](https://developers.kakao.com/docs/en/kakaomap/rest-api): 장소 및 도보 경로. API 인증이나 실제 경로 응답은 아직 실행하지 않았다.
- [Naver 지도 URL scheme](https://guide.ncloud-docs.com/docs/maps-url-scheme): 앱 검색은 `query`, 호출자 `appname` 사용. 좌표 핀과 검색은 별도 계약이다.
- [Naver 지역 검색](https://developers.naver.com/docs/serviceapi/search/local/local.md), [Naver Directions 5](https://api.ncloud-docs.com/en/ai-naver-mapsdirections-driving).
- [Pleos FusedLocation 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [수명 관리](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release), [공식 샘플](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app).

## 검증 경계

`NearbyMapProbe`는 네트워크·위치 조회·외부 UI 없이 URL 인코딩, 지역 검색의 좌표 비포함, 잘못된·오래된 위치 거부, 안전 조건 false에서의 실행 거부를 검사한다. 실제 단말 권한 승인, 차량 위치 수신, 휴대폰 QR 연결 및 외부 지도 검색은 별도 수동 확인 대상이다.
