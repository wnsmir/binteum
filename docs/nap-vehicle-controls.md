# 낮잠의 차량 지원 상태 — 2026-11-12

빈틈의실은 낮잠 설정에서 실제 실내 온도, 공조 전원, 설정 온도와 시트 지원 정보를 구분한다. 지원하지 않거나 응답이 없으면 값을 만들지 않는다. 시트 지원 조회는 읽기 전용이며, SDK가 이동 기능을 제공한다는 사실과 해당 차량에서 실제 이동·정지가 검증됐다는 사실을 구분한다.

## 현재 앱에서 하는 일

| 항목 | 현재 구현 |
| --- | --- |
| 실내 온도 | `checkHvacTemperatureCurrentCapabilities`와 `getHvacTemperatureCurrent`로 앞좌석 왼쪽을 포함하는 최소 구역의 실제 센서값 조회. `cabinCelsius`이며 목표 설정값과 별도 표시 |
| 공조 전원 | `checkHvacPowerCapabilities`와 `getHvacPowerState`로 조회. 자동으로 켜거나 끄지 않음 |
| 목표 온도 | 지원되는 범위·간격 안에서 20–24°C 선택. 이 범위는 제품 선택지이며 수면에 적합한 의학적 온도 주장 아님 |
| 온도 적용 | 사용자가 직접 누른 경우에만 권한·capability·현재값·P단·최신 속도 0·앱 상태 확인 후 `setHvacTemperature` 호출. 별도의 getter가 선택한 값과 일치해야 성공 표시. ‘온도 적용 후 낮잠 시작’도 이 확인 이후에만 시작하며 실패·상태 변화·화면 이탈·시간 초과 시 예약 시작 취소 |
| 온도 복원 | 이전 값을 저장하고 별도 복원 버튼 제공. 다른 곳에서 온도가 바뀌었거나 구역이 달라지면 기존 값으로 덮어쓰지 않음. 낮잠 시작·종료·화면 이탈에서 자동 쓰기 없음 |
| 시트 등받이 위치 | `checkSeatBackrestAnglePositionCapabilities` 후 읽기 가능한 경우 원시 Int 값만 진단. 각도·기울기 퍼센트로 해석하지 않음 |
| 시트 이동 지원 | 등받이·앞뒤·높이·방석 기울기 4가지의 MOVE capability와 앞좌석 왼쪽 단일 구역 지원 여부 조회. 실제 모터 명령은 보내지 않음 |

공조/시트 조회는 속도 권한과 별도로 수행한다. 따라서 속도 권한이 아직 없거나 공조 설정값 읽기가 실패해도 시트 지원 상태와 읽을 수 있는 공조 정보를 보여줄 수 있다. 모든 선택적 조회는 8초 이내로 제한하며, 새 조회·화면 이탈 뒤 도착한 이전 콜백은 버린다. 실패·권한 누락·미확인 상태를 정상 지원으로 표시하지 않는다.

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

`selectSeatMemory`도 있지만 슬롯이 사용자에게 어떤 자세인지, 기존 자세를 새 슬롯에 저장·복원하는 흐름은 이 앱에서 확인되지 않았다. 낮잠 시작 시 임의 메모리 슬롯을 선택하지 않는다. 열선/통풍 setter도 SDK에 있지만 이번 변경에는 연결하지 않았다.

시트 UI의 “지원 확인”은 위 capability의 응답을 뜻한다. 자동 이동이 활성화됐다는 뜻이 아니며, 실제 자세는 차량의 시트 버튼으로 맞춘다.

## 검증 경계

`ComfortCapabilityProbe`는 공조 설정값·실내 센서값·공조 전원·등받이 위치 capability·4개 이동 capability·AAOS P/속도를 읽는다. Activity나 UI를 열거나 권한을 자동 부여하지 않고, 모든 실행에서 `diagnostic.vehicleWrites=0`을 보고한다. 이동 capability는 `advertised support only`로 출력한다.

이전 읽기 진단에서는 앞좌석 공조 구역의 15–30°C·0.5°C 간격을 확인했지만, 공조 전원은 꺼져 있었고 설정 온도 getter는 unavailable이었다. 당시 CAR_SPEED 권한도 없었다. 이 기록을 새 시트 조회 성공이나 온도 쓰기 검증으로 간주하지 않는다. 최신 probe와 실제 화면 검수 결과는 [구현·검증 상태](implementation-status.md)에 별도로 기록한다.

2026-11-12 최신 읽기 진단은 실내 19.1°C, 공조 전원 false, 같은 온도 범위를 수신했다. 등받이·앞뒤·높이·방석 기울기의 앞좌석 왼쪽 MOVE capability가 모두 READ_WRITE로 반환됐다. 설정값 getter는 unavailable이고 CAR_SPEED 권한은 없었다. 이 결과는 지원 응답이며 모터 이동·온도 쓰기를 실행하거나 검증한 것이 아니다.

## 근거

- [Pleos Seat API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry)
- [등받이 MOVE capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-move-seat-backrest-angle-capabilities)
- [등받이 이동](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/move-seat-backrest-angle)
- [공조 목표 온도](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-temperature)
- [공조 실제 온도](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current)

공식 문서의 공개 페이지는 API 항목의 존재를 확인하는 데 사용했고, 정확한 함수 서명·권한·MoveControl 값·센서 온도 단위는 이 프로젝트가 내려받은 Vehicle 2.3.2 공식 소스 배포물과 대조했다.
