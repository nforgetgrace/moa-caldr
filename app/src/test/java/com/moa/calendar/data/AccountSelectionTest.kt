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
    @Test fun selectedAccountsUnsyncedCalendarsRemainDiscoverable() {
        val unsynced = googleB.copy(syncEnabled = false)
        assertEquals(listOf(unsynced), calendarsForGoogleAccount(listOf(googleA, unsynced), googleB.account))
    }
    @Test fun accountMatchingIgnoresCaseButNeverGuessesSpelling() {
        assertEquals(listOf(googleB), calendarsForGoogleAccount(listOf(googleA, googleB), " PERSONAL@example.test "))
        assertTrue(calendarsForGoogleAccount(listOf(googleA, googleB), "personal@exampel.test").isEmpty())
    }
    @Test fun localCalendarsWithEmailNamesNeverBecomeGoogleCalendars() {
        val local = device.copy(account = googleA.account, name = "Samsung Calendar")
        val result = calendarsForGoogleAccount(listOf(googleA, googleB, local), googleB.account)
        assertEquals(listOf(googleB), result.filter { it.source == CalendarSource.GOOGLE })
        assertEquals(listOf(local), result.filter { it.source == CalendarSource.DEVICE })
    }
    @Test fun calendarPickerDoesNotRepeatIdenticalNameAndAccount() {
        val calendar = device.copy(name = "My Calendar", account = "My Calendar")
        assertEquals("기기 캘린더", calendar.selectionSubtitle())
        assertEquals("기기 캘린더", calendar.copy(account = " my calendar ").selectionSubtitle())
    }
    @Test fun calendarPickerKeepsDifferentAccountIdentitiesForMatchingNames() {
        val first = googleA.copy(name = "My Calendar")
        val second = googleB.copy(name = "My Calendar")
        assertEquals("Google · work@example.test", first.selectionSubtitle())
        assertEquals("Google · personal@example.test", second.selectionSubtitle())
        assertNotEquals(first.id, second.id)
    }
    @Test fun emailNamedCalendarStillIdentifiesTheServiceWithoutRepeatingEmail() {
        assertEquals("Google", googleA.copy(name = googleA.account).selectionSubtitle())
        assertEquals("NAVER · naver-user", naver.selectionSubtitle())
    }
}
