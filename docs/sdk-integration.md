# 웨이팅 사용 SDK/API 및 검증 범위

기준: 2026-11-14, `codex/unified-waiting-flow`. 의존성을 추가했다는 사실과 실제 서비스 성공은 구분한다. 버전은 `app/build.gradle.kts` 기준이다.

## 직접 추가한 SDK·라이브러리 전체

| 항목 | 고정 버전 | 현재 사용 | 확인된 범위 / 남은 검증 |
| --- | --- | --- | --- |
| Pleos Vehicle | 2.3.2 | 배터리 SOC·에너지 capability·충전 목표 제어 경로, 기존 시트 메모리 지원 조회/적용 요청 | 에뮬레이터 SOC·capability 읽기 확인. 실제 충전 목표 쓰기·시트 이동 완료 미검증. 공조 코드가 남아 있으나 현재 UI에서 제외 |
| Pleos FusedLocation | 1.1.3 | 차량 위치 권한, 일회성 위치 조회와 원본 시각 검사 | 에뮬레이터 시험 GPS와 실제 차량 위치 검증을 구분. 최신 연결 결과는 구현 상태 문서에 기록 |
| Pleos TextToSpeech | 3.3.1 | 개발용 음성 미리 듣기, 엔진 초기화·언어·오디오 포커스·서버 등록 경로 | 코드 존재. 현재 기기의 실제 출력음 미확인. Gleo 연동 또는 시간 완료 음성 보장으로 소개하지 않음 |
| ZXing core | 3.5.3 | 음악·지도 링크를 휴대폰에 전달하는 QR 생성 | QR 생성 코드/기기 연결 경로 검사. 실제 휴대폰 스캔·외부 서비스 성공은 별도 |

`project(":core")`는 직접 작성한 시간 계산·추천·안전 판정 모듈이며 외부 SDK가 아니다.

## 플랫폼·웹 API 및 서버

| 항목 | 사용 방식 | 한계 |
| --- | --- | --- |
| Android Automotive / android.car | CarPropertyManager의 EV_CHARGE_STATE, EV_CHARGE_TIME_REMAINING, EV_CHARGE_PERCENT_LIMIT, 기어/속도 확인; CarUxRestrictionsManager | AAOS 실제 충전 상태와 충전 요청 스위치를 구분. 차량 ETA는 availability·목표 일치·신선도 확인 후 사용 |
| Android 프레임워크 | WebView, Intent/PackageManager, AudioManager, SQLite, View/Canvas/RenderNode 등 | 별도 외부 라이브러리 버전이 아닌 OS API |
| YouTube IFrame Player API | 앱 HTML/WebView 안에 공식 임베드 플레이어, 샘플 영상 카드 선택 | YouTube Data API·별도 API 키 미사용. 현재 버전 실제 영상/소리 검수는 별도 |
| Netflix / YouTube Music / Apple Music / Spotify | 공식 앱·웹 주소 외부 열기; 음악은 QR 대안 | 해당 서비스 SDK·OAuth·인앱 Netflix 재생 미구현. 로그인·구독·차량 지원 필요 |
| OpenStreetMap / Geofabrik 데이터 | 개발 서버의 대한민국 로컬 POI 인덱스 | 운영 배포 전 데이터 갱신·제공 범위 점검 필요 |
| Valhalla 경로 API | 가는 길/돌아오는 길 도보 경로 조회 | 경로 없는 장소는 적합 판정 제외. 직선거리를 도보시간으로 대체하지 않음 |
| Kakao Local REST | 서버 설정에 키가 있을 때만 선택 가능한 공급자 | 현재 사용 중인 OSM 공급자와 구분. 현재 Kakao 서비스 연결 완료로 소개하지 않음 |

## 시트 기능의 정확한 표현

- `checkSeatMemoryCapabilities`로 지원 번호 확인, `selectSeatMemory`로 이미 저장된 자세 적용 요청.
- `moveSeatBackrestAngle` 등 연속 이동 및 `STOP` API는 SDK에 존재하지만 현재 앱에서 직접 모터 이동 UI는 제공하지 않음.
- SDK 2.3.2 Seat에는 현재 자세를 새 메모리에 저장하는 API가 없음. 앱이 저장하는 것은 선택한 기존 메모리 번호뿐.
- P단·최신 속도 0·권한·지원 구역 확인 후 사용자 명령만 전송. 적용 완료 콜백/readback이 없어 요청 전송을 실제 이동 성공으로 표시하지 않음.

## 제출 문안에 포함하면 안 되는 완료 주장

- Gleo가 충전/타이머 종료를 자동으로 말해 준다는 주장.
- Netflix 인앱 재생, 음악 계정 연결·직접 스트리밍 구현 주장.
- 차량 시트 자세 새로 저장, 자동 수면 자세 조작, 실제 모터 이동 검증 주장.
- 운영 서버 배포, 실제 차량 위치·충전 ETA 전체 검증, 강화학습 개인화 주장.
- 현재 화면에서 제거한 공조·배경음·기상 준비 설정을 현재 기능으로 나열하는 것.

근거: [앱 의존성](../app/build.gradle.kts), [현재 검증 기록](implementation-status.md), [Seat API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry), [FusedLocation 공식 예제](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app).
