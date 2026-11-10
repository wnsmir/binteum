# 빈틈의실 — Pleos 공개 API 조사·연동 매트릭스

정적 목록 확인일: 2026-11-08. 앱 연결 갱신: 2026-11-10. 기준: 공모전 공식 홈페이지가 연결하는 Pleos 문서, 공식 샘플, Maven의 `Vehicle:2.3.2` 배포물. 이 문서는 **문서에 있는 기능, 배포물에 있는 메서드, 실행 환경의 capability, 우리 앱에서 검증한 동작을 구분**한다. 모든 API를 실차나 에뮬레이터에서 실행했다는 뜻이 아니다.

2026-11-10에는 `FusedLocation:1.1.3`의 요청별 차량 위치와 `ComfortController`의 명시적 공조 온도 적용/복원을 추가했다. 위치는 지역 검색·차량 핀 QR에만 사용하고 저장하지 않는다. 공조는 지원 구역·온도와 권한, AAOS P단·속도 0, 앱 안전 상태를 확인하고 별도 readback으로 검증한다. 시트 이동은 구현하지 않았다. 실제 위치 수신·공조 쓰기와 QR 연결은 수동 검증 대상으로 남는다. 지도 제공사 기능은 [지도 구현 근거](nearby-maps.md)를 참고한다.

## 확인 범위와 숫자 차이

[공모전 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)의 SDK 링크는 [Pleos SDK Overview](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)로 이어진다. 현재 안내하는 모듈은 Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation의 다섯 개다. Gleo는 별도의 앱 Intent/응답 연동 가이드다.

[공식 Vehicle 샘플](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)은 19개 영역·442개 공개 메서드라고 안내한다. 그러나 이번에 받은 `Vehicle:2.3.2`의 **AAR Java reflection과 sources.jar 독립 집계는 모두 440개**였다. 공식 샘플의 `PublicApiPolicy`와 동일하게 `@Deprecated` 및 명시적 비공개 9개 메서드를 제외한 결과다. 아래 전체 목록은 실제 확인한 **440개**이며, 442개에 맞추려고 존재하지 않는 메서드를 추가하지 않았다. 공식 자료의 집계 차이는 제공사 확인 항목이다.

- 공식 샘플 조사 커밋: `959981d960ec71144accb27af5ae5d341e72a644`.
- [분류 정책 원본](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/explorer/PublicApiPolicy.kt).
- [검증 AAR](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2.aar), SHA-256 `e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937`.
- [검증 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar), SHA-256 `62b5dd2c4c0523fd13c2e64040cd775bc89a14ce54f35a37560d0eb4eb3fab40`.
- [Vehicle API 인덱스](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/entry)의 19개 도메인 페이지도 전부 탐색했다. 메서드명 페이지는 458개이며, 폐기된 항목 잔존과 오버로드 묶음 때문에 배포물의 메서드 수와 직접 비교할 수 없다.

이 목록의 단위는 19개 Vehicle 도메인의 공개 메서드다. `Vehicle` 생성/초기화/해제, 데이터 클래스·enum·listener 타입, 다른 네 모듈과 Android 시스템 API는 별도로 정리한다. Android 전체 프레임워크의 모든 API를 조사했다는 뜻은 아니다.

## 실제 구현한 연결과 남은 검증

| 데이터/동작 | 확인한 실제 API | 권한·지원 조건 | 현재 구현 및 한계 |
| --- | --- | --- | --- |
| SDK 세션 | `Vehicle(context).initialize()`, `getEvBattery()`, `release()` | Pleos Vehicle Service가 있어야 함 | `PleosVehicleDataSource`에 구현. initialize 반환만으로 연결 성공이라고 판단하지 않고 capability 응답을 기다림 |
| SOC % | `checkEvBatteryStateOfChargeCapabilities`, `getEvBatteryStateOfCharge` | `pleos.car.permission.CAR_ENERGY`; READ 또는 READ_WRITE | 0..100 Int만 사용. null/오류/미지원은 미확인. 원본 측정시각 미제공 |
| 충전 시작/정지 요청 스위치 | `checkEvChargeSwitchCapabilities`, `getEvChargeSwitchState` | 동일한 Pleos 읽기 권한 | 별도 `chargeSwitchEnabled` 진단값. **실제 충전 중 판정으로 사용하지 않음** |
| 실제 충전 상태 | `CarPropertyManager.getProperty<Int>(VehiclePropertyIds.EV_CHARGE_STATE, 0)` | `android.car.permission.CAR_ENERGY` 런타임 허가 + 실제 VHAL 지원 | AAOS `STATE_CHARGING`만 true. NOT_CHARGING/FULLY_CHARGED/ERROR는 false. UNKNOWN·미지원·권한 거부·실패는 null |
| 차량 충전 예상 잔여 시간 | `EV_CHARGE_TIME_REMAINING`의 Int 초·원본 timestamp | `android.car.permission.CAR_ENERGY`, 읽기 capability 및 CONTINUOUS 샘플 | 2026-11-10 선택 읽기 연결. 실제 충전 중이고 원본 샘플이 30초 이내인 양수 값만 전달. 0·미지원·실패는 예상시간 미확인으로 처리 |
| 차량의 목표 충전량 | `EV_CHARGE_PERCENT_LIMIT`의 Float % | 같은 AAOS 읽기 권한 및 읽기 capability | 2026-11-10 읽기 연결. 현재 모델은 정확한 정수 1..100%만 수용하며 앱에서 고른 목표와 구분 |
| 명시적 차량 목표 적용 | Pleos `checkEvChargePercentLimitCapabilities`, `setEvChargePercentLimit` + AAOS 현재 값 재조회 | `pleos.car.permission.CONTROL_CAR_ENERGY` normal 권한, READ_WRITE capability, 지원 목표값, 신선한 안전 상태 | 사용자 적용 동작에서만 호출. 8초 안에 별도 readback이 일치해야 성공. timeout은 미적용 보장이 아니라 적용 여부 미확인. 충전 시작/중지·전류 제어는 호출하지 않음 |
| 공조 설정 온도 | `checkHvacTemperatureCapabilities`, `getHvacTemperature`, `setHvacTemperature` + 별도 재조회 | Pleos `CAR_CLIMATE`/`CONTROL_CAR_CLIMATE`, READ_WRITE·지원 구역/온도. AAOS `CAR_POWERTRAIN`/`CAR_SPEED`로 P·정지 확인 | 사용자 적용/복원 버튼에서만 호출. 원래 값과 마지막 앱 요청값을 보관하고 외부 변경이 있으면 복원으로 덮지 않음. 낮잠/앱 lifecycle의 자동 쓰기 없음. 실제 쓰기 미검증 |
| 시트 등받이 위치 | `checkSeatBackrestAnglePositionCapabilities`, `getCurrentSeatBackrestAnglePosition` | Pleos `CAR_SEATS`, 읽기 지원 영역 | 지원 진단의 원시 위치 읽기만 연결. 숫자를 각도·퍼센트로 해석하지 않음. 연속 MOVE와 정지 동작의 검증 없이 자동 젖힘/복원 명령을 보내지 않음 |
| 차량 위치 | FusedLocation `isSupported`, `initialize`, `getLastKnownLocation`, 구독/해제, `release` | Pleos `FUSED_LOCATION` 런타임 허가 | 사용자 요청별 최대 12초. 원본 timestamp 120초 이내·유효 좌표만 받고 메모리에 유지. 차량 위치는 휴대폰 위치가 아니며 영역 알림/백그라운드 추적 미사용 |
| 주행 UX 제한 | `CarUxRestrictionsManager.registerListener`, `getCurrentCarUxRestrictions`, `unregisterListener` | AAOS Car Service 연결 | DO 필요 또는 baseline 이외 제한이 하나라도 있으면 보수적으로 잠금. 미확인도 사용 허가로 처리하지 않음. 기어 P/브레이크로 추론하지 않음 |
| 활성 상태 | `PowerManager.isInteractive`, 화면 on/off, 소유 Activity의 start/stop | Android 디바이스 상태 | 기존 `vehicleAwake` 필드는 현재 디바이스에서 UI를 관측 가능한 상태인지 나타내는 대용값. **실제 IGN 신호가 아님** |
| 실패·재시작 | 세션/읽기 식별자, 제한 시간, release | callback이 오지 않을 수도 있음 | capability 20초, 읽기 3초 timeout; 정상 읽기 종료 후 3초 간격. timeout 뒤 무한 재요청하지 않고 재연결 필요. 종료 뒤 늦은 callback 무시 |

코드: [PleosVehicleDataSource](../app/src/main/java/com/binteum/app/vehicle/PleosVehicleDataSource.kt), [AndroidCarUxReader](../app/src/main/java/com/binteum/app/vehicle/AndroidCarUxReader.kt), [ComfortController](../app/src/main/java/com/binteum/app/vehicle/ComfortController.kt), [VehicleLocationReader](../app/src/main/java/com/binteum/app/nearby/VehicleLocationReader.kt). 최신 빌드·기기 결과는 [구현·검증 기록](implementation-status.md)을 따른다. 정적 검사 통과와 에뮬레이터 capability/허가/실제 반환값, 실차 동작, 앱 마켓 승인은 별개다.

### 충전 스위치와 실제 상태를 나눈 이유

Pleos의 [`getEvChargeSwitchState`](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-ev-charge-switch-state) 설명은 charging state라는 표현을 쓴다. 그러나 배포 소스의 `EvBatteryImpl`은 `EV_CHARGE_SWITCH` 속성을 읽으며, [`setEvChargeSwitch`](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/set-ev-charge-switch)도 같은 속성에 쓴다. [Android 공식 속성 정의](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_CHARGE_SWITCH)는 이 스위치를 사용자가 요청한 시작/정지 설정으로 구분하고, 실제 충전 상태는 [`EV_CHARGE_STATE`](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_CHARGE_STATE)로 정의한다. 따라서 스위치 true만으로 실제 충전 중이라고 단정하지 않는다.

[`EvChargeState`](https://developer.android.com/reference/android/car/hardware/property/EvChargeState)의 FULLY_CHARGED는 차량에 설정된 충전 한도에 도달한 상태일 수도 있다. 앱에서 정한 목표 SOC와 자동으로 같은 값이라고 보지 않는다. 충전 중단과 목표 도달 역시 별도로 판단한다.

### 시간·수명주기 계약

- Pleos SOC 콜백에는 원본 측정시각이 없다. SOC의 관측 기준은 **이번 읽기를 시작한 시각**이며, 이전 값을 재표시할 때 현재 시각으로 갱신하지 않는다. 차량 서비스 내부 캐시의 신선도까지 보장하지 못한다.
- AAOS 충전 값은 현재 연결에서 새 `getProperty` 요청이 성공하고 `STATUS_AVAILABLE`인지 확인한다. 스냅샷 관측시각은 해당 읽기 요청을 시작한 시각이다. `CarPropertyValue.timestamp`는 별도의 `chargeStateChangedAtMillis`로 보존한다. 부팅 기준 ns를 epoch ms로 변환하며 사용할 수 없는 timestamp는 별도 값만 null로 둔다.
- ONCHANGE 속성의 timestamp는 마지막 상태 변경 시각이므로 정상적으로 충전 상태가 유지되어도 오래될 수 있다. 새 서비스 읽기의 성공을 관측시각으로 사용하되, 원본 상태 변경 시각을 현재 시각으로 덮어쓰지 않는다. 30초 stale 정책은 성공한 읽기 이후 응답이 끊긴 경우에 적용한다. 차량 서비스 내부 값의 신선도까지 보장하는 것은 아니다.
- ETA는 CONTINUOUS 속성이므로 ONCHANGE 충전 상태와 다른 계약을 적용한다. 원본 샘플 timestamp가 없거나 미래이거나 30초보다 오래되면 사용하지 않는다. `chargeTimeObservedAtMillis`를 그대로 전달하며 같은 값을 재게시해 카운트다운을 다시 시작하지 않는다. ETA·차량 목표는 별도 worker와 3초 제한시간으로 읽고, 이 선택 값의 실패는 SOC·실제 충전 상태·UX 읽기를 중단시키지 않는다.
- ETA 읽기 전후의 목표값·원본 변경 timestamp가 일치하는지 검사하며 ETA 샘플이 목표 변경보다 앞서면 버린다. 앱의 목표 쓰기 readback 성공 시 이전 ETA와 진행 중 선택 읽기를 무효화한다. 목표가 변경된 뒤 새로운 차량 ETA가 올 때까지 이전 목표의 시간을 새 목표 시간으로 표시하지 않는다.
- 화면 off/on을 걸친 응답을 재사용하지 않는다. 화면이 닫히면 연결을 해제한다. 차량 밖 휴대폰으로 충전을 계속 감시하거나 IGN OFF 뒤 알림을 보장하는 서비스는 구현하지 않았다.
- [Pleos Suspend 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)에 따르면 IGN OFF 후 UI·일반 오디오 등이 중단될 수 있다. `isInteractive`를 IGN 상태로 이름만 바꾸어 사용할 수 없다.

### 선언 범위

현재 읽기 연결에 필요한 것은 `implementation("ai.pleos.playground:Vehicle:2.3.2")`, `useLibrary("android.car")`, 선택적 `android.car` uses-library, Pleos/Android 각각의 `CAR_ENERGY`다. 두 권한의 접두사와 허가 방식이 다르다. Pleos 읽기 권한은 SDK의 normal 권한이며 AAOS energy는 런타임 dangerous 권한이다.

2026-11-10 명시적 목표 적용에는 `pleos.car.permission.CONTROL_CAR_ENERGY`를 추가했다. 공식 2.3.2 sources.jar의 `EvBattery.setEvChargePercentLimit` 설명과 이 에뮬레이터 권한 정의 모두 이 Pleos 권한을 **normal**로 표시한다. AAOS의 별개 `android.car.permission.CONTROL_CAR_ENERGY`는 signature/privileged이며 선언·요청하지 않는다. Pleos setter 성공 콜백은 요청값을 돌려주므로 실제 적용 확인은 AAOS `EV_CHARGE_PERCENT_LIMIT`의 새 읽기로 한다. SDK 2.3.2에는 별도 `getEvChargePercentLimit`가 없다. 서버 연결 끊김·늦은 응답·미지원·안전 상태 유실 시 성공으로 표시하거나 자동 재시도하지 않는다.

공식 문서상 Vehicle 권한 사용은 [앱 마켓과 협의·승인된 범위](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)를 따라야 한다. manifest 선언과 에뮬레이터 허가는 배포 승인과 다르다. 이번 작업은 공개 SDK를 이용한 개발·검증이며 마켓 승인 신청을 대신하지 않는다.

## Vehicle 19개 영역 요약

아래 `C/R/W/S/U`는 capability 확인 / 읽기 / 제어 / 구독 / 구독 해제를 뜻한다. 실제 지원은 연결된 차량의 capability 및 area/bounds에 따른다. 모든 영역의 배포물 메서드를 정적으로 확인했지만, 빈틈의실은 EV 읽기·목표량 설정, 공조 온도 읽기/설정과 시트 등받이 원시 위치 읽기를 연결한다. 그 밖의 영역은 현재 호출하지 않는다.

| 영역 | 메서드 수 (C/R/W/S/U) | 확인한 기능 | 필요한 권한 종류 | 앱 활용 |
| --- | --- | --- | --- | --- |
| [Brake](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/entry) | 4 (1/1/0/1/1) | 브레이크 페달 상태 | `CAR_DRIVE` | 안전 판정의 보조 정보일 뿐 UX 허가 근거 아님 |
| [CarInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/entry) | 2 (1/1/0/0/0) | VIN | `CAR_INFO` | 이번 앱에 불필요한 식별정보로 수집하지 않음 |
| [Display](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/entry) | 4 (1/1/0/1/1) | 온도 표시 단위 | `CAR_INFO` | 차량 온도 콘텐츠를 확장할 때 검토 |
| [Door](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/entry) | 15 (3/3/3/3/3) | 도어 위치/잠금·글로브박스 잠금 | `CAR_DOORS` / `CONTROL_CAR_DOORS` | 문 상태 기반 외출 추론·제어를 하지 않음 |
| [DrivingMode](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/entry) | 12 (3/3/0/3/3) | 주행 모드·크루즈 상태/목표 속도 | `CAR_DRIVE` | 주행 제한은 이 값이 아니라 AAOS UX를 사용 |
| [EvBattery](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/entry) | 30 (9/7/4/5/5) | SOC·충전 요청 스위치/한도·전비 | `CAR_ENERGY` / `CONTROL_CAR_ENERGY` | SOC+요청 스위치 읽기 및 명시적 목표량 설정. 실제 상태·ETA·목표 readback은 AAOS |
| [Fuel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/entry) | 20 (5/5/0/5/5) | 연료 비율·현재/구간/누적 연비 | `CAR_ENERGY` | EV 중심 MVP에서 제외 |
| [HVAC](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/entry) | 72 (14/15/13/15/15) | 에어컨·자동/순환·제상·팬·전원·온도·공기청정 | `CAR_CLIMATE` / `CONTROL_CAR_CLIMATE` | 명시적 온도 적용/복원과 readback 연결. 전원·팬·제상 등 다른 설정은 변경하지 않음 |
| [Light](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/entry) | 71 (19/19/11/11/11) | 실내/독서/전조/안개/후미·무드등·주행모드 연동 | `CAR_LIGHTS` / `CONTROL_CAR_LIGHTS` | 분위기 자동 제어 제외 |
| [Odometer](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/entry) | 30 (9/7/0/7/7) | 누적 거리·구간 시간/거리 | `CAR_DRIVE` | 오래 운전한 뒤 휴식 제안 후보, 현재 미사용 |
| [Safety](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/entry) | 33 (9/8/0/8/8) | 유체·에어백·벨트·경고 상태 | `CAR_SAFETY` | OEM 경고 대체 금지, 현재 미사용 |
| [Seat](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry) | 86 (27/15/14/15/15) | 착좌/벨트·시트/헤드레스트 위치·이동·메모리·열선/통풍 | `CAR_SEATS` / `CONTROL_CAR_SEATS` | 등받이 원시 위치의 읽기만 연결. 시트 이동·자동 복원 없음 |
| [SideMirror](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/entry) | 5 (1/1/1/1/1) | 사이드미러 열선 | `CAR_CLIMATE` / `CAR_MIRRORS` / `CONTROL_CAR_MIRRORS` | 앱 목적상 미사용 |
| [SteeringWheel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry) | 9 (2/2/1/2/2) | 스티어링 각도·열선 | `CAR_CLIMATE` / `CAR_DRIVE` / `CONTROL_CAR_CLIMATE` | 각도로 정차/운전 여부를 추론하지 않음 |
| [Tire](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/tire/entry) | 2 (1/1/0/0/0) | 저압 상태 | `CAR_SAFETY` | 안전 경고 대체하지 않음 |
| [Toll](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/entry) | 5 (1/1/1/1/1) | ETCS 음성 안내 볼륨 | `CAR_TOLL` / `CONTROL_CAR_TOLL` | 앱 목적상 미사용 |
| [TurnSignal](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/entry) | 4 (1/1/0/1/1) | 방향지시 상태 | `CAR_LIGHTS` | 앱 목적상 미사용 |
| [Window](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/entry) | 29 (7/6/6/5/5) | 창문 잠금/위치·블라인드·스마트글라스 | `CAR_WINDOWS` / `CONTROL_CAR_WINDOWS` | 프라이버시/휴식 자동 제어 제외 |
| [Wiper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/entry) | 7 (2/2/1/1/1) | 와이퍼 상태/스위치 | `CAR_WINDOWS` / `CONTROL_CAR_WINDOWS` | 앱 목적상 미사용 |

## 지도·위치·음성·Gleo 공개 기능

| 모듈 | 공식 기준 버전·공개 기능 | 권한/제약 | 빈틈의실 적용·검증 상태 |
| --- | --- | --- | --- |
| [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro) | 3.0.5. 차량 목적지·경유지·경로 옵션·재탐색, 경로/위치/안내/충전사업자 정보, 차량 내 Navigation 화면 | `NAVI_ROUTE`, 경로 변경 `NAVI_ROUTE_SEARCH`, 화면 `NAVI_CUSTOM_MAP`. **차량 Navigation 연동** | 문서+배포 소스 확인. 앱 미연결. 보행 경로/주변 POI 서비스나 휴대폰 내비로 대체하지 않음 |
| [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro) | 1.1.3. 차량 마지막 위치, 위치 구독, 영역 진입/이탈, 지원 여부 | `pleos.car.permission.FUSED_LOCATION`; `isSupported` 선확인; 위치 null·timestamp·신뢰도 검사 | 사용자 요청별 one-shot 위치 연결, 12초 제한·수명 해제·원본 freshness 검사. 실제 수신 미검증. **차량 위치는 차 밖 사람의 휴대폰 위치가 아님** |
| [TextToSpeech](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro) | 3.3.1. 합성/중지, locale·speaker·audio attributes·zone, 이벤트 | `TTS_SERVICE`; HYBRID/SERVER는 My Project 자격 증명과 앱 등록, ON_DEVICE는 서버 등록 불필요 | 실험용 미리 듣기 연결. 엔진/프로젝트 권한 문제로 정상 합성 미검증. 낮잠 기상음은 별도 Android ToneGenerator 사용 |
| [SpeechToText](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro) | 3.3.1. 인식 시작/중지, 결과, audio 전송/종료, locale | `STT_SERVICE`; 모드별 등록·네트워크 조건은 TTS와 유사 | 문서+AAR 시그니처 확인. 앱 미연결. 받아쓴 텍스트를 반환하는 기능이며 명령 실행 자체가 아님 |
| [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts) | App Schema로 앱 함수를 등록하고 Intent를 받아 callback broadcast로 응답 | [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)는 최대 3개 action, 영어 description. [응답](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)은 success/asking/fail, 제한 시간 7초 | 계획 단계. `SHOW_CHARGING_STATUS`, `RECOMMEND_ACTIVITY`, `END_BREAK` 같은 앱 기능 후보. 실제 등록·음성 end-to-end 검증 미완료 |

`NAVI_*`, `TTS_SERVICE`, `STT_SERVICE`도 전체 이름은 `pleos.car.permission.`으로 시작한다. 자세한 매핑은 [Navi 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [TTS 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions), [STT 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions)에 있다.

각 모듈의 공개 entry point 메서드명 전체는 다음과 같다. 아래 이름 목록은 오버로드를 하나로 묶으며, 생성자·상수·자동 생성 함수는 포함하지 않는다. 인자/콜백 상세는 연결한 reference를 따른다.

- [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry): `initialize`, `release`, `addListener`, `removeListener`, `requestRoute`, `requestRouteWithWaypoints`, `cancelRoute`, `addWaypoint`, `removeWaypoint`, `changeRouteOption`, `requestReRoute`, `getBookmarkInfo`, `getRecentDestinationInfo`, `getRouteStateInfo`, `getCurrentLocationInfo`, `getDestinationInfo`, `getWaypointInfo`, `getTBTInfo`, `getChargerOperatorInfo`, `requestNavigationScreen`, `requestTrip`.
- [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/entry): `initialize`, `release`, `isSupported`, `getLastKnownLocation`, `registerFusedLocationCallback`, `unregisterFusedLocationCallback`, `showLocationAccessBlockedPopup`, `addProximityAlert`, `removeProximityAlert`.
- [TextToSpeech](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/entry): `initialize`, `release`, `addEventListener`, `removeEventListener`, `registerApp`, `setLocale`, `setSpeakerType`, `setAudioAttributes`, `setAudioZoneId`, `speak`, `stop`.
- [SpeechToText](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/speech-to-text/ai.pleos.playground.stt/speech-to-text/entry): `initialize`, `release`, `addListener`, `removeListener`, `registerApp`, `setLocale`, `request`, `stop`, `sendAudio`, `completeAudioSend`.

TTS/STT의 sources classifier는 이번 공개 저장소 요청에서 제공되지 않았지만 AAR/POM은 다운로드·검사했다. AAR에만 나타나는 `getAmbientAi`, `getCallApi`, Navi `getInternalApi` 등은 현 공개 API reference의 지원 계약이 확인되지 않아 사용 목록에 넣지 않았다. 예전 소개 페이지의 ADAS/LLM/Sync 표기도 현재 공모전이 연결한 5개 모듈의 구현 근거로 확대하지 않는다.

## Android/Pleos 시스템 연동과 미확정 기능

| 항목 | 공식 근거·지원 범위 | 앱 판단 |
| --- | --- | --- |
| 주행 제한 | [CarUxRestrictions 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide); 시스템이 지역별 active flags 제공 | listener+현재 값 조회 연결. `NO_VIDEO` 등의 조건 반영. P단/충전만으로 영상 허용을 추론하지 않음 |
| 충전 ETA | [AAOS EV_CHARGE_TIME_REMAINING](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_CHARGE_TIME_REMAINING), Int 초·CONTINUOUS, 미충전 시 0 | 선택 읽기 연결. 2026-11-10 에뮬레이터 VHAL에 읽기·1–10Hz 지원과 0초 이벤트 확인. 이는 충전 중 ETA·정확도 검증이 아님. 앱 임의 목표에 차량 ETA를 비례 환산하지 않음 |
| 충전 포트 | [AAOS EV_CHARGE_PORT_CONNECTED](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_CHARGE_PORT_CONNECTED), 별도 `CAR_ENERGY_PORTS` 읽기 권한 | 선택 후속 진단 후보. 포트 연결 true 역시 실제 충전 중과 다름. 현재 미연결 |
| 오디오·Suspend | [오디오 Suspend 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus) | 중단 시 오디오/일시적 연결 정리, 재개 시 현재 안전 상태 재평가. 운전자 부재 동안 항상 켜짐 보장 없음 |
| 하드키·회전 입력 | [CCP 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development) | 사용자 설치 앱의 CCP 지원은 선택 가능. 터치로 필수 기능 완결, 미지원 메타데이터/최상위 Back hide 처리 확인 필요 |
| 차량 미디어 통합 | [Music/Audio 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide) | 오디오를 주기능으로 배포한다면 MediaBrowserService/MediaSession 등 카테고리 요구 별도 검증. 부가 짧은 영상 기능만으로 차량 미디어 통합 완료라 하지 않음 |
| 지도 화면 | [Maps/Navigation 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide) | 지도/내비 앱 분류 정책과 NaviHelper 기능은 별개. 외부 장소·보행 제공사 API를 사용할 권한도 별개 |
| 휴대폰 QR 전달 | 현재 5개 SDK의 공개 기능에 휴대폰 동기화·푸시 전달 보장 없음 | 외부 지도 검색/차량 핀 QR를 로컬 생성. 실제 스캔 검수는 남아 있으며 휴대폰 동기화·복귀 알람은 미구현 |
| IGN OFF 뒤 충전 알림 | 공개 문서에서 일반 앱의 지속 실행·원격 SOC 수신 보장 미확인 | 차량 foreground 관측과 차 밖 원격 감시를 분리. 휴대폰/서버 중계·차량 데이터 전송 근거를 별도로 검증해야 함 |
| 주변 장소·도보 왕복 | Pleos FusedLocation은 차량 좌표, NaviHelper는 차량 내 내비. 도보 왕복/영업시간은 현재 모듈에서 확인 못함 | 지도 링크와 사용자가 확인·입력한 왕복 각 시간+체류+5분 여유 비교를 구현. 장소·도보 API 자동 호출은 미구현 |

## 문서와 배포물의 차이

`Gear`는 AAR에 클래스가 있어도 공식 공개 도메인/샘플 정책에 포함되지 않는다. `CarInfo.getCapabilityInfo`, Safety telltales 4개, Tire pressure 4개는 공식 샘플에서 명시적으로 제외한다. `@Deprecated` 오버로드와 숨김 기능을 현재 제품 의존성으로 사용하지 않는다.

문서 인덱스에 있으나 이번 공개 배포물 목록에서 제외된 메서드명은 다음과 같다. 소스의 Deprecated 또는 공식 샘플 제외 정책에 해당한다.

| 영역 | 문서에 잔존하는 제외 메서드 |
| --- | --- |
| CarInfo | [check-c-r-n-info-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/check-c-r-n-info-capabilities), [get-c-r-n-info](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/get-c-r-n-info) |
| Odometer | [check-trip-instantaneous-driving-distance-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-instantaneous-driving-distance-capabilities), [get-trip-instantaneous-driving-distance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-instantaneous-driving-distance), [register-trip-instantaneous-driving-distance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-instantaneous-driving-distance), [set-range-remaining-distance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/set-range-remaining-distance), [unregister-trip-instantaneous-driving-distance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-instantaneous-driving-distance) |
| Safety | [check-telltales-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-telltales-capabilities), [get-telltales-info](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/get-telltales-info), [register-telltales](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-telltales), [unregister-telltales](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-telltales) |
| Seat | [check-seat-backrest-angle-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-backrest-angle-capabilities), [check-seat-belt-height-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-belt-height-capabilities), [check-seat-depth-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-depth-capabilities), [check-seat-fore-aft-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-fore-aft-capabilities), [check-seat-headrest-angle-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-headrest-angle-capabilities), [check-seat-headrest-fore-aft-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-headrest-fore-aft-capabilities), [check-seat-headrest-height-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-headrest-height-capabilities), [check-seat-height-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-height-capabilities), [check-seat-lumbar-fore-aft-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-lumbar-fore-aft-capabilities), [check-seat-lumbar-side-support-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-lumbar-side-support-capabilities), [check-seat-tilt-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-tilt-capabilities) |
| Tire | [check-wheel-area-capabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/tire/check-wheel-area-capabilities) |

차량 기종·area별 capability는 런타임의 `AccessType.NONE/READ/WRITE/READ_WRITE` 및 실제 반환 bounds로 확인한다. 예를 들어 창문/시트 숫자를 무조건 0..100 퍼센트로 해석하지 않는다. 현재 차량 쓰기는 명시적 충전 목표와 공조 온도 적용/복원에 한정한다.

## Vehicle 전체 메서드 목록 — 배포물 확인 440개

모든 행의 `정적 확인`은 SDK 소스와 AAR 메타데이터에서 존재·공개 분류를 확인했다는 뜻이다. 모든 행의 `차량 실행` 기본 상태는 **미검증**이다. 아래 목록의 앱 연결 표시는 2026-11-08 조사 기반에 현재 연결 메서드를 갱신한 것이며, 실제 실행 검증과 별개다. `C`가 성공해도 모든 읽기/제어 성공을 보장하지 않으며 area와 accessType을 확인해야 한다. 권한은 `pleos.car.permission.` 접두사를 생략했다. 오버로드는 서명 차이를 괄호에 적었다.


### Brake — 4개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkBrakePedalStatusCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/check-brake-pedal-status-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getCurrentBrakePedalStatus](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/get-current-brake-pedal-status) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerBrakePedalStatus](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/register-brake-pedal-status) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterBrakePedalStatus](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/unregister-brake-pedal-status) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |

### CarInfo — 2개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkVINInfoCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/check-v-i-n-info-capabilities) | C | `CAR_INFO` | 확인 / 미연결 / 미검증 |
| [getVINInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/get-v-i-n-info) | R | `CAR_INFO` | 확인 / 미연결 / 미검증 |

### Display — 4개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkTemperatureDisplayUnitCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/check-temperature-display-unit-capabilities) | C | `CAR_INFO` | 확인 / 미연결 / 미검증 |
| [getCurrentTemperatureDisplayUnit](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/get-current-temperature-display-unit) | R | `CAR_INFO` | 확인 / 미연결 / 미검증 |
| [registerTemperatureDisplayUnit](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/register-temperature-display-unit) | S | `CAR_INFO` | 확인 / 미연결 / 미검증 |
| [unregisterTemperatureDisplayUnit](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/unregister-temperature-display-unit) | U | `CAR_INFO` | 확인 / 미연결 / 미검증 |

### Door — 15개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkDoorLockCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/check-door-lock-capabilities) | C | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [checkDoorPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/check-door-position-capabilities) | C | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [checkGloveBoxLockCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/check-glove-box-lock-capabilities) | C | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [getCurrentDoorPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/get-current-door-position) | R | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [getCurrentGloveBoxLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/get-current-glove-box-lock-state) | R | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [getDoorLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/get-door-lock-state) | R | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [registerDoorLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/register-door-lock-state) | S | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [registerDoorPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/register-door-position) | S | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [registerGloveBoxLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/register-glove-box-lock-state) | S | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [setDoorLock](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/set-door-lock) | W | `CONTROL_CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [setDoorPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/set-door-position) | W | `CONTROL_CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [setGloveBoxLock](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/set-glove-box-lock) | W | `CONTROL_CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [unregisterDoorLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/unregister-door-lock-state) | U | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [unregisterDoorPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/unregister-door-position) | U | `CAR_DOORS` | 확인 / 미연결 / 미검증 |
| [unregisterGloveBoxLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/unregister-glove-box-lock-state) | U | `CAR_DOORS` | 확인 / 미연결 / 미검증 |

### DrivingMode — 12개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkCruiseControlStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/check-cruise-control-state-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkCruiseControlTargetSpeedCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/check-cruise-control-target-speed-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkDrivingModeStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/check-driving-mode-state-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getCruiseControlState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/get-cruise-control-state) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getCruiseControlTargetSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/get-cruise-control-target-speed) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getCurrentDrivingModeState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/get-current-driving-mode-state) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerCruiseControlState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/register-cruise-control-state) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerCruiseControlTargetSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/register-cruise-control-target-speed) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerDrivingModeState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/register-driving-mode-state) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterCruiseControlState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/unregister-cruise-control-state) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterCruiseControlTargetSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/unregister-cruise-control-target-speed) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterDrivingModeState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/unregister-driving-mode-state) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |

### EvBattery — 30개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkCumulativeEvEfficiencyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-cumulative-ev-efficiency-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkCurrentEvEfficiencyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-current-ev-efficiency-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkEvBatteryStateOfChargeCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-ev-battery-state-of-charge-capabilities) | C | `CAR_ENERGY` | 확인 / 연결 / 미검증 |
| [checkEvChargeCurrentDrawLimitCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-ev-charge-current-draw-limit-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkEvChargePercentLimitCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-ev-charge-percent-limit-capabilities) | C | `CAR_ENERGY` | 확인 / 연결 / 지원값 수신 확인 |
| [checkEvChargePortOpenCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-ev-charge-port-open-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkEvChargeSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-ev-charge-switch-capabilities) | C | `CAR_ENERGY` | 확인 / 연결 / 미검증 |
| [checkInstantaneousEvEfficiencyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-instantaneous-ev-efficiency-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkSinceChargeEvEfficiencyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/check-since-charge-ev-efficiency-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getCumulativeEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-cumulative-ev-efficiency) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getCurrentEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-current-ev-efficiency) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getEvBatteryStateOfCharge](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-ev-battery-state-of-charge) | R | `CAR_ENERGY` | 확인 / 연결 / 미검증 |
| [getEvChargeCurrentDrawLimit](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-ev-charge-current-draw-limit) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getEvChargeSwitchState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-ev-charge-switch-state) | R | `CAR_ENERGY` | 확인 / 연결 / 미검증 |
| [getInstantaneousEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-instantaneous-ev-efficiency) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getSinceChargeEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-since-charge-ev-efficiency) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerCumulativeEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/register-cumulative-ev-efficiency) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerCurrentEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/register-current-ev-efficiency) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerEvBatteryStateOfCharge](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/register-ev-battery-state-of-charge) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerInstantaneousEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/register-instantaneous-ev-efficiency) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerSinceChargeEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/register-since-charge-ev-efficiency) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [setEvChargeCurrentDrawLimit](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/set-ev-charge-current-draw-limit) | W | `CONTROL_CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [setEvChargePercentLimit](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/set-ev-charge-percent-limit) | W | `CONTROL_CAR_ENERGY` | 확인 / 명시적 적용+readback 연결 / 실제 쓰기 미검증 |
| [setEvChargePortOpen](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/set-ev-charge-port-open) | W | `CONTROL_CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [setEvChargeSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/set-ev-charge-switch) | W | `CONTROL_CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterCumulativeEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/unregister-cumulative-ev-efficiency) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterCurrentEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/unregister-current-ev-efficiency) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterEvBatteryStateOfCharge](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/unregister-ev-battery-state-of-charge) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterInstantaneousEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/unregister-instantaneous-ev-efficiency) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterSinceChargeEvEfficiency](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/unregister-since-charge-ev-efficiency) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |

### Fuel — 20개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkCumulativeFuelEconomyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/check-cumulative-fuel-economy-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkCurrentFuelEconomyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/check-current-fuel-economy-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkFuelPercentCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/check-fuel-percent-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkInstantaneousFuelEconomyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/check-instantaneous-fuel-economy-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [checkSinceChargeFuelEconomyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/check-since-charge-fuel-economy-capabilities) | C | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getCumulativeFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/get-cumulative-fuel-economy) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getCurrentFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/get-current-fuel-economy) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getFuelPercent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/get-fuel-percent) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getInstantaneousFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/get-instantaneous-fuel-economy) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [getSinceChargeFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/get-since-charge-fuel-economy) | R | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerCumulativeFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/register-cumulative-fuel-economy) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerCurrentFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/register-current-fuel-economy) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerFuelPercent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/register-fuel-percent) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerInstantaneousFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/register-instantaneous-fuel-economy) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [registerSinceChargeFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/register-since-charge-fuel-economy) | S | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterCumulativeFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/unregister-cumulative-fuel-economy) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterCurrentFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/unregister-current-fuel-economy) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterFuelPercent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/unregister-fuel-percent) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterInstantaneousFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/unregister-instantaneous-fuel-economy) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |
| [unregisterSinceChargeFuelEconomy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/unregister-since-charge-fuel-economy) | U | `CAR_ENERGY` | 확인 / 미연결 / 미검증 |

### HVAC — 72개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkHvacAcCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-ac-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacAirCleanerCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-air-cleaner-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacAutoCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-auto-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacAutoRecirculationCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-auto-recirculation-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacDefrosterCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-defroster-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacDualCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-dual-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacFanDirectionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-fan-direction-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacFanSpeedCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-fan-speed-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacMaxAcCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-max-ac-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacMaxDefrostCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-max-defrost-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacPowerCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-power-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacRecirculationCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-recirculation-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkHvacTemperatureCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-temperature-capabilities) | C | `CAR_CLIMATE` | 확인 / 연결 / 에뮬레이터 READ_WRITE·앞좌석 2구역·15–30°C(0.5 간격) 확인 |
| [checkHvacTemperatureCurrentCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-temperature-current-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacAcState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-ac-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacAirCleanerState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-air-cleaner-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacAutoRecirculationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-auto-recirculation-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacAutoState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-auto-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacDefrosterState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-defroster-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacDualState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-dual-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacFanDirection](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-fan-direction) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacFanSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-fan-speed) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacMaxAcState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-max-ac-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacMaxDefrostState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-max-defrost-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacPowerState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-power-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacRecirculationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-recirculation-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature) | R | `CAR_CLIMATE` | 확인 / 연결 / 에뮬레이터 읽기 예외·미확인 |
| [getHvacTemperatureCurrent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current) — `( area: SeatArea, onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit )` | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [getHvacTemperatureCurrent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current) — `( area: SeatArea, unit: TemperatureUnit, onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit )` | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacAcState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-ac-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacAirCleanerState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-air-cleaner-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacAutoRecirculationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-auto-recirculation-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacAutoState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-auto-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacDefrosterState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-defroster-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacDualState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-dual-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacFanSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-fan-speed) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacMaxAcState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-max-ac-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacMaxDefrostState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-max-defrost-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacPowerState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-power-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacRecirculationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-recirculation-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-temperature) — `(area: SeatArea, listener: HvacTemperatureListener)` | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-temperature) — `(area: SeatArea, listener: HvacTemperatureValueListener)` | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacTemperatureCurrent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-temperature-current) — `(area: SeatArea, listener: HvacTemperatureListener)` | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacTemperatureCurrent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/register-hvac-temperature-current) — `(area: SeatArea, listener: HvacTemperatureValueListener)` | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacAc](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-ac) | W | `CAR_CLIMATE` / `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacAirCleaner](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-air-cleaner) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacAuto](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-auto) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacAutoRecirculation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-auto-recirculation) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacDefroster](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-defroster) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacDual](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-dual) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacFanDirection](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-fan-direction) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacFanSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-fan-speed) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacMaxAc](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-max-ac) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacMaxDefrost](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-max-defrost) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacPower](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-power) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacRecirculation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-recirculation) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-temperature) | W | `CONTROL_CAR_CLIMATE` | 확인 / 명시적 적용+readback 연결 / 실제 쓰기 미검증 |
| [unregisterHvacAcState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-ac-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacAirCleanerState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-air-cleaner-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacAutoRecirculationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-auto-recirculation-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacAutoState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-auto-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacDefrosterState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-defroster-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacDualState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-dual-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacFanSpeed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-fan-speed) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacMaxAcState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-max-ac-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacMaxDefrostState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-max-defrost-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacPowerState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-power-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacRecirculationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-recirculation-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-temperature) — `(area: SeatArea, listener: HvacTemperatureListener)` | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-temperature) — `(area: SeatArea, listener: HvacTemperatureValueListener)` | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacTemperatureCurrent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-temperature-current) — `(area: SeatArea, listener: HvacTemperatureListener)` | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacTemperatureCurrent](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/unregister-hvac-temperature-current) — `(area: SeatArea, listener: HvacTemperatureValueListener)` | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |

### Light — 71개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkAmbientColorAutoByDriveModeEnabledCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-ambient-color-auto-by-drive-mode-enabled-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkAmbientLightBrightnessCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-ambient-light-brightness-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkAmbientLightBrightnessColorCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-ambient-light-brightness-color-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkAmbientLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-ambient-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkAmbientLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-ambient-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkCabinLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-cabin-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkCabinLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-cabin-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkFrontFogLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-front-fog-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkFrontFogLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-front-fog-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkHighBeamLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-high-beam-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkHighBeamLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-high-beam-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkLowBeamLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-low-beam-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkLowBeamLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-low-beam-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkReadingLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-reading-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkReadingLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-reading-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkRearFogLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-rear-fog-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkRearFogLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-rear-fog-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkTailLampLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-tail-lamp-light-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [checkTailLampLightSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-tail-lamp-light-switch-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getAmbientColorAutoByDriveModeEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-ambient-color-auto-by-drive-mode-enabled) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getAmbientLightBrightness](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-ambient-light-brightness) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getAmbientLightBrightnessColor](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-ambient-light-brightness-color) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getAmbientLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-ambient-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getAmbientLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-ambient-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCabinLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-cabin-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentCabinLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-cabin-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentFrontFogLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-front-fog-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentHighBeamLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-high-beam-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentLowBeamLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-low-beam-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentReadingLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-reading-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentRearFogLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-rear-fog-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentTailLampLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-tail-lamp-light-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getFrontFogLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-front-fog-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getHighBeamLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-high-beam-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getLowBeamLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-low-beam-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getReadingLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-reading-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getRearFogLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-rear-fog-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getTailLampLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-tail-lamp-light-switch) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerAmbientColorAutoByDriveModeEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-ambient-color-auto-by-drive-mode-enabled) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerAmbientLightBrightnessColorListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-ambient-light-brightness-color-listener) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerAmbientLightBrightnessListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-ambient-light-brightness-listener) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerAmbientLightStateListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-ambient-light-state-listener) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerCabinLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-cabin-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerFrontFogLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-front-fog-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerHighBeamLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-high-beam-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerLowBeamLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-low-beam-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerReadingLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-reading-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerRearFogLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-rear-fog-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerTailLampLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-tail-lamp-light-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setAmbientColorAutoByDriveModeEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-ambient-color-auto-by-drive-mode-enabled) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setAmbientLightBrightness](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-ambient-light-brightness) | W | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setAmbientLightBrightnessColor](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-ambient-light-brightness-color) | W | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setAmbientLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-ambient-light-switch) | W | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setCabinLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-cabin-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setFrontFogLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-front-fog-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setHighBeamLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-high-beam-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setLowBeamLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-low-beam-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setReadingLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-reading-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setRearFogLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-rear-fog-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [setTailLampLightSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-tail-lamp-light-switch) | W | `CONTROL_CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterAmbientColorAutoByDriveModeEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-ambient-color-auto-by-drive-mode-enabled) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterAmbientLightBrightnessColorListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-ambient-light-brightness-color-listener) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterAmbientLightBrightnessListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-ambient-light-brightness-listener) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterAmbientLightStateListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-ambient-light-state-listener) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterCabinLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-cabin-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterFrontFogLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-front-fog-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterHighBeamLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-high-beam-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterLowBeamLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-low-beam-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterReadingLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-reading-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterRearFogLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-rear-fog-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterTailLampLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-tail-lamp-light-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |

### Odometer — 30개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkCurrentDistanceDrivenCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-current-distance-driven-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripCumulativeDrivingDistanceCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-cumulative-driving-distance-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripCumulativeDrivingTimeCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-cumulative-driving-time-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripCurrentDrivingDistanceCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-current-driving-distance-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripCurrentDrivingTimeCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-current-driving-time-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripDrivingDistanceCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-driving-distance-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripDrivingTimeCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-driving-time-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripSinceRefillDrivingDistanceCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-since-refill-driving-distance-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [checkTripSinceRefillDrivingTimeCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/check-trip-since-refill-driving-time-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getCurrentDistanceDriven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-current-distance-driven) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getTripCumulativeDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-cumulative-driving-distance) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getTripCumulativeDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-cumulative-driving-time) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getTripCurrentDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-current-driving-distance) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getTripCurrentDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-current-driving-time) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getTripSinceRefillDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-since-refill-driving-distance) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getTripSinceRefillDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-trip-since-refill-driving-time) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerDistanceDriven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-distance-driven) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerTripCumulativeDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-cumulative-driving-distance) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerTripCumulativeDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-cumulative-driving-time) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerTripCurrentDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-current-driving-distance) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerTripCurrentDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-current-driving-time) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerTripSinceRefillDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-since-refill-driving-distance) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [registerTripSinceRefillDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/register-trip-since-refill-driving-time) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterDistanceDriven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-distance-driven) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterTripCumulativeDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-cumulative-driving-distance) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterTripCumulativeDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-cumulative-driving-time) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterTripCurrentDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-current-driving-distance) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterTripCurrentDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-current-driving-time) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterTripSinceRefillDrivingDistance](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-since-refill-driving-distance) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [unregisterTripSinceRefillDrivingTime](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/unregister-trip-since-refill-driving-time) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |

### Safety — 33개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkAirbagCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-airbag-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkAirbagDeployedCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-airbag-deployed-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkBrakeFluidLevelStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-brake-fluid-level-state-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkEngineOilLevelStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-engine-oil-level-state-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkLowWasherFluidWarningStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-low-washer-fluid-warning-state-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkPowerDownWarningIndicatorCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-power-down-warning-indicator-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkSeatBuckledCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-seat-buckled-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkSeatNotBuckledWarningCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-seat-not-buckled-warning-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [checkTransmissionOilLevelStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/check-transmission-oil-level-state-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [getBrakeFluidLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/get-brake-fluid-level-state) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [getEngineOilLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/get-engine-oil-level-state) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [getTransmissionOilLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/get-transmission-oil-level-state) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [hasLowWasherFluidWarning](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-low-washer-fluid-warning) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [hasPowerDownWarningIndicator](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-power-down-warning-indicator) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [hasSeatAirbagBeenDeployed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-seat-airbag-been-deployed) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [hasSeatAirbagEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-seat-airbag-enabled) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [hasSeatBeltNotBuckledWarning](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-seat-belt-not-buckled-warning) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerBrakeFluidLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-brake-fluid-level-state) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerEngineOilLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-engine-oil-level-state) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerLowWasherFluidWaringState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-low-washer-fluid-waring-state) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerPowerDownWarningIndicator](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-power-down-warning-indicator) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerSeatAirbagDeployed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-seat-airbag-deployed) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerSeatAirbagEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-seat-airbag-enabled) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerSeatBeltNotBuckledWarning](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-seat-belt-not-buckled-warning) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [registerTransmissionOilLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-transmission-oil-level-state) | S | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterBrakeFluidLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-brake-fluid-level-state) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterEngineOilLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-engine-oil-level-state) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterLowWasherFluidWaringState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-low-washer-fluid-waring-state) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterPowerDownWarningIndicator](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-power-down-warning-indicator) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterSeatAirbagDeployed](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-seat-airbag-deployed) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterSeatAirbagEnabled](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-seat-airbag-enabled) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterSeatBeltNotBuckledWarning](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-seat-belt-not-buckled-warning) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [unregisterTransmissionOilLevelState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/unregister-transmission-oil-level-state) | U | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |

### Seat — 86개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkMoveSeatBackrestAngleCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-backrest-angle-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatBeltHeightCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-belt-height-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatDepthCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-depth-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatForeAftCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-fore-aft-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatHeadrestAngleCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-headrest-angle-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatHeadrestForeAftCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-headrest-fore-aft-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatHeadrestHeightCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-headrest-height-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatHeightCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-height-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatLumbarForeAftCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-lumbar-fore-aft-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatLumbarSideSupportCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-lumbar-side-support-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkMoveSeatTiltCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-tilt-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatBackrestAnglePositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-backrest-angle-position-capabilities) | C | `CAR_SEATS` | 확인 / 읽기 진단 연결 / 미검증 |
| [checkSeatBeltBuckledCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-belt-buckled-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatBeltHeightPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-belt-height-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatDepthPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-depth-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatForeAftPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-fore-aft-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatHeadrestAnglePositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-headrest-angle-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatHeadrestForeAftPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-headrest-fore-aft-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatHeadrestHeightPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-headrest-height-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatHeightPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-height-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatLumbarForeAftPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-lumbar-fore-aft-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatLumbarSideSupportPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-lumbar-side-support-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatMemoryCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-memory-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatOccupancyCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-occupancy-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatTemperatureCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-temperature-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatTiltPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-tilt-position-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [checkSeatVentilationCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-ventilation-capabilities) | C | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatBackrestAnglePosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-backrest-angle-position) | R | `CAR_SEATS` | 확인 / 읽기 진단 연결 / 미검증 |
| [getCurrentSeatBeltBuckledState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-belt-buckled-state) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatBeltHeightPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-belt-height-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatDepthPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-depth-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-fore-aft-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatHeadrestAnglePosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-headrest-angle-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatHeadrestForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-headrest-fore-aft-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatHeadrestHeightAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-headrest-height-aft-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatHeightPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-height-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatLumbarForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-lumbar-fore-aft-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatLumbarSideSupportPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-lumbar-side-support-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatOccupancy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-occupancy) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getCurrentSeatTiltPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-tilt-position) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getHvacSeatTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-hvac-seat-temperature) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [getHvacSeatVentilationState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-hvac-seat-ventilation-state) | R | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatBackrestAngle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-backrest-angle) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatBeltHeight](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-belt-height) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatDepth](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-depth) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatForeAft](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-fore-aft) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatHeadrestAngle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-headrest-angle) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatHeadrestForeAft](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-headrest-fore-aft) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatHeadrestHeight](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-headrest-height) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatHeight](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-height) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatLumbarForeAft](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-lumbar-fore-aft) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatLumbarSideSupport](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-lumbar-side-support) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [moveSeatTilt](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-tilt) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerHvacSeatTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-hvac-seat-temperature) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerHvacSeatVentilation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-hvac-seat-ventilation) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatBackrestAnglePosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-backrest-angle-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatBeltBuckledState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-belt-buckled-state) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatBeltHeightPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-belt-height-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatDepthPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-depth-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-fore-aft-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatHeadrestAnglePosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-headrest-angle-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatHeadrestForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-headrest-fore-aft-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatHeadrestHeightAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-headrest-height-aft-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatHeightPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-height-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatLumbarForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-lumbar-fore-aft-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatLumbarSideSupportPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-lumbar-side-support-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatOccupancy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-occupancy) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [registerSeatTiltPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-tilt-position) | S | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [selectSeatMemory](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/select-seat-memory) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [setHvacSeatTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/set-hvac-seat-temperature) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [setHvacSeatVentilation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/set-hvac-seat-ventilation) | W | `CONTROL_CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterHvacSeatTemperature](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-hvac-seat-temperature) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterHvacSeatVentilation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-hvac-seat-ventilation) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatBackrestAnglePosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-backrest-angle-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatBeltBuckledState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-belt-buckled-state) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatBeltHeightPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-belt-height-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatDepthPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-depth-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-fore-aft-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatHeadrestAnglePosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-headrest-angle-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatHeadrestForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-headrest-fore-aft-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatHeadrestHeightAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-headrest-height-aft-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatHeightPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-height-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatLumbarForeAftPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-lumbar-fore-aft-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatLumbarSideSupportPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-lumbar-side-support-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatOccupancy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-occupancy) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |
| [unregisterSeatTiltPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/unregister-seat-tilt-position) | U | `CAR_SEATS` | 확인 / 미연결 / 미검증 |

### SideMirror — 5개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkHvacSideMirrorHeatCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/check-hvac-side-mirror-heat-capabilities) | C | `CAR_MIRRORS` | 확인 / 미연결 / 미검증 |
| [getHvacSideMirrorHeatState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/get-hvac-side-mirror-heat-state) | R | `CAR_MIRRORS` | 확인 / 미연결 / 미검증 |
| [registerHvacSideMirrorHeatState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/register-hvac-side-mirror-heat-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [setHvacSideMirrorHeat](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/set-hvac-side-mirror-heat) | W | `CONTROL_CAR_MIRRORS` | 확인 / 미연결 / 미검증 |
| [unregisterHvacSideMirrorHeatState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/side-mirror/unregister-hvac-side-mirror-heat-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |

### SteeringWheel — 9개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkHvacSteeringWheelHeatCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/check-hvac-steering-wheel-heat-capabilities) | C | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [checkSteeringWheelAngleCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/check-steering-wheel-angle-capabilities) | C | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getCurrentSteeringWheelAngle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/get-current-steering-wheel-angle) | R | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [getHvacSteeringWheelHeatState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/get-hvac-steering-wheel-heat-state) | R | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerHvacSteeringWheelHeatState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/register-hvac-steering-wheel-heat-state) | S | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [registerSteeringWheelAngle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/register-steering-wheel-angle) | S | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |
| [setHvacSteeringWheelHeat](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/set-hvac-steering-wheel-heat) | W | `CONTROL_CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterHvacSteeringWheelHeatState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/unregister-hvac-steering-wheel-heat-state) | U | `CAR_CLIMATE` | 확인 / 미연결 / 미검증 |
| [unregisterSteeringWheelAngle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/unregister-steering-wheel-angle) | U | `CAR_DRIVE` | 확인 / 미연결 / 미검증 |

### Tire — 2개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkLowTirePressureCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/tire/check-low-tire-pressure-capabilities) | C | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |
| [getLowTirePressure](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/tire/get-low-tire-pressure) | R | `CAR_SAFETY` | 확인 / 미연결 / 미검증 |

### Toll — 5개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkEtcsVoiceGuidanceVolumeCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/check-etcs-voice-guidance-volume-capabilities) | C | `CAR_TOLL` | 확인 / 미연결 / 미검증 |
| [getCurrentEtcsVoiceGuidanceVolume](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/get-current-etcs-voice-guidance-volume) | R | `CAR_TOLL` | 확인 / 미연결 / 미검증 |
| [registerEtcsVoiceGuidanceVolume](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/register-etcs-voice-guidance-volume) | S | `CAR_TOLL` | 확인 / 미연결 / 미검증 |
| [setEtcsVoiceGuidanceVolume](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/set-etcs-voice-guidance-volume) | W | `CONTROL_CAR_TOLL` | 확인 / 미연결 / 미검증 |
| [unregisterEtcsVoiceGuidanceVolume](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/toll/unregister-etcs-voice-guidance-volume) | U | `CAR_TOLL` | 확인 / 미연결 / 미검증 |

### TurnSignal — 4개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkTurnSignalStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/check-turn-signal-state-capabilities) | C | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [getCurrentTurnSignalState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/get-current-turn-signal-state) | R | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [registerTurnSignalState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/register-turn-signal-state) | S | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |
| [unregisterTurnSignalState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/unregister-turn-signal-state) | U | `CAR_LIGHTS` | 확인 / 미연결 / 미검증 |

### Window — 29개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkMoveWindowBlindCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-move-window-blind-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkSmartGlassLevelCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-smart-glass-level-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkSmartGlassStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-smart-glass-state-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkSmartGlassZoneStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-smart-glass-zone-state-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkWindowBlindPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-window-blind-position-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkWindowLockCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-window-lock-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkWindowPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-window-position-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentSmartGlassLevel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-smart-glass-level) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentSmartGlassState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-smart-glass-state) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentWindowBlindPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-window-blind-position) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentWindowLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-window-lock-state) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-window-position) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getSmartGlassConfig](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-smart-glass-config) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [moveWindowBlind](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/move-window-blind) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [registerSmartGlassLevel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-smart-glass-level) | S | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [registerSmartGlassState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-smart-glass-state) | S | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [registerWindowBlindPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-blind-position) | S | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [registerWindowLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-lock-state) | S | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [registerWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-position) | S | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [setSmartGlassLevel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/set-smart-glass-level) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [setSmartGlassState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/set-smart-glass-state) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [setSmartGlassZoneState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/set-smart-glass-zone-state) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [setWindowLock](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/set-window-lock) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [setWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/set-window-position) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [unregisterSmartGlassLevel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/unregister-smart-glass-level) | U | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [unregisterSmartGlassState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/unregister-smart-glass-state) | U | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [unregisterWindowBlindPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/unregister-window-blind-position) | U | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [unregisterWindowLockState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/unregister-window-lock-state) | U | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [unregisterWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/unregister-window-position) | U | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |

### Wiper — 7개

| 메서드 | 유형 | 요구 권한 | 정적 확인 / 앱 연결 / 차량 실행 |
| --- | --- | --- | --- |
| [checkWiperStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/check-wiper-state-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [checkWiperSwitchCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/check-wiper-switch-capabilities) | C | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentWiperState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/get-current-wiper-state) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [getCurrentWiperSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/get-current-wiper-switch) | R | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [registerWiperState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/register-wiper-state) | S | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [setWiperSwitch](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/set-wiper-switch) | W | `CONTROL_CAR_WINDOWS` | 확인 / 미연결 / 미검증 |
| [unregisterWiperState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/wiper/unregister-wiper-state) | U | `CAR_WINDOWS` | 확인 / 미연결 / 미검증 |

## 재현·검증 기록

1. 공식 Maven에서 위 AAR와 sources.jar를 내려받아 SHA-256을 확인했다.
2. 19개 `ai.pleos.playground.vehicle.api.*` 인터페이스에서 메서드와 `@RequiresPermission`을 추출했다. Deprecated 및 공식 정책의 숨김 9개를 제외했다.
3. AAR `classes.jar`를 Java reflection으로 독립 집계했다. 소스 집계와 도메인별 수·메서드명·오버로드 수가 일치했다: 총 440개.
4. 19개 Dokka 도메인 페이지의 method-name 링크와 비교했다. 공개 목록의 모든 메서드명은 문서 인덱스에도 있으며, 문서에만 잔존하는 제외 항목은 위에 기록했다.
5. `./scripts/gradle.sh :app:compileDebugKotlin :app:lintDebug` 성공. AAOS `CarPropertyValue.status`는 API 34에서 deprecated 경고가 있지만, 불가/오류 값을 정상으로 오인하지 않기 위해 현재 호환 코드가 확인한다.
6. 다음 실행 검증은 권한 거부, 미지원 capability, unknown 충전 상태, 실제 CHARGING/NOT_CHARGING, 오래된 timestamp, UX 제한 변경, 서비스 끊김, 화면 중지 후 늦은 callback을 포함해야 한다. 실차 제어를 수행하는 검증은 이번 범위에 없다.
