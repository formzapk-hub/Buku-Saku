package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NoteEntity
import com.example.data.NoteRepository
import com.example.network.SmartSearchResult
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

class BookViewModel(application: Application) : AndroidViewModel(application) {

    private val noteDao = AppDatabase.getDatabase(application).noteDao()
    private val repository = NoteRepository(noteDao)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("Semua")
    val selectedCategory = _selectedCategory.asStateFlow()

    // Combined Flow of Notes to implement smart, instantaneous reactive search & categorization filtering locally!
    val filteredNotes: StateFlow<List<NoteEntity>> = combine(
        repository.allNotes,
        _searchQuery,
        _selectedCategory
    ) { notes, query, category ->
        var filtered = notes
        if (category != "Semua") {
            filtered = filtered.filter { it.category == category }
        }
        if (query.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.content.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
            }
        }
        filtered
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Distinct list of categories present
    val categories: StateFlow<List<String>> = repository.allNotes.combine(MutableStateFlow(emptyList<String>())) { notes, _ ->
        val list = mutableListOf("Semua")
        list.addAll(notes.map { it.category }.distinct().sorted())
        list
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = listOf("Semua")
    )

    // --- State Variables for AI & Sync status ---
    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading = _isAiLoading.asStateFlow()

    private val _smartSearchResult = MutableStateFlow<SmartSearchResult?>(null)
    val smartSearchResult = _smartSearchResult.asStateFlow()

    private val _currentUserEmail = MutableStateFlow<String?>(null)
    val currentUserEmail = _currentUserEmail.asStateFlow()

    private val _isFirebaseConfigured = MutableStateFlow(false)
    val isFirebaseConfigured = _isFirebaseConfigured.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed DB on start so it's ready and rich with hospital notes out-of-the-box!
            repository.seedDatabaseIfEmpty()
            checkFirebaseStatus()
        }
    }

    private fun checkFirebaseStatus() {
        try {
            val auth = FirebaseAuth.getInstance()
            _currentUserEmail.value = auth.currentUser?.email
            _isFirebaseConfigured.value = true
        } catch (e: Throwable) {
            _isFirebaseConfigured.value = false
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    // --- Note CRUD Operations with AI Auto-Categorization ---
    fun saveNote(title: String, content: String, imageUri: String? = null, isShared: Boolean = false) {
        viewModelScope.launch {
            _isAiLoading.value = true
            try {
                // Determine category using Gemini API
                val category = repository.categorizeNoteWithAI(content)
                val newNote = NoteEntity(
                    title = title,
                    content = content,
                    category = category,
                    imageUri = imageUri,
                    authorEmail = _currentUserEmail.value ?: "Lokal",
                    isShared = isShared,
                    lastUpdated = System.currentTimeMillis()
                )
                repository.insertNote(newNote)
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed to save/categorize note: ${e.message}")
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun updateExistingNote(note: NoteEntity, title: String, content: String, isShared: Boolean) {
        viewModelScope.launch {
            _isAiLoading.value = true
            try {
                // Re-categorize in case content changed significantly
                val category = repository.categorizeNoteWithAI(content)
                val updated = note.copy(
                    title = title,
                    content = content,
                    category = category,
                    isShared = isShared,
                    lastUpdated = System.currentTimeMillis()
                )
                repository.updateNote(updated)
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed to update/categorize note: ${e.message}")
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            repository.deleteNote(note)
        }
    }

    // --- Smart Object Search (Multimodal Gemini REST Call) ---
    fun performSmartSearch(bitmap: Bitmap) {
        viewModelScope.launch {
            _isAiLoading.value = true
            _smartSearchResult.value = null
            try {
                val result = repository.smartSearchWithAI(bitmap)
                _smartSearchResult.value = result
                if (result != null) {
                    // Pre-fill search query to filter notes based on AI findings
                    _searchQuery.value = result.searchQuery
                    _selectedCategory.value = "Semua"
                }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Smart search execution failure: ${e.message}")
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun clearSmartSearchResult() {
        _smartSearchResult.value = null
        _searchQuery.value = ""
    }

    // --- Documents Export Feature (TXT, HTML, JSON) ---
    fun exportNote(context: Context, note: NoteEntity, format: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "Export_${note.title.replace(" ", "_")}.${format.lowercase()}"
                val cacheDir = context.cacheDir
                val file = File(cacheDir, fileName)
                val fileOutputStream = FileOutputStream(file)

                val contentToWrite = when (format.uppercase()) {
                    "TXT" -> {
                        """
                            BUKU SAKU AKREDITASI RUMAH SAKIT
                            ====================================
                            Judul: ${note.title}
                            Kategori: ${note.category}
                            Terakhir Diperbarui: ${java.text.SimpleDateFormat("dd MMM yyyy HH:mm:ss").format(java.util.Date(note.lastUpdated))}
                            ====================================
                            
                            ${note.content}
                            
                            ====================================
                            SARI ASIH SERANG - Melayani Dengan Kasih Sayang
                        """.trimIndent()
                    }
                    "HTML" -> {
                        """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta charset="utf-8">
                                <title>${note.title}</title>
                                <style>
                                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; padding: 30px; background-color: #f7f9fa; color: #333; line-height: 1.6; }
                                    .card { background: white; padding: 25px; border-radius: 12px; box-shadow: 0 4px 10px rgba(0,0,0,0.05); border-left: 6px solid #008080; }
                                    h1 { color: #008080; margin-top: 0; font-size: 24px; }
                                    .meta { color: #666; font-size: 13px; margin-bottom: 20px; border-bottom: 1px solid #eee; padding-bottom: 10px; }
                                    .content { font-size: 16px; white-space: pre-wrap; }
                                    .footer { text-align: center; margin-top: 30px; font-size: 12px; color: #999; }
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <h1>${note.title}</h1>
                                    <div class="meta">
                                        <strong>Kategori:</strong> ${note.category} | 
                                        <strong>Diperbarui:</strong> ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date(note.lastUpdated))}
                                    </div>
                                    <div class="content">${note.content}</div>
                                </div>
                                <div class="footer">Buku Saku STARKES - RS Sari Asih Serang</div>
                            </body>
                            </html>
                        """.trimIndent()
                    }
                    "JSON" -> {
                        """
                            {
                              "id": ${note.id},
                              "title": "${note.title.replace("\"", "\\\"")}",
                              "category": "${note.category}",
                              "content": "${note.content.replace("\n", "\\n").replace("\"", "\\\"")}",
                              "lastUpdated": ${note.lastUpdated},
                              "author": "${note.authorEmail ?: "Lokal"}",
                              "shared": ${note.isShared}
                            }
                        """.trimIndent()
                    }
                    else -> ""
                }

                fileOutputStream.write(contentToWrite.toByteArray())
                fileOutputStream.close()

                withContext(Dispatchers.Main) {
                    shareExportedFile(context, file, fileName)
                }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed to export document: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Gagal mengekspor dokumen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareExportedFile(context: Context, file: File, fileName: String) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Ekspor Dokumen Buku Saku"))
        } catch (e: Exception) {
            Log.e("BookViewModel", "Sharing exported file failed: ${e.message}")
            Toast.makeText(context, "Gagal membagikan berkas ekspor", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportAllNotesToPdf(context: Context, notesToExport: List<NoteEntity>) {
        if (notesToExport.isEmpty()) {
            Toast.makeText(context, "Tidak ada panduan akreditasi untuk diekspor", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "Laporan_Akreditasi_STARKES_${System.currentTimeMillis()}.pdf"
                val cacheDir = context.cacheDir
                val file = File(cacheDir, fileName)
                val fileOutputStream = FileOutputStream(file)

                // Initialize PdfDocument
                val pdfDocument = android.graphics.pdf.PdfDocument()

                // Standard A4 dimensions in points (72 points per inch)
                // Width = 8.27 in * 72 = 595
                // Height = 11.69 in * 72 = 842
                val pageWidth = 595
                val pageHeight = 842
                
                val leftMargin = 45f
                val rightMargin = 550f
                val topMargin = 50f
                val bottomMargin = 780f
                val maxContentWidth = rightMargin - leftMargin

                var pageNumber = 1
                var pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                var currentPage = pdfDocument.startPage(pageInfo)
                var canvas = currentPage.canvas

                // Initialize Paints
                val titlePaint = Paint().apply {
                    color = android.graphics.Color.rgb(0, 90, 90) // Teal color
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }

                val subtitlePaint = Paint().apply {
                    color = android.graphics.Color.rgb(80, 80, 80)
                    textSize = 10f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    isAntiAlias = true
                }

                val headerLinePaint = Paint().apply {
                    color = android.graphics.Color.rgb(0, 128, 128)
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }

                val textPaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 9.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    isAntiAlias = true
                }

                val boldTextPaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 10f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }

                val badgeBgPaint = Paint().apply {
                    color = android.graphics.Color.rgb(230, 242, 242) // light teal
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }

                val badgeTextPaint = Paint().apply {
                    color = android.graphics.Color.rgb(0, 110, 110) // teal text
                    textSize = 8f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }

                val checkboxPaint = Paint().apply {
                    color = android.graphics.Color.rgb(0, 128, 128)
                    strokeWidth = 1.2f
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }

                val checkmarkPaint = Paint().apply {
                    color = android.graphics.Color.rgb(0, 128, 128)
                    strokeWidth = 1.8f
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }

                val dividerPaint = Paint().apply {
                    color = android.graphics.Color.rgb(220, 220, 220)
                    strokeWidth = 0.8f
                    style = Paint.Style.STROKE
                }

                val metaPaint = Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 8f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    isAntiAlias = true
                }

                // Helper to draw Header and Footer
                fun drawHeaderAndFooter(canvas: android.graphics.Canvas, pageNum: Int) {
                    // Header text
                    val headerPaint = Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 8f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        isAntiAlias = true
                    }
                    val rightHeaderPaint = Paint().apply {
                        color = android.graphics.Color.rgb(0, 128, 128)
                        textSize = 8f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        isAntiAlias = true
                        textAlign = Paint.Align.RIGHT
                    }
                    canvas.drawText("BUKU SAKU AKREDITASI STARKES", leftMargin, 35f, headerPaint)
                    canvas.drawText("RS SARI ASIH SERANG", rightMargin, 35f, rightHeaderPaint)
                    
                    // Header line
                    val linePaint = Paint().apply {
                        color = android.graphics.Color.rgb(230, 230, 230)
                        strokeWidth = 0.5f
                    }
                    canvas.drawLine(leftMargin, 40f, rightMargin, 40f, linePaint)

                    // Footer line
                    canvas.drawLine(leftMargin, 802f, rightMargin, 802f, linePaint)

                    // Footer text
                    val footerPaint = Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 7.5f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                        isAntiAlias = true
                    }
                    val rightFooterPaint = Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 7.5f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        isAntiAlias = true
                        textAlign = Paint.Align.RIGHT
                    }
                    canvas.drawText("Laporan Resmi Internal Akreditasi Rumah Sakit", leftMargin, 814f, footerPaint)
                    canvas.drawText("Halaman $pageNum", rightMargin, 814f, rightFooterPaint)
                }

                // Header on Page 1 (Cover / Main Header Block)
                drawHeaderAndFooter(canvas, pageNumber)

                // Large Banner on Page 1
                canvas.drawText("LAPORAN RESMI AKREDITASI STARKES", leftMargin, 75f, titlePaint)
                canvas.drawText("Rumah Sakit Sari Asih Serang • Dokumen Pelaporan Standar Akreditasi", leftMargin, 92f, subtitlePaint)
                
                val currentFormatDate = java.text.SimpleDateFormat("dd MMMM yyyy HH:mm").format(java.util.Date())
                canvas.drawText("Tanggal Cetak: $currentFormatDate  |  Total Panduan: ${notesToExport.size} berkas", leftMargin, 107f, metaPaint)
                
                canvas.drawLine(leftMargin, 118f, rightMargin, 118f, headerLinePaint)

                var currentY = 140f

                // Wrap text helper
                fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
                    val lines = mutableListOf<String>()
                    val paragraphs = text.split("\n")
                    for (paragraph in paragraphs) {
                        if (paragraph.isEmpty()) {
                            lines.add("")
                            continue
                        }
                        val words = paragraph.split(" ")
                        var currentLine = StringBuilder()
                        for (word in words) {
                            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                            val width = paint.measureText(testLine)
                            if (width <= maxWidth) {
                                currentLine.append(if (currentLine.isEmpty()) word else " $word")
                            } else {
                                lines.add(currentLine.toString())
                                currentLine = StringBuilder(word)
                            }
                        }
                        if (currentLine.isNotEmpty()) {
                            lines.add(currentLine.toString())
                        }
                    }
                    return lines
                }

                // Iterate through notes
                for (note in notesToExport) {
                    val bodyLines = wrapText(note.content, textPaint, maxContentWidth - 30f) // extra indent for checklist
                    
                    // Estimate height:
                    // Category badge: 14pt
                    // Title: 16pt
                    // Body text lines: bodyLines.size * 13pt
                    // Spacing/divider: 20pt
                    // Total height = 14 + 16 + (bodyLines.size * 13) + 20
                    val noteHeight = 14f + 16f + (bodyLines.size * 13f) + 24f

                    // Check if page overflow
                    if (currentY + noteHeight > bottomMargin) {
                        // Finish current page
                        pdfDocument.finishPage(currentPage)
                        
                        // Start a new page
                        pageNumber++
                        pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        currentPage = pdfDocument.startPage(pageInfo)
                        canvas = currentPage.canvas
                        
                        // Draw headers on new page
                        drawHeaderAndFooter(canvas, pageNumber)
                        currentY = 60f // start position on later pages
                    }

                    // Draw Checkbox [ ] with checkmark inside for aesthetic representation of official hospital check
                    val checkSize = 12f
                    val checkX = leftMargin
                    val checkY = currentY + 3f
                    canvas.drawRoundRect(
                        RectF(checkX, checkY, checkX + checkSize, checkY + checkSize),
                        2f, 2f, checkboxPaint
                    )
                    // Draw a cute checkmark inside to indicate "verified/ready for audit"
                    canvas.drawLine(checkX + 2.5f, checkY + 6f, checkX + 5.5f, checkY + 9f, checkmarkPaint)
                    canvas.drawLine(checkX + 5.5f, checkY + 9f, checkX + 9.5f, checkY + 3f, checkmarkPaint)

                    // Draw Category Badge
                    val categoryText = note.category.uppercase()
                    val textWidth = badgeTextPaint.measureText(categoryText)
                    val badgeWidth = textWidth + 12f
                    val badgeHeight = 13f
                    val badgeX = leftMargin + 20f
                    val badgeY = currentY + 2f
                    
                    canvas.drawRoundRect(
                        RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight),
                        4f, 4f, badgeBgPaint
                    )
                    canvas.drawText(categoryText, badgeX + 6f, badgeY + 9.5f, badgeTextPaint)

                    // Draw Title (bold)
                    canvas.drawText(note.title, leftMargin + 20f, currentY + 28f, boldTextPaint)

                    // Draw Content Body (wrapped lines)
                    var textY = currentY + 41f
                    for (line in bodyLines) {
                        if (line.isNotEmpty()) {
                            canvas.drawText(line, leftMargin + 20f, textY, textPaint)
                        }
                        textY += 13f
                    }

                    // Draw thin divider line
                    currentY = textY + 8f
                    canvas.drawLine(leftMargin, currentY, rightMargin, currentY, dividerPaint)
                    
                    currentY += 12f // gap for next note
                }

                // Finish final page
                pdfDocument.finishPage(currentPage)

                // Write the PDF document to output stream
                pdfDocument.writeTo(fileOutputStream)
                fileOutputStream.close()
                pdfDocument.close()

                withContext(Dispatchers.Main) {
                    shareExportedFile(context, file, "Laporan_Akreditasi_STARKES.pdf")
                }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed to export PDF: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Gagal membuat laporan PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- Authentication Actions (Simulated inside prototype, with hooks to actual Auth) ---
    fun loginMockUser(email: String) {
        viewModelScope.launch {
            if (_isFirebaseConfigured.value) {
                try {
                    FirebaseAuth.getInstance().signInWithEmailAndPassword(email, "password123")
                        .addOnSuccessListener {
                            _currentUserEmail.value = email
                            viewModelScope.launch {
                                repository.fetchSharedNotesFromCloud()
                            }
                        }
                } catch (e: Exception) {
                    // fallback to local email mock
                    _currentUserEmail.value = email
                }
            } else {
                _currentUserEmail.value = email
            }
        }
    }

    fun logoutUser() {
        if (_isFirebaseConfigured.value) {
            try {
                FirebaseAuth.getInstance().signOut()
            } catch (e: Exception) {
                // ignore
            }
        }
        _currentUserEmail.value = null
    }
}
