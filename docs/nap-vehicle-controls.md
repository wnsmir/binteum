# 수면의 차량 지원 상태 — 2026-11-14

현재 수면 섹션에는 **남은 시간 동안 수면**과 **시트 위치 설정**만 있다. 공조·온도·배경음·기상 준비 시간 설정은 현재 화면에서 제거했다. 아래 SDK 지원 사실과 실제 차량에서 검증된 동작은 구분한다.

## 현재 수면 동작

- `NapSession.forRemainingTime`으로 타이머·충전 추정·출발 제한에 따른 현재 이용 가능 시간 전체를 사용한다. 별도 기상 준비 시간을 빼지 않는다.
- 시작 시 미디어를 중지하고 화면 밝기를 낮추며, 앱 영역 전체를 검게 표시한다. 카운트다운과 종료 버튼만 남는다.
- 종료 버튼은 수면만 끝내고 기존 화면·스크롤·밝기를 복원한다. 메인 대기 타이머는 계속된다.
- 시간 예산이 줄면 종료 시점을 앞당긴다. 늘어나도 진행 중 수면을 자동 연장하지 않는다.
- 화면 이탈·차량 안전 상태 미확인 시 수면을 종료한다. 앱 종료·기기 절전 이후 기상음을 보장하지 않는다. 실제 기상음 청취는 별도 확인이 필요하다.
- 수면 시작·종료 시 공조·시트를 자동 조작하지 않는다.

## 현재 시트 설정

- 지원되는 차량의 기존 앞좌석 왼쪽 메모리 번호 선택·로컬 기억·명시적 적용 요청.
- 새 자세 저장, 수면 자세 자동 생성, 임의 시간 동안 모터 구동 기능은 제공하지 않는다.
- 메모리 capability 조회는 공조 조회와 분리해 공조 응답 실패가 시트 설정을 막지 않게 했다.
- 기존 공조 읽기/쓰기 로직은 `ComfortController`에 남아 있으나 현재 수면 UI에서 호출하거나 제공하는 기능은 아니다.

## 실제 SDK에 있는 시트 API

현재 고정한 `ai.pleos.playground:Vehicle:2.3.2`의 공식 배포 소스에서 다음 11개 이동 API를 확인했다. 이들은 공통적으로 `SeatArea`, `MoveControl`을 받으며, capability는 `CAR_SEATS`, 이동은 `CONTROL_CAR_SEATS` 권한에 속한다.

| 조절 | 메서드 |
| --- | --- |
| 등받이 | `moveSeatBackrestAngle` |
| 좌석 앞뒤 | `moveSeatForeAft` |
| 좌석 높이 | `moveSeatHeight` |
| 방석 기울기·깊이 | `moveSeatTilt`, `moveSeatDepth` |
| 요추 앞뒤·측면 지지 | `moveSeatLumbarForeAft`, `moveSeatLumbarSideSupport` |
| 헤드레스트 각도·앞뒤·높이 | `moveSeatHeadrestAngle`, `moveSeatHeadrestForeAft`, `moveSeatHeadrestHeight` |
| 안전벨트 높이 | `moveSeatBeltHeight` |

`MoveControl`은 `BACKWARD(-1)`, `STOP(0)`, `FORWARD(1)`이다. **STOP 명령은 존재한다.** 그러나 이 API는 절대 각도나 원하는 위치를 전달하는 setter가 아니다. 기기별 이동 방향·속도, STOP 전달 실패 시 동작, 연결 해제·앱 중단 중 정지, 원시 위치의 의미는 실제 차량에서 검증하지 않았다. 따라서 특정 초 동안 움직이는 방식이나 원시값을 임의의 각도로 바꾸는 자동 젖힘 기능은 추가하지 않았다.

`selectSeatMemory`로 **차량에서 이미 저장한 앞좌석 왼쪽 메모리**를 적용 요청한다. `checkSeatMemoryCapabilities`의 WRITE/READ_WRITE와 단일 ROW_1_LEFT 구역·번호 범위를 매번 다시 확인하고, CAR_SEATS·CONTROL_CAR_SEATS·CAR_POWERTRAIN·CAR_SPEED 권한, 현재 P단과 2초 이내 속도 0, 화면/안전 상태를 확인해야 호출한다. 중복 탭·지연된 콜백·화면 이탈은 무효화한다.

앱은 사용자가 선택한 **기존 메모리 번호만** 로컬 설정에 기억한다. SDK 2.3.2의 Seat에는 현재 자세를 메모리에 저장하는 API와 메모리 적용 완료 콜백/선택 메모리 readback이 없다. 따라서 새 자세는 차량에서 저장해야 하며 반환 후에는 ‘적용 요청을 보냈습니다’로만 표시한다. 늦은 실패 콜백은 실패로 갱신한다. 수면 시작·종료에 자동 적용하거나 원래 위치를 복원하지 않는다. 열선/통풍 setter도 연결하지 않았다.

시트 UI의 지원 번호는 capability 응답이다. 슬롯에 저장된 자세 내용과 실제 이동 완료는 차량에서 확인해야 한다.

## 2026-11-14 결과

- 에뮬레이터 user 10 읽기 진단: 메모리 access=WRITE, ROW_1_LEFT 번호 1..2. CAR_SPEED 권한=false. 실제 이동 명령은 보내지 않았다.
- SeatMemoryGuardProbe의 가짜 Seat 인터페이스로 10개 검사 통과: 안전 상태/권한/쓰기 지원/번호 범위/좌석 구역/신선한 주차 확인 누락, 중복 탭, 화면 이탈 후 콜백 차단. 실제 Vehicle 인스턴스나 모터를 사용하지 않는다.
- 수면은 `NapSession.forRemainingTime`으로 현재 시간 예산 전체를 사용하며 별도 준비 시간과 15분 상한이 없다. 예산이 늘어도 진행 중 수면을 자동 연장하지 않는다.

## 검증 경계

`ComfortCapabilityProbe`는 공조 설정값·실내 센서값·공조 전원·등받이 위치 capability·4개 이동 capability·AAOS P/속도를 읽는다. Activity나 UI를 열거나 권한을 자동 부여하지 않고, 모든 실행에서 `diagnostic.vehicleWrites=0`을 보고한다. 이동 capability는 `advertised support only`로 출력한다.

이전 읽기 진단에서는 앞좌석 공조 구역의 15–30°C·0.5°C 간격을 확인했지만, 공조 전원은 꺼져 있었고 설정 온도 getter는 unavailable이었다. 당시 CAR_SPEED 권한도 없었다. 이 기록을 새 시트 조회 성공이나 온도 쓰기 검증으로 간주하지 않는다. 최신 probe와 실제 화면 검수 결과는 [구현·검증 상태](implementation-status.md)에 별도로 기록한다.

2026-11-12 최신 읽기 진단은 실내 19.1°C, 공조 전원 false, 같은 온도 범위를 수신했다. 등받이·앞뒤·높이·방석 기울기의 앞좌석 왼쪽 MOVE capability가 모두 READ_WRITE로 반환됐다. 설정값 getter는 unavailable이고 CAR_SPEED 권한은 없었다. 이 결과는 지원 응답이며 모터 이동·온도 쓰기를 실행하거나 검증한 것이 아니다.

## 근거

- [메모리 선택](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/select-seat-memory)
- [Pleos Seat API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry)
- [등받이 MOVE capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-backrest-angle-capabilities)
- [등받이 이동](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-backrest-angle)
- [공조 목표 온도](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-temperature)
- [공조 실제 온도](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current)

공식 문서의 공개 페이지는 API 항목의 존재를 확인하는 데 사용했고, 정확한 함수 서명·권한·MoveControl 값·센서 온도 단위는 이 프로젝트가 내려받은 Vehicle 2.3.2 공식 소스 배포물과 대조했다.
