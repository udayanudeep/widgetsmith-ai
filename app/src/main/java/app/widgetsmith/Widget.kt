package app.widgetsmith

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AiWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val design = Store.designFor(context, appWidgetId)
        provideContent { WidgetContent(design) }
    }
}

@Composable
private fun WidgetContent(d: Design?) {
    val ctx = LocalContext.current
    val open = GlanceModifier.clickable(actionStartActivity<MainActivity>())
    if (d == null) {
        Box(open.fillMaxSize().background(Color(0xFF1C1B1F)).cornerRadius(28.dp).padding(16.dp), contentAlignment = Alignment.Center) {
            androidx.glance.text.Text("Tap to create a widget with AI", style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, textAlign = TextAlign.Center))
        }
        return
    }
    val bg = d.bg.map { resolveColor(ctx, it) }
    val base = open.fillMaxSize().cornerRadius(d.radius.dp)
    val withBg = if (bg.size >= 2) base.background(ImageProvider(gradient(bg)), ContentScale.FillBounds) else base.background(Color(bg.first()))
    Box(withBg.padding(d.padding.dp)) { GNode(d.root, GlanceModifier.fillMaxSize()) }
}

private fun gradient(colors: List<Int>): Bitmap {
    val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    Canvas(b).drawRect(0f, 0f, 256f, 256f, Paint().apply {
        shader = LinearGradient(0f, 0f, 256f, 256f, colors.toIntArray(), null, Shader.TileMode.CLAMP)
    })
    return b
}

private fun hAlign(s: String) = when (s) { "center" -> Alignment.Horizontal.CenterHorizontally; "end" -> Alignment.Horizontal.End; else -> Alignment.Horizontal.Start }
private fun vAlign(s: String) = when (s) { "center" -> Alignment.Vertical.CenterVertically; "bottom" -> Alignment.Vertical.Bottom; else -> Alignment.Vertical.Top }

@Composable
private fun GNode(n: Node, mod: GlanceModifier) {
    val ctx = LocalContext.current
    when (n) {
        is Node.Group -> {
            var m = mod
            n.bg?.let { m = m.background(Color(resolveColor(ctx, it))) }
            if (n.radius > 0) m = m.cornerRadius(n.radius.dp)
            m = m.padding(n.padding.dp)
            when (n.kind) {
                "row" -> Row(m, horizontalAlignment = hAlign(n.hAlign), verticalAlignment = vAlign(n.vAlign)) {
                    n.children.forEachIndexed { i, c ->
                        if (i > 0 && n.gap > 0) Spacer(GlanceModifier.width(n.gap.dp))
                        GNode(c, rowMod(c))
                    }
                }
                "box" -> Box(m, contentAlignment = Alignment(hAlign(n.hAlign), vAlign(n.vAlign))) {
                    n.children.forEach { GNode(it, GlanceModifier) }
                }
                else -> Column(m, horizontalAlignment = hAlign(n.hAlign), verticalAlignment = vAlign(n.vAlign)) {
                    n.children.forEachIndexed { i, c ->
                        if (i > 0 && n.gap > 0) Spacer(GlanceModifier.height(n.gap.dp))
                        GNode(c, colMod(c))
                    }
                }
            }
        }
        is Node.Text -> if (n.font == "light") AndroidRemoteViews(
            RemoteViews(ctx.packageName, R.layout.text_light).apply {
                setTextViewText(R.id.text, resolveText(ctx, n.text))
                setTextColor(R.id.text, resolveColor(ctx, n.color))
                setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_SP, n.size.toFloat())
            },
            mod,
        ) else androidx.glance.text.Text(
            resolveText(ctx, n.text), mod, maxLines = n.maxLines,
            style = TextStyle(
                color = ColorProvider(Color(resolveColor(ctx, n.color))), fontSize = n.size.sp,
                fontWeight = if (n.font == "bold") FontWeight.Bold else FontWeight.Normal,
                textAlign = when (n.align) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start },
            ),
        )
        is Node.Clock -> AndroidRemoteViews(
            RemoteViews(ctx.packageName, when (n.font) { "bold" -> R.layout.clock_bold; "regular" -> R.layout.clock; else -> R.layout.clock_light }).apply {
                setCharSequence(R.id.clock, "setFormat24Hour", n.format)
                setCharSequence(R.id.clock, "setFormat12Hour", n.format)
                setTextColor(R.id.clock, resolveColor(ctx, n.color))
                setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_SP, n.size.toFloat())
            },
            mod,
        )
        is Node.Progress -> LinearProgressIndicator(
            resolveProgress(ctx, n.value), mod.height(4.dp),
            color = ColorProvider(Color(resolveColor(ctx, n.color))),
            backgroundColor = ColorProvider(Color(resolveColor(ctx, n.track))),
        )
        is Node.Space -> Spacer(if (n.weight) mod else mod.height(n.size.dp).width(n.size.dp))
    }
}

@Composable private fun RowScope.rowMod(c: Node) = if (c.weight) GlanceModifier.defaultWeight() else GlanceModifier
@Composable private fun ColumnScope.colMod(c: Node) = when {
    c.weight -> GlanceModifier.defaultWeight().fillMaxWidth()
    c is Node.Progress -> GlanceModifier.fillMaxWidth()
    else -> GlanceModifier
}

class AiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = AiWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); scheduleRefresh(context) }
}

/** Called by One UI after the user accepts a "pin widget" request; links the new widget to its design. */
class PinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val design = intent.getStringExtra("design") ?: return
        if (wid == -1) return
        Store.assign(context, wid, design)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { AiWidget().updateAll(context) } finally { pending.finish() }
        }
    }
}

/** Keeps tokens like {battery} and {date} fresh; clocks tick on their own. */
class RefreshWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result { AiWidget().updateAll(applicationContext); return Result.success() }
}

fun scheduleRefresh(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
    "refresh", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build(),
)
