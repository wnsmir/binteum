# 현재 위치 기반 주변 추천 — 2026-11-10

주변 탭에서 카페·식사·간식·산책을 누르면 **차량 현재 위치 → 주변 장소 → 실제 도보 왕복 경로 → 남은 충전시간 비교** 순서로 자동 조회한다. 충전소 이름이나 지역명, 이동시간을 입력하지 않는다. 위치를 받지 못하면 다른 지역이나 임의 좌표를 사용하지 않고 위치 오류와 재검색을 표시한다.

2026-11-12부터 대시보드의 주변 먹거리·산책 추천도 선택 즉시 차량 위치 검색으로 이어진다. 검색은 사용자 선택 후에만 수행한다. 카카오 장소·보행 API는 사용자 카카오 로그인 토큰이 아닌 개발자 REST API 키를 사용하므로 일반 사용자에게 카카오 로그인은 요구하지 않는다. 현재 개발 환경은 키 없이 실제 OSM 인덱스와 Valhalla를 사용한다. 차량 GPS 조회와 휴대폰 지도 길찾기 인계가 구현 범위이며 자체 턴바이턴 내비게이션 엔진은 아니다. [Kakao 공식 인증 방식](https://developers.kakao.com/docs/en/kakaomap/rest-api)

## 사용자 흐름

1. 카페·식사·간식·산책 중 원하는 종류를 고른다.
2. 최초에는 차량 위치 권한을 요청한다. 허용되면 선택한 종류의 검색을 자동으로 이어간다.
3. 현재 좌표 주변 1.5km의 실제 장소 중 가까운 후보 최대 3개와 보행 경로를 조회한다.
4. 카드에서 장소명·주소·가는 시간·오는 시간·체류시간과 남은 충전시간 적합성을 확인한다.
5. 장소의 가는 길 또는 차량으로 돌아오는 길을 눌러 해당 경로를 QR로 휴대폰에 넘긴다.

가게 영업 여부나 실제 보행 여건, 사용자의 복귀를 확인한 것은 아니다. 휴대폰 복귀 알림과 사용자 위치 추적은 구현하지 않았다.

## 장소·보행 공급자

키가 없는 이 Mac의 개발 환경은 **Geofabrik의 실제 대한민국 OSM 추출 데이터 + Valhalla pedestrian 경로**를 사용한다. 원본 지도 기준일은 2026-11-08T21:21:00Z이며 로컬 인덱스를 준비한 뒤 서버를 시작한다. 장소 검색은 로컬에서 수행하고 경로만 네트워크로 조회한다. 만들어 둔 가게 목록이나 샘플 좌표를 실제 현재 위치 결과로 표시하지 않는다. 화면에는 OSM 저작권 링크·공급자·데이터 기준일을 표시하고 지원 범위 밖은 별도로 안내한다.

Overpass 공개 API는 이번 실제 조회에서 응답 시간 초과가 발생했다. 기본 실행에는 사용하지 않으며 개발자가 `NEARBY_USE_OVERPASS=1`로 명시한 경우에만 선택한다. 데이터나 키가 없는 새 환경은 준비 필요 상태를 반환한다.

카페는 `amenity=cafe`, 식사는 restaurant/fast_food, 간식은 convenience, 산책은 park를 조회한다. 직선거리는 가까운 후보를 고르는 데만 쓰고, 도보시간은 경로 서비스의 두 leg 값으로 계산한다. Valhalla는 차량→장소→차량 순서의 보행 경로에서 양방향 시간을 얻는다. 공원 체류는 기본 10분이며 공원 안에서 검증된 순환 산책 코스를 생성한다는 의미가 아니다. 추출 범위는 이름이 있는 node/way이며 relation-only 장소는 빠질 수 있다. 면 객체의 좌표는 대표 지점이므로 실제 출입구라고 보장하지 않는다.

`server/.env`에 `KAKAO_REST_API_KEY`가 설정되면 Kakao의 장소 검색과 `/v2/routing/walk`를 사용한다. Kakao는 가는 길과 돌아오는 길을 각각 요청한다. 이번 작업에서 Kakao 로그인은 진행하지 않았으며, 키를 APK나 Git에 넣지 않는다. 키가 있는데 인증이 실패하면 실패를 표시하며 임의 장소로 바꾸지 않는다.

공개 OSM/Valhalla 인스턴스는 소규모 개발 검증용이다. 요청 수를 제한하고, 과부하·제한 응답을 무시해 반복 요청하지 않는다. 운영 배포는 제공사 사용 조건에 맞는 자체/계약 서버와 운영 인증·한도 설정이 필요하다. 현재 운영용 HTTPS 서버는 배포하지 않았다.

## 시간 비교

총 시간은 `가는 도보시간 + 오는 도보시간 + 체류시간 + 복귀 여유 300초`다. 체류 기본값은 카페 10분, 식사 20분, 간식 5분, 공원 10분이다. 서버 결과를 받은 뒤에도 앱은 현재 남은 충전/출발 시간 예산으로 다시 비교한다.

- `FITS`: 양방향 경로를 받았고 현재 시간 예산 안에 들어온다.
- `TOO_LONG`: 경로는 확인했지만 현재 시간 예산보다 길다.
- `UNKNOWN`: 경로는 확인했지만 남은 시간 예산을 알 수 없다.
- `ROUTE_UNAVAILABLE`: 양방향 보행시간을 확인하지 못했다. 시간에 맞는 추천으로 표시하지 않는다.

원본 차량 위치와 결과는 120초 이내만 사용한다. 오래된 결과는 재검색을 요구한다. 앱의 실제 충전·주행 제한·데이터 신선도 조건은 조회와 지도 열기 직전에도 확인한다.

## 위치·개인정보 경계

`VehicleLocationReader`는 Pleos FusedLocation 1.1.3의 지원 여부와 `pleos.car.permission.FUSED_LOCATION` 런타임 권한을 확인한다. live 구독을 먼저 열고 cached 조회를 별도로 처리하여 캐시가 비었거나 실패해도 live 수신을 기다린다. 12초 내 유효 위치를 받으면 구독과 SDK를 해제한다. 취소·화면 이탈 후 늦은 응답은 버린다.

좌표 범위, 원본 timestamp, 유한한 nonnegative confidence를 검사한다. confidence를 미터 정확도로 해석하지 않는다. Mac·휴대폰의 위치를 차량 위치로 바꾸지 않으며 시연 충전 데이터도 임의 GPS를 만들지 않는다. 에뮬레이터에서는 에뮬레이터에 설정된 차량 위치라는 안내를 붙인다.

카테고리 선택 시 현재 좌표가 지도 서비스에 전달됨을 화면에 알린다. 로컬 인덱스 모드에서 장소 조회는 외부 요청이 없으며, 보행 경로 조회에는 출발·목적 좌표가 Valhalla로 전달된다. 앱 개인화에는 활동 종류·충전시간·SOC만 저장하며 좌표·장소명·지도 결과·실제 방문 이력을 넣지 않는다. BFF는 요청 본문·좌표·키를 일반 로그나 디스크에 기록하지 않고 최대 60초·8개 항목의 메모리 캐시만 사용한다.

## 개발 연결

`python3 scripts/prepare-nearby-pois.py`가 공식 지도와 격리된 Python 의존성을 `.local/maps/`에 내려받고 `south-korea-pois.json`을 생성한다. 데이터 갱신은 `--refresh`를 사용하며 갱신 뒤 서버를 재시작한다. 원본·인덱스·캐시·의존성은 Git에 올리지 않는다.

`./scripts/start-nearby-server.sh`는 Node.js 22 이상으로 `127.0.0.1:3210`의 로컬 서버를 실행한다. 에뮬레이터 연결은 `adb -s VERIFIED_SERIAL reverse tcp:3210 tcp:3210`을 사용한다. 서버를 외부 인터페이스에 노출하거나 공개 운영 서버로 배포하지 않았다.

Debug 앱만 `http://127.0.0.1:3210` 연결을 허용한다. Release는 기본 주소가 비어 있으며 별도 HTTPS 주소를 `-PbinteumNearbyApiBaseUrl=https://...`로 지정해야 한다. 구현된 경로는 `GET /health`, `POST /v1/nearby`이며 상세 계약은 [API 명세](api-spec.md)에 있다.

## 근거와 검증

- [Pleos FusedLocation 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [공식 샘플](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app): 차량 위치·지원 여부·권한 및 lifecycle.
- [Geofabrik 대한민국 추출 데이터](https://download.geofabrik.de/asia/south-korea.html), [OSM 저작권](https://www.openstreetmap.org/copyright): 실제 장소 데이터·기준일·귀속 표시.
- [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API): 위치·태그별 OSM 데이터 조회와 공개 인스턴스 이용 범위.
- [Valhalla API](https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/), [FOSSGIS demo](https://valhalla.openstreetmap.de/): pedestrian 경로 및 공급자.
- [Kakao REST API](https://developers.kakao.com/docs/en/kakaomap/rest-api), [지도 URL 가이드](https://apis.map.kakao.com/web/guide/): 선택적 장소/도보 API와 경로 공유 링크.

서버 fixture 테스트·응답 파서 검사·위치 신선도 검사는 실제 사용자 위치에서의 성공과 구분한다. `LocationCapabilityProbe`는 지원/권한/유효 위치 수신 여부만 보고하고 좌표 자체를 출력하지 않는다. 실제 차량 위치·장소 카드·QR 스캔의 최종 검증 결과는 [구현·검증 기록](implementation-status.md)을 따른다.
