package com.moa.calendar.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.CalendarContract
import com.moa.calendar.data.DeviceCalendars
import android.widget.RemoteViews
import com.moa.calendar.MainActivity
import com.moa.calendar.R
import com.moa.calendar.data.CalendarRepository
import com.moa.calendar.data.CalendarSnapshot
import com.moa.calendar.data.calendarWeekLayout
import com.moa.calendar.data.calendarMonthLineCapacity
import com.moa.calendar.data.WidgetMonthSelection
import com.moa.calendar.data.parseWidgetMonthSelection
import com.moa.calendar.data.resolveWidgetMonth
import com.moa.calendar.data.shiftWidgetMonth
import com.moa.calendar.data.widgetMonthRange
import com.moa.calendar.data.widgetMonthTitle
import com.moa.calendar.data.widgetRefreshInProgress
import com.moa.calendar.data.widgetStatusText
import kotlin.math.ceil
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Instant

open class BaseCalendarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updateAsync(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { updateAsync(context) }
    override fun onEnabled(context: Context) { CalendarSyncJob.schedule(context); updateAsync(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            REFRESH -> { WidgetUpdater.beginRefresh(context); CalendarSyncJob.scheduleNow(context) }
            PREVIOUS_MONTH, NEXT_MONTH, CURRENT_MONTH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetUpdater.navigate(context, id, intent.action!!)
                updateAsync(context)
            }
        }
    }
    private fun updateAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(8000) { WidgetUpdater.update(context) } }
            catch (_: Exception) { /* Keep the last rendered cache if a device provider is unavailable. */ }
            finally { pending.finish() }
        }
    }
    companion object {
        const val REFRESH = "com.moa.calendar.REFRESH_WIDGET"
        const val PREVIOUS_MONTH = "com.moa.calendar.WIDGET_PREVIOUS_MONTH"
        const val NEXT_MONTH = "com.moa.calendar.WIDGET_NEXT_MONTH"
        const val CURRENT_MONTH = "com.moa.calendar.WIDGET_CURRENT_MONTH"
    }
}

class MonthWidget : BaseCalendarWidget()
class AgendaWidget : BaseCalendarWidget()

object WidgetUpdater {
    /** Month chosen inside a month widget; expires after midnight (see [resolveWidgetMonth]). */
    fun monthSelection(context: Context, widgetId: Int): WidgetMonthSelection? =
        parseWidgetMonthSelection(context.getSharedPreferences("moa_calendar", 0).getString(monthKey(widgetId), null))

    fun navigate(context: Context, widgetId: Int, action: String) {
        val prefs = context.getSharedPreferences("moa_calendar", 0)
        val today = LocalDate.now()
        when (action) {
            BaseCalendarWidget.PREVIOUS_MONTH -> prefs.edit().putString(monthKey(widgetId), shiftWidgetMonth(monthSelection(context, widgetId), today, -1).encode()).apply()
            BaseCalendarWidget.NEXT_MONTH -> prefs.edit().putString(monthKey(widgetId), shiftWidgetMonth(monthSelection(context, widgetId), today, 1).encode()).apply()
            BaseCalendarWidget.CURRENT_MONTH -> prefs.edit().remove(monthKey(widgetId)).apply()
        }
    }

    private fun monthKey(widgetId: Int) = "widget_month_$widgetId"

    /** Show the spinner right away on every widget; the sync job clears it through [finishRefresh]. */
    fun beginRefresh(context: Context) {
        context.getSharedPreferences("moa_calendar", 0).edit().putLong(REFRESH_STARTED, System.currentTimeMillis()).apply()
        val manager = AppWidgetManager.getInstance(context)
        listOf(MonthWidget::class.java to R.layout.widget_month, AgendaWidget::class.java to R.layout.widget_agenda).forEach { (widget, layout) ->
            manager.getAppWidgetIds(ComponentName(context, widget)).forEach { id ->
                val views = RemoteViews(context.packageName, layout)
                views.setViewVisibility(R.id.widget_refresh, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widget_status, widgetStatusText(true, false, true, 0, ZoneId.systemDefault()))
                manager.partiallyUpdateAppWidget(id, views)
            }
        }
    }

    /** Record the outcome of a sync job so the next render hides the spinner and shows when data was last confirmed. */
    fun finishRefresh(context: Context, success: Boolean) {
        val editor = context.getSharedPreferences("moa_calendar", 0).edit().remove(REFRESH_STARTED)
        if (success) editor.putLong(LAST_SYNC, System.currentTimeMillis())
        editor.apply()
    }

    private const val REFRESH_STARTED = "widget_refresh_started"
    private const val LAST_SYNC = "widget_last_sync"

    suspend fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val monthIds = manager.getAppWidgetIds(ComponentName(context, MonthWidget::class.java))
        val agendaIds = manager.getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
        if (monthIds.isEmpty() && agendaIds.isEmpty()) return
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val repository = CalendarRepository(context)
        val prefs = context.getSharedPreferences("moa_calendar", 0)
        val refreshing = widgetRefreshInProgress(prefs.getLong(REFRESH_STARTED, 0), System.currentTimeMillis())
        val months = monthIds.associateWith { resolveWidgetMonth(monthSelection(context, it), today) }
        val grids = months.values.distinct().map { widgetMonthRange(it) }
        // Cover today's agenda window plus every month grid currently shown by a widget.
        val from = (grids.map { it.first } + today.withDayOfMonth(1).minusDays(7)).min()
        val to = (grids.map { it.second } + today.plusMonths(2)).max()
        val snapshot = repository.load(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.atStartOfDay(zone).toInstant().toEpochMilli(), false)
        val visible = snapshot.copy(events = snapshot.events.filter { it.calendarId !in repository.hiddenCalendars() })
        val status = widgetStatusText(refreshing, snapshot.errors.isNotEmpty(), snapshot.calendars.isNotEmpty(),
            maxOf(prefs.getLong(LAST_SYNC, 0), snapshot.lastSyncMillis), zone)
        monthIds.forEach { manager.updateAppWidget(it, month(context, visible, today, months.getValue(it), it, manager.getAppWidgetOptions(it), status, refreshing)) }
        agendaIds.forEach { manager.updateAppWidget(it, agenda(context, visible, today, it, manager.getAppWidgetOptions(it), status, refreshing)) }
    }

    private fun spinner(views: RemoteViews, refreshing: Boolean) {
        views.setViewVisibility(R.id.widget_refresh, if (refreshing) android.view.View.GONE else android.view.View.VISIBLE)
        views.setViewVisibility(R.id.widget_progress, if (refreshing) android.view.View.VISIBLE else android.view.View.GONE)
    }

    private fun month(context: Context, snapshot: CalendarSnapshot, today: LocalDate, month: YearMonth, widgetId: Int, options: Bundle, status: String, refreshing: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_month)
        spinner(views, refreshing)
        val currentMonth = month == YearMonth.from(today)
        views.setTextViewText(R.id.widget_title, widgetMonthTitle(month))
        views.setContentDescription(R.id.widget_title, if (currentMonth) widgetMonthTitle(month) else context.getString(R.string.go_current_month))
        views.setOnClickPendingIntent(R.id.widget_prev, navigation(context, widgetId, BaseCalendarWidget.PREVIOUS_MONTH))
        views.setOnClickPendingIntent(R.id.widget_next, navigation(context, widgetId, BaseCalendarWidget.NEXT_MONTH))
        views.setOnClickPendingIntent(R.id.widget_title, if (currentMonth) open(context, today) else navigation(context, widgetId, BaseCalendarWidget.CURRENT_MONTH))
        views.setTextViewText(R.id.widget_status, status)
        views.removeAllViews(R.id.widget_days)
        val headings = RemoteViews(context.packageName, R.layout.widget_week_header)
        listOf("일", "월", "화", "수", "목", "금", "토").forEach { heading ->
            val cell = RemoteViews(context.packageName, R.layout.widget_day)
            cell.setTextViewText(R.id.widget_day_text, heading)
            cell.setTextColor(R.id.widget_day_text, Color.rgb(119, 128, 149))
            headings.addView(R.id.widget_week_row, cell)
        }
        views.addView(R.id.widget_days, headings)
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value % 7
        val start = first.minusDays(offset.toLong())
        val weeks = (offset + month.lengthOfMonth() + 6) / 7
        val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        val height = options.getInt(heightKey, 300)
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val lineHeight = ceil(14 * fontScale).toInt()
        val fixedHeight = 16 + 40 + 20 + ceil(28 * fontScale).toInt() + 8
        val weekLayouts = (0 until weeks).map { row -> calendarWeekLayout(snapshot.events, start.plusWeeks(row.toLong())) }
        val capacity = calendarMonthLineCapacity(weekLayouts.map { it.rowCount }, height - fixedHeight, lineHeight)
        val spanLayouts = intArrayOf(R.layout.widget_event_span_1, R.layout.widget_event_span_2, R.layout.widget_event_span_3,
            R.layout.widget_event_span_4, R.layout.widget_event_span_5, R.layout.widget_event_span_6, R.layout.widget_event_span_7)
        fun span(columns: Int, text: String = "", color: Int? = null): RemoteViews {
            val entry = RemoteViews(context.packageName, spanLayouts[columns - 1])
            entry.setTextViewText(R.id.widget_day_event, text)
            entry.setTextColor(R.id.widget_day_event, Color.rgb(36, 42, 61))
            if (color != null) {
                fun tint(channel: Int) = (channel * .28 + 255 * .72).toInt()
                entry.setInt(R.id.widget_day_event, "setBackgroundColor", Color.rgb(tint(Color.red(color)), tint(Color.green(color)), tint(Color.blue(color))))
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) entry.setViewLayoutHeight(R.id.widget_day_event, lineHeight.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
            return entry
        }
        repeat(weeks) { row ->
            val weekStart = start.plusWeeks(row.toLong())
            val layout = calendarWeekLayout(snapshot.events, weekStart, capacity)
            val week = RemoteViews(context.packageName, R.layout.widget_week)
            val dates = RemoteViews(context.packageName, R.layout.widget_week_header)
            repeat(7) { column ->
                val date = weekStart.plusDays(column.toLong())
                val cell = RemoteViews(context.packageName, R.layout.widget_month_day)
                val count = layout.eventCounts[column]
                cell.setTextViewText(R.id.widget_day_text, "${date.dayOfMonth}" + if (capacity == 0 && count > 0) "·" else "")
                cell.setTextColor(R.id.widget_day_text, when {
                    date == today -> Color.WHITE
                    YearMonth.from(date) != month -> Color.rgb(160, 167, 182)
                    column == 0 -> Color.rgb(206, 120, 120)
                    column == 6 -> Color.rgb(105, 138, 192)
                    else -> Color.rgb(36, 42, 61)
                })
                if (date == today) cell.setInt(R.id.widget_day_text, "setBackgroundResource", R.drawable.widget_today)
                dates.addView(R.id.widget_week_row, cell)
                val hit = RemoteViews(context.packageName, R.layout.widget_date_hit)
                val titles = snapshot.events.filter { it.occursOn(date) }.joinToString { it.title }
                hit.setContentDescription(R.id.widget_date_hit, "${date.monthValue}월 ${date.dayOfMonth}일 일정 ${count}개" + if (count > 0) ": $titles" else "")
                hit.setOnClickPendingIntent(R.id.widget_date_hit, open(context, date))
                week.addView(R.id.widget_week_taps, hit)
            }
            week.addView(R.id.widget_week_content, dates)
            repeat(layout.rowCount) { lane ->
                val line = RemoteViews(context.packageName, R.layout.widget_event_lane)
                var nextColumn = 0
                layout.segments.filter { it.row == lane }.forEach { segment ->
                    if (segment.startColumn > nextColumn) line.addView(R.id.widget_event_lane, span(segment.startColumn - nextColumn))
                    line.addView(R.id.widget_event_lane, span(segment.endColumn - segment.startColumn + 1, segment.label(), segment.event.color))
                    nextColumn = segment.endColumn + 1
                }
                if (nextColumn < 7) line.addView(R.id.widget_event_lane, span(7 - nextColumn))
                week.addView(R.id.widget_week_content, line)
            }
            if (capacity > 0 && layout.hiddenCounts.any { it > 0 }) {
                val overflow = RemoteViews(context.packageName, R.layout.widget_event_lane)
                layout.hiddenCounts.forEach { count ->
                    val more = span(1, if (count > 0) "+$count" else "")
                    more.setTextColor(R.id.widget_day_event, Color.rgb(89, 101, 216))
                    overflow.addView(R.id.widget_event_lane, more)
                }
                week.addView(R.id.widget_week_content, overflow)
            }
            views.addView(R.id.widget_days, week)
        }
        views.setTextViewText(R.id.widget_footer, "오늘 ${snapshot.events.count { it.occursOn(today) }}개의 일정  →")
        actions(context, views, today, MonthWidget::class.java, widgetId)
        return views
    }

    private fun agenda(context: Context, snapshot: CalendarSnapshot, today: LocalDate, widgetId: Int, options: Bundle, status: String, refreshing: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        spinner(views, refreshing)
        views.setTextViewText(R.id.widget_title, "${today.monthValue}월 ${today.dayOfMonth}일")
        views.setTextViewText(R.id.widget_status, status)
        views.removeAllViews(R.id.widget_events)
        val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        val capacity = ((options.getInt(heightKey, 160) - 105) / 51).coerceIn(1, 5)
        val events = snapshot.events.filter { it.isUpcoming() }.sortedBy { it.startMillis }.take(capacity)
        events.forEach { event ->
            val row = RemoteViews(context.packageName, R.layout.widget_event)
            row.setTextViewText(R.id.widget_event_title, event.title)
            val date = event.date()
            val time = if (event.allDay) "종일" else Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            row.setTextViewText(R.id.widget_event_time, "${date.monthValue}/${date.dayOfMonth} · $time")
            row.setInt(R.id.widget_event_dot, "setBackgroundColor", event.color)
            row.setOnClickPendingIntent(R.id.widget_event_root, open(context, date))
            views.addView(R.id.widget_events, row)
        }
        views.setTextViewText(R.id.widget_footer, if (events.isEmpty()) "다가오는 일정이 없어요 · 앱 열기  →" else "모든 일정 보기  →")
        actions(context, views, today, AgendaWidget::class.java, widgetId)
        return views
    }

    private fun open(context: Context, date: LocalDate): PendingIntent = PendingIntent.getActivity(context, date.toEpochDay().toInt(),
        Intent(context, MainActivity::class.java).putExtra("date", date.toString()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun navigation(context: Context, id: Int, action: String): PendingIntent = PendingIntent.getBroadcast(context, id,
        Intent(context, MonthWidget::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actions(context: Context, views: RemoteViews, today: LocalDate, receiver: Class<*>, id: Int) {
        views.setOnClickPendingIntent(R.id.widget_root, open(context, today))
        views.setOnClickPendingIntent(R.id.widget_footer, open(context, today))
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id,
            Intent(context, receiver).setAction(BaseCalendarWidget.REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}

class CalendarSyncJob : JobService() {
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = CoroutineScope(Dispatchers.IO).launch {
            var retry = false
            try {
                if (params.jobId == CONTENT_JOB) {
                    WidgetUpdater.update(applicationContext)
                    return@launch
                }
                val now = LocalDate.now()
                val zone = ZoneId.systemDefault()
                val repository = CalendarRepository(applicationContext)
                val from = params.extras.getLong("from", now.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val to = params.extras.getLong("to", now.plusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli())
                val result = repository.load(from, to, initialOnly = params.jobId == INITIAL_JOB)
                retry = if (params.jobId == INITIAL_JOB) repository.initialNaverSyncPending() else result.errors.isNotEmpty()
                WidgetUpdater.finishRefresh(applicationContext, success = !retry)
                WidgetUpdater.update(applicationContext)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                retry = true
                // Hide a widget-triggered spinner even when the sync itself failed; the status line keeps the last good sync time.
                WidgetUpdater.finishRefresh(applicationContext, success = false)
                try { WidgetUpdater.update(applicationContext) } catch (_: Exception) { }
            }
            finally { withContext(NonCancellable + Dispatchers.Main) {
                jobs.remove(params.jobId)
                if (params.jobId == CONTENT_JOB) {
                    // Replace after processing, preserving changes that arrived during this job.
                    // Android stops the old job itself when the same ID is scheduled again.
                    if (!scheduleContentWatch(applicationContext, replace = true)) jobFinished(params, false)
                } else jobFinished(params, retry)
            } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { jobs.values.forEach { it.cancel() }; jobs.clear(); super.onDestroy() }
    companion object {
        const val CONTENT_JOB = 2703
        const val INITIAL_JOB = 2704
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val repository = CalendarRepository(context)
            val job = JobInfo.Builder(2701, ComponentName(context, CalendarSyncJob::class.java))
                .setPeriodic(30 * 60 * 1000L).setPersisted(true)
                .setRequiredNetworkType(if (repository.naverConnected()) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_NONE).build()
            val existing = scheduler.getPendingJob(2701)
            @Suppress("DEPRECATION")
            val changed = existing == null || existing.networkType != job.networkType
            if (changed) scheduler.schedule(job)
            if (repository.initialNaverSyncPending() && scheduler.getPendingJob(INITIAL_JOB) == null) {
                val now = LocalDate.now()
                val zone = ZoneId.systemDefault()
                scheduleInitial(context, now.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    now.plusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli())
            } else if (!repository.naverConnected()) scheduler.cancel(INITIAL_JOB)
            scheduleContentWatch(context)
        }
        fun scheduleInitial(context: Context, from: Long, to: Long) {
            val extras = PersistableBundle().apply { putLong("from", from); putLong("to", to) }
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(INITIAL_JOB, ComponentName(context, CalendarSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setExtras(extras).build())
        }
        fun scheduleContentWatch(context: Context, replace: Boolean = false): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!DeviceCalendars(context).hasReadPermission()) { scheduler.cancel(CONTENT_JOB); return false }
            if (!replace && scheduler.getPendingJob(CONTENT_JOB) != null) return true
            val job = JobInfo.Builder(CONTENT_JOB, ComponentName(context, CalendarSyncJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(5000).build()
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
        fun scheduleNow(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(2702, ComponentName(context, CalendarSyncJob::class.java))
                .setOverrideDeadline(0).build())
        }
    }
}

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        CalendarSyncJob.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(8000) { WidgetUpdater.update(context) } }
            catch (_: Exception) { /* Retain the previous widget when the provider is unavailable. */ }
            finally { pending.finish() }
        }
    }
}
