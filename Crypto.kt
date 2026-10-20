package com.hesap.calc

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Şifreden AES-256 anahtarı türetir (PBKDF2) ve dosyaları 64 KB'lık
 * parçalar halinde AES-GCM ile şifreler. Dosya adı da şifreli saklanır.
 *
 * Format: [8 bayt rastgele önek][parça0 = ad][parça1..n = veri]
 * Parça: [4 bayt uzunluk][şifreli veri + 16 bayt etiket]
 */
object Crypto {
    private const val CHUNK = 64 * 1024
    private val rnd = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        prefix + ByteBuffer.allocate(4).putInt(counter).array()

    private fun writeChunk(out: DataOutputStream, key: SecretKeySpec, prefix: ByteArray, counter: Int, data: ByteArray, len: Int) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
        val enc = c.doFinal(data, 0, len)
        out.writeInt(enc.size)
        out.write(enc)
    }

    private fun readChunk(inp: DataInputStream, key: SecretKeySpec, prefix: ByteArray, counter: Int): ByteArray? {
        val size = try { inp.readInt() } catch (e: EOFException) { return null }
        if (size < 16 || size > CHUNK + 16) throw IOException("Bozuk dosya")
        val buf = ByteArray(size)
        inp.readFully(buf)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
        return c.doFinal(buf)
    }

    private fun fill(i: InputStream, b: ByteArray): Int {
        var t = 0
        while (t < b.size) {
            val n = i.read(b, t, b.size - t)
            if (n < 0) break
            t += n
        }
        return t
    }

    fun encrypt(key: SecretKeySpec, name: String, input: InputStream, output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output))
        val prefix = ByteArray(8).also { rnd.nextBytes(it) }
        out.write(prefix)
        val nb = name.toByteArray(Charsets.UTF_8)
        writeChunk(out, key, prefix, 0, nb, nb.size)
        val buf = ByteArray(CHUNK)
        var counter = 1
        while (true) {
            val n = fill(input, buf)
            if (n <= 0) break
            writeChunk(out, key, prefix, counter++, buf, n)
        }
        out.flush()
    }

    /** Yanlış şifrede veya bozuk dosyada exception fırlatır. */
    fun readName(key: SecretKeySpec, file: File): String =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
            val prefix = ByteArray(8)
            inp.readFully(prefix)
            String(readChunk(inp, key, prefix, 0) ?: throw IOException("Boş"), Charsets.UTF_8)
        }

    fun decrypt(key: SecretKeySpec, file: File, output: OutputStream) {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
            val prefix = ByteArray(8)
            inp.readFully(prefix)
            readChunk(inp, key, prefix, 0) ?: throw IOException("Boş")
            var counter = 1
            while (true) {
                val data = readChunk(inp, key, prefix, counter++) ?: break
                output.write(data)
            }
            output.flush()
        }
    }
}
