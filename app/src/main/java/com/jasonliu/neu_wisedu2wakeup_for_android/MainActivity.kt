package com.jasonliu.neu_wisedu2wakeup_for_android

import android.app.Activity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.jasonliu.neu_wisedu2wakeup_for_android.ui.theme.NEU_Wisedu2Wakeup_for_AndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var loading by remember { mutableStateOf(false) }
    var detectingNetwork by remember { mutableStateOf(false) }
    var networkConfig by remember { mutableStateOf<NetworkConfig?>(null) }

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
                status = "网络检测完成：${config.modeLabel}。请在下方官方页面完成登录。"
                webView?.loadUrl(config.loginUrl)
            }.onFailure { e ->
                networkConfig = null
                status = "网络检测失败：${e.message}"
            }
            detectingNetwork = false
        }
    }

    LaunchedEffect(Unit) {
        detectNetworkAndLoadLogin()
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || savingCsvContent == null) {
            status = "已取消导出。"
            return@rememberLauncherForActivityResult
        }
        val csvToSave = savingCsvContent.orEmpty()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write("\uFEFF".toByteArray())
                        out.write(csvToSave.toByteArray())
                    } ?: error("无法写入文件。")
                }
            }
            status = if (result.isSuccess) "CSV 导出成功。"
            else "CSV 导出失败：${result.exceptionOrNull()?.message}"
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
            Text("状态：$status")
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
                Button(
                    onClick = {
                        val localClient = client
                        if (localClient == null) {
                            status = "网络模式未就绪，请先检测网络。"
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            val result = runCatching { localClient.fetchCurrentUser() }
                            result.onSuccess { user ->
                                currentUser = user
                                if (termCode.isBlank() && user.defaultTermCode.isNotBlank()) {
                                    termCode = user.defaultTermCode
                                }
                                status = buildString {
                                    append("登录有效。")
                                    if (user.termName.isNotBlank() && user.defaultTermCode.isNotBlank()) {
                                        append(" 当前学期：${user.termName} (${user.defaultTermCode})")
                                    }
                                }
                            }.onFailure { e ->
                                status = "登录状态检查失败：${e.message}"
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && networkConfig != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("检测登录状态")
                }
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
                        val selectedTermCode = termCode.trim()
                        if (selectedTermCode.isBlank()) {
                            status = "请先填写学期代码，或先点“检测登录状态”自动填充。"
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            val result = runCatching { localClient.fetchSchedule(selectedTermCode) }
                            result.onSuccess { fetchedRows ->
                                rows = fetchedRows
                                status = "课表获取完成，共 ${fetchedRows.size} 条课程记录。"
                            }.onFailure { e ->
                                status = "课表获取失败：${e.message}"
                            }
                            loading = false
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("获取课表")
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

            if (rows.isNotEmpty()) {
                Text("预览：${rows.take(3).joinToString(" | ") { "${it.courseName} 周${it.weeks}" }}")
            }
            if (loading) {
                Text("处理中，请稍候...")
            }
            if (detectingNetwork) {
                Text("正在检测网络...")
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
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
    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/csv"
        putExtra(Intent.EXTRA_TITLE, fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary:Documents")
            )
        }
    }
}
