package app.widgetsmith

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** In-app preview that mirrors the Glance renderer in Widget.kt. */
@Composable
fun DesignPreview(d: Design, modifier: Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val bg = d.bg.map { Color(resolveColor(ctx, it)) }
    val shape = RoundedCornerShape(d.radius.dp)
    val brush = if (bg.size >= 2) Brush.linearGradient(bg) else Brush.linearGradient(listOf(bg.first(), bg.first()))
    key(tick) {
        Box(modifier.clip(shape).background(brush).padding(d.padding.dp)) { CNode(d.root, Modifier.fillMaxSize()) }
    }
}

private fun hA(s: String) = when (s) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
private fun vA(s: String) = when (s) { "center" -> Alignment.CenterVertically; "bottom" -> Alignment.Bottom; else -> Alignment.Top }

@Composable
private fun CNode(n: Node, mod: Modifier) {
    val ctx = LocalContext.current
    when (n) {
        is Node.Group -> {
            var m = mod
            if (n.radius > 0) m = m.clip(RoundedCornerShape(n.radius.dp))
            n.bg?.let { m = m.background(Color(resolveColor(ctx, it))) }
            m = m.padding(n.padding.dp)
            when (n.kind) {
                "row" -> Row(m, horizontalArrangement = Arrangement.spacedBy(n.gap.dp, when (n.hAlign) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }), verticalAlignment = vA(n.vAlign)) {
                    n.children.forEach { CNode(it, if (it.weight) Modifier.weight(1f) else Modifier) }
                }
                "box" -> Box(m, contentAlignment = when (n.vAlign to n.hAlign) {
                    "center" to "center" -> Alignment.Center; "top" to "center" -> Alignment.TopCenter; "bottom" to "center" -> Alignment.BottomCenter
                    "center" to "end" -> Alignment.CenterEnd; "top" to "end" -> Alignment.TopEnd; "bottom" to "end" -> Alignment.BottomEnd
                    "center" to "start" -> Alignment.CenterStart; "bottom" to "start" -> Alignment.BottomStart; else -> Alignment.TopStart
                }) { n.children.forEach { CNode(it, Modifier) } }
                else -> Column(m, verticalArrangement = Arrangement.spacedBy(n.gap.dp, when (n.vAlign) { "center" -> Alignment.CenterVertically; "bottom" -> Alignment.Bottom; else -> Alignment.Top }), horizontalAlignment = hA(n.hAlign)) {
                    n.children.forEach { CNode(it, when { it.weight -> Modifier.weight(1f).fillMaxWidth(); it is Node.Progress -> Modifier.fillMaxWidth(); else -> Modifier }) }
                }
            }
        }
        is Node.Text -> Text(
            resolveText(ctx, n.text), mod, color = Color(resolveColor(ctx, n.color)), fontSize = n.size.sp,
            fontWeight = fw(n.font), lineHeight = (n.size * 1.1f).sp, letterSpacing = if (n.font == "light") (-0.02f * n.size).sp else 0.sp, maxLines = n.maxLines, overflow = TextOverflow.Ellipsis,
            textAlign = when (n.align) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start },
        )
        is Node.Clock -> Text(
            runCatching { LocalDateTime.now().format(DateTimeFormatter.ofPattern(n.format)) }.getOrDefault("--:--"), mod,
            color = Color(resolveColor(ctx, n.color)), fontSize = n.size.sp, lineHeight = n.size.sp,
            fontWeight = fw(n.font), letterSpacing = (-0.02f * n.size).sp,
        )
        is Node.Progress -> LinearProgressIndicator(
            progress = { resolveProgress(ctx, n.value) }, modifier = mod.height(4.dp),
            color = Color(resolveColor(ctx, n.color)), trackColor = Color(resolveColor(ctx, n.track)), gapSize = 0.dp, drawStopIndicator = {},
        )
        is Node.Space -> Spacer(if (n.weight) mod else mod.size(n.size.dp))
    }
}

private fun fw(font: String) = when (font) { "bold" -> FontWeight.Bold; "light" -> FontWeight.Light; else -> FontWeight.Normal }
