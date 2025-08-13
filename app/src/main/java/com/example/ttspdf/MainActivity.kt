package com.example.ttspdf

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import kotlinx.coroutines.*
import java.util.Locale
import android.graphics.RectF

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { PdfScreenPro() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreenPro() {
    val ctx = LocalContext.current
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(ReaderSettings()) }

    var wordBoxes by remember { mutableStateOf<List<WordBox>>(emptyList()) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var pageCount by remember { mutableStateOf(0) }

    var pdfView: PDFView? by remember { mutableStateOf(null) }
    var overlay: HighlightOverlayView? by remember { mutableStateOf(null) }

    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) { tts?.language = Locale.getDefault() } }
    }
    DisposableEffect(Unit) { onDispose { tts?.shutdown() } }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri!=null) pdfUri = uri }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) { Text("Open PDF") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (pdfUri != null && !isAnalyzing) {
                    isAnalyzing = true
                    CoroutineScope(Dispatchers.Main).launch {
                        runCatching {
                            val analyzer = PdfAnalyzer(ctx)
                            val (fullText, words, pages) = analyzer.extractWordBoxes(pdfUri!!)
                            pageCount = pages; sentences = analyzer.sentencesFromText(fullText)
                            if (words.isEmpty()) {
                                // OCR fallback needs bitmaps of pages; approximate by rendering the current view
                                val ocr = OcrAnalyzer(ctx)
                                suspend fun render(p: Int): android.graphics.Bitmap {
                                    val v = pdfView ?: throw IllegalStateException("Open PDF first")
                                    v.jumpTo(p, true); delay(400)
                                    val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                                    val c = android.graphics.Canvas(bmp); v.draw(c); return bmp
                                }
                                val ocrWords = ocr.ocrBitmaps(pages, ::render)
                                wordBoxes = ocrWords.map { WordBox(it.page, it.text, it.box) }
                            } else wordBoxes = words
                        }.onFailure { /* ignore MVP */ }
                        isAnalyzing = false
                    }
                }
            }, enabled = pdfUri != null && !isAnalyzing) { Text(if (isAnalyzing) "Analyzing..." else "Analyze") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (!isReading) {
                    isReading = true
                    CoroutineScope(Dispatchers.Main).launch {
                        val analyzer = PdfAnalyzer(ctx)
                        val msPerWord = (60_000 / settings.wpm).toLong().coerceIn(120, 900)
                        for (s in sentences) {
                            if (settings.highlightMode == HighlightMode.Sentence) {
                                val sb = analyzer.mapSentenceToBoxes(s, wordBoxes)
                                if (sb != null) {
                                    updateHighlight(pdfView, overlay, settings, sb.boxes)
                                    if (settings.followMode == FollowMode.TTS) speak(tts, s) else delay(msPerWord * s.split(Regex("\s+")).size)
                                }
                            } else {
                                val words = analyzer.mapWords(s, wordBoxes)
                                for (w in words) {
                                    updateHighlight(pdfView, overlay, settings, listOf(w.box))
                                    if (settings.followMode == FollowMode.TTS) {
                                        // For per-word with TTS, we can't guarantee onRangeStart; approximate timing:
                                        speak(tts, w.text)
                                        while (tts?.isSpeaking == true) delay(80)
                                    } else delay(msPerWord)
                                }
                            }
                        }
                        isReading = false
                    }
                } else { tts?.stop(); isReading = false }
            }, enabled = wordBoxes.isNotEmpty()) { Text(if (isReading) "Stop" else "Start") }
        }

        Spacer(Modifier.height(8.dp))
        SettingsBar(settings) { settings = it }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> PDFView(context, null) },
                update = { pdf ->
                    pdfView = pdf
                    overlay = HighlightOverlayView(pdf.context)
                    pdfUri?.let { uri ->
                        pdf.fromUri(uri)
                            .defaultPage(0)
                            .enableDoubletap(true)
                            .enableSwipe(true)
                            .scrollHandle(DefaultScrollHandle(ctx))
                            .load()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SettingsBar(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var exp1 by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = exp1, onExpandedChange = { exp1 = !exp1 }) {
            TextField(readOnly = true, value = "Highlight: ${settings.highlightMode}", onValueChange = {},
                label = { Text("Mode") }, modifier = Modifier.menuAnchor().width(180.dp))
            ExposedDropdownMenu(expanded = exp1, onDismissRequest = { exp1 = false }) {
                DropdownMenuItem(text = { Text("Word") }, onClick = { onChange(settings.copy(highlightMode = HighlightMode.Word)); exp1=false })
                DropdownMenuItem(text = { Text("Sentence") }, onClick = { onChange(settings.copy(highlightMode = HighlightMode.Sentence)); exp1=false })
            }
        }
        Spacer(Modifier.width(8.dp))
        var exp2 by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = exp2, onExpandedChange = { exp2 = !exp2 }) {
            TextField(readOnly = true, value = "Follow: ${settings.followMode}", onValueChange = {},
                label = { Text("Follow") }, modifier = Modifier.menuAnchor().width(180.dp))
            ExposedDropdownMenu(expanded = exp2, onDismissRequest = { exp2 = false }) {
                DropdownMenuItem(text = { Text("TTS") }, onClick = { onChange(settings.copy(followMode = FollowMode.TTS)); exp2=false })
                DropdownMenuItem(text = { Text("AutoTimer") }, onClick = { onChange(settings.copy(followMode = FollowMode.AutoTimer)); exp2=false })
            }
        }
        Spacer(Modifier.width(8.dp))
        SliderWithLabel("Zoom", settings.targetZoom, 1.2f, 4.0f) { onChange(settings.copy(targetZoom = it)) }
        Spacer(Modifier.width(8.dp))
        SliderWithLabel("WPM", settings.wpm.toFloat(), 80f, 260f) { onChange(settings.copy(wpm = it.toInt())) }
    }
}

@Composable
fun SliderWithLabel(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${if (label == "WPM") value.toInt() else String.format("%.1f", value)}")
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = 10, modifier = Modifier.width(180.dp))
    }
}

private fun speak(tts: TextToSpeech?, text: String) {
    if (tts == null) return
    val params = android.os.Bundle()
    val id = System.nanoTime().toString()
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
}

private fun updateHighlight(pdfView: PDFView?, overlay: HighlightOverlayView?, settings: ReaderSettings, rects: List<RectF>) {
    if (pdfView == null || overlay == null || rects.isEmpty()) return
    val union = RectF(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
    val targetZoom = settings.targetZoom.coerceAtLeast(pdfView.zoom)
    pdfView.zoomWithAnimation(targetZoom)
    pdfView.moveTo(union.centerX(), union.centerY())
    overlay.setHighlightRects(rects)
}