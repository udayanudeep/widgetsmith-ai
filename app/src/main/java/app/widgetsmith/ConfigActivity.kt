package app.widgetsmith

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Lets the user pick (or change, via long-press → Settings) which design a widget shows. */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        val designs = Store.designs(this)
        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(20.dp)) {
                        Text("Choose a widget", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        if (designs.isEmpty()) {
                            Text("No designs yet. Create one first.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { startActivity(Intent(this@ConfigActivity, MainActivity::class.java)); finish() }) { Text("Open WidgetSmith AI") }
                        }
                        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(designs) { d ->
                                Column(Modifier.clickable { pick(wid, d.id) }) {
                                    DesignPreview(d, Modifier.fillMaxWidth().aspectRatio(1f))
                                    Text(d.name, fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(wid: Int, designId: String) {
        Store.assign(this, wid, designId)
        lifecycleScope.launch {
            AiWidget().update(this@ConfigActivity, GlanceAppWidgetManager(this@ConfigActivity).getGlanceIdBy(wid))
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
            finish()
        }
    }
}
