package com.jasonliu.neu_wisedu2wakeup_for_android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jasonliu.neu_wisedu2wakeup_for_android.ui.theme.NEU_Wisedu2Wakeup_for_AndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NEU_Wisedu2Wakeup_for_AndroidTheme {
                AppScreen()
            }
        }
    }
}

private enum class GuideStep {
    NONE,
    NEED_LOGIN,
    NEED_FETCH
}

@Composable
fun AppScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在检测网络模式...") }
    var currentUser by remember { mutableStateOf<CurrentUser?>(null) }
    var termCode by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<CourseRow>>(emptyList()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var savingCsvContent by remember { mutableStateOf<String?>(null) }
    var savingIcsContent by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var detectingNetwork by remember { mutableStateOf(false) }
    var networkConfig by remember { mutableStateOf<NetworkConfig?>(null) }
    var guideStep by remember { mutableStateOf(GuideStep.NONE) }
    var showLoginPage by remember { mutableStateOf(true) }

    val client = remember(networkConfig) {
        networkConfig?.let { config ->
            JwxtClient(config) { targetUrl ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.flush()
                val cookieCandidates = listOf(
                    cookieManager.getCookie(targetUrl).orEmpty(),
                    cookieManager.getCookie("https://jwxt.neu.edu.cn").orEmpty(),
                    cookieManager.getCookie("https://webvpn.neu.edu.cn").orEmpty()
                ).filter { it.isNotBlank() }
                if (cookieCandidates.isEmpty()) null else cookieCandidates.joinToString("; ")
            }
        }
    }

    fun detectNetworkAndLoadLogin() {
        if (detectingNetwork) return
        detectingNetwork = true
        scope.launch {
            val result = runCatching { NetworkDetector.detect() }
            result.onSuccess { config ->
                networkConfig = config
                status = "网络检测完成：${config.modeLabel}。请在下方官方页面完成登录。登录完成后，请点击检测登录状态来确认登录结果。"
                guideStep = GuideStep.NEED_LOGIN
                showLoginPage = true
                webView?.loadUrl(config.loginUrl)
            }.onFailure { e ->
                networkConfig = null
                status = "网络检测失败：${e.message}"
                guideStep = GuideStep.NONE
            }
            detectingNetwork = false
        }
    }

    LaunchedEffect(Unit) {
        detectNetworkAndLoadLogin()
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val uri = activityResult.data?.data
        if (activityResult.resultCode != Activity.RESULT_OK || uri == null || savingCsvContent == null) {
            status = "已取消导出。"
            return@rememberLauncherForActivityResult
        }
        val csvToSave = savingCsvContent.orEmpty()
        val grantFlags = activityResult.data?.flags ?: 0
        scope.launch {
            val saveResult = runCatching {
                val persistableFlags = grantFlags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (persistableFlags != 0) {
                    context.contentResolver.takePersistableUriPermission(uri, persistableFlags)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write("\uFEFF".toByteArray())
                        out.write(csvToSave.toByteArray())
                    } ?: error("无法写入文件。")
                }
            }
            saveResult.onSuccess {
                val opened = openCsvWithChooser(context, uri)
                status = if (opened) {
                    "CSV 导出成功，已弹出打开方式。"
                } else {
                    "CSV 导出成功，但没有可打开 CSV 的应用。"
                }
            }.onFailure { e ->
                status = "CSV 导出失败：${e.message}"
            }
        }
    }

    val exportIcsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val uri = activityResult.data?.data
        if (activityResult.resultCode != Activity.RESULT_OK || uri == null || savingIcsContent == null) {
            status = "已取消导出。"
            return@rememberLauncherForActivityResult
        }
        val icsToSave = savingIcsContent.orEmpty()
        val grantFlags = activityResult.data?.flags ?: 0
        scope.launch {
            val saveResult = runCatching {
                val persistableFlags = grantFlags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (persistableFlags != 0) {
                    context.contentResolver.takePersistableUriPermission(uri, persistableFlags)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(icsToSave.toByteArray())
                    } ?: error("无法写入文件。")
                }
            }
            saveResult.onSuccess {
                val opened = openFileWithChooser(
                    context = context,
                    uri = uri,
                    mimeType = "text/calendar",
                    chooserTitle = "选择应用打开课表 ICS"
                )
                status = if (opened) {
                    "ICS 导出成功，已弹出打开方式。"
                } else {
                    "ICS 导出成功，但没有可打开 ICS 的应用。"
                }
            }.onFailure { e ->
                status = "ICS 导出失败：${e.message}"
            }
        }
    }

    DisposableEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        onDispose {
            webView?.destroy()
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("NEU 教务课程表导出")
            Text(
                text = "状态：$status",
                color = statusColorForMessage(status)
            )
            Text("登录地址：${networkConfig?.loginUrl ?: "等待网络检测"}")
            Text("网络模式：${networkConfig?.modeLabel ?: "未知"}")
            currentUser?.let { user ->
                Text("用户：${user.userName} (${user.userId})")
            }

            OutlinedTextField(
                value = termCode,
                onValueChange = { termCode = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("学期代码（示例：2025-2026-1）") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { detectNetworkAndLoadLogin() },
                    enabled = !detectingNetwork,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重新检测网络")
                }
                Button(
                    onClick = {
                        val config = networkConfig ?: return@Button
                        showLoginPage = true
                        guideStep = GuideStep.NEED_LOGIN
                        webView?.loadUrl(config.loginUrl)
                    },
                    enabled = networkConfig != null && !loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("回到登录页")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GuidePulseButton(
                    text = "检测登录状态",
                    onClick = {
                        val localClient = client
                        if (localClient == null) {
                            status = "网络模式未就绪，请先检测网络。"
                            return@GuidePulseButton
                        }
                        loading = true
                        scope.launch {
                            val result = runCatching { localClient.fetchCurrentUser() }
                            result.onSuccess { user ->
                                currentUser = user
                                if (termCode.isBlank() && user.defaultTermCode.isNotBlank()) {
                                    termCode = user.defaultTermCode
                                }
                                guideStep = GuideStep.NEED_FETCH
                                showLoginPage = false
                                status = buildString {
                                    append("登录有效。")
                                    if (user.termName.isNotBlank() && user.defaultTermCode.isNotBlank()) {
                                        append(" 当前学期：${user.termName} (${user.defaultTermCode})")
                                    }
                                }
                            }.onFailure { e ->
                                status = "登录状态检查失败：${e.message}"
                                guideStep = GuideStep.NEED_LOGIN
                                showLoginPage = true
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && networkConfig != null,
                    highlight = guideStep == GuideStep.NEED_LOGIN,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GuidePulseButton(
                    text = "获取课表",
                    onClick = {
                        val localClient = client
                        if (localClient == null) {
                            status = "网络模式未就绪，请先检测网络。"
                            return@GuidePulseButton
                        }
                        val selectedTermCode = termCode.trim()
                        if (selectedTermCode.isBlank()) {
                            status = "请先填写学期代码，或先点“检测登录状态”自动填充。"
                            return@GuidePulseButton
                        }
                        loading = true
                        scope.launch {
                            val result = runCatching { localClient.fetchSchedule(selectedTermCode) }
                            result.onSuccess { fetchedRows ->
                                rows = fetchedRows
                                status = "课表获取完成，共 ${fetchedRows.size} 条课程记录。"
                                guideStep = GuideStep.NONE
                            }.onFailure { e ->
                                status = "课表获取失败：${e.message}"
                            }
                            loading = false
                        }
                    },
                    enabled = !loading,
                    highlight = guideStep == GuideStep.NEED_FETCH,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val localClient = client
                        if (localClient == null) {
                            status = "网络模式未就绪，请先检测网络。"
                            return@Button
                        }
                        if (rows.isEmpty()) {
                            status = "当前没有可导出的课表数据。"
                            return@Button
                        }
                        val selectedTermCode = termCode.trim()
                        if (selectedTermCode.isBlank()) {
                            status = "请先填写学期代码，或先点“检测登录状态”自动填充当前学期。"
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            val result = runCatching {
                                val termStartMillis = localClient.fetchTermStartMillis(selectedTermCode)
                                buildIcs(
                                    rows = rows,
                                    termCode = selectedTermCode,
                                    termStartMillis = termStartMillis
                                )
                            }
                            result.onSuccess { icsContent ->
                                savingIcsContent = icsContent
                                exportIcsLauncher.launch(
                                    buildCreateDocumentIntent(
                                        fileName = "schedule_$selectedTermCode.ics",
                                        mimeType = "text/calendar"
                                    )
                                )
                            }.onFailure { e ->
                                status = "ICS 导出失败：${e.message}"
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && networkConfig != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出 ICS")
                }
                Button(
                    onClick = {
                        if (rows.isEmpty()) {
                            status = "当前没有可导出的课表数据。"
                            return@Button
                        }
                        val selectedTermCode = termCode.trim().ifBlank { "term" }
                        savingCsvContent = buildCsv(rows)
                        exportCsvLauncher.launch(buildCreateCsvIntent("schedule_$selectedTermCode.csv"))
                    },
                    enabled = !loading && networkConfig != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出 CSV")
                }
            }

            if (loading) {
                Text("处理中，请稍候...")
            }
            if (detectingNetwork) {
                Text("正在检测网络...")
            }

            if (showLoginPage) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                GuideHalo(
                    highlight = guideStep == GuideStep.NEED_LOGIN,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    cornerRadius = 12.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("官方登录页")
                        val config = networkConfig
                        if (config == null) {
                            Text("无法初始化登录页，请先完成网络检测。")
                        } else {
                            key(config.mode) {
                                AndroidView(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    factory = { ctx ->
                                        WebView(ctx).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.javaScriptCanOpenWindowsAutomatically = true
                                            webViewClient = WebViewClient()
                                            webChromeClient = WebChromeClient()
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                            loadUrl(config.loginUrl)
                                        }.also { webView = it }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHalo(
    highlight: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(cornerRadius),
    content: @Composable () -> Unit
) {
    if (!highlight) {
        Box(modifier = modifier) {
            content()
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "guide-halo")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo-alpha"
    )
    Box(
        modifier = modifier
            .border(
                width = 2.dp,
                color = GUIDE_HALO_COLOR.copy(alpha = alpha),
                shape = shape
            )
    ) {
        content()
    }
}

@Composable
private fun GuidePulseButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    val baseColor = MaterialTheme.colorScheme.primary
    val containerColor = if (highlight) {
        val transition = rememberInfiniteTransition(label = "guide-button-pulse")
        val pulse by transition.animateFloat(
            initialValue = 0.05f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "button-pulse"
        )
        lerp(baseColor, GUIDE_HALO_COLOR, pulse)
    } else {
        baseColor
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor
        )
    ) {
        Text(text)
    }
}

private fun buildCsv(rows: List<CourseRow>): String {
    val header = listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数")
    val body = rows.map {
        listOf(
            it.courseName,
            it.dayOfWeek.toString(),
            it.beginSection.toString(),
            it.endSection.toString(),
            it.teacher,
            it.location,
            it.weeks
        )
    }
    return (listOf(header) + body).joinToString("\r\n") { cols ->
        cols.joinToString(",") { cell -> csvEscapeMinimal(cell) }
    } + "\r\n"
}

private fun csvEscapeMinimal(value: String): String {
    val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    if (!needsQuote) return value
    return "\"${value.replace("\"", "\"\"")}\""
}

private fun buildCreateCsvIntent(fileName: String): Intent {
    return buildCreateDocumentIntent(fileName = fileName, mimeType = "text/csv")
}

private fun buildCreateDocumentIntent(fileName: String, mimeType: String): Intent {
    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary:Documents")
            )
        }
    }
}

private fun statusColorForMessage(status: String): Color {
    return when {
        status.startsWith("课表获取失败") -> Color(0xFFC62828)
        status.startsWith("课表获取完成") -> Color(0xFF2E7D32)
        else -> Color.Black
    }
}

private fun openCsvWithChooser(context: Context, uri: Uri): Boolean {
    return openFileWithChooser(
        context = context,
        uri = uri,
        mimeType = "text/csv",
        chooserTitle = "选择应用打开课程表 CSV"
    )
}

private fun openFileWithChooser(
    context: Context,
    uri: Uri,
    mimeType: String,
    chooserTitle: String
): Boolean {
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(viewIntent, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private data class ClockTime(val hour: Int, val minute: Int)

private fun buildIcs(rows: List<CourseRow>, termCode: String, termStartMillis: Long): String {
    val tz = TimeZone.getTimeZone("Asia/Shanghai")
    val dtFormatter = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
        timeZone = tz
    }
    val rawTermStart = Calendar.getInstance(tz).apply {
        timeInMillis = termStartMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val sundayBaseDate = rawTermStart.toSundayBase()
    val dtStamp = dtFormatter.format(Calendar.getInstance(tz).time)

    val sb = StringBuilder()
    sb.append("BEGIN:VCALENDAR\r\n")
    sb.append("VERSION:2.0\r\n")
    sb.append("PRODID:-//NEU_Wisedu2Wakeup_for_Android//Schedule//CN\r\n")
    sb.append("CALSCALE:GREGORIAN\r\n")
    sb.append("METHOD:PUBLISH\r\n")
    sb.append("X-WR-TIMEZONE:Asia/Shanghai\r\n")
    sb.append("X-WR-CALNAME:${escapeIcsText("NEU课程表-$termCode")}\r\n")

    var eventCount = 0
    for (row in rows) {
        val weeks = parseWeekNumbers(row.weeks)
        if (weeks.isEmpty()) continue

        val campus = resolveCampus(row)
        val startClock = sectionStartTime(campus, row.beginSection) ?: continue
        val endClock = sectionEndTime(campus, row.endSection) ?: continue

        for (week in weeks) {
            val dayOffset = dayOffsetFromSundayBase(row.dayOfWeek)
            val startCal = (sundayBaseDate.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + dayOffset)
                set(Calendar.HOUR_OF_DAY, startClock.hour)
                set(Calendar.MINUTE, startClock.minute)
                set(Calendar.SECOND, 0)
            }
            val endCal = (sundayBaseDate.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + dayOffset)
                set(Calendar.HOUR_OF_DAY, endClock.hour)
                set(Calendar.MINUTE, endClock.minute)
                set(Calendar.SECOND, 0)
            }

            val uid = "${UUID.randomUUID()}@neu-wisedu2wakeup"
            val summary = escapeIcsText(row.courseName)
            val location = escapeIcsText(row.location)
            val description = escapeIcsText(
                "老师: ${row.teacher}\n周数: ${row.weeks}\n节次: ${row.beginSection}-${row.endSection}\n校区: $campus"
            )

            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:$uid\r\n")
            sb.append("DTSTAMP:$dtStamp\r\n")
            sb.append("DTSTART:${dtFormatter.format(startCal.time)}\r\n")
            sb.append("DTEND:${dtFormatter.format(endCal.time)}\r\n")
            sb.append("SUMMARY:$summary\r\n")
            sb.append("LOCATION:$location\r\n")
            sb.append("DESCRIPTION:$description\r\n")
            sb.append("END:VEVENT\r\n")
            eventCount++
        }
    }

    if (eventCount == 0) {
        throw IllegalStateException("没有可导出的 ICS 事件，请检查周数字段。")
    }

    sb.append("END:VCALENDAR\r\n")
    return sb.toString()
}

private fun Calendar.toSundayBase(): Calendar {
    val result = (clone() as Calendar)
    val day = result.get(Calendar.DAY_OF_WEEK)
    val offsetToSunday = -((day - Calendar.SUNDAY + 7) % 7)
    result.add(Calendar.DAY_OF_YEAR, offsetToSunday)
    return result
}

private fun dayOffsetFromSundayBase(dayOfWeek: Int): Int {
    val normalized = ((dayOfWeek - 1) % 7 + 7) % 7 + 1
    return normalized % 7
}

private fun parseWeekNumbers(weeksText: String): List<Int> {
    if (weeksText.isBlank()) return emptyList()
    val result = linkedSetOf<Int>()
    val tokens = weeksText.split("、").map { it.trim() }.filter { it.isNotBlank() }
    for (token in tokens) {
        var normalized = token.replace("周", "").trim()
        val oddOnly = normalized.endsWith("单")
        val evenOnly = normalized.endsWith("双")
        if (oddOnly || evenOnly) {
            normalized = normalized.dropLast(1)
        }
        val numbers = Regex("\\d+").findAll(normalized).map { it.value.toInt() }.toList()
        if (numbers.isEmpty()) continue
        val candidates = if (numbers.size >= 2) {
            (numbers[0]..numbers[1]).toList()
        } else {
            listOf(numbers[0])
        }
        candidates.forEach { week ->
            if (oddOnly && week % 2 == 0) return@forEach
            if (evenOnly && week % 2 != 0) return@forEach
            result += week
        }
    }
    return result.toList().sorted()
}

private fun resolveCampus(row: CourseRow): String {
    if (row.campus.isNotBlank()) return row.campus
    return when {
        row.location.contains("南湖") -> "南湖校区"
        row.location.contains("浑南") -> "浑南校区"
        else -> "浑南校区"
    }
}

private fun sectionStartTime(campus: String, section: Int): ClockTime? {
    val isNanhu = campus.contains("南湖")
    val table = if (isNanhu) SECTION_TIME_NANHU else SECTION_TIME_HUNNAN
    return table[section]?.first
}

private fun sectionEndTime(campus: String, section: Int): ClockTime? {
    val isNanhu = campus.contains("南湖")
    val table = if (isNanhu) SECTION_TIME_NANHU else SECTION_TIME_HUNNAN
    return table[section]?.second
}

private fun escapeIcsText(raw: String): String {
    return raw
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
}

private val GUIDE_HALO_COLOR = Color(0xFF1E88E5)

private val SECTION_TIME_NANHU = mapOf(
    1 to (ClockTime(8, 0) to ClockTime(8, 45)),
    2 to (ClockTime(8, 55) to ClockTime(9, 40)),
    3 to (ClockTime(10, 0) to ClockTime(10, 45)),
    4 to (ClockTime(10, 55) to ClockTime(11, 40)),
    5 to (ClockTime(14, 0) to ClockTime(14, 45)),
    6 to (ClockTime(14, 55) to ClockTime(15, 40)),
    7 to (ClockTime(16, 0) to ClockTime(16, 45)),
    8 to (ClockTime(16, 55) to ClockTime(17, 40)),
    9 to (ClockTime(18, 30) to ClockTime(19, 15)),
    10 to (ClockTime(19, 25) to ClockTime(20, 10)),
    11 to (ClockTime(20, 20) to ClockTime(21, 5)),
    12 to (ClockTime(21, 15) to ClockTime(22, 0))
)

private val SECTION_TIME_HUNNAN = mapOf(
    1 to (ClockTime(8, 30) to ClockTime(9, 15)),
    2 to (ClockTime(9, 25) to ClockTime(10, 10)),
    3 to (ClockTime(10, 30) to ClockTime(11, 15)),
    4 to (ClockTime(11, 25) to ClockTime(12, 10)),
    5 to (ClockTime(14, 0) to ClockTime(14, 45)),
    6 to (ClockTime(14, 55) to ClockTime(15, 40)),
    7 to (ClockTime(16, 0) to ClockTime(16, 45)),
    8 to (ClockTime(16, 55) to ClockTime(17, 40)),
    9 to (ClockTime(18, 30) to ClockTime(19, 15)),
    10 to (ClockTime(19, 25) to ClockTime(20, 10)),
    11 to (ClockTime(20, 20) to ClockTime(21, 5)),
    12 to (ClockTime(21, 15) to ClockTime(22, 0))
)
