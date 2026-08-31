package com.jasonliu.neu_wisedu2wakeup_for_android

data class ClockTime(val hour: Int, val minute: Int)

data class SectionRange(val beginSection: Int, val endSection: Int)

val SECTION_TIME_NANHU = mapOf(
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

val SECTION_TIME_HUNNAN = mapOf(
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

fun inferCampusByLocation(location: String): String {
    return when {
        location.contains("南湖") -> "南湖校区"
        location.contains("浑南") -> "浑南校区"
        else -> "浑南校区"
    }
}

fun resolveCampus(row: CourseRow): String {
    if (row.campus.isNotBlank()) return row.campus
    return inferCampusByLocation(row.location)
}

fun sectionStartTime(campus: String, section: Int): ClockTime? {
    return sectionTimeTable(campus)[section]?.first
}

fun sectionEndTime(campus: String, section: Int): ClockTime? {
    return sectionTimeTable(campus)[section]?.second
}

fun findNearestExamSections(
    campus: String,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int
): SectionRange? {
    val table = sectionTimeTable(campus)
    if (table.isEmpty()) return null

    val examStart = startHour * 60 + startMinute
    val examEnd = endHour * 60 + endMinute
    val sortedEntries = table.entries.sortedBy { it.key }

    val beginSection = sortedEntries
        .filter { (_, timeRange) -> minutesOf(timeRange.first) <= examStart }
        .maxByOrNull { (_, timeRange) -> minutesOf(timeRange.first) }
        ?.key
        ?: sortedEntries.first().key

    val endSection = sortedEntries
        .minByOrNull { (_, timeRange) ->
            kotlin.math.abs(minutesOf(timeRange.second) - examEnd)
        }
        ?.key
        ?: return null

    return SectionRange(
        beginSection = beginSection,
        endSection = maxOf(beginSection, endSection)
    )
}

private fun sectionTimeTable(campus: String): Map<Int, Pair<ClockTime, ClockTime>> {
    return if (campus.contains("南湖")) SECTION_TIME_NANHU else SECTION_TIME_HUNNAN
}

private fun minutesOf(time: ClockTime): Int {
    return time.hour * 60 + time.minute
}
