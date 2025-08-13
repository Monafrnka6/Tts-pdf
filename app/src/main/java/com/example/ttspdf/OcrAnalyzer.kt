package com.example.ttspdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrWord(val page: Int, val text: String, val box: RectF)

class OcrAnalyzer(private val context: Context) {
    suspend fun ocrBitmaps(pages: Int, renderPage: suspend (Int) -> Bitmap): List<OcrWord> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val out = mutableListOf<OcrWord>()
        for (p in 0 until pages) {
            val bmp = renderPage(p)
            val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {
                        val r = el.boundingBox ?: continue
                        out.add(OcrWord(p, el.text, RectF(r)))
                    }
                }
            }
            bmp.recycle()
        }
        return out
    }
}