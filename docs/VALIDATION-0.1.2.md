# v0.1.2 검증 · 2026-09-09

## 수정과 근거

Google 계정을 바꿔도 선택한 계정의 캘린더가 나타나지 않는 문제를 이전 APK에서 재현했다. Calendar Provider 조회가 `sync_events=1`인 행만 읽고 있어, 선택한 B 계정의 동기화가 꺼진 캘린더는 숨겨졌다. 이메일 이름을 가진 LOCAL 캘린더는 Google 계정 필터 대상이 아니었지만 같은 목록에 표시되어 계정 불일치처럼 보였다.

v0.1.2는 동기화가 꺼진 Google 캘린더도 표시하고, 해당 행의 동기화를 켜는 동작을 제공한다. 선택한 Google 계정과 다른 기기 캘린더를 별도 그룹으로 표시한다. 계정 선택 및 수동 갱신 시 Android의 해당 계정 캘린더 동기화에 요청한다. 클라우드 수신은 비동기이며 실제 동기화 어댑터가 필요하다. 기기의 전역 자동 동기화 설정을 변경하지 않는다. [Android requestSync 문서](https://developer.android.com/reference/android/content/ContentResolver#requestSync(android.accounts.Account,%20java.lang.String,%20android.os.Bundle))

기존 앱에는 VEVENT 일정 조회만 있었고 VTODO 할 일 조회는 없었다. VTODO 지원 캘린더 발견, 기간 제한 없는 할 일 조회, 별도 캐시와 읽기 전용 목록을 추가했다. 서버의 지원 구성 요소 속성이 없으면 RFC에 따라 VEVENT와 VTODO 모두 지원하는 것으로 취급하고, VEVENT만 명시한 목록은 할 일 조회에서 제외한다. [RFC 4791 §5.2.3](https://www.rfc-editor.org/rfc/rfc4791.html#section-5.2.3)

할 일은 마감일 없는 항목, 지난 항목, 완료 항목을 보존한다. 날짜가 있는 미완료 항목만 달력·위젯으로 변환하며, 임의 날짜를 만들지 않는다. 반복 할 일은 응답에 포함된 항목만 표시하고 미래 회차를 생성하지 않는다. 서버 미지원, 빈 응답, 조회 실패를 구분한다. 할 일 조회 실패 시 기존 할 일 캐시를 유지하며 일반 일정 동기화를 성공 처리할 수 있다.

## 자동 검증

- JDK 21, Gradle 9.4.1, Android SDK 37.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 성공.
- 단위 테스트 **52개, 실패 0, 오류 0, 건너뜀 0**.
- 신규 13개: 계정/동기화 필터 3개, CalDAV 할 일 발견·요청 3개, VTODO 날짜·완료·반복 식별 7개.
- Android lint **오류 0, 경고 31**. 경고는 KTX 권고, 낮은 API에서 무시되는 속성, 의존 버전/target 권고, SharedPreferences 및 Compose 스타일 관련이다. 권한 누락/API 호환 오류는 없다.
- APK: `dist/moa-calendar-0.1.2-debug.apk`, versionCode 3. 서명·해시는 `verification/v0.1.2/`에 기록한다.

## 에뮬레이터 검증

전용 AVD `moa_calendar_api35` / `emulator-5554`, API 35, 1080×2400에서 확인했다. 삼성 실기기는 사용하지 않았다.

1. Google A(sync=1), Google B(sync=0), A와 같은 이메일 이름의 LOCAL 캘린더를 Calendar Provider에 테스트 행으로 삽입했다. 로그인 계정이나 서버 자료가 아니다.
2. B 선택 상태에서 v0.1.1에는 B 캘린더가 없고 LOCAL만 보이는 증상을 재현했다.
3. v0.1.2에서 B 캘린더가 표시됐고 ‘동기화 켜기’를 눌러 `sync_events=1`로 변경되는 것을 확인했다. B 일정은 나타나고 다른 Google A 일정은 제외됐다. LOCAL은 별도 그룹으로 남았다.
4. 동기화 어댑터가 없는 AVD에서 설치/설정 안내가 표시되는 것을 확인했다. 실제 Google 서버 수신 성공으로 간주하지 않는다.
5. VTODO 캐시 테스트 자료로 마감일 있음/없음/기한 경과/완료 4개를 넣었다. 기본 완료 숨김, 완료 항목 표시, 읽기 전용 상세의 계정·마감일, 월간·일정 위젯의 □ 항목을 확인했다.
6. 최종 APK를 다시 설치하고 계정 그룹, 완료 표시 행 클릭 및 월간 위젯을 확인했다. 화면과 접근성 트리에서 잘림이나 항목 중복이 관찰되지 않았다.
7. 테스트 뒤 삽입한 Provider 행과 앱 캐시 자료를 삭제하고 빈 실제 데이터 상태로 돌아오는 것을 확인했다.

**할 일 화면의 fixture-user 및 example.test 계정은 UI 검증용 캐시다. 네이버 인증이나 실제 서버 할 일 수신 증거가 아니다.** 이 때문에 해당 화면에는 네이버 연결 안내도 함께 보인다. 배포 APK에는 이 자료나 더미 일정 생성 기능이 없다. 화면은 `screenshots/v0.1.2/`, 접근성·빌드·검사 기록은 `verification/v0.1.2/`에 있다. 시각 평가는 실제 화면 수동 검사이며 참고 이미지와의 픽셀 일치를 주장하지 않는다.

## 확인되지 않은 범위

- 사용자가 v0.1.1의 실제 네이버 일반 일정 연결 성공을 확인했다. 개발 환경에서 사용자 계정으로 인증하거나 네이버 웹 ‘내 할 일’이 CalDAV VTODO로 공개되는지 확인하지 않았다. 미제공이면 앱만으로 가져올 수 없으며 이를 화면에 안내한다.
- 실제 사용자 Google 계정의 클라우드 수신 및 삼성 기기의 동기화 어댑터 동작은 미검증이다. Provider 필터·활성화·화면 반영은 에뮬레이터에서 검증했다.
- Google Tasks는 별도 API이며 이번 버전에는 연결하지 않았다. [Google Tasks API 개요](https://developers.google.com/workspace/tasks/overview)
- 네이버 할 일 완료/수정/삭제, 미래 반복 회차 생성은 지원하지 않는다. 원본 사이트에서 관리한다.
- 스토어 배포용 서명과 출시는 포함하지 않는다.
