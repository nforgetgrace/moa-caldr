package com.moa.calendar.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarColorsTest {
    @Test fun `calendar colors are isolated by source account and id`() {
        val prefs = FakeSharedPreferences()
        val colors = CalendarColors(prefs)
        val googleWork = CalendarInfo("same-id", "업무", "work@example.test", CalendarSource.GOOGLE, 0xFF03A86B.toInt())
        val googleHome = googleWork.copy(account = "home@example.test")
        val naverWork = googleWork.copy(source = CalendarSource.NAVER, account = "work")

        colors.set(googleWork, 0xFF4285F4.toInt())
        colors.set(naverWork, 0xFFB39DDB.toInt())

        val applied = colors.applyCalendars(listOf(googleWork, googleHome, naverWork))
        assertEquals(0xFF4285F4.toInt(), applied[0].color)
        assertEquals(0xFF03A86B.toInt(), applied[1].color)
        assertEquals(0xFFB39DDB.toInt(), applied[2].color)
    }

    @Test fun `snapshot applies custom colors to calendars events and tasks`() {
        val prefs = FakeSharedPreferences()
        val colors = CalendarColors(prefs)
        val calendar = CalendarInfo("cal", "개인", "naver", CalendarSource.NAVER, 0xFFB39DDB.toInt())
        val event = CalendarEvent("event", "cal", "일정", 100, 200, source = CalendarSource.NAVER, color = calendar.color)
        val task = CalendarTask("task", "cal", "할 일", "", 100, true, completed = false, recurring = false,
            source = CalendarSource.NAVER, color = calendar.color)

        colors.set(calendar, 0xFFEA8D77.toInt())
        val snapshot = colors.apply(CalendarSnapshot(listOf(calendar), listOf(event), tasks = listOf(task)))

        assertEquals(0xFFEA8D77.toInt(), snapshot.calendars.single().color)
        assertEquals(0xFFEA8D77.toInt(), snapshot.events.single().color)
        assertEquals(0xFFEA8D77.toInt(), snapshot.tasks.single().color)
        assertEquals(0xFFEA8D77.toInt(), snapshot.tasks.single().asCalendarEvent()!!.color)
    }

    @Test fun `reset removes the local color override`() {
        val prefs = FakeSharedPreferences()
        val colors = CalendarColors(prefs)
        val calendar = CalendarInfo("cal", "기본", "user", CalendarSource.NAVER, 0xFFB39DDB.toInt())

        colors.set(calendar, 0xFF4285F4.toInt())
        val coloredSnapshot = colors.apply(CalendarSnapshot(listOf(calendar)))
        assertEquals(0xFF4285F4.toInt(), coloredSnapshot.calendars.single().color)
        assertEquals(true, colors.isCustomized(calendar))
        colors.set(calendar, null)

        assertEquals(0xFFB39DDB.toInt(), colors.applyCalendars(listOf(calendar)).single().color)
        assertEquals(0xFFB39DDB.toInt(), colors.apply(coloredSnapshot).calendars.single().color)
        assertEquals(0xFFB39DDB.toInt(), colors.defaultColor(coloredSnapshot.calendars.single()))
        assertEquals(false, colors.isCustomized(calendar))
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { updates[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { updates[key!!] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { updates[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { updates[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { updates[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { updates[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removals.forEach { values.remove(it) }
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}
