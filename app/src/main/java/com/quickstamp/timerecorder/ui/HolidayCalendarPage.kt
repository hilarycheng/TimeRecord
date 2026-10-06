package com.quickstamp.timerecorder.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.HolidayCalendarCache
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.data.PublicHoliday
import com.quickstamp.timerecorder.model.HkTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HolidayCalendarPage(
    context: Context,
    onBack: () -> Unit,
) {
    var cache by remember { mutableStateOf(HolidayCalendarStore.load(context)) }
    var month by remember { mutableStateOf(YearMonth.from(HkTime.today())) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        cache = withContext(Dispatchers.IO) { HolidayCalendarStore.refresh(context.applicationContext) }
        refreshing = false
    }

    LaunchedEffect(Unit) {
        if (HolidayCalendarStore.shouldRefresh(cache)) refresh()
    }

    val byDate = cache.holidays.groupBy { it.date }
    val selectedHolidays = selectedDate?.let { byDate[it].orEmpty() }.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(WebBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBack,
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF131A26),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF243044)),
            ) {
                Text("‹", modifier = Modifier.padding(horizontal = 17.dp, vertical = 8.dp), fontSize = 27.sp, color = WebInk, fontWeight = FontWeight.Bold)
            }
            Text(
                "香港公眾假期",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
                color = WebInk,
            )
            Spacer(Modifier.width(50.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF131A26),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF273449)),
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MonthArrow("‹") { month = month.minusMonths(1); selectedDate = null }
                    Text(
                        "${month.year}年${month.monthValue}月",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = WebInk,
                    )
                    MonthArrow("›") { month = month.plusMonths(1); selectedDate = null }
                }

                Row(Modifier.fillMaxWidth()) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { index, label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (index == 0) WebDanger else WebMuted,
                        )
                    }
                }

                MonthGrid(
                    month = month,
                    holidays = byDate,
                    selectedDate = selectedDate,
                    onSelect = { selectedDate = it },
                )
            }
        }

        if (selectedDate != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = WebCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedHolidays.isNotEmpty()) WebAccent else WebLine),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        selectedDate!!.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEE", Locale.TRADITIONAL_CHINESE)),
                        fontSize = 12.sp,
                        color = WebMuted,
                        fontWeight = FontWeight.Bold,
                    )
                    if (selectedHolidays.isEmpty()) {
                        Text("非公眾假期", fontSize = 14.sp, color = WebInk, fontWeight = FontWeight.Bold)
                    } else {
                        selectedHolidays.forEach {
                            Text(it.name, fontSize = 15.sp, color = WebAccent2, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = WebCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(HolidayCalendarStore.sourceName, fontSize = 12.sp, fontWeight = FontWeight.Black, color = WebInk)
                InfoLine("資料更新", formatCacheTime(cache.displayUpdatedAt, dateOnly = true))
                InfoLine("最後檢查", formatCacheTime(cache.checkedAt, dateOnly = false))
                if (cache.errorMessage != null) {
                    Text(if (cache.holidays.isEmpty()) "更新失敗 · 尚未有快取資料" else "更新失敗 · 使用快取資料", fontSize = 10.sp, color = WebDanger, fontWeight = FontWeight.Bold)
                } else if (cache.holidays.isEmpty() && !refreshing) {
                    Text("尚未下載假期資料", fontSize = 10.sp, color = WebMuted)
                } else if (refreshing) {
                    Text("正在更新…舊資料會繼續顯示", fontSize = 10.sp, color = WebBus)
                }
                Button(
                    onClick = { scope.launch { refresh() } },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = WebAccentSoft, contentColor = Color(0xFFE7E1FF)),
                ) {
                    Text(if (refreshing) "正在更新…" else "立即更新", fontWeight = FontWeight.Black)
                }
            }
        }

    }
}

@Composable
private fun MonthArrow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = WebCard2,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp), fontSize = 23.sp, color = WebInk, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    holidays: Map<LocalDate, List<PublicHoliday>>,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value % 7
    val total = month.lengthOfMonth()
    val today = HkTime.today()

    repeat(6) { week ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { weekday ->
                val index = week * 7 + weekday
                val day = index - offset + 1
                if (day !in 1..total) {
                    Spacer(Modifier.weight(1f).height(50.dp))
                } else {
                    val date = month.atDay(day)
                    val isHoliday = holidays[date].orEmpty().isNotEmpty()
                    val selected = date == selectedDate
                    val isToday = date == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .padding(2.dp)
                            .background(
                                color = when {
                                    selected -> WebAccentSoft
                                    isHoliday -> Color(0xFF251B32)
                                    else -> Color.Transparent
                                },
                                shape = RoundedCornerShape(11.dp),
                            )
                            .then(
                                if (isToday) Modifier.border(1.dp, WebBus, RoundedCornerShape(11.dp))
                                else if (selected) Modifier.border(1.dp, WebAccent, RoundedCornerShape(11.dp))
                                else Modifier
                            )
                            .clickable { onSelect(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(
                                day.toString(),
                                fontSize = 13.sp,
                                fontWeight = if (isHoliday || isToday) FontWeight.Black else FontWeight.Medium,
                                color = when {
                                    isHoliday -> Color(0xFFFF9EC6)
                                    weekday == 0 -> WebDanger
                                    else -> WebInk
                                },
                            )
                            if (isHoliday) {
                                Text("假", fontSize = 8.sp, color = WebAccent2, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = WebMuted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 10.sp, color = WebInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatCacheTime(timestamp: Long, dateOnly: Boolean): String {
    if (timestamp <= 0L) return "—"
    val z = Instant.ofEpochMilli(timestamp).atZone(HkTime.zone)
    return z.format(
        if (dateOnly) DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
        else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
    )
}
