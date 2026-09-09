package com.moa.calendar

import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moa.calendar.ui.MoaApp
import com.moa.calendar.ui.MoaTheme
import com.moa.calendar.widget.CalendarSyncJob
import com.moa.calendar.data.DeviceCalendars

class MainActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)
    private var resumed by mutableStateOf(false)
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { revision++ }
    }
    private val preferences by lazy { getSharedPreferences("moa_calendar", MODE_PRIVATE) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("remote", "last_sync", "last_sync_error", "google_account")) revision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        CalendarSyncJob.schedule(this)
        setContent { MoaTheme { MoaApp(revision, resumed, intent.getStringExtra("date"), ::updateCalendarAccess) } }
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        updateCalendarAccess()
    }
    private fun updateCalendarAccess() {
        val canObserve = resumed && DeviceCalendars(this).hasReadPermission()
        if (canObserve && !observing) {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            observing = true
        } else if (!canObserve && observing) {
            contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        CalendarSyncJob.schedule(this)
        revision++
    }
    override fun onPause() {
        resumed = false
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
        super.onPause()
    }
    override fun onDestroy() {
        if (observing) contentResolver.unregisterContentObserver(observer)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDestroy()
    }
}
