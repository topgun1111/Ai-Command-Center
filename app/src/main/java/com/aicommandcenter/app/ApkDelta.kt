package com.aicommandcenter.app

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** ACCDLT01: bounded COPY and LITERAL operations over an exact base APK. */
object ApkDelta {
    fun digest(file: File): String = file.inputStream().buffered().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun apply(base: File, patch: File, output: File, expectedSize: Long) {
        require(expectedSize in 1..UpdateRules.MAX_APK_BYTES)
        require(base.length() in 1..UpdateRules.MAX_APK_BYTES && patch.length() in 49..UpdateRules.MAX_APK_BYTES)
        require(output.canonicalFile != base.canonicalFile && output.canonicalFile != patch.canonicalFile)
        try {
            DataInputStream(patch.inputStream().buffered()).use { input ->
                val magic = ByteArray(8); input.readFully(magic)
                require(magic.contentEquals("ACCDLT01".toByteArray(Charsets.US_ASCII))) { "Unsupported delta format" }
                val baseHash = ByteArray(32); input.readFully(baseHash)
                require(baseHash.joinToString("") { "%02x".format(it.toInt() and 255) } == digest(base)) { "Installed APK does not match patch base" }
                require(input.readLong() == expectedSize) { "Wrong reconstructed APK size" }
                RandomAccessFile(base, "r").use { old ->
                    output.outputStream().buffered().use { out ->
                        val buffer = ByteArray(65536)
                        var written = 0L
                        while (true) {
                            val op = input.readUnsignedByte()
                            if (op == 0) break
                            require(op == 1 || op == 2) { "Unknown delta operation" }
                            val offset = if (op == 1) input.readLong() else 0L
                            val length = input.readInt().toLong()
                            require(length > 0 && length <= expectedSize - written) { "Delta output exceeds expected size" }
                            if (op == 1) {
                                require(offset >= 0 && offset <= old.length() && length <= old.length() - offset) { "Delta copy out of bounds" }
                                old.seek(offset)
                            }
                            var remaining = length
                            while (remaining > 0) {
                                val count = minOf(remaining, buffer.size.toLong()).toInt()
                                if (op == 1) old.readFully(buffer, 0, count) else input.readFully(buffer, 0, count)
                                out.write(buffer, 0, count); remaining -= count
                            }
                            written += length
                        }
                        require(written == expectedSize && input.read() == -1) { "Incomplete or trailing delta data" }
                    }
                }
            }
        } catch (e: Exception) { output.delete(); throw e }
    }
}
