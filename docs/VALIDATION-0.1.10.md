# v0.1.10 — 반복 일정과 캘린더별 색상

일정 등록·수정 화면에 반복 없음, 매일, 매주, 매월, 매년을 추가했습니다. Google/기기 Calendar Provider와 네이버 CalDAV 원본에 반복 규칙을 저장합니다. 이후 회차를 눌러 수정해도 반복의 첫 날짜를 기준으로 전체 반복을 수정하며, 화면에 적용 범위를 표시합니다. 반복 해제 시 첫 일정 하나가 남는다는 안내도 표시합니다.

기존 사용자 지정 반복 주기와 종료 조건은 선택을 바꾸지 않으면 유지합니다. 개별 회차 변경·예외가 있는 반복은 원본 앱에서 수정하도록 제한합니다. 매월 31일 등 존재하지 않는 날짜는 건너뛰며, 매년 2월 29일은 윤년에 반복합니다. 시간대, 종일 일정의 종료일 제외 규칙, UID와 기존 CalDAV 알림을 보존합니다.

설정의 캘린더별 색상 버튼에서 8가지 표시색을 선택하거나 기본색으로 복원합니다. 선택은 서비스·계정·캘린더별로 기기에 보관하며, 일정·할 일·앱·위젯에 함께 적용합니다. 원본 서버의 캘린더 색상을 변경하지 않습니다. 네이버 연보라와 Google 초록은 기본값입니다.

## 검증 범위

- 단위 테스트: 반복 직렬화와 날짜 확장, 이후 회차 수정, 반복 해제/변경, 월말/윤년/DST, 예외 보호, 캐시 호환성, 색상 구분과 초기화.
- 격리 에뮬레이터: 실제 Calendar Provider와 앱 편집 UI, 제어한 CalDAV HTTP 응답, 색상 설정, 기존 로그인·저장·동기화·위젯 경로.
- APK 메타데이터·서명·SHA-256 및 lint 결과는 `docs/verification/v0.1.10/`에 기록합니다.

## 확인 한계

실제 사용자 계정으로 반복 일정을 생성·수정하는 클라우드 왕복은 수행하지 않았습니다. Google 서버 전송은 기기 동기화 어댑터, Naver 연결은 서버의 CalDAV 지원에 따릅니다. 갤럭시는 v0.1.9 설치 후 다른 세션에 넘겼으며 이번 버전에서는 화면 조작과 APK 설치를 하지 않았습니다. v0.1.10 APK는 별도 설치가 필요합니다.

## 구현 근거

- [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider): 반복 이벤트의 RRULE과 DURATION 저장 형식.
- [CalendarContract.Events](https://developer.android.com/reference/android/provider/CalendarContract.Events): 원본 이벤트 ID를 통한 수정, 반복 이벤트와 일반 이벤트의 종료 정보 차이.
- [biweekly DateStart](https://mangstadt.github.io/biweekly/javadocs/latest/biweekly/property/DateStart.html): 날짜 속성의 시간대 지정.
- [biweekly](https://github.com/mangstadt/biweekly): 기존 의존성의 반복 규칙 파서·직렬화 기능 사용. 추가 의존성 없음.
