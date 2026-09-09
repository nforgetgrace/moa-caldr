# 검증 기록

검증일: 2026-09-09. 개발용 v0.1.0.

## 빌드 및 자동 테스트

환경: macOS ARM64, JDK 21, Gradle 9.4.1, Android Gradle Plugin 9.2.1, compileSdk 37, targetSdk 36, minSdk 26.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

결과: **BUILD SUCCESSFUL**. 컴파일·APK 패키징 성공, 단위 테스트 **26개 통과**, 실패·오류 0개. Android lint **오류 0개, 경고 26개**. 남은 경고는 고정한 의존성/Gradle/target SDK의 새 버전 안내, API 31 위젯 메타데이터의 이전 OS 비적용, KTX/Modifier 인자 순서 권장 및 백그라운드의 동기식 SharedPreferences 쓰기에 대한 권장 사항입니다. 경고를 숨기기 위한 lint baseline은 사용하지 않았습니다.

- [빌드 결과](verification/build.txt)
- [테스트 결과](verification/unit-tests.json)
- [APK SHA-256](verification/apk-sha256.txt)
- [APK 서명 확인](verification/apk-signature.txt): Android APK v2 서명 검증 성공.

자동 테스트는 다음을 확인합니다.

- 한국어 iCalendar 텍스트, 이스케이프, 줄바꿈, 일반 일정 왕복 저장.
- 주간 반복, EXDATE, 변경/취소된 반복 예외, DST 전환.
- 서울/로스앤젤레스/오클랜드에서 종일 일정 날짜, 종일 반복, 종일 저장 왕복.
- 자정 종료 일정의 날짜 경계 및 위젯의 현지 날짜 기준 종일 일정 표시.
- 일반 일정 수정 시 UID/사용자 정의 속성/VALARM 보존.
- 제목·시간 범위 검증 및 반복 일정 직접 수정 차단.
- CalDAV principal/home/캘린더 탐색과 쓰기 권한 해석.
- 생성 If-None-Match, 수정 If-Match, 충돌/인증 오류, 약한·누락 ETag 차단.
- 다른 출처로의 리디렉션과 발견 URL 차단, HTTP 차단, XML 외부 엔티티 차단.
- 일부 리소스 실패를 빈 캘린더로 오인해 캐시를 비우지 않는 처리.

## Android 실행 검증

별도로 생성한 `moa_calendar_api35` 에뮬레이터(`emulator-5554`)에서 수행했습니다. Android 15 / API 35, ARM64 Google APIs 이미지, 1080 × 2400px, 420dpi, 기본 글꼴 크기. 다른 기기/에뮬레이터에는 설치하지 않았습니다.

| 항목 | 확인 결과 |
| --- | --- |
| 권한 없는 첫 실행 | 예시 캘린더 정상 표시, 실행 중 크래시 없음 |
| 예시 일정 생성 | `MoaSmokeTest`를 작성해 선택 날짜에 표시 확인 |
| 예시 일정 수정/영구 저장 | 장소 `Seoul` 저장 후 앱 강제 종료·재실행하여 유지 확인 |
| 예시 일정 삭제 | 확인 대화상자에서 삭제 후 날짜의 일정 수 복원 확인 |
| 월/주 이동 | 다음 달, 오늘, 주 보기 전환 확인 |
| Calendar Provider 권한 | Android 권한 요청과 허용 후 기기 일정 조회 확인 |
| Calendar Provider 조회 | 격리된 LOCAL 캘린더의 `ProviderFixture` 표시 확인 |
| Calendar Provider 생성 | 앱에서 `ProviderWriteTest` 생성, Provider 원본 행 `_id=2` 확인 |
| Calendar Provider 수정 | 장소를 `ProviderUpdated`로 저장, Provider `eventLocation` 값 확인 |
| Calendar Provider 삭제 | 앱에서 삭제 후 원본 조회 결과에 테스트 일정이 없는 것을 확인 |
| 네이버 연결 화면 | 지원 제한, ID/앱 비밀번호 입력, 암호 마스킹, 안내 링크 구현 |
| 홈 화면 위젯 | 런처 고정 대화상자로 월간·일정 위젯 각각 실제 설치, 일정 데이터 렌더링 확인 |
| 위젯 날짜 이동 | 월간 위젯의 9월 11일을 눌러 앱의 9월 11일 금요일 일정으로 이동 확인 |
| 로그 | 최종 실행에서 AndroidRuntime/RemoteViews/AppWidgetHostView 오류 없음 |

Provider 테스트에 사용한 `moa-smoke@example.test`는 네트워크 계정이 아닌 에뮬레이터의 LOCAL 테스트 캘린더입니다. 검증 후 해당 캘린더를 삭제하고 예시 모드로 복원했습니다. Google 서버 동기화가 검증되었다는 의미는 아닙니다.

## 화면

- [월간 화면](screenshots/calendar.png)
- [주간 화면](screenshots/week.png)
- [일정 편집](screenshots/editor.png)
- [설정](screenshots/settings.png)
- [앱 내 위젯 미리보기](screenshots/widget-preview.png)
- [실제 월간 위젯](screenshots/month-widget.png)
- [실제 일정 위젯](screenshots/agenda-widget.png)

캡처는 `scripts/device_ui.py --serial emulator-5554 screenshot <경로>`로 재생성할 수 있습니다. 참고 이미지는 디자인 방향의 근거이며 픽셀 단위 복제 대상이 아닙니다. 시각 점검 기록은 `.omx/state/moa/ralph-progress.json`에 있습니다.

## 미검증 범위

- 실제 Google 계정의 클라우드 업로드/다운로드 및 네이버 실계정 인증/쓰기 권한.
- 네이버는 Android CalDAV를 공식 지원하지 않습니다. 구현된 표준 커넥터와 실제 서비스 지원 여부는 구분해야 합니다.
- Android 8~14/16 이상 및 제조사별 절전·위젯 런처 동작. 실행 검증은 API 35 한 환경입니다.
- 장시간 백그라운드 배터리 제한, 재부팅 이후의 실제 30분 주기 갱신.
- Play Store 배포·출시 서명·실서비스 인증 심사.
