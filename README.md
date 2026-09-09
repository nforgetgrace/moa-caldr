# 모아 캘린더 · MOA

Google 캘린더와 네이버 CalDAV 일정을 한곳에서 관리하는 네이티브 Android 앱입니다. Kotlin + Jetpack Compose로 만들었으며, 제공된 Samsung Calendar 참고 이미지의 간결한 달력·일정 구성을 반영했습니다.

## 실행

- Android 8.0(API 26) 이상.
- 설치 파일: [`dist/moa-calendar-0.1.2-debug.apk`](dist/moa-calendar-0.1.2-debug.apk) (약 12MB).
- **실제 일정만 표시합니다.** 연결 전에는 빈 캘린더이며, 업데이트하면 이전 버전의 예시 일정과 체험 설정을 제거합니다. 원본 서비스의 일정은 유지합니다.
- **설정 → Google Calendar → Google 계정 선택 · 변경**에서 사용할 계정을 선택합니다. 처음에는 캘린더 접근을 허용합니다. 목록에 없는 계정은 **다른 Google 계정 선택 / 추가**로 Android 계정 선택·로그인 화면을 엽니다. Android에 Google 계정이 등록되어 있고 캘린더 동기화가 켜져 있어야 합니다. Google Calendar 앱/계정 동기화가 서버 반영을 담당합니다.
- **표시할 캘린더 → Google · 선택한 계정**에서 캘린더를 확인합니다. 동기화가 꺼진 캘린더도 목록에 표시하며 **동기화 켜기**로 활성화합니다. **선택한 Google 계정에서 다시 가져오기**는 기기 동기화에 갱신을 요청합니다. Google Calendar 동기화 기능이 없거나 계정 자동 동기화가 꺼져 있으면 안내합니다. 이메일로 이름 붙인 로컬 캘린더는 **이 기기의 다른 캘린더**로 구분합니다.
- **설정 → NAVER Calendar → 계정 연결**에서 네이버 ID와 비밀번호를 입력합니다. 2단계 인증 계정은 앱 비밀번호를 사용합니다. 서버·SSL·포트는 기본 설정되어 있고, 아이폰 고급 설정의 principal 계정 주소는 로그인 후 자동으로 찾습니다.
- **일정 → 할 일**에서 네이버 CalDAV가 제공하는 할 일을 조회합니다. 마감일 없는 항목과 지난 항목도 포함하며, 완료 항목은 스위치로 표시합니다. 서버가 할 일을 제공하지 않거나 조회에 실패하면 이유를 표시합니다. 네이버 웹에 있는 모든 할 일이 CalDAV로 공개된다는 의미는 아닙니다.
- **위젯** 탭에서 월간 위젯 또는 다가오는 일정 위젯을 홈 화면에 추가합니다. 런처가 고정 요청을 지원하지 않으면 홈 화면을 길게 눌러 위젯 목록에서 추가합니다.

## 네이버 연결의 지원 범위

네이버 공식 도움말은 **Android CalDAV를 지원하지 않는다**고 명시합니다. 이 앱의 직접 연결은 표준 HTTPS CalDAV로 호환성을 확인하는 실험적 기능입니다. 사용자가 v0.1.1에서 실제 네이버 일정 연결 성공을 확인했으며, 다른 계정의 연결이나 웹 ‘내 할 일’의 공개 여부까지 검증한 것은 아닙니다. iOS/macOS 전용 클라이언트인 것처럼 위장하지 않습니다.

서버는 `https://caldav.calendar.naver.com/`, SSL/443을 사용합니다. 2단계 인증에는 앱 비밀번호가 필요합니다. 단체 계정의 제한은 네이버 안내를 확인해야 합니다.

Google과 네이버에서 같은 제목의 일정도 원본이 다르면 별개의 일정으로 유지합니다. 일정을 작성할 때 저장할 원본 캘린더를 선택합니다. **두 서비스 간 자동 복제 기능은 포함하지 않습니다.**

## 구현된 기능

- 월/주 보기, 오늘 이동, 날짜별 일정, 다가오는 일정, 제목·장소·메모 검색.
- 캘린더 색상과 표시 여부 필터. 필터는 위젯에도 적용됩니다.
- Google 계정 전환 및 선택 상태 저장. 선택한 계정의 캘린더가 아직 없으면 다른 계정으로 대체하지 않습니다.
- 일정 상세에 Google/NAVER/기기 구분, 계정 정보, 원본 캘린더 이름을 작게 표시합니다.
- Android Calendar Provider로 기기 Google 캘린더와 다른 기기 캘린더 조회, 쓰기 가능한 캘린더에 일반 일정 생성/수정/삭제.
- CalDAV principal 및 calendar-home-set 탐색, 캘린더 목록/권한 조회, 기간별 REPORT, 조건부 PUT/DELETE. 캘린더 자체의 행은 일정과 구분하고, 본문 없는 리소스는 GET으로 조회합니다.
- iCalendar 시간대, 종일 일정, RRULE/RDATE/EXDATE, 개별 반복 예외 조회.
- CalDAV의 VTODO 지원 목록을 발견하고 할 일을 별도로 조회·캐시합니다. 마감일이 있는 미완료 항목은 달력과 위젯에 □로 표시합니다. 할 일 상세에는 계정, 원본 목록, 마감일, 완료 상태를 표시합니다. 할 일 조회 실패는 일반 일정 동기화를 중단하지 않습니다.
- 일반 CalDAV 일정 수정 시 원래 UID, 알림 및 알 수 없는 속성 보존. ETag/If-Match로 충돌 차단. 신규 생성에는 If-None-Match 사용.
- 월간 라이트 위젯의 날짜 칸에 일정 제목과 계정별 색상, 초과 일정 수를 표시합니다. 크기에 따라 최대 4줄이며, 매우 작은 공간에서는 날짜 표시로 축약합니다.
- 일정 다크 위젯, 날짜를 눌러 앱 열기, 크기 변경, 수동 갱신.
- Google/기기 캘린더 변경을 앱의 ContentObserver와 백그라운드 Content URI Job으로 감지해 화면·위젯을 자동 갱신합니다.
- 네이버는 앱을 다시 열 때, 사용 중 1분 간격, 백그라운드 약 30분 간격으로 조회합니다. 연결/해제 시 네트워크 작업 조건을 재설정합니다.
- 재부팅/앱 업데이트/시간대·날짜 변경 시 작업을 복구하고 위젯을 갱신합니다.
- 캐시를 통한 네이버 오프라인 조회. 실패 시 캐시 유지와 오류 표시.
- Android Keystore AES-GCM으로 비밀번호 암호화, HTTPS만 허용, 서버 이동/발견 URL의 동일 출처 검사, XML 외부 엔티티 차단, 응답 크기 제한.
- 백업 및 기기 이전에서 앱 데이터 제외. 별도 서버나 분석 수집 기능 없음.

## 현재 제한

- 개발 환경에서 실제 Google 클라우드 수신과 네이버 VTODO 공개 여부는 검증하지 않았습니다. Google 기기 동기화와 네이버의 서버 정책에 의존합니다.
- 할 일은 CalDAV VTODO 조회만 지원합니다. 완료·수정·삭제는 네이버 원본에서 수행하며, 반복 할 일의 미래 회차는 펼치지 않습니다. Google Tasks는 Calendar Provider와 별도 API이므로 이번 버전에는 포함하지 않습니다.
- 반복 일정은 조회할 수 있지만 생성·개별 회차/전체 반복 수정·삭제는 원본 앱에서 수행합니다. `RANGE=THISANDFUTURE`는 잘못 표시하는 대신 지원 제한 오류를 표시합니다.
- 네이버 초대/참석자 일정은 읽기 전용입니다. 일정 알림을 새로 설정하는 UI는 없으며, 기존 CalDAV 알림은 수정 시 유지합니다.
- 원본 캘린더 간 이동, 여러 네이버 계정, 자동 서비스 간 복제, 위젯 내 직접 편집은 미지원입니다.
- 네이버 캐시는 최근 성공한 조회 범위에 해당합니다. 오프라인에서 한 번도 조회하지 않은 기간의 일정은 보장하지 않습니다.
- 검색은 현재 불러온 기간 내에서 수행합니다. 기본 조회는 선택한 달 주변과 현재 날짜 이후 약 3개월을 포함합니다.
- 앱 닫힘/절전 상태에서 갱신은 Android가 지연할 수 있습니다. Google 서버 반영 주기는 기기 계정 동기화 설정을 따릅니다.
- 배포용 서명 및 Play Store 출시는 포함하지 않은 개발용 APK입니다.

## 빌드 및 검증

JDK 17 이상(이 환경에서는 JDK 21), Android SDK 37 및 Build Tools 36.0.0이 필요합니다. AGP 9.2.1, Gradle Wrapper 9.4.1을 고정했습니다.

```sh
# macOS, JDK 21이 설치된 경우
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Android Studio에서는 이 폴더를 열고 Gradle JDK를 17 이상으로 설정합니다. 다른 컴퓨터에서는 SDK 경로를 `local.properties`의 `sdk.dir`에 설정하거나 `ANDROID_HOME`을 사용합니다. `local.properties`는 버전 관리에서 제외됩니다.

```sh
# 설치할 기기를 명시합니다.
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk

# 접근성 트리/스크린샷 확인용 보조 도구
python3 scripts/device_ui.py --adb /path/to/adb --serial DEVICE_SERIAL dump
python3 scripts/device_ui.py --adb /path/to/adb --serial DEVICE_SERIAL screenshot screen.png
```

v0.1.2 검증 결과는 [`docs/VALIDATION-0.1.2.md`](docs/VALIDATION-0.1.2.md), 화면은 `docs/screenshots/v0.1.2/`에 있습니다. 이전 검증 기록은 `docs/VALIDATION-0.1.1.md`와 `docs/VALIDATION.md`에 보존합니다. 디자인 규칙은 [`DESIGN.md`](DESIGN.md)에 있습니다.

## 주요 코드

| 경로 | 역할 |
| --- | --- |
| `app/src/main/java/com/moa/calendar/ui/` | Compose 화면, 일정 편집, 계정 연결, 디자인 토큰 |
| `app/src/main/java/com/moa/calendar/data/` | 통합 저장소, Calendar Provider, CalDAV, iCalendar, 암호화 저장 |
| `app/src/main/java/com/moa/calendar/widget/` | 홈 화면 위젯 및 백그라운드 갱신 |
| `app/src/test/` | 일정/시간대/반복/HTTP/인증 정보 보호 테스트 |

## 근거 문서와 라이선스

- [네이버 CalDAV 설정과 Android 지원 제한](https://help.naver.com/service/5620/contents/2426?lang=ko&osType=COMMONOS)
- [네이버 애플리케이션 비밀번호](https://help.naver.com/service/5640/contents/8584?lang=ko&osType=COMMONOS)
- [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)
- [Android 수동 동기화 요청](https://developer.android.com/reference/android/content/ContentResolver#requestSync(android.accounts.Account,%20java.lang.String,%20android.os.Bundle))
- [Google Tasks 별도 API](https://developers.google.com/workspace/tasks/overview)
- [Android App Widgets](https://developer.android.com/develop/ui/views/appwidgets/overview)
- [CalDAV RFC 4791](https://www.rfc-editor.org/rfc/rfc4791.html)
- [AGP 9.2 호환성](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [biweekly iCalendar 라이브러리](https://github.com/mangstadt/biweekly): BSD-2-Clause, Android 호환. 시간대·반복 처리를 위해 사용했습니다.
- [OkHttp](https://square.github.io/okhttp/): Apache-2.0. CalDAV의 PROPFIND/REPORT 및 안전한 HTTPS 요청에 사용했습니다.
- AndroidX/Compose: Apache-2.0, Kotlin: Apache-2.0. 의존 라이선스 공지는 `docs/THIRD_PARTY_NOTICES.md`에 있습니다.
