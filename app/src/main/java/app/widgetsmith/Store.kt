package app.widgetsmith

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Saved designs, the API key, and which design each placed widget shows. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context) = prefs(ctx).getString("apiKey", "").orEmpty()
    fun setApiKey(ctx: Context, k: String) = prefs(ctx).edit().putString("apiKey", k.trim()).apply()

    fun designs(ctx: Context): List<Design> {
        val arr = JSONArray(prefs(ctx).getString("designs", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Design(it.getString("id"), it.optString("prompt"), it.getJSONObject("spec")) }
    }

    fun design(ctx: Context, id: String?) = designs(ctx).firstOrNull { it.id == id }

    /** Inserts or replaces a design and returns it; newest first. */
    fun save(ctx: Context, prompt: String, spec: JSONObject, id: String = UUID.randomUUID().toString()): Design {
        val d = Design(id, prompt, spec)
        val rest = designs(ctx).filter { it.id != id }
        write(ctx, listOf(d) + rest)
        return d
    }

    fun delete(ctx: Context, id: String) = write(ctx, designs(ctx).filter { it.id != id })

    private fun write(ctx: Context, list: List<Design>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("prompt", it.prompt).put("spec", it.json)) }
        prefs(ctx).edit().putString("designs", arr.toString()).apply()
    }

    fun assign(ctx: Context, appWidgetId: Int, designId: String) =
        prefs(ctx).edit().putString("w$appWidgetId", designId).apply()

    /** The design shown by a widget; an unassigned widget shows the newest design. */
    fun designFor(ctx: Context, appWidgetId: Int): Design? =
        design(ctx, prefs(ctx).getString("w$appWidgetId", null)) ?: designs(ctx).firstOrNull()
}
