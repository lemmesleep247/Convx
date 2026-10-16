package com.convx.music.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convx.music.R
import com.convx.music.canvas.ApplePlaylist
import com.convx.music.canvas.AppleMusicCatalog
import com.convx.music.canvas.AppleMusicPlaylist
import com.convx.music.canvas.ApplePlaylistExport
import com.convx.music.canvas.ApplePlaylistLink
import com.convx.music.canvas.ApplePlaylistTrack
import com.convx.music.db.MusicDatabase
import com.convx.music.models.MediaMetadata
import com.convx.music.models.toMediaMetadata
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.spotify.SpotifyMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_SONG_MATCH_SCORE = 0.78

internal fun chooseBestMatch(track: ApplePlaylistTrack, candidates: List<SongItem>): SongItem? {
    val normalizedTitle = AppleMusicCatalog.normalize(track.title)
    val seenIds = mutableSetOf<String>()
    var bestSong: SongItem? = null
    var bestScore = Double.NEGATIVE_INFINITY

    for (candidate in candidates) {
        if (!seenIds.add(candidate.id)) continue
        if (AppleMusicCatalog.normalize(candidate.title) != normalizedTitle) continue

        val candidateArtists = candidate.artists.joinToString(", ") { it.name }
        if (!AppleMusicCatalog.artistMatches(track.artist, candidateArtists)) continue

        val score = SpotifyMapper.matchScore(
            track.title,
            track.artist,
            track.durationMs,
            candidate.title,
            candidate.artists.joinToString(" ") { it.name },
            candidate.duration,
        )
        if (bestSong == null || score > bestScore) {
            bestSong = candidate
            bestScore = score
        }
    }

    return bestSong?.takeIf { bestScore >= MIN_SONG_MATCH_SCORE }
}

data class AppleMusicImportState(
    val session: Int = 0,
    val visible: Boolean = false,
    val url: String = "",
    val busy: Boolean = false,
    val title: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val songs: List<MediaMetadata> = emptyList(),
    val ready: Boolean = false,
    val choosingDestination: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
class AppleMusicImportViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppleMusicImportState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun open() {
        if (state.value.busy) return
        mutableState.value = AppleMusicImportState(session = state.value.session + 1, visible = true)
    }

    fun updateUrl(url: String) {
        mutableState.update { it.copy(url = url, error = null) }
    }

    fun dismiss() {
        job?.cancel()
        mutableState.value = AppleMusicImportState(session = state.value.session)
    }

    fun chooseDestination() {
        mutableState.update { it.copy(visible = false, choosingDestination = true) }
    }

    fun load() {
        if (state.value.busy) return
        val link = ApplePlaylistLink.parse(state.value.url)
        if (link == null) {
            mutableState.update { it.copy(error = R.string.apple_music_invalid_link) }
            return
        }
        startLoad { AppleMusicPlaylist.fetch(link) }
    }

    fun loadExport(uri: Uri) {
        startLoad(R.string.apple_music_export_failed) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    currentCoroutineContext().ensureActive()
                    require(output.size() + count <= ApplePlaylistExport.MAX_BYTES) { "Export too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Export is unreadable")
            ApplePlaylistExport.parse(bytes)
        }
    }

    private fun startLoad(error: Int = R.string.apple_music_import_failed, fetch: suspend () -> ApplePlaylist) {
        if (state.value.busy) return
        mutableState.update {
            it.copy(
                busy = true,
                error = null,
                ready = false,
                songs = emptyList(),
                completed = 0,
                total = 0,
            )
        }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val playlist = fetch()
                ensureActive()
                mutableState.update {
                    it.copy(
                        title = playlist.title,
                        total = playlist.tracks.size,
                        skipped = playlist.unsupportedTracks,
                    )
                }
                val matched = mutableListOf<MediaMetadata>()
                playlist.tracks.forEachIndexed { index, track ->
                    ensureActive()
                    val best = findBestMatch(track)
                    if (best != null) matched += best.toMediaMetadata()
                    mutableState.update {
                        it.copy(
                            completed = index + 1,
                            skipped = it.skipped + if (best == null) 1 else 0,
                        )
                    }
                    if (index < playlist.tracks.lastIndex) delay(150)
                }
                mutableState.update { it.copy(busy = false, ready = true, songs = matched.toList()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(busy = false, error = error) }
            }
        }
    }

    private suspend fun findBestMatch(track: ApplePlaylistTrack): SongItem? {
        val results = YouTube.search(
            "${track.artist} ${track.title}",
            SearchFilter.FILTER_SONG,
        ).getOrThrow()
        return chooseBestMatch(track, results.items.filterIsInstance<SongItem>())
    }

    suspend fun persistSongs(): List<String> = withContext(Dispatchers.IO) {
        val songs = state.value.songs
        database.withTransaction { songs.forEach { insert(it) } }
        songs.map { it.id }
    }
}
