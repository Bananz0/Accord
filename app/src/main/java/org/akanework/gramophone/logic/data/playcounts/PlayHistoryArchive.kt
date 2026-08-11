package org.akanework.gramophone.logic.data.playcounts

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream

/**
 * Reads a listening history out of an archive a service handed the user.
 *
 * Spotify, Apple Music and YouTube expose no play counts over any API - between them they offer a
 * fifty-item recently-played list and two ranked charts with no numbers on them - so the only
 * complete history any of them will give up is the export you request from your account and
 * receive days later. This turns those files into per-track tallies.
 *
 * Everything here is written to be tolerant. These are undocumented formats that change without
 * notice, arrive in several vintages at once, and are nested differently depending on which button
 * the user pressed to request them; a parser that insisted on one exact shape would be wrong within
 * a year. Unrecognised rows are skipped rather than fatal, and the import preview shows the user
 * how much was understood before anything is written.
 */
object PlayHistoryArchive {

    private const val TAG = "PlayHistoryArchive"

    /**
     * Spotify counts a stream once thirty seconds have played, and its export records every start
     * including skips. Counting those would turn a flicked-past track into listening.
     */
    private const val SPOTIFY_MIN_MS = 30_000L

    /**
     * Apple records a play event's duration too, and the same reasoning applies. Its own threshold
     * is not published, so Spotify's is borrowed rather than invented.
     */
    private const val APPLE_MIN_MS = 30_000L

    /** Guards against a wrong file - a whole Takeout, say - being walked entry by entry. */
    private const val MAX_ENTRIES = 2_000_000

    data class Parsed(
        val source: PlayCountSource,
        val tracks: List<ImportedTrack>,
        /** Rows recognised as history but unusable - no title, no artist, below the threshold. */
        val skipped: Int,
        /** Files inside the archive that were read. Shown so a wrong pick is obvious. */
        val filesRead: List<String>,
    )

    sealed interface Outcome {
        data class Success(val parsed: Parsed) : Outcome
        /** The file opened but held nothing any parser recognised. */
        data object Unrecognised : Outcome
        data class Unreadable(val message: String) : Outcome
    }

    /**
     * Reads [uri], which may be a single exported file or the whole archive as a zip.
     *
     * The zip case is the one that matters: every one of these services delivers a zip, and asking
     * a user to find `Streaming_History_Audio_2023_7.json` several folders down - when there are
     * eleven of them and all are needed - is asking them to get it wrong.
     */
    fun read(context: Context, uri: Uri, expected: PlayCountSource): Outcome {
        val name = displayName(context, uri).orEmpty()
        return try {
            context.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) return Outcome.Unreadable("Could not open the file")
                if (name.endsWith(".zip", ignoreCase = true) || looksLikeZip(uri, context)) {
                    readZip(context, uri, expected)
                } else {
                    readSingle(stream, name, expected)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read archive", e)
            Outcome.Unreadable(e.message ?: "Could not read the file")
        }
    }

    private fun readSingle(stream: InputStream, name: String, expected: PlayCountSource): Outcome {
        val text = stream.bufferedReader().readText()
        val accumulator = Accumulator()
        val handled = parseInto(text, name, expected, accumulator)
        if (!handled) return Outcome.Unrecognised
        return finish(expected, accumulator, listOf(name))
    }

    /**
     * Walks the zip, feeding every member a parser recognises.
     *
     * Streamed rather than extracted. These archives run to hundreds of megabytes and the entries
     * that matter are a fraction of that; unpacking to disk first would need space the phone may
     * not have for data that is thrown away immediately.
     */
    private fun readZip(context: Context, uri: Uri, expected: PlayCountSource): Outcome {
        val accumulator = Accumulator()
        val filesRead = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val entryName = entry.name.substringAfterLast('/')
                    if (!isInteresting(entryName, expected)) continue
                    // Not closed with use(): closing the reader closes the whole zip stream and
                    // the remaining entries with it.
                    val text = BufferedReader(zip.reader()).readText()
                    if (parseInto(text, entryName, expected, accumulator)) filesRead += entryName
                }
            }
        } ?: return Outcome.Unreadable("Could not open the archive")

        if (filesRead.isEmpty()) return Outcome.Unrecognised
        return finish(expected, accumulator, filesRead)
    }

    /** Cheap name filter, so a Takeout full of photos is not decoded looking for scrobbles. */
    private fun isInteresting(name: String, expected: PlayCountSource): Boolean {
        val lower = name.lowercase()
        return when (expected) {
            PlayCountSource.SPOTIFY ->
                lower.endsWith(".json") && lower.contains("streaminghistory") ||
                    lower.endsWith(".json") && lower.contains("streaming_history")
            PlayCountSource.APPLE_MUSIC ->
                lower.endsWith(".csv") && (lower.contains("play activity") ||
                    lower.contains("play_activity") || lower.contains("playactivity"))
            PlayCountSource.YOUTUBE_MUSIC ->
                lower.endsWith(".json") && lower.contains("watch-history") ||
                    lower.endsWith(".json") && lower.contains("watch_history")
            PlayCountSource.LAST_FM -> false
        }
    }

    private fun parseInto(
        text: String,
        name: String,
        expected: PlayCountSource,
        into: Accumulator,
    ): Boolean = when (expected) {
        PlayCountSource.SPOTIFY -> parseSpotify(text, into)
        PlayCountSource.YOUTUBE_MUSIC -> parseYouTube(text, into)
        PlayCountSource.APPLE_MUSIC -> parseAppleCsv(text, into)
        PlayCountSource.LAST_FM -> false
    }.also { if (!it) Log.d(TAG, "Nothing recognised in $name") }

    // ---------------------------------------------------------------- Spotify

    /**
     * Both vintages of Spotify's export.
     *
     * The "extended streaming history" uses `ts`/`ms_played` with the track under
     * `master_metadata_*`; the older account-data download uses `endTime`/`msPlayed` with plain
     * `artistName`/`trackName`. Users have both, often in the same folder, and neither is labelled.
     */
    private fun parseSpotify(text: String, into: Accumulator): Boolean {
        val array = text.asJsonArray() ?: return false
        var recognised = false
        for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val row = array.optJSONObject(index) ?: continue
            val extended = row.has("ms_played")
            val legacy = row.has("msPlayed")
            if (!extended && !legacy) continue
            recognised = true

            val playedMs = if (extended) row.optLong("ms_played") else row.optLong("msPlayed")
            val title = (
                row.optStringOrNull("master_metadata_track_name")
                    ?: row.optStringOrNull("trackName")
                ) ?: run { into.skip(); continue }
            val artist = (
                row.optStringOrNull("master_metadata_album_artist_name")
                    ?: row.optStringOrNull("artistName")
                ) ?: run { into.skip(); continue }
            if (playedMs < SPOTIFY_MIN_MS) { into.skip(); continue }

            val album = row.optStringOrNull("master_metadata_album_album_name")
            val at = (row.optStringOrNull("ts") ?: row.optStringOrNull("endTime"))
                ?.toEpochSeconds() ?: 0L
            into.add(artist, title, album, at)
        }
        return recognised
    }

    // ---------------------------------------------------------------- YouTube

    /**
     * Google Takeout's watch history, narrowed to YouTube Music.
     *
     * Takeout puts YouTube and YouTube Music in one file and distinguishes them only by a `header`
     * field, so this is a music import only if that is honoured. Titles arrive as "Watched <name>"
     * and the artist sits in `subtitles`, usually as the auto-generated "<artist> - Topic" channel.
     */
    private fun parseYouTube(text: String, into: Accumulator): Boolean {
        val array = text.asJsonArray() ?: return false
        var recognised = false
        for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val row = array.optJSONObject(index) ?: continue
            val header = row.optStringOrNull("header") ?: continue
            if (!header.equals("YouTube Music", ignoreCase = true)) continue
            recognised = true

            val rawTitle = row.optStringOrNull("title") ?: run { into.skip(); continue }
            // Removed by prefix rather than by locale-specific word, because a non-English Takeout
            // says something else entirely - and a title that keeps the verb matches nothing.
            val title = rawTitle.removePrefix("Watched ").trim()
            if (title.isEmpty() || title.startsWith("https://")) { into.skip(); continue }

            val channel = row.optJSONArray("subtitles")
                ?.optJSONObject(0)
                ?.optStringOrNull("name")
            if (channel == null) { into.skip(); continue }
            val artist = channel.removeSuffix(" - Topic").trim()
            if (artist.isEmpty()) { into.skip(); continue }

            val at = row.optStringOrNull("time")?.toEpochSeconds() ?: 0L
            into.add(artist, title, album = null, atSeconds = at)
        }
        return recognised
    }

    // ------------------------------------------------------------ Apple Music

    /**
     * Apple's Play Activity CSV.
     *
     * The columns are found by name rather than by position: Apple has renamed and reordered them
     * between exports, and the file is wide enough that a fixed index would silently read the
     * wrong field rather than fail. Several plausible names are accepted for each because which
     * one you get depends on the vintage of the export.
     */
    private fun parseAppleCsv(text: String, into: Accumulator): Boolean {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) return false
        val header = splitCsv(lines.next()).map { it.trim().lowercase() }

        fun column(vararg candidates: String): Int =
            candidates.firstNotNullOfOrNull { candidate ->
                header.indexOf(candidate).takeIf { it >= 0 }
            } ?: -1

        val titleAt = column("song name", "content name", "track name", "item name")
        val artistAt = column("artist name", "container artist name", "album artist name")
        val albumAt = column("album name", "container name")
        val playedAt = column("play duration milliseconds", "media duration in milliseconds")
        val timeAt = column("event start timestamp", "event end timestamp", "play date time")
        if (titleAt < 0) return false

        var count = 0
        while (lines.hasNext() && count < MAX_ENTRIES) {
            val row = splitCsv(lines.next())
            if (row.size <= titleAt) continue
            count++
            val title = row.getOrNull(titleAt)?.trim().orEmpty()
            // Apple has no artist column in some exports at all; those rows are unusable rather
            // than guessable, and saying so is better than matching every "Intro" in the library.
            val artist = artistAt.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }.orEmpty()
            if (title.isEmpty() || artist.isEmpty()) { into.skip(); continue }
            if (playedAt >= 0) {
                val played = row.getOrNull(playedAt)?.trim()?.toLongOrNull()
                if (played != null && played < APPLE_MIN_MS) { into.skip(); continue }
            }
            val album = albumAt.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }
                ?.takeIf { it.isNotEmpty() }
            val at = timeAt.takeIf { it >= 0 }
                ?.let { row.getOrNull(it) }
                ?.toEpochSeconds() ?: 0L
            into.add(artist, title, album, at)
        }
        return count > 0
    }

    /** A CSV splitter that understands quoting, because song titles contain commas. */
    private fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    // ----------------------------------------------------------------- shared

    /** Collects plays per track as the walk proceeds, so no file is held in full. */
    private class Accumulator {
        private val tracks = HashMap<String, Entry>()
        var skipped = 0
            private set

        fun skip() { skipped++ }

        fun add(artist: String, title: String, album: String?, atSeconds: Long) {
            val key = TrackKey.exact(artist, title)
            val entry = tracks.getOrPut(key) { Entry(artist, title, album) }
            entry.plays++
            if (atSeconds > entry.lastPlayed) entry.lastPlayed = atSeconds
            if (entry.album == null && album != null) entry.album = album
        }

        fun toTracks(): List<ImportedTrack> = tracks.values.map {
            ImportedTrack(
                artist = it.artist,
                title = it.title,
                album = it.album,
                plays = it.plays,
                lastPlayedSeconds = it.lastPlayed,
            )
        }

        private class Entry(val artist: String, val title: String, var album: String?) {
            var plays = 0
            var lastPlayed = 0L
        }
    }

    private fun finish(
        source: PlayCountSource,
        accumulator: Accumulator,
        filesRead: List<String>,
    ): Outcome {
        val tracks = accumulator.toTracks()
        if (tracks.isEmpty()) return Outcome.Unrecognised
        Log.d(TAG, "Parsed ${tracks.size} tracks for ${source.id} from ${filesRead.size} file(s)")
        return Outcome.Success(
            Parsed(
                source = source,
                tracks = tracks,
                skipped = accumulator.skipped,
                filesRead = filesRead,
            )
        )
    }

    private fun String.asJsonArray(): JSONArray? = try {
        when (val parsed = JSONTokener(this).nextValue()) {
            is JSONArray -> parsed
            // Some Takeout variants wrap the list in an object with a single key.
            is JSONObject -> parsed.keys().asSequence()
                .mapNotNull { parsed.optJSONArray(it) }
                .firstOrNull()
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /**
     * ISO-8601 as every one of these writes it, which is not quite the same in any two.
     *
     * Spotify's legacy export omits the zone and the seconds ("2021-03-14 09:26"); the extended one
     * and Takeout are proper instants. A timestamp that cannot be read costs only the accuracy of
     * LastPlayedDate, so it degrades to zero rather than dropping the play.
     */
    private fun String.toEpochSeconds(): Long {
        val trimmed = trim()
        if (trimmed.isEmpty()) return 0L
        runCatching { return Instant.parse(trimmed).epochSecond }
        runCatching { return Instant.parse(trimmed.replace(' ', 'T') + ":00Z").epochSecond }
        runCatching { return Instant.parse(trimmed.replace(' ', 'T') + "Z").epochSecond }
        return try {
            Instant.parse(trimmed.substringBefore(' ') + "T00:00:00Z").epochSecond
        } catch (e: DateTimeParseException) {
            0L
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The picker often reports a generic type, so the magic decides rather than the name. */
    private fun looksLikeZip(uri: Uri, context: Context): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val magic = ByteArray(2)
            stream.read(magic) == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        } == true
    } catch (e: Exception) {
        false
    }
}
