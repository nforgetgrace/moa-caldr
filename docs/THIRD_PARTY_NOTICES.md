# Third-party notices

This application uses the following open-source components. Dependencies remain subject to their respective licenses; this list does not grant additional rights.

| Component | License | Source |
| --- | --- | --- |
| AndroidX Activity, Compose UI/Foundation/Material 3 and transitive AndroidX components | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library and kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp 4.12.0, Okio | Apache-2.0 | https://github.com/square/okhttp / https://github.com/square/okio |
| biweekly 0.6.8 | BSD-2-Clause | https://github.com/mangstadt/biweekly |
| vinnie (iCalendar text parser used by biweekly) | BSD-2-Clause | https://github.com/mangstadt/vinnie |
| Apache Commons Codec (transitive) | Apache-2.0 | https://commons.apache.org/proper/commons-codec/ |
| JUnit 4 (test only) | EPL-1.0 | https://github.com/junit-team/junit4 |
| MockWebServer (test only) | Apache-2.0 | https://github.com/square/okhttp |
| JSON-java (test only) | Public domain | https://github.com/stleary/JSON-java |

License copies: [`licenses/biweekly.txt`](licenses/biweekly.txt), [`licenses/okhttp.txt`](licenses/okhttp.txt). Dependency JAR/AAR archives contain their own notices. Jackson is excluded because this app reads and writes iCalendar text rather than jCal JSON.

The user-supplied image `reference.png` is design reference material, not an app asset. MOA's brand icon is an original vector created for this project. Google and NAVER names identify the connected services; this application is not an official product of either provider.
