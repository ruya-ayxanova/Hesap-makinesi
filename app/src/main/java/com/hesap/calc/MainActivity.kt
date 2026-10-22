package com.hesap.calc

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Orijinali silebilmek için yazma izni de ister. */
class OpenDocs : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

/** kind: 0 = üst klasör, 1 = klasör, 2 = dosya */
data class Entry(val file: File?, val name: String, val kind: Int)

/**
 * Dışarıdan çalışan bir hesap makinesi görünür.
 * Gizli kodu yazıp "=" a basınca kasa açılır.
 */
class MainActivity : ComponentActivity() {
    private var key: SecretKeySpec? = null
    private var picking = false
    private var pendingRestore: File? = null
    private var fails = 0
    private var lockedUntil = 0L
    private var expr = ""
    private var tempFile: File? = null
    private var screen: VideoScreen? = null
    private lateinit var backCb: OnBackPressedCallback
    private var listView: ListView? = null
    private var titleView: TextView? = null
    private var items: List<Entry> = emptyList()
    private var curDir: File? = null
    private var viewing = false
    private val cur: File get() = curDir ?: vaultDir

    private val vaultDir by lazy { File(filesDir, "vault").apply { mkdirs() } }
    private val saltFile get() = File(filesDir, "salt.bin")
    private val checkFile get() = File(filesDir, "check.bin")

    private val addPicker = registerForActivityResult(OpenDocs()) { uris ->
        picking = false
        if (uris.isNotEmpty()) addFiles(uris)
    }
    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        picking = false
        val f = pendingRestore
        pendingRestore = null
        if (uri != null && f != null) restore(f, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        backCb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { if (viewing) closeViewer() else goUp() }
        }
        onBackPressedDispatcher.addCallback(this, backCb)
        cleanupTemp()
        if (saltFile.exists()) showCalc() else showSetup()
    }

    override fun onStop() {
        super.onStop()
        if (!picking && key != null) lock()
    }

    private fun lock() {
        cleanupTemp()
        curDir = null
        key = null
        expr = ""
        showCalc()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- İlk kurulum: sadece rakamlardan oluşan gizli kod ----------
    private fun showSetup() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(60), dp(20), dp(20))
        }
        root.addView(TextView(this).apply { text = "Gizli kod belirle"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Sadece rakam, en az 4 hane. Hesap makinesinde bu kodu yazıp \"=\" a basınca kasa açılır. " +
                    "Kod unutulursa dosyalar kurtarılamaz."
            setPadding(0, dp(12), 0, dp(12))
        })
        fun field(h: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val a = field("Kod"); val b = field("Kod (tekrar)")
        val btn = Button(this).apply { text = "Kaydet" }
        root.addView(a); root.addView(b); root.addView(btn)
        btn.setOnClickListener {
            val p = a.text.toString()
            if (p.length < 4) return@setOnClickListener toast("En az 4 rakam olmalı")
            if (p != b.text.toString()) return@setOnClickListener toast("Kodlar aynı değil")
            val salt = Crypto.newSalt()
            val k = Crypto.deriveKey(p.toCharArray(), salt)
            checkFile.outputStream().use { Crypto.encrypt(k, "ok", "x".byteInputStream(), it) }
            saltFile.writeBytes(salt)
            showCalc()
        }
        setContentView(root)
    }

    // ---------- Hesap makinesi (ön yüz) ----------
    private fun showCalc() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(8), dp(24), dp(8), dp(8))
        }
        val display = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.BOTTOM
            setPadding(dp(16), dp(8), dp(16), dp(16))
            text = expr.ifEmpty { "0" }
        }
        root.addView(display, LinearLayout.LayoutParams(-1, 0, 2f))
        val rows = listOf(
            listOf("C", "⌫", "÷", "×"), listOf("7", "8", "9", "−"),
            listOf("4", "5", "6", "+"), listOf("1", "2", "3", "."), listOf("0", "=")
        )
        for (r in rows) {
            val row = LinearLayout(this)
            for (label in r) {
                val btn = Button(this).apply {
                    text = label
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if ("÷×−+=".contains(label)) 0xFFFF9800.toInt() else 0xFF2C2C2C.toInt())
                    setOnClickListener { press(label, display) }
                }
                val lp = LinearLayout.LayoutParams(0, -1, if (label == "0" || label == "=") 2f else 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(btn, lp)
            }
            root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    private fun press(l: String, d: TextView) {
        when (l) {
            "C" -> expr = ""
            "⌫" -> expr = expr.dropLast(1)
            "=" -> equals()
            else -> {
                if (expr == "Hata") expr = ""
                if (expr.length < 40) expr += l
            }
        }
        d.text = expr.ifEmpty { "0" }
    }

    private fun equals() {
        if (expr.isEmpty()) return
        val now = System.currentTimeMillis()
        if (expr.length >= 4 && expr.all { it.isDigit() } && now >= lockedUntil) {
            val k = Crypto.deriveKey(expr.toCharArray(), saltFile.readBytes())
            val ok = try { Crypto.readName(k, checkFile) == "ok" } catch (e: Exception) { false }
            if (ok) {
                fails = 0
                expr = ""
                key = k
                showMain()
                return
            }
            fails++
            if (fails >= 8) { lockedUntil = now + 15_000L; fails = 0 }
        }
        expr = try { fmt(eval(expr)) } catch (e: Exception) { "Hata" }
    }

    private fun fmt(r: Double): String {
        if (r.isNaN() || r.isInfinite()) throw ArithmeticException()
        val s = if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString()
        else String.format(Locale.US, "%.10f", r).trimEnd('0').trimEnd('.')
        return s.replace('-', '−')
    }

    private fun eval(s: String): Double {
        var pos = 0
        fun peek() = if (pos < s.length) s[pos] else '\u0000'
        fun number(): Double {
            val st = pos
            while (peek().isDigit() || peek() == '.') pos++
            return s.substring(st, pos).toDouble()
        }
        fun unary(): Double {
            if (peek() == '−') { pos++; return -unary() }
            return number()
        }
        fun term(): Double {
            var v = unary()
            while (peek() == '×' || peek() == '÷') {
                val op = s[pos++]
                val r = unary()
                v = if (op == '×') v * r else v / r
            }
            return v
        }
        fun sum(): Double {
            var v = term()
            while (peek() == '+' || peek() == '−') {
                val op = s[pos++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        val r = sum()
        if (pos != s.length) throw IllegalArgumentException()
        return r
    }

    // ---------- Kasa ----------
    private fun showMain() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }
        titleView = TextView(this).apply { textSize = 18f; setPadding(dp(4), 0, 0, dp(8)) }
        root.addView(titleView)
        val bar = LinearLayout(this)
        fun barBtn(t: String, action: () -> Unit) {
            val btn = Button(this).apply { text = t; textSize = 12f; setOnClickListener { action() } }
            bar.addView(btn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        barBtn("Dosya ekle") { picking = true; addPicker.launch(arrayOf("*/*")) }
        barBtn("Yeni klasör") { newFolderDialog() }
        barBtn("Kilitle") { lock() }
        root.addView(bar)
        listView = ListView(this).also {
            root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f))
            it.setOnItemClickListener { _, _, pos, _ -> onEntryClick(items[pos]) }
            it.setOnItemLongClickListener { _, _, pos, _ -> onEntryLong(items[pos]); true }
        }
        setContentView(root)
        refresh()
    }

    private fun folderName(k: SecretKeySpec, dir: File): String =
        try { Crypto.readName(k, File(dir, "folder.meta")) } catch (e: Exception) { "?" }

    private fun pathLabel(k: SecretKeySpec): String {
        val parts = ArrayList<String>()
        var d: File = cur
        while (d != vaultDir) {
            parts.add(0, folderName(k, d))
            d = d.parentFile ?: break
        }
        return "Kasa" + parts.joinToString("") { " / $it" }
    }

    private fun refresh() {
        val k = key ?: return
        val all = cur.listFiles() ?: emptyArray()
        val folders = all.filter { it.isDirectory }
            .map { Entry(it, folderName(k, it), 1) }
            .sortedBy { it.name.lowercase() }
        val files = all.filter { it.isFile && it.name.endsWith(".enc") }
            .mapNotNull { try { Entry(it, Crypto.readName(k, it), 2) } catch (e: Exception) { null } }
            .sortedBy { it.name.lowercase() }
        val list = ArrayList<Entry>()
        if (cur != vaultDir) list.add(Entry(null, "..", 0))
        list.addAll(folders)
        list.addAll(files)
        items = list
        titleView?.text = pathLabel(k)
        backCb.isEnabled = viewing || cur != vaultDir
        listView?.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map {
            when (it.kind) { 0 -> "⬆  Üst klasör"; 1 -> "📁  " + it.name; else -> it.name }
        })
    }

    private fun goUp() {
        val p = cur.parentFile
        curDir = if (p == null || cur == vaultDir || p == vaultDir) null else p
        refresh()
    }

    private fun onEntryClick(e: Entry) {
        when (e.kind) {
            0 -> goUp()
            1 -> { curDir = e.file; refresh() }
            else -> fileMenu(e)
        }
    }

    private fun onEntryLong(e: Entry) {
        if (e.kind == 1) folderMenu(e) else if (e.kind == 2) fileMenu(e)
    }

    private fun fileMenu(e: Entry) {
        val f = e.file ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(arrayOf("Aç", "Klasöre taşı", "Geri getir", "Kalıcı sil")) { _, i ->
                when (i) {
                    0 -> openItem(f, e.name)
                    1 -> moveDialog(f)
                    2 -> { picking = true; pendingRestore = f; savePicker.launch(e.name) }
                    else -> android.app.AlertDialog.Builder(this)
                        .setMessage("Bu dosya kalıcı olarak silinecek.")
                        .setPositiveButton("Sil") { _, _ -> f.delete(); refresh() }
                        .setNegativeButton("Vazgeç", null).show()
                }
            }.show()
    }

    private fun folderMenu(e: Entry) {
        val d = e.file ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(arrayOf("Yeniden adlandır", "Klasörü sil")) { _, i ->
                if (i == 0) {
                    nameDialog("Yeni ad", e.name) { n -> writeFolderName(d, n); refresh() }
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setMessage("Klasör ve içindeki tüm dosyalar kalıcı olarak silinecek.")
                        .setPositiveButton("Sil") { _, _ -> d.deleteRecursively(); refresh() }
                        .setNegativeButton("Vazgeç", null).show()
                }
            }.show()
    }

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine() }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("Tamam") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun writeFolderName(dir: File, name: String) {
        val k = key ?: return
        File(dir, "folder.meta").outputStream().use { Crypto.encrypt(k, name, "x".byteInputStream(), it) }
    }

    private fun newFolderDialog() = nameDialog("Klasör adı", "") { n ->
        val d = File(cur, UUID.randomUUID().toString())
        d.mkdirs()
        writeFolderName(d, n)
        refresh()
    }

    private fun allFolders(k: SecretKeySpec, dir: File, prefix: String, out: MutableList<Pair<File, String>>) {
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach {
            val n = folderName(k, it)
            out.add(it to (prefix + n))
            allFolders(k, it, "$prefix$n / ", out)
        }
    }

    private fun moveDialog(f: File) {
        val k = key ?: return
        val dests = ArrayList<Pair<File, String>>()
        dests.add(vaultDir to "Kasa (ana dizin)")
        allFolders(k, vaultDir, "", dests)
        val opts = dests.filter { it.first != f.parentFile }
        if (opts.isEmpty()) {
            toast("Önce bir klasör oluştur")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Nereye taşınsın?")
            .setItems(opts.map { it.second }.toTypedArray()) { _, i ->
                toast(if (f.renameTo(File(opts[i].first, f.name))) "Taşındı" else "Taşınamadı")
                refresh()
            }.show()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "dosya"

    private fun addFiles(uris: List<Uri>) {
        val k = key ?: return
        val dest = cur
        toast("Şifreleniyor...")
        Thread {
            var ok = 0; var bad = 0; var kept = 0
            for (uri in uris) {
                val target = File(dest, UUID.randomUUID().toString() + ".enc")
                try {
                    val name = displayName(uri)
                    contentResolver.openInputStream(uri)!!.use { i ->
                        target.outputStream().use { o -> Crypto.encrypt(k, name, i, o) }
                    }
                    val deleted = try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (e: Exception) { false }
                    if (!deleted) kept++
                    ok++
                } catch (e: Exception) {
                    target.delete(); bad++
                }
            }
            runOnUiThread {
                toast("$ok dosya eklendi" +
                        (if (kept > 0) "\n$kept dosyanın orijinali silinemedi, elle sil." else "") +
                        (if (bad > 0) "\n$bad dosya eklenemedi." else ""))
                refresh()
            }
        }.start()
    }

    // ---------- Uygulama içi görüntüleyici ----------
    private fun cleanupTemp() {
        screen?.release()
        screen = null
        tempFile?.delete()
        tempFile = null
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("view_")) it.delete() }
        viewing = false
        if (::backCb.isInitialized) backCb.isEnabled = false
    }

    private fun closeViewer() {
        cleanupTemp()
        if (key != null) showMain()
    }

    private fun openItem(f: File, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> 1
            "mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v",
            "mp3", "m4a", "wav", "ogg", "aac", "flac" -> 2
            else -> 0
        }
        if (kind == 0) {
            toast("Bu dosya türü uygulamada açılamıyor. \"Geri getir\" ile çıkarabilirsin.")
            return
        }
        val k = key ?: return
        toast("Açılıyor...")
        Thread {
            try {
                val t = File(cacheDir, "view_" + UUID.randomUUID() + "." + ext)
                tempFile = t
                FileOutputStream(t).use { Crypto.decrypt(k, f, it) }
                runOnUiThread { if (key != null) showViewer(t, kind) else t.delete() }
            } catch (e: Exception) {
                runOnUiThread { toast("Açılamadı") }
            }
        }.start()
    }

    private fun showViewer(t: File, kind: Int) {
        if (kind == 2) {
            val vs = VideoScreen(this, t) { closeViewer() }
            screen = vs
            viewing = true
            backCb.isEnabled = true
            setContentView(vs)
            return
        }
        val bmp = loadBitmap(t)
        if (bmp == null) {
            toast("Görüntü açılamadı")
            closeViewer()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val close = Button(this).apply {
            text = "✕ Kapat"
            setOnClickListener { closeViewer() }
        }
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        lp.setMargins(dp(8), dp(32), dp(8), 0)
        root.addView(close, lp)
        viewing = true
        backCb.isEnabled = true
        setContentView(root)
    }

    private fun loadBitmap(f: File): android.graphics.Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        val orient = try {
            ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } catch (e: Exception) { 1 }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun restore(f: File, uri: Uri) {
        val k = key ?: return
        Thread {
            try {
                contentResolver.openOutputStream(uri)!!.use { Crypto.decrypt(k, f, it) }
                f.delete()
                runOnUiThread { toast("Geri getirildi"); refresh() }
            } catch (e: Exception) {
                runOnUiThread { toast("Geri getirilemedi") }
            }
        }.start()
    }
}
