# 주변 장소 로컬 개발 서버

카페·식사·간식·산책 선택을 차량 좌표 기반의 실제 장소 카드와 보행 왕복시간으로 바꾸는 로컬 BFF다. 장소명 입력이나 가짜 장소 데이터는 사용하지 않는다. 현재 이 서버는 `127.0.0.1`에만 바인딩하며 외부 배포용 인증 서버가 아니다.

## 실행

```sh
python3 scripts/prepare-nearby-pois.py
./scripts/start-nearby-server.sh
adb -s VERIFIED_EMULATOR_SERIAL reverse tcp:3210 tcp:3210
```

Node.js 22 이상을 사용한다. PATH에 Node가 없으면 이 Mac의 Codex 번들 런타임을 사용할 수 있다. 다른 설치본은 `BINTEUM_NODE=/absolute/path/to/node`로 지정한다. 전역 환경을 변경하지 않는다. Android debug 앱은 `http://127.0.0.1:3210`에 접속하고, release에는 로컬 cleartext 서버를 배포하지 않는다.

- 키가 없고 `.local/maps/south-korea-pois.json`이 있으면 **Geofabrik의 실제 대한민국 OSM 추출 데이터 + Valhalla**를 사용한다. 서버 시작 시 인덱스의 출처·지역 범위·좌표·카테고리를 검사하고, 차량 반경 1.5km에서 최대 3곳을 찾는다. 장소 검색은 네트워크를 사용하지 않는다. 실제 경로는 Valhalla의 pedestrian 왕복 조회로 받는다. 다른 경로의 인덱스는 `LOCAL_POI_FILE`로 지정한다.
- 로컬 인덱스와 카카오 키가 모두 없으면 `NOT_CONFIGURED`로 설정 필요 상태를 반환한다. 자동으로 공개 검색 서버를 사용하지 않는다. 개발자가 `NEARBY_USE_OVERPASS=1`을 명시적으로 설정한 경우에만 **Private.coffee Overpass + Valhalla 공개 개발 서버**를 사용한다. 사용자 버튼 한 번에 최대 1회의 반경 1.5km 검색과 3회의 pedestrian 왕복 경로 조회만 수행한다. 현재 검증 때 Overpass API가 응답하지 않아, 이 Mac의 기본 검증 경로는 로컬 인덱스다. 자동으로 여러 공개 서버를 순회하지 않는다.
- `server/.env`에 `KAKAO_REST_API_KEY`를 설정하면 **카카오 공식 장소/도보 API**를 사용한다. `.env.example`을 참고하되 실제 파일은 Git에서 제외된다. 키는 APK·QR·응답·로그로 전달하지 않는다.
- 키를 바꾸면 서버를 재시작한다. 키 파일 권한은 소유자만 읽을 수 있도록 설정한다.

공개 Valhalla 연결은 소수 개발자의 로컬 검증을 위한 경로다. 로컬 OSM 인덱스는 데이터 생성일 기준 실제 장소이며, 실시간 영업 정보가 아니다. 대한민국 추출 범위 밖에서는 `OUTSIDE_COVERAGE`와 빈 목록을 반환한다. 공개 서비스는 SLA가 없고 한국 내 장소 정보가 적을 수 있다. 일반 이용자에게 배포하기 전 자체/계약 서버를 준비해야 하며, Valhalla 운영자가 요청하는 공개 앱 사용 통지도 별도로 필요하다. 앱이 자동으로 통지를 보내거나 공개 서비스 사용 승인을 받은 것으로 표시하지 않는다.

## 계약

`POST /v1/nearby`, JSON:

```json
{
  "lat": 37.5,
  "lon": 127.0,
  "category": "CAFE",
  "availableSeconds": 1800,
  "originTimestampMillis": 1800000000000
}
```

위 예제는 **스키마 설명용**이다. 실제 호출에는 사용자 요청으로 확인한 최신 차량 좌표와 원본 timestamp를 넣는다. `category`는 `CAFE`, `FOOD`, `CONVENIENCE`, `PARK`이며 남은 시간이 미확인이면 `availableSeconds`는 null이다. 120초보다 오래된 위치, 서버보다 10초를 초과해 앞선 timestamp, 잘못된 좌표/시간은 거부한다. 단말·서버 시계 차이를 위해 최대 10초의 미래 편차만 허용하며 원본 timestamp를 재작성하지 않는다.

응답:

- `provider`: `OPENSTREETMAP` 또는 `KAKAO`
- `dataSource`: `LOCAL_OSM_EXTRACT`/`KAKAO_API`/`OVERPASS_API` 종류. 로컬 데이터에는 `datasetTimestamp`, 공식 `sourceUrl`, `coverage: SOUTH_KOREA` 포함
- `coverageStatus`: `WITHIN_COVERAGE` 또는 `OUTSIDE_COVERAGE`. 대한민국 인덱스를 전 세계 데이터로 표시하지 않음
- `attribution`: 카드와 함께 표시할 제공자 출처. OSM 데이터에는 `© OpenStreetMap contributors · Valhalla` 표시
- `generatedAtMillis`: 실제 지도/경로 응답을 확보한 시각. 캐시 재사용 때 갱신하지 않음
- `originTimestampMillis`: 요청에 사용한 위치의 원본 timestamp
- `returnReserveSeconds`: 300
- `places`: 최대 3개. `id`, `name`, `address`, `url`, `latitude`, `longitude`, `outboundSeconds`, `returnSeconds`, `staySeconds`, `totalSeconds`, `fit`, `outboundUrl`, `returnUrl`, `routeErrorCode`

`fit`은 `FITS`, `TOO_LONG`, `UNKNOWN`, `ROUTE_UNAVAILABLE`다. 카페/공원 기본 체류는 10분, 식사는 20분, 편의점은 5분이며 응답에 명시한다. 앱은 응답 후 줄어든 충전 예산으로 다시 판정한다. 영업 중 여부, 실제 보행 안전, 복귀 알람 전달 여부는 추정하지 않는다.

거리순 후보 선정에만 직선 거리를 내부적으로 사용한다. **직선 거리로 보행 시간을 만들지 않는다.** OSM 경로는 `costing: pedestrian`와 `[차량, 장소, 차량]`의 세 break point로 요청하고, 실제 반환된 두 leg의 시간을 각각 사용한다. 카카오는 왕복 방향을 별도 요청한다. 경로 조회에 실패하면 실제 장소 카드는 보존하되 시간은 null/`ROUTE_UNAVAILABLE`로 표시한다. 빈 장소 응답은 빈 목록이며 장애를 빈 목록으로 숨기지 않는다.

오류는 `{ "error": { "code": "...", "message": "...", "retryable": true } }` 형태다. 코드: `NOT_CONFIGURED`, `INVALID_REQUEST`, `LOCATION_STALE`, `RATE_LIMITED`, `PROVIDER_AUTH`, `PROVIDER_UNAVAILABLE`, `PROVIDER_TIMEOUT`. `GET /health`는 설정된 제공자만 표시하고 인증값이나 위치는 포함하지 않는다. `configured`는 설정 상태이며 제공자 실시간 정상 동작을 보증하지 않는다.

## 요청 제한·보존

전체 서버에서 새 검색은 최소 15초 간격, 동시 검색 1개로 제한한다. 제공자 429/406 응답 뒤에는 최소 30초 동안 새 요청을 차단한다. 같은 좌표/카테고리의 결과는 60초 동안 최대 8개를 메모리에서만 재사용하고 서버 재시작 시 사라진다. 사용자 위치·검색 이력을 파일/DB/일반 로그에 기록하지 않는다. 결과 캐시에는 요청 처리에 필요한 좌표가 일시적으로 포함된다.

Valhalla 요청은 최소 1.2초 간격으로 직렬 실행하며 `X-Client-Id: binteum-pleos-local-dev`를 보낸다. 자동 재시도·다른 공개 서버로의 자동 우회·백그라운드 요청은 없다. 검색 하나의 HTTP 처리 제한은 30초다. upstream 호스트/경로는 코드가 고정하고, 호출자가 임의 URL이나 API 키를 전달할 수 없다. 브라우저 Origin 요청과 loopback이 아닌 Host 헤더 요청을 거부한다.

## 검증

```sh
node --test server/test/nearby.test.mjs
```

테스트는 fetch를 주입한 **테스트 전용 응답**을 사용한다. 앱이나 실행 서버에 fixture 모드를 넣지 않았다. 좌표 순서, 왕복 비대칭, 시간 미확인, 경로 실패, API 인증 실패, 보존 시각, 요청량 제한, 잘못된/사적/중복 장소, HTTP 경계를 검사한다. 실제 제공자 확인은 테스트 위치와 차량 위치를 구분해서 수행한다. 단위/HTTP 계약 검사 16개가 통과했다. 공개 강남역 인근의 명시적 테스트 좌표로 Valhalla pedestrian 왕복 2개 leg가 HTTP 200으로 반환되는 것을 확인했다. 이 결과는 차량 위치 수신 검증이 아니다.

## 공식 근거

- [Geofabrik 대한민국 공식 추출 데이터](https://download.geofabrik.de/asia/south-korea.html)
- [카카오 장소·보행 REST API](https://developers.kakao.com/docs/en/kakaomap/rest-api)
- [Private.coffee 운영·이용 조건·개인정보 처리](https://overpass.private.coffee/)
- [Overpass 공개 자원과 요청 제한](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)
- [Overpass 언어와 지도 객체](https://dev.overpass-api.de/overpass-doc/en/)
- [Valhalla 공개 개발 서버·식별 헤더](https://valhalla.github.io/valhalla/)
- [Valhalla 경로 API·pedestrian costing](https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/)
- [OpenStreetMap 저작권·출처 표시](https://www.openstreetmap.org/copyright)
