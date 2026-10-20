package com.hesap.calc

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Orijinali silebilmek için yazma izni de ister. */
class OpenDocs : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

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
    private var listView: ListView? = null
    private var items: List<Pair<File, String>> = emptyList()

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
        if (saltFile.exists()) showCalc() else showSetup()
    }

    override fun onStop() {
        super.onStop()
        if (!picking && key != null) lock()
    }

    private fun lock() {
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
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        root.addView(TextView(this).apply { text = "Kasa"; textSize = 24f })
        root.addView(Button(this).apply {
            text = "Dosya ekle"
            setOnClickListener { picking = true; addPicker.launch(arrayOf("*/*")) }
        })
        root.addView(Button(this).apply { text = "Kilitle"; setOnClickListener { lock() } })
        listView = ListView(this).also {
            root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f))
            it.setOnItemClickListener { _, _, pos, _ -> itemMenu(items[pos]) }
        }
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val k = key ?: return
        items = (vaultDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            try { f to Crypto.readName(k, f) } catch (e: Exception) { null }
        }.sortedBy { it.second.lowercase() }
        listView?.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { it.second })
    }

    private fun itemMenu(item: Pair<File, String>) {
        android.app.AlertDialog.Builder(this)
            .setTitle(item.second)
            .setItems(arrayOf("Geri getir", "Kalıcı sil")) { _, i ->
                if (i == 0) {
                    picking = true
                    pendingRestore = item.first
                    savePicker.launch(item.second)
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setMessage("Bu dosya kalıcı olarak silinecek.")
                        .setPositiveButton("Sil") { _, _ -> item.first.delete(); refresh() }
                        .setNegativeButton("Vazgeç", null).show()
                }
            }.show()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "dosya"

    private fun addFiles(uris: List<Uri>) {
        val k = key ?: return
        toast("Şifreleniyor...")
        Thread {
            var ok = 0; var bad = 0; var kept = 0
            for (uri in uris) {
                val target = File(vaultDir, UUID.randomUUID().toString() + ".enc")
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
