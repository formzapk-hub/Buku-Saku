package com.example.data

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.network.GeminiContent
import com.example.network.GeminiGenerationConfig
import com.example.network.GeminiInlineData
import com.example.network.GeminiPart
import com.example.network.GeminiRequest
import com.example.network.RetrofitClient
import com.example.network.SmartSearchResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class NoteRepository(private val noteDao: NoteDao) {

    val allNotes: Flow<List<NoteEntity>> = noteDao.getAllNotes()

    fun getNotesByCategory(category: String): Flow<List<NoteEntity>> {
        return noteDao.getNotesByCategory(category)
    }

    fun searchNotes(query: String): Flow<List<NoteEntity>> {
        return noteDao.searchNotes(query)
    }

    suspend fun insertNote(note: NoteEntity) {
        noteDao.insertNote(note)
        syncNoteToCloud(note)
    }

    suspend fun updateNote(note: NoteEntity) {
        noteDao.updateNote(note)
        syncNoteToCloud(note)
    }

    suspend fun deleteNote(note: NoteEntity) {
        noteDao.deleteNote(note)
        deleteNoteFromCloud(note)
    }

    // --- Firebase Safety Guard ---
    private fun isFirebaseAvailable(): Boolean {
        return try {
            FirebaseAuth.getInstance()
            FirebaseFirestore.getInstance()
            true
        } catch (e: Throwable) {
            Log.e("NoteRepository", "Firebase not available/configured: ${e.message}")
            false
        }
    }

    // --- Firebase Sync Implementations ---
    private fun syncNoteToCloud(note: NoteEntity) {
        if (!isFirebaseAvailable()) return
        
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null && (note.isShared || note.authorEmail == user.email)) {
                val db = FirebaseFirestore.getInstance()
                val docId = note.cloudId ?: "note_${note.id}_${UUID.randomUUID()}"
                
                val cloudData = hashMapOf(
                    "id" to note.id,
                    "title" to note.title,
                    "content" to note.content,
                    "category" to note.category,
                    "imageUri" to note.imageUri,
                    "authorEmail" to (note.authorEmail ?: user.email),
                    "isShared" to note.isShared,
                    "lastUpdated" to note.lastUpdated
                )

                db.collection("notes").document(docId)
                    .set(cloudData)
                    .addOnSuccessListener {
                        Log.d("NoteRepository", "Successfully synced note ${note.title} to Firestore")
                    }
                    .addOnFailureListener { e ->
                        Log.e("NoteRepository", "Error syncing note: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            Log.e("NoteRepository", "Failed to sync to cloud: ${e.message}")
        }
    }

    private fun deleteNoteFromCloud(note: NoteEntity) {
        if (!isFirebaseAvailable() || note.cloudId == null) return
        try {
            val db = FirebaseFirestore.getInstance()
            db.collection("notes").document(note.cloudId)
                .delete()
                .addOnSuccessListener {
                    Log.d("NoteRepository", "Successfully deleted note ${note.title} from Firestore")
                }
        } catch (e: Exception) {
            Log.e("NoteRepository", "Failed to delete from cloud: ${e.message}")
        }
    }

    // Pull from cloud shared notes to local
    suspend fun fetchSharedNotesFromCloud() = withContext(Dispatchers.IO) {
        if (!isFirebaseAvailable()) return@withContext
        try {
            val db = FirebaseFirestore.getInstance()
            db.collection("notes")
                .whereEqualTo("isShared", true)
                .get()
                .addOnSuccessListener { result ->
                    val notesToInsert = mutableListOf<NoteEntity>()
                    for (doc in result) {
                        val title = doc.getString("title") ?: ""
                        val content = doc.getString("content") ?: ""
                        val category = doc.getString("category") ?: "Umum"
                        val authorEmail = doc.getString("authorEmail")
                        val isShared = doc.getBoolean("isShared") ?: true
                        val lastUpdated = doc.getLong("lastUpdated") ?: System.currentTimeMillis()
                        val imageUri = doc.getString("imageUri")
                        
                        notesToInsert.add(
                            NoteEntity(
                                title = title,
                                content = content,
                                category = category,
                                imageUri = imageUri,
                                cloudId = doc.id,
                                authorEmail = authorEmail,
                                isShared = isShared,
                                lastUpdated = lastUpdated
                            )
                        )
                    }
                    // Insert into local DB
                    if (notesToInsert.isNotEmpty()) {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            noteDao.insertNotes(notesToInsert)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("NoteRepository", "Failed to fetch cloud notes: ${e.message}")
        }
    }

    // --- AI Integration (Gemini Option B REST API) ---

    // Ask Gemini to categorize notes
    suspend fun categorizeNoteWithAI(content: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("NoteRepository", "Gemini API key is not configured")
            return@withContext "Umum"
        }

        val prompt = """
            Anda adalah asisten akreditasi Rumah Sakit. Berdasarkan teks catatan berikut, kategorikan catatan ini ke dalam salah satu bab akreditasi STARKES utama:
            1. Sasaran Keselamatan Pasien (SKP)
            2. Hak Pasien dan Keluarga (HPK)
            3. Pencegahan dan Pengendalian Infeksi (PPI)
            4. Manajemen Fasilitas dan Keselamatan (MFK)
            5. Program Nasional (PROGNAS)
            6. Tata Kelola Rumah Sakit (TKRS)
            7. Umum (jika tidak cocok dengan di atas)
            
            Teks Catatan:
            "$content"
            
            Kembalikan HANYA nama kategori tersebut saja tanpa penjelasan apapun (contoh: "Sasaran Keselamatan Pasien (SKP)").
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
            generationConfig = GeminiGenerationConfig(temperature = 0.2f)
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val resultText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            if (!resultText.isNullOrEmpty()) {
                cleanCategoryName(resultText)
            } else {
                "Umum"
            }
        } catch (e: Exception) {
            Log.e("NoteRepository", "Error categorizing note: ${e.message}")
            "Umum"
        }
    }

    private fun cleanCategoryName(raw: String): String {
        return when {
            raw.contains("SKP") || raw.contains("Sasaran Keselamatan") -> "Sasaran Keselamatan Pasien (SKP)"
            raw.contains("HPK") || raw.contains("Hak Pasien") -> "Hak Pasien dan Keluarga (HPK)"
            raw.contains("PPI") || raw.contains("Pencegahan") -> "Pencegahan dan Pengendalian Infeksi (PPI)"
            raw.contains("MFK") || raw.contains("Fasilitas") -> "Manajemen Fasilitas dan Keselamatan (MFK)"
            raw.contains("PROGNAS") || raw.contains("Program Nasional") -> "Program Nasional (PROGNAS)"
            raw.contains("TKRS") || raw.contains("Tata Kelola") -> "Tata Kelola Rumah Sakit (TKRS)"
            else -> "Umum"
        }
    }

    // Smart Search with Object Recognition: takes an image bitmap, analyzes it with Gemini, and maps it to Hospital accreditation guidelines
    suspend fun smartSearchWithAI(bitmap: Bitmap): SmartSearchResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("NoteRepository", "Gemini API key is not configured for Smart Search")
            return@withContext null
        }

        // Convert bitmap to base64
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val prompt = """
            Analisis gambar medis / fasilitas rumah sakit ini.
            Identifikasi objek atau simbol yang ada di gambar (contoh: APAR/alat pemadam api, rambu cuci tangan, tanda risiko jatuh, titik kumpul, jalur evakuasi, resep obat, gelang pasien, dsb).
            Hubungkan objek tersebut dengan topik Akreditasi Rumah Sakit (STARKES) yang sesuai.
            Kembalikan respons dalam format JSON dengan struktur persis seperti ini:
            {
              "detectedObject": "[Nama Objek yang dideteksi, misal: Alat Pemadam Api Ringan (APAR)]",
              "searchQuery": "[Kata kunci pencarian yang relevan, misal: APAR]",
              "explanation": "[Penjelasan singkat hubungan objek ini dengan standar keselamatan atau akreditasi rumah sakit]",
              "relevantCategory": "[Kategori STARKES utama yang cocok, pilih salah satu dari: Sasaran Keselamatan Pasien (SKP), Hak Pasien dan Keluarga (HPK), Pencegahan dan Pengendalian Infeksi (PPI), Manajemen Fasilitas dan Keselamatan (MFK), Program Nasional (PROGNAS), Umum]"
            }
            Pastikan HANYA menghasilkan format JSON yang valid, tanpa teks markdown pembungkus ```json atau apa pun di luar JSON.
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = prompt),
                        GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GeminiGenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.4f
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonResponseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            if (!jsonResponseText.isNullOrEmpty()) {
                Log.d("NoteRepository", "Smart search raw result: $jsonResponseText")
                // Parse using Moshi
                val adapter = RetrofitClient.moshiInstance.adapter(SmartSearchResult::class.java)
                adapter.fromJson(jsonResponseText)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("NoteRepository", "Error on AI smart search: ${e.message}")
            null
        }
    }

    // --- Seeding Data ---
    suspend fun seedDatabaseIfEmpty() {
        val currentNotes = noteDao.getAllNotes().first()
        if (currentNotes.isNotEmpty()) {
            return // Already seeded
        }

        val initialNotes = listOf(
            NoteEntity(
                title = "Motto & Nilai Utama SARI ASIH",
                content = """
                    Selamat datang di Buku Saku Akreditasi STARKES Rumah Sakit Sari Asih Serang.
                    
                    Motto RS Sari Asih:
                    "Melayani dengan kasih sayang"
                    
                    Greeting "3S":
                    - Senyum
                    - Salam
                    - Syafakumullah / Syafakillah (Semoga Allah menyembuhkanmu)
                    
                    Nilai Utama SARI ASIH:
                    1. Sigap: Cepat tanggap dalam memberikan pelayanan.
                    2. Amanah: Dapat dipercaya menjaga keselamatan pasien.
                    3. Ramah: Bersikap sopan dan berempati.
                    4. Ikhtiar: Selalu berupaya maksimal untuk kesembuhan.
                    5. Aman: Memberikan rasa aman bebas dari bahaya medis.
                    6. Sesuai standar: Mengikuti SPO (Standar Prosedur Operasional) resmi.
                    7. Islami: Mengedepankan akhlakul karimah dan nilai keislaman.
                    8. Happy: Menyebarkan aura positif dan kebahagiaan kepada pasien.
                """.trimIndent(),
                category = "Umum",
                lastUpdated = System.currentTimeMillis() - 1000000
            ),
            NoteEntity(
                title = "SKP 1: Identifikasi Pasien dengan Benar",
                content = """
                    Bagaimana cara mengidentifikasi pasien dengan benar dan aman?
                    
                    1. Identifikasi dilakukan menggunakan minimal 2 parameter identitas pasien:
                       - Nama Lengkap Pasien (sesuai KTP/KK)
                       - Tanggal Lahir Pasien
                       - Nomor Rekam Medis (RM)
                       * CATATAN: Nomor kamar atau nomor tempat tidur TIDAK BOLEH digunakan untuk identifikasi.
                       
                    2. Kapan identifikasi dilakukan?
                       - Sebelum pemberian obat.
                       - Sebelum pemberian darah/produk darah.
                       - Sebelum pengambilan darah atau spesimen klinis lain.
                       - Sebelum melakukan tindakan medis, prosedur diagnostik, atau terapi.
                       
                    3. Kode Warna Gelang Identifikasi:
                       - Merah Muda (Pink): Pasien Perempuan
                       - Biru: Pasien Laki-Laki
                       - Merah: Pasien dengan Alergi (obat, makanan, dsb)
                       - Kuning: Pasien dengan Risiko Jatuh Tinggi
                       - Ungu: Pasien dengan instruksi Do Not Resuscitate (DNR)
                """.trimIndent(),
                category = "Sasaran Keselamatan Pasien (SKP)",
                lastUpdated = System.currentTimeMillis() - 900000
            ),
            NoteEntity(
                title = "SKP 2: Komunikasi yang Efektif (SBAR & TBAK)",
                content = """
                    Bagaimana cara berkomunikasi secara efektif di rumah sakit untuk mencegah kesalahan informasi?
                    
                    1. Menggunakan Teknik SBAR untuk melapor atau serah terima pasien:
                       - S (Situation): Kondisi terkini pasien yang dilaporkan.
                       - B (Background): Riwayat klinis atau latar belakang pasien.
                       - A (Assessment): Hasil analisis atau penilaian kondisi pasien oleh petugas.
                       - R (Recommendation): Tindakan atau tindak lanjut yang disarankan.
                       
                    2. Menggunakan Teknik TBAK untuk menerima instruksi lisan atau via telepon:
                       - T (Tulis): Tulis lengkap instruksi lisan yang diberikan penerima telepon.
                       - Ba (Baca): Bacakan kembali instruksi tersebut kepada pemberi instruksi.
                       - K (Konfirmasi): Konfirmasikan kembali kebenaran instruksi tersebut (pemberi instruksi harus menandatangani rekam medis dalam waktu maksimal 24 jam sebagai verifikasi).
                """.trimIndent(),
                category = "Sasaran Keselamatan Pasien (SKP)",
                lastUpdated = System.currentTimeMillis() - 800000
            ),
            NoteEntity(
                title = "SKP 3: Keamanan Obat Berisiko Tinggi (High Alert)",
                content = """
                    Bagaimana pengawasan obat-obatan yang perlu diwaspadai (High Alert Medications)?
                    
                    1. Apa itu Obat High Alert?
                       Obat yang persentasenya tinggi menyebabkan kesalahan/sentinel event, serta obat yang tampak mirip atau terdengar mirip (LASA - Look Alike Sound Alike / NORUM - Nama Obat Rupa Ucapan Mirip).
                       
                    2. Pengelolaan Obat High Alert:
                       - Penyimpanan harus diberi penanda label khusus merah bertuliskan "HIGH ALERT".
                       - Larutan elektrolit pekat (misal KCl 7.46%, NaCl 3%) tidak boleh disimpan di ruang perawatan biasa, kecuali di ICU/UGD dalam kondisi terkontrol keras.
                       - Penyimpanan obat LASA/NORUM harus diselingi obat lain (tidak boleh berdampingan langsung) dan diberi label "LASA" berwarna hijau.
                       - Sebelum obat diberikan kepada pasien, wajib dilakukan "Double Check" oleh dua orang perawat mandiri untuk memastikan 5 Benar (Benar Pasien, Benar Obat, Benar Dosis, Benar Rute, Benar Waktu).
                """.trimIndent(),
                category = "Sasaran Keselamatan Pasien (SKP)",
                lastUpdated = System.currentTimeMillis() - 700000
            ),
            NoteEntity(
                title = "SKP 5: Kebersihan Tangan (6 Langkah WHO & 5 Moments)",
                content = """
                    Kebersihan tangan adalah pilar utama Pencegahan dan Pengendalian Infeksi (PPI).
                    
                    6 Langkah Mencuci Tangan WHO (Durasi: Handwash air mengalir 40-60 detik; Handrub berbasis alkohol 20-30 detik):
                    1. Gosok kedua telapak tangan secara lembut dengan arah memutar.
                    2. Gosok punggung tangan kiri dengan telapak tangan kanan (dan sebaliknya).
                    3. Gosok sela-sela jari tangan hingga bersih.
                    4. Bersihkan ujung jari dengan posisi saling mengunci (gerakan mengunci).
                    5. Gosok dan putar kedua ibu jari secara bergantian.
                    6. Gosokkan ujung kuku/jari kanan pada telapak tangan kiri secara memutar (dan sebaliknya).
                    
                    5 Momen Kebersihan Tangan (5 Moments of Hand Hygiene):
                    1. Sebelum menyentuh pasien.
                    2. Sebelum melakukan tindakan aseptik / bersih.
                    3. Setelah terkena cairan tubuh pasien yang berisiko.
                    4. Setelah menyentuh pasien.
                    5. Setelah menyentuh lingkungan sekitar pasien.
                """.trimIndent(),
                category = "Sasaran Keselamatan Pasien (SKP)",
                lastUpdated = System.currentTimeMillis() - 600000
            ),
            NoteEntity(
                title = "SKP 6: Pengurangan Risiko Pasien Jatuh",
                content = """
                    Bagaimana cara mencegah pasien cedera akibat jatuh?
                    
                    1. Melakukan Asesmen Risiko Jatuh pada setiap pasien masuk:
                       - Morse Fall Scale: Digunakan untuk pasien Dewasa.
                       - Humpty Dumpty Scale: Digunakan untuk pasien Anak-Anak.
                       - Ontario Modified Stratify: Digunakan untuk pasien Geriatri / Lansia.
                       
                    2. Tindakan Pencegahan Risiko Jatuh Tinggi:
                       - Pasang kancing penanda KUNING pada gelang identitas pasien.
                       - Pasang tanda segitiga jatuh/risiko jatuh di pintu kamar atau di atas tempat tidur pasien.
                       - Pastikan penghalang tempat tidur (side rails) selalu dalam posisi terpasang dan terkunci.
                       - Posisikan tempat tidur pada tingkat terendah.
                       - Pastikan lampu panggilan/bell mudah dijangkau pasien.
                       - Edukasi pasien dan keluarga agar meminta bantuan perawat jika ingin turun dari tempat tidur.
                """.trimIndent(),
                category = "Sasaran Keselamatan Pasien (SKP)",
                lastUpdated = System.currentTimeMillis() - 500000
            ),
            NoteEntity(
                title = "MFK: Kode Darurat (Emergency Codes)",
                content = """
                    Pengenalan kode darurat rumah sakit untuk menjamin keselamatan fasilitas:
                    
                    - CODE BLUE: Gawat Darurat Medis (Henti jantung atau henti nafas pada pasien, pengunjung, atau staf). Hubungi nomor darurat internal Rumah Sari Asih (ex: ext 111) dan sebutkan lokasi kejadian secara spesifik.
                    - CODE RED: Bahaya Kebakaran. Gunakan helm merah untuk memadamkan api dengan APAR, helm kuning untuk evakuasi pasien, helm biru untuk evakuasi dokumen, helm putih untuk evakuasi aset medis.
                    - CODE PINK: Penculikan Bayi atau Anak-Anak di lingkungan rumah sakit.
                    - CODE YELLOW: Kebocoran bahan berbahaya dan beracun (B3) atau tumpahan cairan kimia berbahaya.
                    - CODE GRAY: Gangguan keamanan, huru-hara, atau tindak kekerasan fisik aktif.
                    - CODE ORANGE: Perintah evakuasi darurat masal menuju titik kumpul karena gempa bumi atau bencana besar lainnya.
                    - CODE BLACK: Ancaman bom atau ditemukannya benda mencurigakan yang diduga bahan peledak.
                """.trimIndent(),
                category = "Manajemen Fasilitas dan Keselamatan (MFK)",
                lastUpdated = System.currentTimeMillis() - 400000
            ),
            NoteEntity(
                title = "MFK: Cara Menggunakan APAR (PASS / APAT)",
                content = """
                    Bagaimana cara menggunakan Alat Pemadam Api Ringan (APAR) dengan benar?
                    
                    Gunakan teknik PASS (bahasa Inggris) atau APAT (bahasa Indonesia):
                    
                    1. P - Pull (Tarik pin pengunci): Tarik segel plastik dan lepaskan pin pengunci pada tuas APAR.
                    2. A - Aim (Arahkan): Arahkan corong atau selang pemadam ke pangkal/sumber api (jangan ke arah lidah api).
                    3. S - Squeeze (Tekan): Tekan tuas pegangan APAR untuk menyemburkan media pemadam (bubuk/gas CO2).
                    4. S - Sweep (Kibaskan): Kibaskan corong dari sisi ke sisi secara merata menyelimuti area kebakaran.
                    
                    Aturan keselamatan penggunaan APAR:
                    - Pastikan arah angin bertiup dari belakang Anda (jangan melawan arah angin karena api dapat menerpa Anda).
                    - Jarak aman penggunaan APAR adalah 1,5 hingga 3 meter dari sumber api.
                    - Evakuasi segera jika api bertambah besar dan tidak bisa dikontrol dengan APAR tunggal.
                """.trimIndent(),
                category = "Manajemen Fasilitas dan Keselamatan (MFK)",
                lastUpdated = System.currentTimeMillis() - 300000
            ),
            NoteEntity(
                title = "PPI: Pembuangan Sampah Rumah Sakit",
                content = """
                    Manajemen pemisahan sampah sangat penting untuk mencegah infeksi nosokomial:
                    
                    1. Kantong Kuning (Sampah Infeksius/Medis):
                       - Digunakan untuk sampah yang terkontaminasi darah, cairan tubuh, ekskresi, jaringan tubuh.
                       - Contoh: perban bekas, sarung tangan medis bekas, kassa, masker sekali pakai, slang infus.
                       
                    2. Kantong Hitam (Sampah Non-Infeksius/Domestik):
                       - Digunakan untuk sampah rumah tangga biasa yang tidak terkontaminasi bahan biologis.
                       - Contoh: kertas, plastik makanan, sisa makanan, pembungkus obat, tisu kering bersih.
                       
                    3. Safety Box Kuning (Sampah Benda Tajam):
                       - Wadah tahan tusukan dan bocor khusus benda tajam medis.
                       - Contoh: jarum suntik, ampul obat kosong yang pecah, bisturi/pisau bedah bekas.
                       - Aturan: Pengisian safety box maksimal 3/4 bagian, tidak boleh diisi penuh agar tidak membahayakan petugas sampah medis.
                       
                    4. Kantong Ungu:
                       - Digunakan untuk pembuangan obat sitostatika (kemoterapi).
                """.trimIndent(),
                category = "Pencegahan dan Pengendalian Infeksi (PPI)",
                lastUpdated = System.currentTimeMillis() - 200000
            ),
            NoteEntity(
                title = "HPK: Hak Pasien dan Keluarga Utama",
                content = """
                    Standar Hak Pasien dan Keluarga (HPK) menjamin martabat pasien dihargai:
                    
                    Beberapa Hak Pasien yang wajib dipenuhi staf rumah sakit:
                    1. Memperoleh informasi mengenai tata tertib dan peraturan yang berlaku di Rumah Sakit.
                    2. Memperoleh pelayanan yang manusiawi, adil, jujur, dan tanpa diskriminasi.
                    3. Memperoleh layanan kesehatan yang bermutu sesuai dengan standar profesi dan standar prosedur operasional.
                    4. Meminta konsultasi tentang penyakit yang dideritanya kepada dokter lain yang mempunyai Surat Izin Praktik (SIP) baik di dalam maupun di luar Rumah Sakit (Second Opinion).
                    5. Mendapatkan privasi dan kerahasiaan penyakit yang diderita termasuk data-data medisnya.
                    6. Memberikan persetujuan atau menolak atas tindakan yang akan dilakukan oleh tenaga kesehatan terhadap penyakit yang dideritanya (Informed Consent).
                    7. Didampingi keluarganya dalam keadaan kritis atau menjelang ajal.
                """.trimIndent(),
                category = "Hak Pasien dan Keluarga (HPK)",
                lastUpdated = System.currentTimeMillis() - 100000
            ),
            NoteEntity(
                title = "PROGNAS: Program Nasional Rumah Sakit",
                content = """
                    Program Nasional (PROGNAS) wajib dilaksanakan oleh rumah sakit terakreditasi:
                    
                    Fokus Utama Program Nasional:
                    1. PONEK (Pelayanan Obstetri Neonatal Emergency Komprehensif): Penurunan Angka Kematian Ibu (AKI) dan Angka Kematian Bayi (AKB). RS menyediakan IGD PONEK 24 jam dengan tim medis siaga penuh.
                    2. Penanggulangan Tuberkulosis (TB-DOTS): Pengendalian penyakit TB paru dengan strategi DOTS (Directly Observed Treatment, Short-course) untuk mencegah resistensi obat.
                    3. Penanggulangan HIV / AIDS: Menyediakan klinik VCT (Voluntary Counseling and Testing) dan ART (Antiretroviral Therapy) untuk dukungan pasien terinfeksi.
                    4. Penurunan Prevalensi Stunting dan Wasting pada Balita: Program nutrisi terpadu bagi balita risiko gizi buruk.
                    5. Keluarga Berencana Rumah Sakit (KBRS): Pelayanan kontrasepsi pasca-persalinan langsung bagi pasien melahirkan.
                """.trimIndent(),
                category = "Program Nasional (PROGNAS)",
                lastUpdated = System.currentTimeMillis() - 50000
            )
        )

        noteDao.insertNotes(initialNotes)
    }
}
