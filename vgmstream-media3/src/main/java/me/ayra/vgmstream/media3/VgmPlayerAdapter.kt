package me.ayra.vgmstream.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import me.ayra.vgmstream.AudioTrackVgmPlayer
import me.ayra.vgmstream.VgmSettings

@UnstableApi
class VgmPlayerAdapter(
    context: Context,
    initialSettings: VgmSettings = VgmSettings(),
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {
    private val appContext = context.applicationContext
    private val applicationHandler = Handler(looper)
    private val player = AudioTrackVgmPlayer(appContext, initialSettings)
    private val released = AtomicBoolean(false)
    private val stateLock = Any()

    private var playlist: List<MediaItem> = emptyList()
    private var currentIndex = C.INDEX_UNSET
    private var playbackState = Player.STATE_IDLE
    private var playWhenReady = false
    private var pendingStartPositionMs = 0L
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffleModeEnabled = false
    private var volume = 1f
    private var playbackError: PlaybackException? = null
    private var monitorThread: Thread? = null

    var settings: VgmSettings
        get() = player.settings
        set(value) {
            player.settings = value
        }

    override fun getState(): State {
        val items = synchronized(stateLock) {
            playlist.mapIndexed { index, item ->
                val durationMs = if (index == currentIndex) player.duration else C.TIME_UNSET
                MediaItemData.Builder(mediaItemUid(item, index))
                    .setMediaItem(item)
                    .setDurationUs(durationMs.toDurationUs())
                    .setIsSeekable(true)
                    .build()
            }
        }
        val state = synchronized(stateLock) { playbackState }
        return State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs(player.position)
            .setContentBufferedPositionMs(PositionSupplier { player.position })
            .setPlaybackState(state)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .setVolume(volume)
            .setPlayerError(playbackError)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<Any> {
        player.stop()
        synchronized(stateLock) {
            playlist = mediaItems.toList()
            currentIndex = when {
                playlist.isEmpty() -> C.INDEX_UNSET
                startIndex != C.INDEX_UNSET -> startIndex.coerceIn(playlist.indices)
                else -> 0
            }
            pendingStartPositionMs = startPositionMs.takeUnless { it == C.TIME_UNSET } ?: 0L
            playbackError = null
            playbackState = Player.STATE_IDLE
        }
        stopMonitoring()
        invalidateState()
        return immediateFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<Any> {
        synchronized(stateLock) {
            val targetIndex = index.coerceIn(0, playlist.size)
            playlist = playlist.toMutableList().apply { addAll(targetIndex, mediaItems) }
            if (currentIndex == C.INDEX_UNSET && playlist.isNotEmpty()) {
                currentIndex = 0
            } else if (currentIndex >= targetIndex) {
                currentIndex += mediaItems.size
            }
        }
        invalidateState()
        return immediateFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int
    ): ListenableFuture<Any> {
        synchronized(stateLock) {
            val movedItems = playlist.subList(fromIndex, toIndex).toList()
            val updatedPlaylist = playlist.toMutableList().apply {
                subList(fromIndex, toIndex).clear()
                addAll(newIndex.coerceIn(0, size), movedItems)
            }
            val currentItem = playlist.getOrNull(currentIndex)
            playlist = updatedPlaylist
            currentIndex = currentItem?.let { playlist.indexOf(it) }?.takeIf { it >= 0 }
                ?: playlist.indices.firstOrNull()
                ?: C.INDEX_UNSET
        }
        invalidateState()
        return immediateFuture()
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    ): ListenableFuture<Any> {
        synchronized(stateLock) {
            val currentItem = playlist.getOrNull(currentIndex)
            playlist = playlist.toMutableList().apply {
                subList(fromIndex, toIndex).clear()
                addAll(fromIndex.coerceIn(0, size), mediaItems)
            }
            currentIndex = currentItem?.let { playlist.indexOf(it) }?.takeIf { it >= 0 }
                ?: playlist.indices.firstOrNull()
                ?: C.INDEX_UNSET
        }
        invalidateState()
        return immediateFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<Any> {
        synchronized(stateLock) {
            val currentItem = playlist.getOrNull(currentIndex)
            playlist = playlist.toMutableList().apply { subList(fromIndex, toIndex).clear() }
            currentIndex = currentItem?.let { playlist.indexOf(it) }?.takeIf { it >= 0 }
                ?: playlist.indices.firstOrNull()
                ?: C.INDEX_UNSET
            if (playlist.isEmpty()) {
                player.stop()
                stopMonitoring()
                playbackState = Player.STATE_IDLE
                playWhenReady = false
            }
        }
        invalidateState()
        return immediateFuture()
    }

    override fun handlePrepare(): ListenableFuture<Any> {
        openCurrentItem()
        return immediateFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<Any> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            if (playbackState == Player.STATE_IDLE && currentIndex != C.INDEX_UNSET) {
                openCurrentItem()
            }
            if (playbackState == Player.STATE_READY) {
                player.play()
                startMonitoring()
            }
        } else {
            player.pause()
            stopMonitoring()
        }
        invalidateState()
        return immediateFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<Any> {
        this.repeatMode = repeatMode
        invalidateState()
        return immediateFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<Any> {
        this.shuffleModeEnabled = shuffleModeEnabled
        invalidateState()
        return immediateFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<Any> {
        this.volume = volume.coerceIn(0f, 1f)
        invalidateState()
        return immediateFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<Any> {
        val targetIndex = mediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: currentIndex
        if (targetIndex == C.INDEX_UNSET) return immediateFuture()
        val switchItem = targetIndex != currentIndex

        synchronized(stateLock) {
            if (targetIndex in playlist.indices) {
                currentIndex = targetIndex
            }
            pendingStartPositionMs = positionMs.takeUnless { it == C.TIME_UNSET } ?: 0L
        }

        if (playbackState == Player.STATE_IDLE || switchItem) {
            openCurrentItem()
        } else {
            player.seekTo(pendingStartPositionMs)
        }
        invalidateState()
        return immediateFuture()
    }

    override fun handleStop(): ListenableFuture<Any> {
        stopPlayback(Player.STATE_IDLE)
        return immediateFuture()
    }

    override fun handleRelease(): ListenableFuture<Any> {
        if (released.compareAndSet(false, true)) {
            stopPlayback(Player.STATE_IDLE)
        }
        return immediateFuture()
    }

    private fun openCurrentItem() {
        val item = synchronized(stateLock) { playlist.getOrNull(currentIndex) }
        val uri = item?.localConfiguration?.uri
        if (uri == null) {
            synchronized(stateLock) {
                playbackState = if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_ENDED
            }
            invalidateState()
            return
        }

        runCatching {
            player.stop()
            player.open(uri)
            if (pendingStartPositionMs > 0L) {
                player.seekTo(pendingStartPositionMs)
            }
        }.onSuccess {
            synchronized(stateLock) {
                playbackError = null
                playbackState = Player.STATE_READY
            }
            if (playWhenReady) {
                player.play()
                startMonitoring()
            }
            invalidateState()
        }.onFailure { error ->
            player.stop()
            stopMonitoring()
            synchronized(stateLock) {
                playbackError = PlaybackException(
                    "Unable to open vgmstream media item",
                    error,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )
                playbackState = Player.STATE_IDLE
            }
            invalidateState()
        }
    }

    private fun stopPlayback(nextState: Int) {
        stopMonitoring()
        player.stop()
        synchronized(stateLock) {
            playbackState = nextState
            playbackError = null
        }
        invalidateState()
    }

    private fun startMonitoring() {
        if (monitorThread?.isAlive == true) return
        monitorThread = thread(name = "VgmMedia3Monitor", isDaemon = true) {
            runCatching {
                while (!released.get() && playWhenReady && player.isPlaying) {
                    Thread.sleep(250)
                    invalidateStateOnApplicationThread()
                }
            }.onFailure { error ->
                if (error !is InterruptedException) throw error
                Thread.currentThread().interrupt()
                return@thread
            }
            val shouldMarkEnded = synchronized(stateLock) {
                !released.get() && playWhenReady && playbackState == Player.STATE_READY
            }
            if (shouldMarkEnded) {
                synchronized(stateLock) {
                    playbackState = Player.STATE_ENDED
                    playWhenReady = false
                }
                invalidateStateOnApplicationThread()
            }
        }
    }

    private fun stopMonitoring() {
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun mediaItemUid(item: MediaItem, index: Int): Any =
        item.mediaId.takeIf { it.isNotBlank() } ?: "vgmstream-$index"

    private fun invalidateStateOnApplicationThread() {
        if (Looper.myLooper() == applicationHandler.looper) {
            invalidateState()
        } else {
            applicationHandler.post {
                if (!released.get()) {
                    invalidateState()
                }
            }
        }
    }

    private fun Long.toDurationUs(): Long =
        if (this == C.TIME_UNSET || this <= 0L) C.TIME_UNSET else this * 1_000L

    private fun immediateFuture(): ListenableFuture<Any> =
        Futures.immediateFuture(Unit)

    private companion object {
        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_GET_AUDIO_ATTRIBUTES)
            .add(Player.COMMAND_GET_VOLUME)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_GET_TEXT)
            .add(Player.COMMAND_GET_TRACKS)
            .add(Player.COMMAND_RELEASE)
            .build()
    }
}
