package com.arindam.camerax.data.camera

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/**
 * Data: writes an Apple Live Photo pair — a still whose EXIF Apple MakerNote carries the content
 * identifier plus a `.mov` sibling whose `moov/meta` carries the same identifier and a
 * `com.apple.quicktime.still-image-time` timed metadata track. That pair is what Photos,
 * iCloud and macOS read as a Live Photo (Android Motion Photo v1 stays in
 * [MotionPhotoMuxer] as the fallback).
 *
 * Runs on the camera executor / IO dispatcher — never from Compose or a click handler.
 * The byte-level writers return `false` instead of throwing so the caller can fall back to a
 * plain still without losing the capture.
 */
object AppleLivePhotoWriter {

    /** Sibling video extension used by Apple Live Photos. */
    const val EXTENSION = "mov"

    private const val CONTENT_IDENTIFIER_KEY =
        "com.apple.quicktime.content.identifier"
    private const val STILL_IMAGE_TIME_KEY =
        "com.apple.quicktime.still-image-time"

    /**
     * Timed metadata sample Apple writes for the still frame. Apple stores the still time as a
     * nine-byte payload; the trailing `0xFF` marks this sample as the still.
     */
    private val STILL_IMAGE_TIME_SAMPLE = byteArrayOf(
        0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x01, 0xFF.toByte()
    )

    private const val TAG_MARKER_NOTE = 0x927C
    private const val TAG_EXIF_IFD = 0x8769
    private const val TYPE_ASCII = 2
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_LONG = 4

    /** Uppercase UUID, the same text Apple stores in the still MakerNote and the clip. */
    fun newIdentifier(): String = UUID.randomUUID().toString().uppercase(Locale.US)

    /** `<name>.mov` next to `<name>.jpg` — the pair layout iPhone uses on disk. */
    fun companionFor(still: File): File =
        File(still.parentFile, still.nameWithoutExtension + "." + EXTENSION)

    fun isLivePhoto(still: File): Boolean {
        if (!still.isStill()) return false
        val companion = companionFor(still)
        return companion.exists() && companion.length() > 0L
    }

    /**
     * Rewrites [still]'s Exif APP1 segment with an Apple MakerNote carrying [identifier].
     * Returns false when the still has no Exif segment (never fabricate one).
     */
    fun attachIdentifier(still: File, identifier: String): Boolean {
        if (!still.exists() || still.length() < 4L) return false
        val makerNote = appleMakerNote(identifier) ?: return false
        val temp = File(still.parentFile, still.nameWithoutExtension + ".live.tmp")
        var patched = false
        try {
            RandomAccessFile(still, "r").use { input ->
                temp.outputStream().buffered().use { output ->
                    if (input.readUnsignedByte() != 0xFF ||
                        input.readUnsignedByte() != 0xD8
                    ) {
                        return false
                    }
                    output.write(0xFF)
                    output.write(0xD8)
                    patched = copyJpeg(input, output, makerNote)
                }
            }
        } catch (error: Exception) {
            temp.delete()
            return false
        }
        if (!patched) {
            temp.delete()
            return false
        }
        return replace(still, temp)
    }

    /**
     * Copies [source] to [output] as a `.mov` carrying the Live Photo metadata.
     *
     * The clip is streamed box by box (no full-file load). A new top-level `mdat` holds the
     * still-image-time sample, so `moov` must be the last box in the source (MediaMuxer always
     * writes it last); when it is not, the new `moov` is padded to its original size so the
     * existing chunk offsets stay valid, and the write is refused if that is not possible.
     */
    fun writeVideo(source: File, output: File, identifier: String): Boolean {
        if (!source.exists() || source.length() <= 0L) return false
        return try {
            val boxes = topLevelBoxes(source)
            val moov = boxes.firstOrNull { it.type == BOX_MOOV } ?: return false
            val moovBytes = readRange(source, moov.start + moov.header, moov.size - moov.header)
            if (moovBytes.isEmpty()) return false
            val moovIsLast = boxes.last().type == BOX_MOOV

            val draft = buildMoov(moovBytes, identifier, chunkOffset = 0L) ?: return false
            val sizeWithDraft = boxes.sumOf { box ->
                if (box.type == BOX_MOOV) draft.size.toLong() else box.size
            }
            val sampleOffset = sizeWithDraft + MDAT_HEADER
            val finalMoov = buildMoov(moovBytes, identifier, sampleOffset) ?: return false
            if (finalMoov.size != draft.size) return false

            val padding = if (!moovIsLast) moov.size - finalMoov.size else 0L
            if (!moovIsLast && padding < 0L) return false
            val writtenMoov = if (padding > 0L) padMoov(finalMoov, padding) ?: return false else finalMoov

            RandomAccessFile(source, "r").use { input ->
                output.outputStream().buffered().use { out ->
                    for (box in boxes) {
                        if (box.type == BOX_MOOV) {
                            out.write(writtenMoov)
                        } else {
                            input.seek(box.start)
                            copyExact(input, out, box.size)
                        }
                    }
                    out.write(intBytes(8 + STILL_IMAGE_TIME_SAMPLE.size, ByteOrder.BIG_ENDIAN))
                    out.write(BOX_MDAT.toByteArray(Charsets.ISO_8859_1))
                    out.write(STILL_IMAGE_TIME_SAMPLE)
                }
            }
            output.length() > 0L
        } catch (error: Exception) {
            output.delete()
            false
        }
    }

    // ---- JPEG / Exif -------------------------------------------------------------------

    /**
     * Streams the JPEG writing a rebuilt Exif APP1 in place of the original. Returns whether the
     * Exif segment was found and replaced.
     */
    private fun copyJpeg(input: RandomAccessFile, output: OutputStream, makerNote: ByteArray): Boolean {
        var patched = false
        while (true) {
            val markerStart = input.read()
            if (markerStart < 0) break
            if (markerStart != 0xFF) {
                // Entropy-coded data without a leading marker: copy the rest verbatim.
                output.write(markerStart)
                copyExact(input, output, input.length() - input.filePointer)
                break
            }
            var marker = input.read()
            while (marker == 0xFF) marker = input.read()
            if (marker < 0) break
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                output.write(0xFF)
                output.write(marker)
                continue
            }
            val lengthHi = input.read()
            val lengthLo = input.read()
            if (lengthHi < 0 || lengthLo < 0) break
            val length = (lengthHi shl 8) or lengthLo
            if (length < 2) break
            val payload = ByteArray(length - 2)
            if (!readFully(input, payload)) break
            if (!patched && marker == 0xE1 && isExifPayload(payload)) {
                val tiff = payload.copyOfRange(6, payload.size)
                val rebuilt = tiffWithMakerNote(tiff, makerNote)
                if (rebuilt != null) {
                    writeSegment(output, marker, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + rebuilt)
                    patched = true
                    continue
                }
            }
            writeSegment(output, marker, payload)
            if (marker == 0xDA) {
                // Start of scan: everything left is compressed image data.
                copyExact(input, output, input.length() - input.filePointer)
                break
            }
        }
        return patched
    }

    private fun isExifPayload(payload: ByteArray): Boolean =
        payload.size > 14 &&
            payload[0] == 0x45.toByte() && // E
            payload[1] == 0x78.toByte() && // x
            payload[2] == 0x69.toByte() && // i
            payload[3] == 0x66.toByte() && // f
            payload[4] == 0x00.toByte() &&
            payload[5] == 0x00.toByte()

    private fun writeSegment(output: OutputStream, marker: Int, payload: ByteArray) {
        val length = payload.size + 2
        output.write(0xFF)
        output.write(marker)
        output.write((length shr 8) and 0xFF)
        output.write(length and 0xFF)
        output.write(payload)
    }

    /**
     * Inserts an Apple MakerNote into the Exif IFD without moving any existing data: the original
     * TIFF block is kept verbatim, a rebuilt IFD0 (pointing at a rebuilt Exif IFD) is appended,
     * and the TIFF header's IFD0 offset is repointed at it.
     */
    private fun tiffWithMakerNote(tiff: ByteArray, makerNote: ByteArray): ByteArray? {
        val order = when {
            tiff.size > 8 && tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() ->
                ByteOrder.LITTLE_ENDIAN
            tiff.size > 8 && tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() ->
                ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val buffer = ByteBuffer.wrap(tiff).order(order)
        if (buffer.getShort(2).toInt() != 42) return null
        val ifd0Offset = buffer.getInt(4)
        val ifd0 = readIfd(tiff, order, ifd0Offset) ?: return null
        val exifPointer = ifd0.entries.firstOrNull { it.tag == TAG_EXIF_IFD }
        val exifIfd = exifPointer?.let { readIfd(tiff, order, intValue(it, order)) }

        val ifd0Entries = ifd0.entries.filterNot { it.tag == TAG_EXIF_IFD }.toMutableList()
        ifd0Entries += IfdEntry(TAG_EXIF_IFD, TYPE_LONG, 1, ByteArray(4))
        ifd0Entries.sortBy { it.tag }

        val exifEntries = (exifIfd?.entries ?: emptyList())
            .filterNot { it.tag == TAG_MARKER_NOTE }
            .toMutableList()
        exifEntries += IfdEntry(TAG_MARKER_NOTE, TYPE_UNDEFINED, makerNote.size, ByteArray(4))
        exifEntries.sortBy { it.tag }

        val base = align4(tiff.size)
        val ifd0Size = 2 + ifd0Entries.size * 12 + 4
        val exifOffset = base + ifd0Size
        val exifSize = 2 + exifEntries.size * 12 + 4
        val makerNoteOffset = exifOffset + exifSize

        val newIfd0 = ifd0Entries.map { entry ->
            if (entry.tag == TAG_EXIF_IFD) entry.copy(valueField = intBytes(exifOffset, order)) else entry
        }
        val newExif = exifEntries.map { entry ->
            if (entry.tag == TAG_MARKER_NOTE) {
                entry.copy(valueField = intBytes(makerNoteOffset, order))
            } else {
                entry
            }
        }

        val output = ByteArray(makerNoteOffset + makerNote.size)
        tiff.copyInto(output, 0, 0, tiff.size)
        ByteBuffer.wrap(output).order(order).putInt(4, base)
        writeIfd(output, order, base, newIfd0, ifd0.nextOffset)
        writeIfd(output, order, exifOffset, newExif, exifIfd?.nextOffset ?: 0)
        makerNote.copyInto(output, makerNoteOffset)
        return output
    }

    /**
     * Apple MakerNote: `Apple iOS\0`, two version bytes, then a self-contained big-endian TIFF
     * whose single IFD entry is tag 17 (0x0011) = the content identifier string.
     */
    private fun appleMakerNote(identifier: String): ByteArray? {
        val value = identifier.toByteArray(Charsets.US_ASCII)
        if (value.isEmpty() || value.size > 255) return null
        val header = "Apple iOS\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00, 0x01)
        val tiff = ByteBuffer.allocate(26 + value.size).order(ByteOrder.BIG_ENDIAN)
        tiff.put('M'.code.toByte()).put('M'.code.toByte())
        tiff.putShort(42)
        tiff.putInt(8)                       // IFD0 offset, relative to the TIFF header
        tiff.putShort(1)                     // one entry
        tiff.putShort(0x0011)                // Apple ContentIdentifier
        tiff.putShort(TYPE_ASCII.toShort())
        tiff.putInt(value.size)
        tiff.putInt(26)                      // value offset, relative to the TIFF header
        tiff.putInt(0)                       // no next IFD
        tiff.put(value)
        return header + tiff.array()
    }

    // ---- MOV ---------------------------------------------------------------------------

    private fun buildMoov(original: ByteArray, identifier: String, chunkOffset: Long): ByteArray? {
        val children = childBoxes(original)
        val (movieTimescale, movieDuration) = children.firstOrNull { it.type == BOX_MVHD }
            ?.let { readMvhd(original, it) }
            ?: (600 to 600L)
        val mediaDuration = if (movieTimescale > 0) {
            ((movieDuration.toDouble() / movieTimescale) * MOVIE_TIMESCALE)
                .toLong()
                .coerceAtLeast(1L)
        } else {
            MOVIE_TIMESCALE.toLong()
        }
        val meta = metaBox(identifier)
        val trak = stillImageTimeTrak(movieDuration, mediaDuration, chunkOffset)
        val payload = ByteArrayOutputStreamWriter()
        payload.write(original)
        payload.write(meta)
        payload.write(trak)
        return box(BOX_MOOV, payload.toByteArray())
    }

    /** Pads a rewritten `moov` with a `free` child so chunk offsets elsewhere stay valid. */
    private fun padMoov(moov: ByteArray, totalPadding: Long): ByteArray? {
        if (totalPadding < 8L) return null
        val payload = ByteArrayOutputStreamWriter()
        payload.write(moov, 8, moov.size - 8)
        payload.write(box(BOX_FREE, ByteArray((totalPadding - 8L).toInt())))
        return box(BOX_MOOV, payload.toByteArray())
    }

    private fun metaBox(identifier: String): ByteArray {
        val hdlr = fullBox(
            BOX_HDLR, version = 0, flags = 0,
            intBytes(0, ByteOrder.BIG_ENDIAN) +
                BOX_MDTA.toByteArray(Charsets.ISO_8859_1) +
                ByteArray(12) +
                byteArrayOf(0x00)
        )
        val keyName = CONTENT_IDENTIFIER_KEY.toByteArray(Charsets.US_ASCII)
        val keys = fullBox(
            BOX_KEYS, version = 0, flags = 0,
            intBytes(1, ByteOrder.BIG_ENDIAN) +
                intBytes(keyName.size + 8, ByteOrder.BIG_ENDIAN) +
                BOX_MDTA.toByteArray(Charsets.ISO_8859_1) +
                keyName
        )
        val data = box(
            BOX_DATA,
            intBytes(1, ByteOrder.BIG_ENDIAN) + // well-known type: UTF-8 text
                intBytes(0, ByteOrder.BIG_ENDIAN) + // locale
                identifier.toByteArray(Charsets.US_ASCII)
        )
        val item = boxContent(intBytes(1, ByteOrder.BIG_ENDIAN) + data)
        val ilst = box(BOX_ILST, item)
        return fullBox(BOX_META, version = 0, flags = 0, hdlr + keys + ilst)
    }

    private fun stillImageTimeTrak(
        movieDuration: Long,
        mediaDuration: Long,
        chunkOffset: Long
    ): ByteArray {
        val tkhd = fullBox(
            BOX_TKHD, version = 0, flags = 0x000003,
            intBytes(0, ByteOrder.BIG_ENDIAN) + // creation time
                intBytes(0, ByteOrder.BIG_ENDIAN) + // modification time
                intBytes(TRACK_ID, ByteOrder.BIG_ENDIAN) +
                intBytes(0, ByteOrder.BIG_ENDIAN) + // reserved
                intBytes(movieDuration.toInt(), ByteOrder.BIG_ENDIAN) +
                ByteArray(8) + // reserved
                byteArrayOf(0, 0, 0, 0) + // layer, alternate group
                byteArrayOf(0, 0, 0, 0) + // volume, reserved
                UNITY_MATRIX +
                intBytes(0, ByteOrder.BIG_ENDIAN) + // width
                intBytes(0, ByteOrder.BIG_ENDIAN) // height
        )
        val mdhd = fullBox(
            BOX_MDHD, version = 0, flags = 0,
            intBytes(0, ByteOrder.BIG_ENDIAN) +
                intBytes(0, ByteOrder.BIG_ENDIAN) +
                intBytes(MOVIE_TIMESCALE, ByteOrder.BIG_ENDIAN) +
                intBytes(mediaDuration.toInt(), ByteOrder.BIG_ENDIAN) +
                byteArrayOf(0x55, 0xC4.toByte()) + // 'und'
                byteArrayOf(0, 0)
        )
        val hdlr = fullBox(
            BOX_HDLR, version = 0, flags = 0,
            intBytes(0, ByteOrder.BIG_ENDIAN) +
                BOX_META.toByteArray(Charsets.ISO_8859_1) +
                ByteArray(12) +
                byteArrayOf(0x00) // empty name
        )
        val stsd = fullBox(
            BOX_STSD, version = 0, flags = 0,
            intBytes(1, ByteOrder.BIG_ENDIAN) +
                box(
                    BOX_MEBX,
                    ByteArray(6) + // reserved
                        byteArrayOf(0, 1) // data reference index
                )
        )
        val stts = fullBox(
            BOX_STTS, version = 0, flags = 0,
            intBytes(1, ByteOrder.BIG_ENDIAN) +
                intBytes(1, ByteOrder.BIG_ENDIAN) + // sample count
                intBytes(mediaDuration.toInt(), ByteOrder.BIG_ENDIAN) // sample delta
        )
        val stsc = fullBox(
            BOX_STSC, version = 0, flags = 0,
            intBytes(1, ByteOrder.BIG_ENDIAN) +
                intBytes(1, ByteOrder.BIG_ENDIAN) + // first chunk
                intBytes(1, ByteOrder.BIG_ENDIAN) + // samples per chunk
                intBytes(1, ByteOrder.BIG_ENDIAN) // sample description index
        )
        val stsz = fullBox(
            BOX_STSZ, version = 0, flags = 0,
            intBytes(STILL_IMAGE_TIME_SAMPLE.size, ByteOrder.BIG_ENDIAN) +
                intBytes(1, ByteOrder.BIG_ENDIAN) +
                intBytes(STILL_IMAGE_TIME_SAMPLE.size, ByteOrder.BIG_ENDIAN)
        )
        val stco = fullBox(
            BOX_STCO, version = 0, flags = 0,
            intBytes(1, ByteOrder.BIG_ENDIAN) +
                intBytes(chunkOffset.toInt(), ByteOrder.BIG_ENDIAN)
        )
        val stbl = box(BOX_STBL, stsd + stts + stsc + stsz + stco)
        val nmhd = fullBox(BOX_NMHD, version = 0, flags = 0, ByteArray(0))
        val minf = box(BOX_MINF, nmhd + stbl)
        val mdia = box(BOX_MDIA, mdhd + hdlr + minf)
        return box(BOX_TRAK, tkhd + mdia)
    }

    private fun readMvhd(moov: ByteArray, box: Box): Pair<Int, Long> {
        val payload = box.offset + box.header + 4
        if (payload + 20 > moov.size || box.offset + box.size > moov.size) return 600 to 600L
        val version = moov[box.offset + box.header].toInt() and 0xFF
        val buffer = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
        return if (version == 1) {
            if (payload + 28 > moov.size) return 600 to 600L
            val timescale = buffer.getInt(payload + 16)
            val duration = buffer.getLong(payload + 20)
            if (timescale <= 0) 600 to 600L else timescale to duration
        } else {
            val timescale = buffer.getInt(payload + 8)
            val duration = buffer.getInt(payload + 12).toLong() and 0xFFFFFFFFL
            if (timescale <= 0) 600 to 600L else timescale to duration
        }
    }

    // ---- Box helpers -------------------------------------------------------------------

    private data class Box(
        val type: String,
        val offset: Int,
        val size: Int,
        val header: Int
    )

    private fun childBoxes(bytes: ByteArray): List<Box> = parseBoxes(bytes, 0, bytes.size)

    private fun parseBoxes(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = from
        while (offset + 8 <= to) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            var size = buffer.getInt(offset)
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1) {
                if (offset + 16 > to) break
                size = buffer.getLong(offset + 8).toInt()
                header = 16
            } else if (size == 0) {
                size = to - offset
            }
            if (size < header || offset + size > to) break
            boxes += Box(type, offset, size, header)
            offset += size
        }
        return boxes
    }

    private data class TopBox(
        val type: String,
        val start: Long,
        val size: Long,
        val header: Int
    )

    private fun topLevelBoxes(file: File): List<TopBox> {
        val boxes = mutableListOf<TopBox>()
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            var position = 0L
            while (position + 8L <= length) {
                input.seek(position + 4L)
                val type = String(readBytes(input, 4), Charsets.ISO_8859_1)
                input.seek(position)
                var size = readUnsignedInt(input)
                var header = 8
                if (size == 1L) {
                    size = input.readLong()
                    header = 16
                } else if (size == 0L) {
                    size = length - position
                }
                if (size < header || position + size > length) break
                boxes += TopBox(type, position, size, header)
                position += size
            }
        }
        return boxes
    }

    private fun readRange(file: File, start: Long, length: Long): ByteArray {
        val bytes = ByteArray(length.toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            if (!readFully(input, bytes)) return ByteArray(0)
        }
        return bytes
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        intBytes(8 + payload.size, ByteOrder.BIG_ENDIAN) +
            type.toByteArray(Charsets.ISO_8859_1) +
            payload

    /** Item inside `ilst`: length + payload, with no type field of its own. */
    private fun boxContent(payload: ByteArray): ByteArray =
        intBytes(8 + payload.size, ByteOrder.BIG_ENDIAN) + payload

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        intBytes(8 + 4 + payload.size, ByteOrder.BIG_ENDIAN) +
            type.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                version.toByte(),
                ((flags shr 16) and 0xFF).toByte(),
                ((flags shr 8) and 0xFF).toByte(),
                (flags and 0xFF).toByte()
            ) +
            payload

    private fun intBytes(value: Int, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(4).order(order).putInt(value).array()

    private fun intValue(entry: IfdEntry, order: ByteOrder): Int =
        ByteBuffer.wrap(entry.valueField).order(order).int

    // ---- TIFF helpers ------------------------------------------------------------------

    private data class IfdEntry(
        val tag: Int,
        val type: Int,
        val count: Int,
        val valueField: ByteArray
    )

    private data class Ifd(val entries: List<IfdEntry>, val nextOffset: Int)

    private fun readIfd(bytes: ByteArray, order: ByteOrder, offset: Int): Ifd? {
        if (offset < 8 || offset + 2 > bytes.size) return null
        val buffer = ByteBuffer.wrap(bytes).order(order)
        val count = buffer.getShort(offset).toInt() and 0xFFFF
        if (offset + 2 + count * 12 + 4 > bytes.size) return null
        val entries = ArrayList<IfdEntry>(count)
        for (index in 0 until count) {
            val entryOffset = offset + 2 + index * 12
            entries += IfdEntry(
                tag = buffer.getShort(entryOffset).toInt() and 0xFFFF,
                type = buffer.getShort(entryOffset + 2).toInt() and 0xFFFF,
                count = buffer.getInt(entryOffset + 4),
                valueField = bytes.copyOfRange(entryOffset + 8, entryOffset + 12)
            )
        }
        return Ifd(entries, buffer.getInt(offset + 2 + count * 12))
    }

    private fun writeIfd(
        output: ByteArray,
        order: ByteOrder,
        offset: Int,
        entries: List<IfdEntry>,
        nextOffset: Int
    ) {
        val buffer = ByteBuffer.wrap(output).order(order)
        buffer.putShort(offset, entries.size.toShort())
        entries.forEachIndexed { index, entry ->
            val entryOffset = offset + 2 + index * 12
            buffer.putShort(entryOffset, entry.tag.toShort())
            buffer.putShort(entryOffset + 2, entry.type.toShort())
            buffer.putInt(entryOffset + 4, entry.count)
            entry.valueField.copyInto(output, entryOffset + 8)
        }
        buffer.putInt(offset + 2 + entries.size * 12, nextOffset)
    }

    // ---- IO helpers --------------------------------------------------------------------

    private fun readFully(input: RandomAccessFile, target: ByteArray): Boolean {
        var read = 0
        while (read < target.size) {
            val count = input.read(target, read, target.size - read)
            if (count <= 0) return false
            read += count
        }
        return true
    }

    private fun readBytes(input: RandomAccessFile, count: Int): ByteArray {
        val bytes = ByteArray(count)
        readFully(input, bytes)
        return bytes
    }

    private fun readUnsignedInt(input: RandomAccessFile): Long =
        input.readInt().toLong() and 0xFFFFFFFFL

    private fun copyExact(input: RandomAccessFile, output: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun replace(target: File, replacement: File): Boolean {
        if (target.exists() && !target.delete()) {
            replacement.delete()
            return false
        }
        if (!replacement.renameTo(target)) {
            replacement.delete()
            return false
        }
        return true
    }

    private fun align4(value: Int): Int = (value + 3) and -4

    private fun File.isStill(): Boolean =
        extension.equals("jpg", ignoreCase = true) ||
            extension.equals("jpeg", ignoreCase = true)

    private const val MOVIE_TIMESCALE = 600
    private const val TRACK_ID = 2
    private const val MDAT_HEADER = 8L

    private const val BOX_MOOV = "moov"
    private const val BOX_MDAT = "mdat"
    private const val BOX_FREE = "free"
    private const val BOX_MVHD = "mvhd"
    private const val BOX_META = "meta"
    private const val BOX_MDTA = "mdta"
    private const val BOX_HDLR = "hdlr"
    private const val BOX_KEYS = "keys"
    private const val BOX_ILST = "ilst"
    private const val BOX_DATA = "data"
    private const val BOX_TRAK = "trak"
    private const val BOX_TKHD = "tkhd"
    private const val BOX_MDIA = "mdia"
    private const val BOX_MDHD = "mdhd"
    private const val BOX_MINF = "minf"
    private const val BOX_NMHD = "nmhd"
    private const val BOX_STBL = "stbl"
    private const val BOX_STSD = "stsd"
    private const val BOX_MEBX = "mebx"
    private const val BOX_STTS = "stts"
    private const val BOX_STSC = "stsc"
    private const val BOX_STSZ = "stsz"
    private const val BOX_STCO = "stco"

    private val UNITY_MATRIX = byteArrayOf(
        0x00, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x40, 0x00, 0x00, 0x00
    )

    /** Minimal growable byte sink; keeps the box builders free of stream plumbing. */
    private class ByteArrayOutputStreamWriter {
        private var buffer = ByteArray(1024)
        private var size = 0

        fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensure(size + length)
            bytes.copyInto(buffer, size, offset, offset + length)
            size += length
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(capacity: Int) {
            if (capacity <= buffer.size) return
            var next = buffer.size
            while (next < capacity) next *= 2
            buffer = buffer.copyOf(next)
        }
    }
}
