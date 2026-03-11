package com.jasonliu.neu_wisedu2wakeup_for_android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone

data class CurrentUser(
    val userName: String,
    val userId: String,
    val defaultTermCode: String,
    val termName: String
)

data class CourseRow(
    val courseName: String,
    val dayOfWeek: Int,
    val beginSection: Int,
    val endSection: Int,
    val teacher: String,
    val location: String,
    val weeks: String,
    val campus: String
)

class JwxtClient(
    private val networkConfig: NetworkConfig,
    private val cookieProvider: (String) -> String?
) {

    suspend fun fetchCurrentUser(): CurrentUser = withContext(Dispatchers.IO) {
        val response = requestJson(
            method = "GET",
            path = "/jwapp/sys/homeapp/api/home/currentUser.do"
        )
        val datas = response.optJSONObject("datas")
            ?: throw IOException("未检测到登录态，请先在页面完成登录。")
        val welcome = datas.optJSONObject("welcomeInfo")
        CurrentUser(
            userName = datas.optString("userName"),
            userId = datas.optString("userId"),
            defaultTermCode = welcome?.optString("xnxqdm").orEmpty(),
            termName = welcome?.optString("xnxqmc").orEmpty()
        )
    }

    suspend fun fetchSchedule(termCode: String): List<CourseRow> = withContext(Dispatchers.IO) {
        val rowsFromCourses = runCatching {
            fetchByCourses(termCode)
        }.getOrDefault(emptyList())
        val rowsFromScheduleDetail = runCatching {
            fetchByScheduleDetail(termCode)
        }.getOrDefault(emptyList())

        if (rowsFromCourses.isEmpty() && rowsFromScheduleDetail.isEmpty()) {
            throw IOException("课表为空或解析失败，请确认学期代码和登录状态。")
        }

        if (rowsFromCourses.isEmpty()) {
            return@withContext mergeRowsWithSameCourseSlot(rowsFromScheduleDetail)
        }

        val campusBySlot = rowsFromScheduleDetail
            .filter { it.campus.isNotBlank() }
            .associateBy(
                keySelector = { slotKeyOf(it) },
                valueTransform = { it.campus }
            )
        val enrichedRowsFromCourses = rowsFromCourses.map { row ->
            val campus = row.campus.ifBlank { campusBySlot[slotKeyOf(row)].orEmpty() }
            if (campus == row.campus) row else row.copy(campus = campus)
        }

        // `courses.do` 作为主数据源，`getMyScheduleDetail.do` 仅补充实验课(`[实]`前缀)。
        val experimentRows = rowsFromScheduleDetail.filter { isExperimentCourse(it.courseName) }
        mergeRowsWithSameCourseSlot(enrichedRowsFromCourses + experimentRows)
    }

    suspend fun fetchTermStartMillis(termCode: String): Long = withContext(Dispatchers.IO) {
        val response = requestJson(
            method = "GET",
            path = "/jwapp/sys/homeapp/api/home/getTermWeeks.do?termCode=$termCode"
        )
        val startDate = response.optJSONArray("datas")
            ?.optJSONObject(0)
            ?.optString("startDate")
            .orEmpty()
        if (startDate.isBlank()) {
            throw IOException("未获取到学期起始日期。")
        }
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        parser.parse(startDate)?.time
            ?: throw IOException("学期起始日期解析失败：$startDate")
    }

    private fun fetchByScheduleDetail(termCode: String): List<CourseRow> {
        val campusResp = runCatching {
            requestJson(
            method = "GET",
            path = "/jwapp/sys/homeapp/api/home/student/getMyScheduledCampus.do?termCode=$termCode"
            )
        }.getOrNull()

        val campusCodes = linkedSetOf("00", "01")
        val campusArray = campusResp?.optJSONArray("datas")
        if (campusArray != null) {
            for (i in 0 until campusArray.length()) {
                val code = campusArray.optJSONObject(i)?.optString("id").orEmpty()
                if (code.isNotBlank()) {
                    campusCodes += code
                }
            }
        }

        val result = mutableListOf<CourseRow>()
        for (campusCode in campusCodes) {
            val detailResp = requestJson(
                method = "POST",
                path = "/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do",
                contentType = "application/x-www-form-urlencoded;charset=UTF-8",
                body = encodeFormBody(
                    mapOf(
                        "termCode" to termCode,
                        "campusCode" to campusCode,
                        "type" to "term"
                    )
                )
            )
            val datas = detailResp.optJSONObject("datas")
            val arranged = datas?.optJSONArray("arrangedList")
                ?: datas?.optJSONObject("getMyScheduleDetail")?.optJSONArray("arrangedList")
                ?: continue
            result += parseArrangedList(arranged)
        }
        return result
    }

    private fun fetchByCourses(termCode: String): List<CourseRow> {
        val resp = requestJson(
            method = "GET",
            path = "/jwapp/sys/homeapp/api/home/student/courses.do?termCode=$termCode"
        )
        val list = resp.optJSONArray("datas") ?: return emptyList()
        val result = mutableListOf<CourseRow>()

        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val courseName = item.optString("courseName")
            val classDateAndPlace = item.optString("classDateAndPlace")
            if (classDateAndPlace.isBlank() || classDateAndPlace == "null") continue

            val singleInfos = classDateAndPlace.split("，")
            for (singleInfo in singleInfos) {
                val parts = singleInfo.split("/")
                if (parts.size < 4) continue

                val dayOfWeek = DAY_OF_WEEK_MAP[stripBrackets(parts[1])] ?: continue
                val sectionText = stripBrackets(parts[2])
                val sectionPair = parseSectionRange(sectionText) ?: continue
                val location = (parts.getOrNull(4)?.replace("*", "") ?: "暂未安排教室")
                if (location == "停课") continue

                result += CourseRow(
                    courseName = courseName,
                    dayOfWeek = dayOfWeek,
                    beginSection = sectionPair.first,
                    endSection = sectionPair.second,
                    teacher = stripBrackets(parts[3]),
                    location = location,
                    weeks = normalizeWeeks(stripBrackets(parts[0])),
                    campus = ""
                )
            }
        }
        return result
    }

    private fun parseArrangedList(arrangedList: org.json.JSONArray): List<CourseRow> {
        val result = mutableListOf<CourseRow>()

        for (i in 0 until arrangedList.length()) {
            val item = arrangedList.optJSONObject(i) ?: continue
            val courseName = item.optString("courseName")
            val dayOfWeek = item.optInt("dayOfWeek", -1)
            val beginSection = item.optInt("beginSection", -1)
            val endSection = item.optInt("endSection", -1)
            if (dayOfWeek <= 0 || beginSection <= 0 || endSection <= 0) continue

            val teacherFromWeeksAndTeachers =
                stripBrackets(item.optString("weeksAndTeachers").substringAfterLast('/'))
            val details = item.optJSONArray("titleDetail")
            val campusFromItem = item.optString("campusName")

            if (details == null || details.length() <= 1) {
                result += CourseRow(
                    courseName = courseName,
                    dayOfWeek = dayOfWeek,
                    beginSection = beginSection,
                    endSection = endSection,
                    teacher = teacherFromWeeksAndTeachers,
                    location = "暂未安排教室",
                    weeks = "",
                    campus = campusFromItem
                )
                continue
            }

            for (j in 1 until details.length()) {
                val detail = details.optString(j).trim()
                if (detail.isEmpty() || !detail.first().isDigit()) continue
                val parts = detail.split(Regex("\\s+"))
                val weeksRaw = parts.firstOrNull().orEmpty()
                val teacherFromDetail = parts.getOrNull(1)
                    ?.takeIf { !it.endsWith("校区") }
                    .orEmpty()
                val teacher = teacherFromWeeksAndTeachers.ifBlank { teacherFromDetail }
                val campusFromDetail = extractCampusFromParts(parts).ifBlank { campusFromItem }
                val rawLocation = extractLocationFromParts(
                    courseName = courseName,
                    parts = parts,
                    campus = campusFromDetail
                )
                var location = rawLocation
                location = location.replace("*", "")
                if (location.endsWith("校区")) location = "暂未安排教室"
                if (location == "停课") continue

                result += CourseRow(
                    courseName = courseName,
                    dayOfWeek = dayOfWeek,
                    beginSection = beginSection,
                    endSection = endSection,
                    teacher = teacher,
                    location = location,
                    weeks = normalizeWeeks(weeksRaw),
                    campus = campusFromDetail
                )
            }
        }

        return result
    }

    private fun requestJson(
        method: String,
        path: String,
        contentType: String? = null,
        body: String? = null
    ): JSONObject {
        val text = requestText(method, path, contentType, body)
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("返回数据不是 JSON，可能登录已过期。")
        }
    }

    private fun requestText(
        method: String,
        path: String,
        contentType: String? = null,
        body: String? = null
    ): String {
        val rawUrl = if (path.startsWith("http")) path else "$BASE_JWXT_URL$path"
        val resolvedUrl = networkConfig.resolve(rawUrl)
        val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Origin", networkConfig.requestOrigin)
            setRequestProperty("Referer", networkConfig.requestReferer)
            cookieProvider(resolvedUrl)?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Cookie", it)
            }
            if (contentType != null) {
                setRequestProperty("Content-Type", contentType)
            }
            if (method == "POST") {
                doOutput = true
                val bytes = body.orEmpty().toByteArray(StandardCharsets.UTF_8)
                outputStream.use { os -> os.write(bytes) }
            }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val payload = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IOException("请求失败($code): ${path.take(120)}")
        }
        return payload
    }

    private fun normalizeWeeks(value: String): String {
        return value.replace(",", "、").replace("(", "").replace(")", "")
    }

    private fun stripBrackets(value: String): String {
        return value.replace(Regex("\\[.*?]"), "").trim()
    }

    private fun parseSectionRange(value: String): Pair<Int, Int>? {
        val parts = value.split("-")
        if (parts.size != 2) return null
        val begin = SECTION_MAP[parts[0]] ?: return null
        val end = SECTION_MAP[parts[1]] ?: return null
        return begin to end
    }

    private fun encodeFormBody(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun isExperimentCourse(courseName: String): Boolean {
        return courseName.startsWith("[实]")
    }

    private data class SlotKey(
        val courseName: String,
        val dayOfWeek: Int,
        val beginSection: Int,
        val endSection: Int,
        val location: String,
        val weeks: String
    )

    private data class SlotAgg(
        val firstRow: CourseRow,
        val teachers: LinkedHashSet<String>,
        val campuses: LinkedHashSet<String>
    )

    private fun slotKeyOf(row: CourseRow): SlotKey {
        return SlotKey(
            courseName = row.courseName,
            dayOfWeek = row.dayOfWeek,
            beginSection = row.beginSection,
            endSection = row.endSection,
            location = row.location,
            weeks = row.weeks
        )
    }

    private fun extractCampusFromParts(parts: List<String>): String {
        return parts.firstOrNull { it.endsWith("校区") }.orEmpty()
    }

    private fun extractLocationFromParts(courseName: String, parts: List<String>, campus: String): String {
        if (parts.isEmpty()) return "暂未安排教室"

        val campusIndex = if (campus.isBlank()) -1 else parts.indexOf(campus)
        val lastIndex = parts.lastIndex

        if (isExperimentCourse(courseName) &&
            lastIndex >= 1 &&
            isExperimentClassMarker(parts[lastIndex])
        ) {
            val candidateIndex = (lastIndex - 1).coerceAtLeast(0)
            return parts[candidateIndex]
        }

        if (campusIndex >= 0 && campusIndex < lastIndex) {
            return parts[campusIndex + 1]
        }
        return parts[lastIndex]
    }

    private fun mergeRowsWithSameCourseSlot(rows: List<CourseRow>): List<CourseRow> {
        val slotMap = LinkedHashMap<SlotKey, SlotAgg>()
        for (row in rows) {
            val key = slotKeyOf(row)
            val agg = slotMap.getOrPut(key) {
                SlotAgg(
                    firstRow = row,
                    teachers = linkedSetOf(),
                    campuses = linkedSetOf()
                )
            }
            splitTeachers(row.teacher).forEach { teacher ->
                if (teacher.isNotBlank()) agg.teachers += teacher
            }
            if (row.campus.isNotBlank()) {
                agg.campuses += row.campus
            }
        }

        return slotMap.values.map { agg ->
            val mergedTeacher = if (agg.teachers.isEmpty()) {
                agg.firstRow.teacher
            } else {
                agg.teachers.joinToString(",")
            }
            val mergedCampus = if (agg.campuses.isEmpty()) {
                agg.firstRow.campus
            } else {
                agg.campuses.first()
            }
            agg.firstRow.copy(
                teacher = mergedTeacher,
                campus = mergedCampus
            )
        }
    }

    private fun splitTeachers(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(Regex("[,，、]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun isExperimentClassMarker(value: String): Boolean {
        return value.contains("实验班")
    }

    companion object {
        private const val BASE_JWXT_URL = "https://jwxt.neu.edu.cn"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val DAY_OF_WEEK_MAP = mapOf(
            "星期一" to 1,
            "星期二" to 2,
            "星期三" to 3,
            "星期四" to 4,
            "星期五" to 5,
            "星期六" to 6,
            "星期日" to 7,
            "星期天" to 7
        )

        private val SECTION_MAP = mapOf(
            "第一节" to 1,
            "第二节" to 2,
            "第三节" to 3,
            "第四节" to 4,
            "第五节" to 5,
            "第六节" to 6,
            "第七节" to 7,
            "第八节" to 8,
            "第九节" to 9,
            "第十节" to 10,
            "第十一节" to 11,
            "第十二节" to 12
        )
    }
}
