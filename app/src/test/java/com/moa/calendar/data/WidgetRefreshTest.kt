package com.moa.calendar.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetRefreshTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val synced = LocalDateTime.of(2026, 9, 11, 0, 14).atZone(seoul).toInstant().toEpochMilli()

    @Test fun refreshIsInProgressOnlyWhileRecent() {
        assertFalse(widgetRefreshInProgress(0, 1_000_000))
        assertTrue(widgetRefreshInProgress(1_000_000, 1_000_000))
        assertTrue(widgetRefreshInProgress(1_000_000, 1_000_000 + WIDGET_REFRESH_TIMEOUT_MILLIS - 1))
        assertFalse(widgetRefreshInProgress(1_000_000, 1_000_000 + WIDGET_REFRESH_TIMEOUT_MILLIS))
        assertFalse(widgetRefreshInProgress(2_000_000, 1_000_000))
    }

    @Test fun syncTimeShowsDateAndTimeInLocalZone() {
        assertEquals("9/11 00:14", widgetSyncTime(synced, seoul))
        assertEquals("9/10 15:14", widgetSyncTime(synced, ZoneId.of("UTC")))
    }

    @Test fun refreshingWinsOverEverything() {
        assertEquals("동기화 중…", widgetStatusText(true, true, false, synced, seoul))
    }

    @Test fun errorsKeepTheLastSyncTime() {
        assertEquals("갱신 확인 필요 · 저장된 일정 · 최근 동기화 9/11 00:14", widgetStatusText(false, true, true, synced, seoul))
        assertEquals("갱신 확인 필요 · 저장된 일정", widgetStatusText(false, true, true, 0, seoul))
    }

    @Test fun missingCalendarsAskToConnect() {
        assertEquals("앱에서 캘린더를 연결해 주세요", widgetStatusText(false, false, false, synced, seoul))
    }

    @Test fun normalStateShowsLastSyncOrCachedNote() {
        assertEquals("최근 동기화 9/11 00:14", widgetStatusText(false, false, true, synced, seoul))
        assertEquals("기기에 저장된 일정", widgetStatusText(false, false, true, 0, seoul))
    }
}
