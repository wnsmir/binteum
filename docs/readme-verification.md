# README 실행 확인 — 2026-11-14

환경: macOS 26.3.1 ARM64, Temurin 21.0.10, Node 22.20.0, Python 3.13.7. Pleos Connect 2.1.0 API 34 ARM64 revision 2, serial emulator-5554, 실행 시 조회한 current user 10. 다른 OS와 새 컴퓨터 설치 완료를 의미하지 않는다.

| README 경로 | 실제 실행 결과 |
| --- | --- |
| Mac 환경변수 + check-environment.sh | JDK 21, API 34, Build Tools 36.1.0, adb, 해당 AVD 확인 |
| core:test / assembleDebug / lintDebug | 87개 통과, APK 생성, 오류 0·경고 63 |
| 현재 사용자 조회 + install --user -r + am start | 기존 데이터를 유지한 업데이트와 Activity 실행 성공 |
| 모의 충전 → 다이얼 20분 → 시작 → 수면 → 종료 → 설정 적용 | ReadmeDemoProbe 13개 통과. 정상 debug 모의 소스·실제 ticker/클릭 사용, 상태 주입 없음 |
| POI 준비 스크립트 | 새 프로젝트 전용 venv 설치, 기존 공식 원본 캐시 재사용, 119,684개 인덱스 생성. 원본 재다운로드는 하지 않음 |
| start-nearby-server.sh | 추가 환경 파일 없이 기본 인덱스 경로로 새 서버 시작. health configured:true, OSM 대한민국 범위 |
| 서버 검사 | 16개 통과 |
| adb reverse + 공개 시험 GPS + SDK probe | 실제 FusedLocation fresh fix → 새 서버 → 실제 장소 3곳·왕복 도보 경로 3건 확인 |
| Virtual Console 스크립트 | 포트 연결 후 출력 URL에서 HTTP 200 확인 |
| README 링크·문법 | 로컬 링크 누락 없음, 코드 블록 쌍 확인 |

기존 SDK 설치와 사용자 약관 동의, 기존 부팅 AVD를 재사용했다. 새 SDK 설치·약관·Windows/Linux 실행, 외부 서비스 로그인·YouTube 음성/영상·Netflix 재생·시트 이동·TTS 청취는 별도다. 위치 권한은 사용자의 사전 동의 후 해당 에뮬레이터의 현재 프로필/패키지에만 부여했다.

ReadmeDemoProbe의 첫 실행 실패는 검사 코드가 클릭 대상인 타이머 컨테이너 대신 내부 그림을 선택하고 null 설명을 처리하지 못한 원인이었다. 검사 대상을 실제 클릭 영역으로 수정하고 오류를 정상 보고하도록 한 뒤 13개를 통과했다. 이 실패를 앱 자체 결함 수정으로 기록하지 않는다.
