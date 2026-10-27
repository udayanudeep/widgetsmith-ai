package app.widgetsmith

import android.content.Context
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A widget design produced by the AI. Kept as the raw JSON so it can be stored and refined. */
class Design(val id: String, val prompt: String, val json: JSONObject) {
    val name: String get() = json.optString("name", "AI Widget")
    val bg: List<String> get() = json.optJSONArray("bg")?.strings() ?: listOf(json.optString("bg", "@surface"))
    val radius: Int get() = json.optInt("radius", 28)
    val padding: Int get() = json.optInt("padding", 16)
    val root: Node get() = Node.parse(json.optJSONObject("root") ?: JSONObject("""{"type":"text","text":"Empty"}"""))
}

sealed class Node {
    abstract val weight: Boolean

    data class Group(
        val kind: String, val children: List<Node>, val hAlign: String, val vAlign: String,
        val gap: Int, val bg: String?, val radius: Int, val padding: Int, override val weight: Boolean,
    ) : Node()

    data class Text(
        val text: String, val size: Int, val color: String, val font: String,
        val align: String, val maxLines: Int, override val weight: Boolean,
    ) : Node()

    data class Clock(val format: String, val size: Int, val color: String, val font: String, override val weight: Boolean) : Node()
    data class Progress(val value: String, val color: String, val track: String, override val weight: Boolean) : Node()
    data class Space(val size: Int, override val weight: Boolean) : Node()

    companion object {
        fun parse(o: JSONObject): Node {
            val w = o.optBoolean("weight", false)
            return when (o.optString("type")) {
                "column", "row", "box" -> Group(
                    o.getString("type"),
                    (o.optJSONArray("children") ?: JSONArray()).objects().take(10).map(::parse),
                    o.optString("hAlign", "start"), o.optString("vAlign", "top"),
                    o.optInt("gap", 0), o.optString("bg").ifBlank { null },
                    o.optInt("radius", 0), o.optInt("padding", 0), w,
                )
                "clock" -> Clock(o.optString("format", "HH:mm"), o.optInt("size", 40), o.optString("color", "@onSurface"), font(o, "light"), w)
                "progress" -> Progress(o.optString("value", "0.5"), o.optString("color", "@accent"), o.optString("track", "#33FFFFFF"), w)
                "spacer" -> Space(o.optInt("size", 8), w)
                else -> Text(
                    o.optString("text"), o.optInt("size", 14), o.optString("color", "@onSurface"),
                    font(o, "regular"), o.optString("align", "start"), o.optInt("maxLines", 3), w,
                )
            }
        }
    }
}

/** "font": "light" | "regular" | "bold"; the older "bold": true still works. */
private fun font(o: JSONObject, default: String) =
    o.optString("font").ifBlank { if (o.has("bold")) (if (o.optBoolean("bold")) "bold" else "regular") else default }

private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

/** Colors: "#RRGGBB", "#AARRGGBB", or One UI / Material You system tokens like "@accent". */
fun resolveColor(ctx: Context, c: String?): Int {
    val s = c?.trim().orEmpty()
    val sys = mapOf(
        "@accent" to android.R.color.system_accent1_300,
        "@accentDark" to android.R.color.system_accent1_700,
        "@accent2" to android.R.color.system_accent2_300,
        "@accent3" to android.R.color.system_accent3_300,
        "@surface" to android.R.color.system_neutral1_900,
        "@surfaceLight" to android.R.color.system_neutral1_50,
        "@onSurface" to android.R.color.system_neutral1_50,
        "@onSurfaceDark" to android.R.color.system_neutral1_900,
    )
    sys[s]?.let { return ctx.getColor(it) }
    return runCatching { android.graphics.Color.parseColor(s) }.getOrDefault(android.graphics.Color.WHITE)
}

private val TOKEN = Regex("""\{(\w+)(?::([^}]*))?\}""")

/** Replaces live tokens such as {battery}, {date:EEE d MMM} or {daysUntil:2026-12-25}. */
fun resolveText(ctx: Context, text: String): String = TOKEN.replace(text) { m ->
    val arg = m.groupValues[2]
    val now = LocalDateTime.now()
    when (m.groupValues[1]) {
        "battery" -> battery(ctx).toString()
        "date" -> now.format(DateTimeFormatter.ofPattern(arg.ifBlank { "EEE, d MMM" }, Locale.getDefault()))
        "time" -> now.format(DateTimeFormatter.ofPattern(arg.ifBlank { "HH:mm" }))
        "daysUntil" -> runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(arg)).toString() }.getOrDefault("?")
        "dayProgress" -> pct(dayProgress()).toString()
        "weekProgress" -> pct(weekProgress()).toString()
        "monthProgress" -> pct(monthProgress()).toString()
        "yearProgress" -> pct(yearProgress()).toString()
        "greeting" -> when (now.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Good night" }
        else -> m.value
    }
}

/** Progress bar values: a number 0..1 or a token name like "battery" / "yearProgress". */
fun resolveProgress(ctx: Context, v: String): Float = when (v.trim('{', '}', ' ')) {
    "battery" -> battery(ctx) / 100f
    "dayProgress" -> dayProgress()
    "weekProgress" -> weekProgress()
    "monthProgress" -> monthProgress()
    "yearProgress" -> yearProgress()
    else -> v.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
}

private fun battery(ctx: Context) =
    (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

private fun pct(f: Float) = (f * 100).toInt()
private fun dayProgress() = LocalDateTime.now().let { (it.hour * 60 + it.minute) / 1440f }
private fun weekProgress() = LocalDateTime.now().let { ((it.dayOfWeek.value - 1) * 24 + it.hour) / 168f }
private fun monthProgress() = LocalDate.now().let { (it.dayOfMonth - 1) / it.lengthOfMonth().toFloat() }
private fun yearProgress() = LocalDate.now().let { (it.dayOfYear - 1) / it.lengthOfYear().toFloat() }
