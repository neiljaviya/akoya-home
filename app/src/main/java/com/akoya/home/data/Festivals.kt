package com.akoya.home.data

import java.time.LocalDate
import java.time.MonthDay

enum class FestivalArt { DIWALI, HOLI, KITES, GARBA, RAKHI, TORAN, PEACOCK, CHRISTMAS, FIREWORKS, SNOW, PETALS, RAIN, LEAVES }

data class Festival(
    val id: String,
    val name: String,
    val greeting: String,
    val art: FestivalArt,
    /** Accent on dark themes / on light themes. */
    val accentDark: Long,
    val accentLight: Long,
    /** Main glow colour mixed into the backdrop mist. */
    val glow: Long,
    val isSeason: Boolean = false,
)

/**
 * Festival calendar. Lunar festival dates are taken from Drik Panchang-based calendars
 * for 2026–2027 (Diwali 8 Nov 2026, Holi 22 Mar 2027, Navratri 30 Sep–8 Oct 2027,
 * Diwali 28–29 Oct 2027…). Fixed-date events repeat every year. Later years need an update.
 */
object Festivals {
    val catalog: List<Festival> = listOf(
        Festival("diwali", "Diwali", "Happy Diwali", FestivalArt.DIWALI, 0xFFF0B04A, 0xFFB0611F, 0xFFE8A33D),
        Festival("holi", "Holi", "Happy Holi", FestivalArt.HOLI, 0xFFF06BA8, 0xFFC2306E, 0xFFF06BA8),
        Festival("uttarayan", "Uttarayan", "Happy Uttarayan", FestivalArt.KITES, 0xFF7CC4F0, 0xFF2A7FB8, 0xFF7CC4F0),
        Festival("navratri", "Navratri", "Happy Navratri", FestivalArt.GARBA, 0xFFE8506A, 0xFFB52845, 0xFFE8506A),
        Festival("dussehra", "Dussehra", "Happy Dussehra", FestivalArt.FIREWORKS, 0xFFF0A040, 0xFFB86414, 0xFFF0A040),
        Festival("rakhi", "Raksha Bandhan", "Happy Raksha Bandhan", FestivalArt.RAKHI, 0xFFF2A0B8, 0xFFB8476A, 0xFFF2A0B8),
        Festival("janmashtami", "Janmashtami", "Happy Janmashtami", FestivalArt.PEACOCK, 0xFF4FC1C6, 0xFF1F7E86, 0xFF2E8F9A),
        Festival("ganesh", "Ganesh Chaturthi", "Ganpati Bappa Morya", FestivalArt.TORAN, 0xFFF5A623, 0xFFB86E0B, 0xFFF5A623),
        Festival("christmas", "Christmas", "Merry Christmas", FestivalArt.CHRISTMAS, 0xFFE8C766, 0xFF9E7A1E, 0xFFD94848),
        Festival("newyear", "New Year", "Happy New Year", FestivalArt.FIREWORKS, 0xFFE8D7A6, 0xFF8C795E, 0xFFC8B79D),
        // Seasons (subtle: art + glow only, no accent change)
        Festival("winter", "Winter", "", FestivalArt.SNOW, 0, 0, 0xFFB9CFE0, isSeason = true),
        Festival("spring", "Spring", "", FestivalArt.PETALS, 0, 0, 0xFFF2C4D2, isSeason = true),
        Festival("monsoon", "Monsoon", "", FestivalArt.RAIN, 0, 0, 0xFF9DB8C4, isSeason = true),
        Festival("autumn", "Autumn", "", FestivalArt.LEAVES, 0, 0, 0xFFD9945A, isSeason = true),
    )

    fun byId(id: String): Festival? = catalog.firstOrNull { it.id == id }

    private data class Window(val id: String, val start: LocalDate, val end: LocalDate)

    private fun w(id: String, start: String, end: String) = Window(id, LocalDate.parse(start), LocalDate.parse(end))

    private val dated = listOf(
        w("holi", "2026-03-02", "2026-03-04"),
        w("rakhi", "2026-08-27", "2026-08-28"),
        w("janmashtami", "2026-09-03", "2026-09-04"),
        w("ganesh", "2026-09-14", "2026-09-24"),
        w("navratri", "2026-10-11", "2026-10-19"),
        w("dussehra", "2026-10-20", "2026-10-20"),
        w("diwali", "2026-11-05", "2026-11-11"),
        w("holi", "2027-03-20", "2027-03-23"),
        w("rakhi", "2027-08-16", "2027-08-17"),
        w("janmashtami", "2027-08-24", "2027-08-25"),
        w("ganesh", "2027-09-03", "2027-09-13"),
        w("navratri", "2027-09-30", "2027-10-08"),
        w("dussehra", "2027-10-09", "2027-10-09"),
        w("diwali", "2027-10-26", "2027-10-31"),
    )

    private data class Yearly(val id: String, val start: MonthDay, val end: MonthDay)

    private val yearly = listOf(
        Yearly("uttarayan", MonthDay.of(1, 13), MonthDay.of(1, 15)),
        Yearly("christmas", MonthDay.of(12, 20), MonthDay.of(12, 26)),
        Yearly("newyear", MonthDay.of(12, 30), MonthDay.of(12, 31)),
        Yearly("newyear", MonthDay.of(1, 1), MonthDay.of(1, 2)),
    )

    fun season(date: LocalDate): Festival = byId(
        when (date.monthValue) {
            12, 1, 2 -> "winter"
            3, 4, 5 -> "spring"
            6, 7, 8, 9 -> "monsoon"
            else -> "autumn"
        },
    )!!

    /** The festival (or season) to show today, honouring the user's setting. */
    fun active(date: LocalDate, mode: String, seasonal: Boolean): Festival? {
        if (mode == "off") return if (seasonal) season(date) else null
        if (mode != "auto") byId(mode)?.let { return it }
        dated.firstOrNull { !date.isBefore(it.start) && !date.isAfter(it.end) }?.let { return byId(it.id) }
        val md = MonthDay.from(date)
        yearly.firstOrNull { !md.isBefore(it.start) && !md.isAfter(it.end) }?.let { return byId(it.id) }
        return if (seasonal) season(date) else null
    }

    /** Next few upcoming festivals, for the settings screen. */
    fun upcoming(from: LocalDate, count: Int = 4): List<Pair<Festival, LocalDate>> {
        val fixed = (0..1).flatMap { dy ->
            yearly.map { it.id to it.start.atYear(from.year + dy) }
        }
        return (dated.map { it.id to it.start } + fixed)
            .filter { !it.second.isBefore(from) }
            .sortedBy { it.second }
            .distinctBy { it.first + it.second.year }
            .take(count)
            .mapNotNull { (id, d) -> byId(id)?.let { it to d } }
    }
}
