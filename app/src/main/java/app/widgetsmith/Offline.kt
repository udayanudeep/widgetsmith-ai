package app.widgetsmith

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Free, instant designer used while the on-device AI model isn't downloaded yet.
 * Reads the prompt for features (clock, date, battery, countdowns, progress) and a color mood,
 * and lays them out in a quiet, minimal style: one solid tone, thin numerals, muted labels, one accent.
 */
fun designOffline(prompt: String): JSONObject {
    val p = prompt.lowercase(Locale.ROOT)
    fun has(vararg w: String) = w.any { p.contains(it) }
    val c = palette(p)

    val top = JSONArray()
    val main = JSONArray()
    fun JSONArray.text(t: String, size: Int, color: String, bold: Boolean = false) =
        put(JSONObject().put("type", "text").put("text", t).put("size", size).put("color", color).put("bold", bold).put("maxLines", 2))
    fun JSONArray.big(t: String, size: Int) =
        put(JSONObject().put("type", "text").put("text", t).put("size", size).put("color", c.fg).put("font", "light").put("maxLines", 1))
    fun JSONArray.bar(value: String) =
        put(JSONObject().put("type", "progress").put("value", value).put("color", c.accent).put("track", c.track))

    val countdown = countdownTarget(p)
    val wantsClock = has("clock", "time") || (countdown == null && !has("battery", "progress", "quote", "greeting", "date", "calendar", "year"))
    var name = "Widget"

    if (has("greeting", "morning", "hello", "hi ")) { top.text("{greeting}", 15, c.fg, true); name = "Greeting" }
    when {
        wantsClock -> {
            if (top.length() == 0) top.text("{date:EEE  d MMM}", 13, c.muted)
            main.put(JSONObject().put("type", "clock").put("format", if (has("12", "am", "pm")) "h:mm" else "HH:mm")
                .put("size", if (has("big", "large", "huge")) 64 else 54).put("color", c.fg).put("font", if (has("bold")) "bold" else "light"))
            name = "Clock"
        }
        countdown != null -> {
            top.text("Days to ${countdown.first}", 13, c.muted)
            main.big("{daysUntil:${countdown.second}}", 56)
            name = "Countdown"
        }
        has("year") -> { top.text("${LocalDate.now().year}", 13, c.muted); main.big("{yearProgress}%", 52); main.bar("yearProgress"); name = "Year" }
        has("day progress") -> { top.text("Today", 13, c.muted); main.big("{dayProgress}%", 52); main.bar("dayProgress"); name = "Day" }
        has("battery", "charge") -> { top.text("Battery", 13, c.muted); main.big("{battery}%", 52); main.bar("battery"); name = "Battery" }
        has("date", "calendar") -> { top.text("{date:MMMM}", 13, c.muted); main.big("{date:d}", 60); main.text("{date:EEEE}", 14, c.muted); name = "Date" }
    }
    Regex("""["“](.+?)["”]""").find(prompt)?.let { main.text(it.groupValues[1], 15, c.fg) }

    // A quiet footer: accent dot plus battery, unless battery is already the main figure.
    val footer = JSONArray()
    if (wantsClock && has("battery", "charge")) {
        footer.text("●", 9, c.accent); footer.text("{battery}%", 12, c.muted)
    }

    val center = has("center", "centered", "middle")
    val h = if (center) "center" else "start"
    val children = JSONArray()
    (0 until top.length()).forEach { children.put(top.get(it)) }
    children.put(JSONObject().put("type", "spacer").put("size", 0).put("weight", true))
    (0 until main.length()).forEach { children.put(main.get(it)) }
    if (footer.length() > 0) children.put(JSONObject().put("type", "row").put("vAlign", "center").put("gap", 6).put("children", footer))
    return JSONObject()
        .put("name", name)
        .put("bg", JSONArray(if (has("gradient")) listOf(c.bg, c.bg2) else listOf(c.bg)))
        .put("radius", 30)
        .put("padding", 20)
        .put("root", JSONObject().put("type", "column").put("hAlign", h).put("vAlign", "top").put("gap", 4).put("children", children))
}

private class Palette(val bg: String, val bg2: String, val fg: String, val muted: String, val accent: String, val track: String)

/** One solid, low-saturation tone per mood with a single accent color. */
private fun palette(p: String): Palette {
    val dark = { bg: String, bg2: String, accent: String -> Palette(bg, bg2, "#F2F2F0", "#8E8E93", accent, "#26FFFFFF") }
    val moods = listOf(
        listOf("wallpaper", "material", "dynamic", "system") to Palette("@surface", "@accentDark", "@onSurface", "@accent2", "@accent", "#26FFFFFF"),
        listOf("light", "white", "clean", "paper") to Palette("#F4F3EF", "#E7E5DF", "#161616", "#8A8A86", "#161616", "#14000000"),
        listOf("sunset", "sunrise", "orange", "warm") to dark("#1C1613", "#3A2318", "#FF8F5E"),
        listOf("ocean", "sea", "blue", "sky") to dark("#0F1620", "#16283D", "#7DB7FF"),
        listOf("festive", "christmas", "new year", "party", "gold") to dark("#141414", "#2A2416", "#E3C27A"),
        listOf("purple", "violet", "galaxy", "space") to dark("#14121C", "#251E3A", "#B9A4FF"),
        listOf("green", "forest", "nature", "mint") to dark("#101714", "#1A2E24", "#86D9AE"),
        listOf("pink", "rose", "love", "valentine") to dark("#1A1216", "#33202A", "#FF9DBF"),
        listOf("neon", "cyber") to dark("#0B0B0C", "#14201E", "#4DFFD2"),
    )
    return moods.firstOrNull { (words, _) -> words.any { p.contains(it) } }?.second
        ?: dark("#111113", "#1E1E22", "@accent")
}

/** Finds a countdown in the prompt: an ISO date, a "25 dec"-style date, or a known holiday. */
private fun countdownTarget(p: String): Pair<String, String>? {
    if (!listOf("countdown", "until", "days to", "days left", "count down").any { p.contains(it) }) return null
    val today = LocalDate.now()
    fun next(md: MonthDay) = md.atYear(today.year).let { if (it.isBefore(today)) it.plusYears(1) else it }
    Regex("""\d{4}-\d{2}-\d{2}""").find(p)?.let { return "the day" to it.value }
    val holidays = mapOf(
        "new year" to MonthDay.of(1, 1), "christmas" to MonthDay.of(12, 25),
        "valentine" to MonthDay.of(2, 14), "halloween" to MonthDay.of(10, 31),
    )
    holidays.entries.firstOrNull { p.contains(it.key) }?.let { (k, md) -> return k.replaceFirstChar { it.uppercase() } to next(md).toString() }
    val months = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    Regex("""(\d{1,2})\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)""").find(p)?.let { m ->
        val md = runCatching { MonthDay.parse("${m.groupValues[1]} ${m.groupValues[2].replaceFirstChar { it.uppercase() }}", months) }.getOrNull()
        if (md != null) return "the day" to next(md).toString()
    }
    return "New Year" to next(MonthDay.of(1, 1)).toString()
}
