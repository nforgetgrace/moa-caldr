# v0.1.5 검증 · 2026-09-10

## 변경

- 네이버 연결은 CalDAV 계정·캘린더 접근 확인(PROPFIND)이 끝나면 입력 창을 닫는다. 일정과 할 일 REPORT는 별도의 Android JobScheduler 작업에서 수행한다. 로그인 성공을 첫 동기화 완료로 표시하지 않으며, 설정에 첫 동기화 상태를 작게 표시한다.
- 첫 동기화 대기 상태와 작업을 저장한다. 실패하면 캐시와 연결을 유지하고 재시도한다. 앱 재진입·부팅 시 미완료 작업을 복구한다. 다른 갱신이 먼저 완료하면 대기 중인 초기 작업은 REPORT를 생략한다.
- 같은 네이버 계정의 재로그인은 기존 일정·할 일을 유지한다. 다른 네이버 계정 연결에 성공하면 이전 계정의 네이버 캐시만 제거한다. 인증 실패는 현재 연결과 캐시를 변경하지 않는다.
- Google의 ‘다른 계정 선택 · 추가’는 앱 디자인에 맞는 두 카드로 구분한다. ‘기기에 있는 계정’은 Android 선택/접근 동의를, ‘새 Google 계정 추가’는 Google 로그인 화면을 연다. 이메일 선택은 기존 한 줄 목록을 사용한다. 로그인 취소는 기존 계정 선택을 바꾸지 않는다.
- Google 계정 추가 실패는 연결 창 안에 표시한다. Android/Google이 제공하는 인증 화면 자체의 디자인을 변경한 것은 아니다. [AccountManager 공식 문서](https://developer.android.com/reference/android/accounts/AccountManager)는 계정 추가 화면을 해당 인증 제공자가 처리한다고 명시한다.
- 기존 위젯 글자·여백·최대 3개와 `+N`, 서비스별 색상, 캐시 유지 및 수동 요청에서만 상단 스피너 표시하는 동작을 유지한다.

## 실행한 검사

전용 API 35 AVD `moa_calendar_api35` (`emulator-5554`)에서 수행했다. 다른 실행 기기는 건드리지 않았다.

| 검사 | 증거와 결과 |
| --- | --- |
| 로그인 성공 뒤 첫 REPORT를 중단 | 실제 NaverDialog와 CalendarRepository, Keystore, HTTP interceptor fixture. 입력 창이 닫히고 캐시 조회가 1.5초 내 완료됨 |
| 로그인 창 제거·홈 화면 이동 후 첫 동기화 | 테스트 소유의 별도 coroutine에서 실제 저장소 실행. 일정·할 일 저장, 대기 상태 해제 확인 |
| 첫 동기화 중복 방지 | 초기 갱신을 다시 호출해 REPORT 횟수가 증가하지 않음 |
| 동일 계정 재로그인 | PROPFIND만 호출, 기존 일정·할 일 캐시 유지 |
| 잘못된 인증·일정 응답 실패·재시도 | 401에서 기존 계정 유지. REPORT 503에서 캐시와 대기 상태 유지. 재시도 성공 후 오류/대기 해제 |
| 다른 네이버 계정 연결 | 이전 네이버 캐시 제거, 새 계정 메타데이터 표시, Google 선택 유지 |
| 초기 Android 작업 등록 | 실제 JobScheduler에서 persisted 및 요청 날짜 범위 확인 후 작업 취소 |
| 동기화 잠금 중 위젯 진입 | 기존 실제 홈 위젯 테스트. 최근 Activity 유지, 다른 날짜 반영, Activity 종료 후 재진입에서도 캐시 유지 |
| Google 새 계정 버튼 | 실제 Google 로그인 이메일 화면까지 진입, 뒤로 가기로 앱 연결 창 복귀 |
| Google 기존 기기 계정 버튼 | Android 계정 선택/로그인 경로 진입과 취소 복귀 확인. AVD에는 실제 Google 계정이 없어 계정 전환 완료는 미검증 |
| 글자 100%·130% | 최종 APK 캡처 수동 검토. 제목·두 카드·설명·닫기 버튼 표시, 확대 시 불필요한 한 글자 줄바꿈 제거 |
| 단위 테스트·lint·빌드 | 76개 통과, 실패/오류/건너뜀 0. lint 오류 0, 경고 31. 앱 및 테스트 APK 빌드 성공 |

로그인 화면 검사는 실제 컴포넌트를 테스트 화면에 장착하고, 응답을 지연시킨 실제 저장소를 연결한 검사다. UI 검사의 별도 coroutine은 대화상자와 독립된 작업의 수명을 검증하며, Android JobService의 실서버 실행·프로세스 종료 후 작업 재시작을 종단 간 검증한 것은 아니다. JobScheduler 등록 조건은 별도로 검사했다. HTTP 응답과 인증 정보는 모두 격리된 테스트 자료다. 테스트 코드는 앱 APK에 포함되지 않는다.

검사 후 임시 네이버 인증·캐시를 제거하고 원래 설정을 복원했다. 테스트 APK 제거, 글자 크기 100% 복원, 전용 에뮬레이터 종료도 수행했다.

## 산출물과 재현

- `dist/moa-calendar-0.1.5-debug.apk` — versionName 0.1.5, versionCode 6, 개발용 서명.
- `docs/verification/v0.1.5/` — 테스트·빌드·서명·해시 기록.
- `docs/screenshots/v0.1.5/` — 격리 에뮬레이터 화면.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -e scenario login com.moa.calendar.test/com.moa.calendar.RefreshRegressionRunner
adb -s DEVICE_SERIAL shell am instrument -w -e scenario widget com.moa.calendar.test/com.moa.calendar.RefreshRegressionRunner
```

`login`은 실제 네이버 계정이 없는 전용 테스트 기기에서만 실행한다. `widget`은 모아 월간 위젯이 홈 화면에 있어야 한다.

## 확인하지 못한 범위

실제 계정으로 Google/Naver 로그인 완료와 서버 데이터 왕복, Galaxy 실기기, 절전·재부팅·프로세스 종료를 포함한 장시간 첫 동기화, 로그인 도중 회전/프로세스 종료, 모든 화면 크기는 검증하지 않았다. Google 인증 화면까지의 연결은 확인했지만 계정을 실제로 추가하지 않았다. Android의 작업 실행 시점은 네트워크·절전·작업 제한에 따라 지연될 수 있다. 배포용 서명과 스토어 출시는 수행하지 않았다. 이전 버전의 검증 범위와 제한은 `VALIDATION-0.1.4.md`에 보존한다.
