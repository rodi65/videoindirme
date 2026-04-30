package com.rodi.videoindirme

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rodi.videoindirme.ui.theme.VideoIndirmeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1
                )
            }
        }
        setContent {
            VideoIndirmeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

object InstanceManager {
    val DEFAULT_INSTANCES = listOf(
        "https://cobalt-api-production-89fa.up.railway.app",
        "https://cobalt-backend.canine.tools",
        "https://dwnld.nichinichi.moe",
        "https://co.eepy.today",
        "https://capi.oodu.uk"
    )
    private const val PREFS = "videoindirme_prefs"
    private const val KEY_CUSTOM = "custom_instance"

    fun getCustomInstance(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, "") ?: ""

    fun setCustomInstance(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM, url.trim().trimEnd('/')).apply()
    }

    fun getActiveInstances(ctx: Context): List<String> {
        val custom = getCustomInstance(ctx)
        return if (custom.isNotBlank()) listOf(custom) + DEFAULT_INSTANCES else DEFAULT_INSTANCES
    }
}

data class CobaltResult(
    val success: Boolean,
    val downloadUrl: String? = null,
    val filename: String? = null,
    val errorMessage: String? = null,
    val usedInstance: String? = null
)

object CobaltClient {
    suspend fun fetchDownload(
        instances: List<String>,
        videoUrl: String,
        quality: String,
        audioOnly: Boolean
    ): CobaltResult = withContext(Dispatchers.IO) {
        var lastError = "Hicbir sunucu yanit vermedi"
        for (instance in instances) {
            try {
                val payload = JSONObject().apply {
                    put("url", videoUrl)
                    if (audioOnly) {
                        put("downloadMode", "audio")
                        put("audioFormat", "mp3")
                    } else {
                        put("downloadMode", "auto")
                        put("videoQuality", quality)
                    }
                    put("filenameStyle", "pretty")
                }
                val conn = (URL(instance).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "VideoIndirme/1.0")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 15000
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    lastError = "Sunucu hatasi ($code)"
                    continue
                }
                val json = JSONObject(response)
                val status = json.optString("status")
                when (status) {
                    "tunnel", "redirect" -> {
                        val url = json.optString("url")
                        val filename = json.optString("filename", "video.mp4")
                        if (url.isNotBlank()) {
                            return@withContext CobaltResult(true, url, filename, null, instance)
                        }
                        lastError = "URL bos dondu"
                    }
                    "error" -> {
                        val errCode = json.optJSONObject("error")?.optString("code") ?: "unknown"
                        lastError = "Hata: $errCode"
                    }
                    "picker" -> {
                        val items = json.optJSONArray("picker")
                        if (items != null && items.length() > 0) {
                            val first = items.getJSONObject(0)
                            val url = first.optString("url")
                            if (url.isNotBlank()) {
                                return@withContext CobaltResult(true, url, "video.mp4", null, instance)
                            }
                        }
                        lastError = "Picker bos"
                    }
                    else -> lastError = "Bilinmeyen yanit"
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "baglanti hatasi"}"
            }
        }
        CobaltResult(success = false, errorMessage = lastError)
    }
}

fun startDownload(context: Context, url: String, filename: String) {
    try {
        val safe = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "video_${System.currentTimeMillis()}.mp4" }
        val req = android.app.DownloadManager.Request(Uri.parse(url))
        req.setTitle(safe)
        req.setDescription("VideoSaver")
        req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
        req.allowScanningByMediaScanner()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        dm.enqueue(req)
        Toast.makeText(context, "Indirme basladi: $safe", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Indirme baslatilamadi: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VideoSaver", fontWeight = FontWeight.Bold)
                        Text("YouTube · TikTok · Instagram", fontSize = 12.sp, color = Color.Gray)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            if (showSettings) Icons.Filled.Close else Icons.Filled.Settings,
                            contentDescription = "Ayarlar"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F14))
            )
        },
        containerColor = Color(0xFF0A0A0F)
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (showSettings) SettingsScreen(onClose = { showSettings = false }) else MainScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("720") }
    var audioOnly by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("VIDEO LINKI", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("youtube.com / tiktok.com / inst...", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF333344),
                            focusedTextColor = Color(0xFF8B5CF6),
                            unfocusedTextColor = Color(0xFF8B5CF6)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { url = it.trim() }
                    }) {
                        Icon(Icons.Filled.ContentPaste, "Yapistir", tint = Color.White)
                    }
                    IconButton(onClick = { url = "" }) {
                        Icon(Icons.Filled.Close, "Temizle", tint = Color.White)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("KALITE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityChip("\uD83C\uDFAF 1080p", quality == "1080" && !audioOnly) {
                        quality = "1080"; audioOnly = false
                    }
                    QualityChip("\u2728 720p", quality == "720" && !audioOnly) {
                        quality = "720"; audioOnly = false
                    }
                    QualityChip("\u26A1 480p", quality == "480" && !audioOnly) {
                        quality = "480"; audioOnly = false
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                QualityChip("\uD83C\uDFB5 MP3", audioOnly) { audioOnly = true }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (url.isBlank()) {
                    statusMessage = "Lutfen bir video linki girin"
                    isError = true
                    return@Button
                }
                isLoading = true
                statusMessage = null
                isError = false
                scope.launch {
                    val instances = InstanceManager.getActiveInstances(context)
                    val result = CobaltClient.fetchDownload(instances, url.trim(), quality, audioOnly)
                    isLoading = false
                    if (result.success && result.downloadUrl != null) {
                        startDownload(context, result.downloadUrl, result.filename ?: "video.mp4")
                        statusMessage = "Indirme baslatildi (${result.usedInstance})"
                        isError = false
                    } else {
                        statusMessage = "Hata: ${result.errorMessage ?: "Bilinmeyen"}"
                        isError = true
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF8B5CF6),
                disabledContainerColor = Color(0xFF4B3B7A)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Araniyor...", color = Color.White, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.Download, null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("INDIR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(
                containerColor = if (isError) Color(0x33FF4444) else Color(0x3300CC66)
            )) {
                Text(
                    msg,
                    color = if (isError) Color(0xFFFF6B6B) else Color(0xFF6BFFB1),
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("NASIL KULLANILIR?", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                StepText("1", "Video linkini yapistirin")
                StepText("2", "Kalite secin (1080p / 720p / 480p / MP3)")
                StepText("3", "Indir butonuna basin")
                StepText("4", "Video Indirilenler klasorune kaydedilir")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Baglanti hatasi alirsan ust sagdaki ayarlar simgesinden kendi sunucu adresini girebilirsin.",
            color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(8.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) Color(0xFF8B5CF6) else Color(0xFF333344), RoundedCornerShape(20.dp))
            .background(if (selected) Color(0x338B5CF6) else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
fun StepText(step: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF252535), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var customUrl by remember { mutableStateOf(InstanceManager.getCustomInstance(context)) }
    val activeList = InstanceManager.getActiveInstances(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Ayarlar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("OZEL SUNUCU (COBALT INSTANCE)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Varsayilan sunucular calismazsa kendi cobalt URL'inizi girin. Online liste: instances.cobalt.best",
                    color = Color.Gray, fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    placeholder = { Text("https://ornekapi.com", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF8B5CF6),
                        unfocusedBorderColor = Color(0xFF333344),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            InstanceManager.setCustomInstance(context, customUrl)
                            Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show()
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        modifier = Modifier.weight(1f)
                    ) { Text("Kaydet") }
                    OutlinedButton(
                        onClick = {
                            customUrl = ""
                            InstanceManager.setCustomInstance(context, "")
                            Toast.makeText(context, "Sifirlandi", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Sifirla", color = Color.White) }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("AKTIF SUNUCU SIRASI", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Uygulama bu sunuculari sirayla deneyecek. Ilk yanit veren kullanilir.",
                    color = Color.Gray, fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                activeList.forEachIndexed { i, srv ->
                    Text(
                        "${i + 1}. $srv",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Geri", color = Color.White)
        }
    }
}
