package app.widgetsmith

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch

private val EXAMPLES = listOf(
    "Minimal clock with date and battery, wallpaper colors",
    "Countdown to New Year with a festive gradient",
    "Year progress bar, dark glassy style",
    "Morning greeting with a big bold clock and sunrise gradient",
    "Battery widget with a big percentage and progress bar",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scheduleRefresh(this)
        setContent {
            val ctx = LocalContext.current
            val dark = dynamicDarkColorScheme(ctx)
            MaterialTheme(colorScheme = dark) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var designs by remember { mutableStateOf(Store.designs(ctx)) }
    var selected by remember { mutableStateOf(designs.firstOrNull()) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var size by remember { mutableIntStateOf(1) }

    fun run(refine: Boolean) {
        if (prompt.isBlank() || busy) return
        busy = true; error = null; status = "Designing your widget…"
        scope.launch {
            try {
                val cur = selected?.takeIf { refine }
                val spec = generateDesign(ctx, prompt, cur?.prompt, cur?.json) { status = it }
                val d = if (cur != null) Store.save(ctx, cur.prompt + " → " + prompt, spec, cur.id) else Store.save(ctx, prompt, spec)
                designs = Store.designs(ctx); selected = d; prompt = ""
                AiWidget().updateAll(ctx)
            } catch (e: AiException) { error = e.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WidgetSmith AI", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Describe a widget. AI builds it for your home screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showKey = true }) { Icon(Icons.Default.Key, "API key") }
        }
        Spacer(Modifier.height(20.dp))

        selected?.let { d ->
            val (w, h) = when (size) { 0 -> 170 to 170; 2 -> 340 to 340; else -> 340 to 170 }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DesignPreview(d, Modifier.size(w.dp, h.dp))
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("2×2", "4×2", "4×4").forEachIndexed { i, l ->
                    SegmentedButton(size == i, { size = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(d.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pinWidget(ctx, d) }, Modifier.weight(1f)) { Text("Add to Home screen") }
                OutlinedButton(onClick = {
                    Store.delete(ctx, d.id); designs = Store.designs(ctx); selected = designs.firstOrNull()
                    scope.launch { AiWidget().updateAll(ctx) }
                }) { Icon(Icons.Default.Delete, "Delete") }
            }
            Spacer(Modifier.height(24.dp))
        }

        OutlinedTextField(
            prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(20.dp),
            placeholder = { Text(if (selected == null) "e.g. A purple clock with today's date and battery" else "Describe a new widget, or how to change this one") },
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EXAMPLES) { SuggestionChip(onClick = { prompt = it }, label = { Text(it, maxLines = 1) }) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run(false) }, Modifier.weight(1f), enabled = !busy && prompt.isNotBlank()) {
                Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Generate new")
            }
            if (selected != null) FilledTonalButton(onClick = { run(true) }, Modifier.weight(1f), enabled = !busy && prompt.isNotBlank()) { Text("Refine this") }
        }
        if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status, fontSize = 13.sp) }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }

        if (designs.size > 1) {
            Spacer(Modifier.height(28.dp))
            Text("Your widgets", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(designs, key = { it.id }) { d ->
                    Column(Modifier.width(120.dp).clickable { selected = d }) {
                        DesignPreview(d, Modifier.size(120.dp))
                        Text(d.name, fontSize = 12.sp, maxLines = 1, fontWeight = if (d.id == selected?.id) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }

    if (showKey) KeyDialog { showKey = false }
}

@Composable
private fun KeyDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Store.apiKey(ctx)) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Claude API key (optional)") },
        text = {
            Column {
                Text("Widgets are designed for free by Gemini Nano on your phone. Optionally paste an Anthropic key for richer designs from Claude (paid). Leave empty to stay free.", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-ant-…") })
            }
        },
        confirmButton = { TextButton(onClick = { Store.setApiKey(ctx, key); close() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** Asks One UI to place the widget; PinnedReceiver links it to [d] once the user accepts. */
private fun pinWidget(ctx: Context, d: Design) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (!mgr.isRequestPinAppWidgetSupported) {
        Toast.makeText(ctx, "Long-press the home screen → Widgets → WidgetSmith AI", Toast.LENGTH_LONG).show(); return
    }
    val cb = PendingIntent.getBroadcast(
        ctx, d.id.hashCode(), Intent(ctx, PinnedReceiver::class.java).putExtra("design", d.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    mgr.requestPinAppWidget(ComponentName(ctx, AiWidgetReceiver::class.java), null, cb)
}
