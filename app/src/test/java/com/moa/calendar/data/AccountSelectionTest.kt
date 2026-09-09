package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test

class AccountSelectionTest {
    private val googleA = CalendarInfo("device:1", "업무", "work@example.test", CalendarSource.GOOGLE, 1)
    private val googleB = CalendarInfo("device:2", "개인", "personal@example.test", CalendarSource.GOOGLE, 2)
    private val naver = CalendarInfo("https://example.test/cal/", "일상", "naver-user", CalendarSource.NAVER, 3)
    private val device = CalendarInfo("device:3", "휴대폰", "", CalendarSource.DEVICE, 4)
    private val calendars = listOf(googleA, googleB, naver, device)

    @Test fun changingGoogleAccountReplacesThePreviousAccountsCalendars() {
        assertEquals(listOf(googleB, naver, device), calendarsForGoogleAccount(calendars, googleB.account))
        assertEquals(listOf(googleA, naver, device), calendarsForGoogleAccount(calendars, googleA.account))
    }
    @Test fun accountWithoutSyncedCalendarsNeverFallsBackToAnotherGoogleAccount() {
        assertEquals(listOf(naver, device), calendarsForGoogleAccount(calendars, "new@example.test"))
    }
    @Test fun allAccountsOptionKeepsAllRealCalendars() {
        assertEquals(calendars, calendarsForGoogleAccount(calendars, null))
    }
    @Test fun sharedCalendarsBelongToTheSelectedSyncAccount() {
        val shared = googleB.copy(id = "device:4", name = "공유 프로젝트")
        assertEquals(listOf(googleB, shared), calendarsForGoogleAccount(listOf(googleA, googleB, shared), googleB.account))
    }
    @Test fun accountLabelsIdentifyTheActualProvider() {
        assertEquals("Google · work@example.test", googleA.accountLabel())
        assertEquals("NAVER · naver-user", naver.accountLabel())
        assertEquals("기기 캘린더", device.accountLabel())
        assertEquals("기기 캘린더 · work@example.test", device.copy(account = googleA.account).accountLabel())
    }
}
