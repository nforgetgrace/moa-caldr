# v0.1.1 검증 기록

2026-09-09. 버전 코드 2. 기존 v0.1.0 위에 설치 가능한 개발용 APK입니다.

## 결과

- JDK 21 / Gradle 9.4.1 / AGP 9.2.1, `testDebugUnitTest lintDebug assembleDebug` 성공.
- 단위 테스트 **39개 통과**, 실패/오류 0개. Android lint 오류 0개, 경고 27개.
- 경고는 업데이트 권장, 구형 OS의 위젯 메타데이터, KTX/Compose 스타일, SharedPreferences 쓰기 권장 등입니다. 오류를 숨기는 baseline은 없습니다.
- [최종 빌드](verification/v0.1.1/build.txt), [단위 테스트](verification/v0.1.1/unit-tests.json), [서명 검증](verification/v0.1.1/apk-signature.txt), [SHA-256](verification/v0.1.1/apk-sha256.txt).

## 네이버 오류와 아이폰 설정 대조

사용자가 제보한 “서버가 일부 일정 데이터를 반환하지 않았어요”는 앱의 REPORT 해석 단계에서 발생하던 오류입니다. 실계정의 원시 응답을 수집한 것은 아닙니다. 모의 서버에서 캘린더 자체의 행/ETag만 있는 일정/본문 속성의 404 응답을 만들었고, 수정 전 신규 회귀 테스트 7개 실패를 확인했습니다. [수정 전 결과](verification/v0.1.1/regression-before.txt).

수정 후 캘린더 자체의 행은 제외하고, 일정의 본문이 없으면 같은 서버의 GET으로 받습니다. GET의 본문과 해당 GET의 ETag를 짝지으며, 오래된 목록 ETag를 재사용하지 않습니다. 잘못된 본문이나 다른 출처 주소, 실제 권한 실패는 계속 오류로 처리합니다. 실제 일정 실패를 무시해서 캐시를 비우지 않습니다.

아이폰 사진의 설정과 대조한 값:

| 항목 | 모아 |
| --- | --- |
| CalDAV 서버 | `caldav.calendar.naver.com` |
| SSL / 포트 | 사용 / 443 |
| 사용자 이름 / 암호 | 네이버 ID / 비밀번호 또는 2단계 인증용 앱 비밀번호 |
| `/principals/users/<아이디>/` 형태의 계정 주소 | `current-user-principal` 응답으로 자동 발견 |
| 캘린더 위치 | `calendar-home-set`으로 자동 발견 |

실제 서버에 인증 정보 없이 PROPFIND를 전송해 **TLS 인증서 검증 성공**, **401 + Basic 인증 요청**을 확인했습니다. [실제 서버 확인 기록](verification/v0.1.1/naver-endpoint.json). 서버에 도달하며 인증이 필요하다는 증거이고, 로그인/일정 조회 성공의 증거는 아닙니다.

근거: [네이버 공식 CalDAV 설정](https://help.naver.com/service/5620/contents/2426?lang=ko&osType=COMMONOS), [CalDAV GET/ETag 동작 RFC 4791](https://www.rfc-editor.org/rfc/rfc4791.html#section-5.3.4).

## Android 실행 검증

별도로 만든 `moa_calendar_api35` / `emulator-5554` / API 35에서만 검증했습니다. 다른 실행 중 기기에는 설치하거나 테스트 데이터를 쓰지 않았습니다.

- v0.1.0의 예시 일정이 저장된 상태에서 덮어 설치 후 `demo`, `demo_events` 키 제거와 실제 캘린더 0개/빈 화면 확인.
- Android의 Google 계정 추가 화면으로 이동하여 Google 로그인 화면 표시 확인. 계정 정보 입력은 하지 않았습니다. [접근성 트리](verification/v0.1.1/native-google-picker-ui.txt).
- 두 Calendar Provider 테스트 계정을 만들어 B 선택 시 B 일정만 표시, A로 바꾸면 A 일정만 표시 확인. 이 계정은 `example.test`의 모의 라벨이며 실제 Google 로그인 계정이 아닙니다.
- 일정 상세에서 서비스, 계정, 캘린더 이름 확인. [상세 화면](screenshots/v0.1.1/event-account.png), [계정 선택](screenshots/v0.1.1/account-picker.png).
- 앱이 열린 상태에서 외부 Provider 일정 제목을 바꾸고 새로고침 없이 화면 반영 확인. [접근성 트리](verification/v0.1.1/foreground-auto-sync-ui.txt).
- 홈 화면으로 나간 상태에서 외부 Provider 일정 제목을 바꾸고, 앱 열기/새로고침 없이 월간 위젯 반영 확인. 선택하지 않은 계정은 위젯에 나타나지 않음. [접근성 트리](verification/v0.1.1/background-auto-sync-ui.txt).
- 월간 위젯에서 짧은 제목 3줄과 `+2` 초과 표시, 원본 색상 확인. [월간 위젯](screenshots/v0.1.1/month-widget.png), [일정 위젯](screenshots/v0.1.1/agenda-widget.png).
- 30분 반복 작업과 Content URI 변경 작업 등록/실행/재등록 확인. 새 작업은 처리 완료 후 같은 ID로 교체하여 계속 변경을 감지합니다. [Android 공식 동작](https://developer.android.com/reference/android/app/job/JobInfo.Builder#addTriggerContentUri(android.app.job.JobInfo.TriggerContentUri)).
- 테스트 데이터는 에뮬레이터에만 만들었으며 APK에는 포함하지 않습니다. 검증 후 해당 Provider 캘린더를 제거합니다.

## 자동 동기화의 범위

Google은 Android에 내려온 캘린더를 읽습니다. Google 서버에서 기기로 내려오는 구간은 Google Calendar/기기 계정 동기화가 담당합니다. 기기에 도착한 변경의 앱/위젯 자동 반영은 위 테스트로 확인했습니다. [Google 동기화 도움말](https://support.google.com/calendar/answer/6261951?co=GENIE.Platform%3DAndroid&hl=ko).

네이버는 연결 시 조회를 완료해야 연결 상태를 저장하며, 앱을 다시 열 때/사용 중 1분마다/백그라운드 약 30분마다 CalDAV를 조회하도록 구현했습니다. Push 방식은 아닙니다. Android 절전/네트워크/작업 할당량으로 지연될 수 있고, 강제 종료 상태에서는 앱을 다시 열어야 합니다. Google과 네이버 사이의 자동 복제는 없습니다.

## 남은 검증 한계

실제 Google 계정의 클라우드 동기화, 네이버 실계정 로그인·다운로드·업로드는 **미검증**입니다. 사용자 사진에는 비밀번호가 가려져 있으며 계정 인증 정보를 확보하거나 추출하지 않았습니다. 네이버 공식 안내는 Android CalDAV를 지원 대상에서 제외하므로 실계정 호환 성공을 보장하지 않습니다.

실행 검증은 API 35 에뮬레이터입니다. Galaxy One UI 실기기, Android 8~14/16+, 큰 글꼴, 장시간 절전/30분 주기 및 재부팅 후 갱신은 별도 확인이 필요합니다. UI와 실제 위젯의 표시 검증은 픽셀 일치 검사가 아닌 육안/접근성 검사입니다.
