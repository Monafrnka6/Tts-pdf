package com.example.ttspdf

import android.content.ContentResolver
import android.content.Context
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument as APDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper as APDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

data class WordBox(val page: Int, val text: String, val box: RectF)
data class SentenceBox(val page: Int, val text: String, val boxes: List<RectF>)

class PdfAnalyzer(private val context: Context) {

    init { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context) }

    suspend fun extractWordBoxes(uri: Uri): Triple<String, List<WordBox>, Int> = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver
        resolver.openInputStream(uri).use { ins ->
            if (ins == null) error("Unable to open PDF")
            val doc = APDDocument.load(ins)
            val totalPages = doc.numberOfPages
            val collector = WordCollector()
            val stripper = object : APDFTextStripper() {
                override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) {
                    super.startPage(page); collector.pageIndex = currentPageNo - 1
                }
                override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                    if (text == null || textPositions == null) return
                    collector.consume(textPositions); super.writeString(text, textPositions)
                }
            }
            stripper.sortByPosition = true
            val fullText = runCatching { stripper.getText(doc) }.getOrDefault("")
            doc.close()
            Triple(fullText, collector.words, totalPages)
        }
    }

    fun sentencesFromText(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    fun mapSentenceToBoxes(sentence: String, wordBoxes: List<WordBox>): SentenceBox? {
        val tokens = tokenize(sentence)
        val boxes = mutableListOf<RectF>()
        var page = -1
        var i = 0; var j = 0
        while (i < wordBoxes.size && j < tokens.size) {
            val wb = wordBoxes[i]
            val w = clean(wb.text); val t = tokens[j]
            if (w == t || (w.isNotEmpty() && t.startsWith(w))) {
                boxes.add(wb.box); page = wb.page; j++
            }
            i++
        }
        if (boxes.isEmpty()) return null
        return SentenceBox(page, sentence, boxes)
    }

    fun mapWords(text: String, wordBoxes: List<WordBox>): List<WordBox> {
        val tokens = tokenize(text)
        val matched = mutableListOf<WordBox>()
        var i = 0; var j = 0
        while (i < wordBoxes.size && j < tokens.size) {
            val wb = wordBoxes[i]
            val w = clean(wb.text); val t = tokens[j]
            if (w == t || (w.isNotEmpty() && t.startsWith(w))) { matched.add(wb); j++ }
            i++
        }
        return matched
    }

    private fun tokenize(s: String) = s.split(Regex("\s+")).map { clean(it) }.filter { it.isNotEmpty() }
    private fun clean(s: String) = s.replace(Regex("[^\p{L}\p{Nd}]"), "").lowercase()

    private class WordCollector {
        var pageIndex = 0
        val words = mutableListOf<WordBox>()
        fun consume(textPositions: MutableList<TextPosition>) {
            val buf = StringBuilder()
            val acc = mutableListOf<TextPosition>()
            fun flush() {
                if (buf.isNotEmpty() && acc.isNotEmpty()) {
                    val xs = acc.map { it.xDirAdj }
                    val xe = acc.map { it.xDirAdj + it.widthDirAdj }
                    val ys = acc.map { it.yDirAdj }
                    val ye = acc.map { it.yDirAdj + it.heightDir }
                    val left = xs.minOrNull() ?: 0f
                    val right = xe.maxOrNull() ?: 0f
                    val top = ys.minOrNull() ?: 0f
                    val bottom = ye.maxOrNull() ?: 0f
                    words.add(WordBox(pageIndex, buf.toString(), RectF(left, top, right, bottom)))
                }
                buf.clear(); acc.clear()
            }
            for (tp in textPositions) {
                val c = tp.unicode ?: continue
                if (c.isBlank()) flush() else { buf.append(c); acc.add(tp) }
            }
            flush()
        }
    }
}