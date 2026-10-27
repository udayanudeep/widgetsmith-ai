package app.widgetsmith

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

class AiException(msg: String) : Exception(msg)

private fun system() = """You design Android home-screen widgets for a Samsung Galaxy S25 Ultra running One UI 9.
Reply with ONE JSON object only — no prose, no code fences. Today is ${LocalDate.now()}.

Schema:
{
  "name": "short title",
  "bg": ["#RRGGBB"] or ["#RRGGBB","#RRGGBB"] (two colors = diagonal gradient),
  "radius": corner radius dp (One UI uses ~26-32),
  "padding": dp,
  "root": NODE
}
NODE is one of:
  {"type":"column"|"row"|"box", "children":[NODE...], "hAlign":"start|center|end", "vAlign":"top|center|bottom",
   "gap":dp, "bg":"#AARRGGBB" (optional card), "radius":dp, "padding":dp, "weight":true (fill remaining space)}
  {"type":"text", "text":"...", "size":sp, "color":"#...", "font":"light|regular|bold", "align":"start|center|end", "maxLines":n}
  {"type":"clock", "format":"HH:mm" | "h:mm" | "EEEE" etc (live-updating TextClock), "size":sp, "color":"#...", "font":"light|regular|bold"}
  {"type":"progress", "value":"battery|dayProgress|weekProgress|monthProgress|yearProgress" or 0..1, "color":"#...", "track":"#AARRGGBB"}
  {"type":"spacer", "size":dp, "weight":true}
Any NODE may have "weight":true. Text may contain live tokens: {battery} {date:EEE d MMM} {daysUntil:YYYY-MM-DD}
{dayProgress} {weekProgress} {monthProgress} {yearProgress} {greeting} (percent tokens give a number 0-100).
Colors may also be Material You tokens that follow the user's One UI wallpaper palette:
@accent @accentDark @accent2 @accent3 @surface @surfaceLight @onSurface @onSurfaceDark.

Style: ultra-modern and minimal, like Nothing OS or iOS widgets. One solid dark or soft neutral background
(no gradient unless asked), lots of empty space, at most 3-4 elements, thin "font":"light" numerals for the
hero figure, small muted grey labels (12-13sp), one restrained accent color, no emoji, no borders,
no cards inside cards. Put labels at the top and the hero figure at the bottom using a {"type":"spacer","weight":true} between.
Rules: at most 10 children per container, at most 4 levels deep. 
Information that cannot be live (weather, news, steps) should be presented as static, decorative content
only if the user asks; otherwise prefer live tokens. """

/** Uses the free on-device Gemini Nano, or Claude when the user has added an API key. */
suspend fun generateDesign(ctx: Context, prompt: String, basePrompt: String?, current: JSONObject?, onStatus: (String) -> Unit): JSONObject {
    val key = Store.apiKey(ctx)
    return if (key.isBlank()) generateOnDevice(prompt, basePrompt, current, onStatus) else generateClaude(key, prompt, current)
}

private const val NANO_SYSTEM = """You output JSON for an Android home-screen widget. Output ONLY the JSON object.
Schema: {"name":str,"bg":[color] or [color,color],"radius":28,"padding":16,"root":NODE}
NODE types:
{"type":"column"|"row","children":[NODE],"hAlign":"start|center|end","vAlign":"top|center|bottom","gap":int,"bg":color,"radius":int,"padding":int}
{"type":"text","text":str,"size":int,"color":color,"font":"light|regular|bold","align":"start|center|end"}
{"type":"clock","format":"HH:mm","size":int,"color":color,"font":"light|regular|bold"}
{"type":"spacer","weight":true} pushes following items to the bottom.
{"type":"progress","value":"battery|dayProgress|yearProgress","color":color,"track":"#33FFFFFF"}
{"type":"spacer","size":int}
color is "#RRGGBB" or "@accent","@surface","@onSurface".
Text tokens: {battery} {date:EEE d MMM} {daysUntil:YYYY-MM-DD} {yearProgress} {greeting}.
Style: minimal and modern. One solid dark background, thin light numerals, small grey labels, one accent, no emoji, no gradients.
Example: {"name":"Clock","bg":["#111113"],"radius":30,"padding":20,"root":{"type":"column","hAlign":"start","vAlign":"top","gap":4,"children":[{"type":"text","text":"{date:EEE  d MMM}","size":13,"color":"#8E8E93"},{"type":"spacer","weight":true},{"type":"clock","format":"HH:mm","size":54,"color":"#F2F2F0","font":"light"},{"type":"text","text":"{battery}%","size":12,"color":"#8E8E93"}]}}"""

private val downloadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
private var downloading = false

/** Lets AICore fetch Gemini Nano in the background; until then the offline designer is used. */
private fun startModelDownload() {
    if (downloading) return
    downloading = true
    downloadScope.launch {
        runCatching { Generation.getClient().download().collect {} }
        downloading = false
    }
}

private suspend fun generateOnDevice(prompt: String, basePrompt: String?, current: JSONObject?, onStatus: (String) -> Unit): JSONObject {
    val model = Generation.getClient()
    try {
        if (model.checkStatus() != FeatureStatus.AVAILABLE) {
            startModelDownload()
            return designOffline(basePrompt?.let { "$it $prompt" } ?: prompt)
        }
        onStatus("Designing on your phone…")
        val user = if (current == null) "Widget request: $prompt" else "Current widget JSON: $current\nChange: $prompt\nReturn the full updated JSON."
        var last: Exception? = null
        repeat(3) { attempt ->
            val req = generateContentRequest(SystemInstruction(NANO_SYSTEM), TextPart(user)) {
                temperature = if (attempt == 0) 0.4f else 0.8f
                maxOutputTokens = 256 * 4
            }
            val text = model.generateContent(req).candidates.firstOrNull()?.text.orEmpty()
            try { return parseSpec(text) } catch (e: AiException) { last = e }
        }
        throw last ?: AiException("The AI didn't return a widget. Try again.")
    } catch (e: GenAiException) {
        return designOffline(basePrompt?.let { "$it $prompt" } ?: prompt)
    } finally { model.close() }
}

private fun parseSpec(text: String): JSONObject {
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw AiException("The AI didn't return a widget. Try again.")
    return try { JSONObject(text.substring(start, end + 1)).also { it.getJSONObject("root") } }
    catch (e: org.json.JSONException) { throw AiException("The AI returned an invalid design. Try again.") }
}

private suspend fun generateClaude(apiKey: String, prompt: String, current: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    val user = if (current == null) "Design this widget: $prompt"
    else "Here is the current widget JSON:\n$current\n\nChange it as follows and return the full updated JSON: $prompt"
    val params = MessageCreateParams.builder()
        .model("claude-opus-5-5")
        .maxTokens(8000L)
        .system(system())
        .addUserMessage(user)
        .addBeta("server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .build()
    try {
        val res = client.beta().messages().create(params)
        if (res.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw AiException("The AI declined that prompt. Try describing a different widget.")
        val text = res.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        parseSpec(text)
    } catch (e: AiException) { throw e
    } catch (e: UnauthorizedException) { throw AiException("That API key wasn't accepted.")
    } catch (e: RateLimitException) { throw AiException("Too many requests — try again in a moment.")
    } catch (e: AnthropicServiceException) { throw AiException("AI unavailable (${e.statusCode()}). Try again later.")
    } catch (e: Exception) { throw AiException("Couldn't reach the AI. Check your internet connection.")
    } finally { client.close() }
}
