package app.phonespeaker.esphome

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Minimal protobuf writer: only the wire types the ESPHome messages we use need. */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var x = value
        while ((x and 0x7FL.inv()) != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun uint(field: Int, v: Long): PbWriter {
        if (v != 0L) { tag(field, 0); varint(v) }
        return this
    }

    fun bool(field: Int, v: Boolean): PbWriter = if (v) uint(field, 1) else this

    fun string(field: Int, s: String): PbWriter {
        if (s.isEmpty()) return this
        val b = s.toByteArray(Charsets.UTF_8)
        tag(field, 2); varint(b.size.toLong()); out.write(b)
        return this
    }

    /** fixed32 is always written, even when 0: entity keys are marked (force) in api.proto. */
    fun fixed32(field: Int, v: Int): PbWriter {
        tag(field, 5)
        for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF)
        return this
    }

    fun float(field: Int, v: Float): PbWriter =
        if (v == 0f) this else fixed32(field, java.lang.Float.floatToRawIntBits(v))

    fun bytes(): ByteArray = out.toByteArray()
}

/** Minimal protobuf reader: collects the last value seen for each field number. */
class PbReader(private val buf: ByteArray) {
    val varints = HashMap<Int, Long>()
    val strings = HashMap<Int, String>()
    val fixed32s = HashMap<Int, Int>()
    private var pos = 0

    init {
        try {
            while (pos < buf.size) {
                val key = readVarint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> varints[field] = readVarint()
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint().toInt()
                        strings[field] = String(buf, pos, len, Charsets.UTF_8)
                        pos += len
                    }
                    5 -> {
                        var v = 0
                        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
                        fixed32s[field] = v
                        pos += 4
                    }
                    else -> break
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // truncated message: keep what we parsed
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
    }

    fun bool(field: Int): Boolean = (varints[field] ?: 0L) != 0L
    fun float(field: Int): Float = java.lang.Float.intBitsToFloat(fixed32s[field] ?: 0)
}

/** ESPHome plaintext framing: 0x00, varint(length), varint(type), payload. */
object Frames {
    fun encode(type: Int, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0)
        writeVarint(o, payload.size)
        writeVarint(o, type)
        o.write(payload)
        return o.toByteArray()
    }

    private fun writeVarint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x and 0x7F.inv() != 0) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
        o.write(x)
    }

    fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
    }
}
