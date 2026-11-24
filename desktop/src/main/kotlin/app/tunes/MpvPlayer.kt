package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

/** Injection keeps native EOF/radio regression tests offline without changing production playback. */
internal data class MpvDependencies(
    val audioSource: (String) -> AudioSource = Yt::audioSource,
    val radio: suspend (List<Song>, ListeningPreferences, List<Song>, RadioExclusions) -> List<Song> =
        { seeds, preferences, queue, exclusions -> playlistRadioSongs(seeds, preferences, queue, exclusions) },
)

/** EBU R128 loudness levelling from mpv's bundled ffmpeg filters. */
internal const val NORMALIZE_FILTER = "lavfi=[loudnorm=I=-16:TP=-1.5:LRA=11]"

/** Shared by the main and the crossfade engine, because a crossfade can promote either to play the next song. */
private val ENGINE_ARGS = listOf("--no-config", "--load-scripts=no", "--no-video", "--idle=yes", "--no-terminal",
    "--ytdl=no", "--replaygain=no")

/** The copy packaged with the app first, then whatever is installed on the PC. */
internal fun locateMpv(): String? {
    val packaged = System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv.exe") }
    val candidates = listOfNotNull(packaged, File("resources/windows/mpv.exe")) +
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "mpv.exe") }
    return candidates.firstOrNull { it.isFile }?.path
}

/**
 * Plays audio through mpv, driven over its JSON pipe. All mpv traffic stays on one thread, because on Windows
 * a blocking read on a named pipe would also block a write from another thread.
 * ponytail: Windows only (named pipe, mpv.exe). Use a unix socket path to add Mac or Linux.
 */
class MpvPlayer internal constructor(
    private val extraArgs: List<String>,
    private val dataDir: File?,
    private val dependencies: MpvDependencies,
) : MusicPlayer, AutoCloseable {
    constructor(extraArgs: List<String> = emptyList(), dataDir: File? = null) : this(extraArgs, dataDir, MpvDependencies())

    override var now by mutableStateOf(NowPlaying())
        private set

    private val modeFile = dataDir?.let { File(it, "playback-mode.txt") }
    private var _playbackMode by mutableStateOf(runCatching {
        PlaybackMode.valueOf(modeFile?.readText()?.trim().orEmpty())
    }.getOrDefault(PlaybackMode.STOP))
    override val playbackMode get() = _playbackMode
    private val transitionsFile = dataDir?.let { File(it, "transitions.json") }
    private var transitions by mutableStateOf(TransitionSettings.load(transitionsFile))
    override val transitionSettings get() = transitions
    @Volatile override var completedTrackRevision = 0L
        private set
    @Volatile private var stopAfterCurrent = false
    @Volatile private var sleepBlocked = false
    private val engineSwap = Any()
    @Volatile private var activePipeName: String? = null
    private val alarm = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "troit-sleep-alarm").apply { isDaemon = true } }
    private var alarmTask: ScheduledFuture<*>? = null
    private var alarmDeadline: Long? = null
    private var firedDeadline: Long? = null
    @Synchronized override fun setSleepDeadline(deadlineMillis: Long?) {
        if (deadlineMillis == null) sleepBlocked = false
        if (deadlineMillis == alarmDeadline || (deadlineMillis != null && deadlineMillis == firedDeadline)) return
        alarmTask?.cancel(false); alarmDeadline = deadlineMillis
        if (deadlineMillis == null) return
        alarmTask = alarm.schedule({
            synchronized(this) {
                if (!running || alarmDeadline != deadlineMillis) return@schedule
                firedDeadline = deadlineMillis; alarmDeadline = null; sleepBlocked = true
            }
            // A separate IPC connection can pause audio even while source lookup blocks the worker.
            val names = synchronized(engineSwap) { listOfNotNull(activePipeName, spare?.name).distinct() }
            names.forEach { name -> runCatching {
                RandomAccessFile(name, "rw").use { channel ->
                    channel.write((JSONObject().put("command", JSONArray(listOf("set_property", "pause", true))).toString() + "\n").toByteArray())
                }
            } }
            now = now.copy(playing = false)
        }, (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }
    override fun setStopAfterCurrent(enabled: Boolean) {
        jobs += { if (stopAfterCurrent != enabled) { stopAfterCurrent = enabled; if (enabled) cancelTransition() } }
    }
    override fun setTransitionSettings(settings: TransitionSettings) {
        jobs += {
            cancelTransition()
            transitions = settings.sanitized()
            transitionsFile?.let { runCatching { transitions.save(it) } }
            command("set_property", "gapless-audio", if (transitions.gapless) "weak" else "no")
            command("set_property", "af", audioFilter())
            runCatching { spare?.takeIf { it.process.isAlive }?.command("set_property", "af", audioFilter()) }
        }
    }
    private fun audioFilter() = if (transitions.normalize) NORMALIZE_FILTER else ""

    private val volumeFile = dataDir?.let { File(it, "volume.txt") }
    private var master by mutableStateOf(runCatching { volumeFile?.readText()?.trim()?.toFloat() }.getOrNull()?.coerceIn(0f, 1f) ?: 1f)
    override val volume get() = master
    /** mpv's own scale; crossfades fade towards and away from this instead of a fixed 100. */
    private fun level() = master * 100f
    override fun setVolume(value: Float) {
        jobs += {
            master = value.coerceIn(0f, 1f)
            runCatching { volumeFile?.writeAtomically(master.toString()) }
            if (pipe != null && !fading) command("set_property", "volume", level())
        }
    }

    @Volatile private var positionAt = 0L
    /**
     * The position between the engine's reports (about ten a second), for the music-video picture,
     * which has to follow the sound frame by frame.
     */
    fun clockMs(): Long {
        val live = now
        return if (live.playing && !live.loading && positionAt != 0L) position + (System.nanoTime() - positionAt) / 1_000_000 else position
    }

    @Volatile override var interactionRevision = 0L
        private set

    private val jobs = LinkedBlockingQueue<() -> Unit>()
    private val pendingQueues = ConcurrentHashMap.newKeySet<CompletableDeferred<Result<Boolean>>>()
    private val controls = QueuedPlayerControls({ jobs.add(it) }, { now }) { action, live ->
        when (action) {
            "next" -> nextOnPlayerThread()
            "previous" -> previousOnPlayerThread()
            "play" -> if (!live.playing) toggleOnPlayerThread()
            "pause" -> if (live.playing) toggleOnPlayerThread()
        }
    }
    private val pipeName = "\\\\.\\pipe\\tunes-${ProcessHandle.current().pid()}-${System.nanoTime()}"
    private var process: Process? = null
    private var pipe: RandomAccessFile? = null
    private var requestId = 0

    @Volatile private var position = 0L
    @Volatile private var duration = 0L
    @Volatile private var running = true
    private var started = false // the current song has actually begun
    private var ended = false // nothing is loaded in mpv any more
    private var loadedAt = 0L
    private var lastEndReason: String? = null
    private var idleSince = 0L
    private val radioSession = RadioQueueSession()
    private val radioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sourceDispatcher = Dispatchers.IO.limitedParallelism(3)
    private var sourceLookup: Job? = null
    private var loadGeneration = 0L
    private var radioJob: Job? = null
    private var radioPending = false
    private var radioGeneration = 0L
    private data class Upcoming(val queue: List<Song>, val from: Int, val index: Int, val source: AudioSource)
    private var upcoming: Upcoming? = null
    private var nextLookup: Job? = null
    private var transitionGeneration = 0L
    private var appended = false
    private var spare: MpvAudioChannel? = null
    private var spareLoaded = false
    private var fading = false
    private var fadeElapsedMs = 0L
    private var fadeLastAt = 0L
    private var fadeLengthMs = 0L
    private var nextRetryAt = 0L
    @Volatile internal var lastFadeVolumes: Pair<Float, Float> = 100f to 0f
        private set

    init {
        Thread(::loop, "mpv").apply { isDaemon = true }.start()
    }

    override fun positionMs() = position
    override suspend fun preloadSource(song: Song) {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { dependencies.audioSource(song.url) }
    }

    override fun durationMs() = duration.takeIf { it > 0 } ?: ((now.song?.durationSeconds ?: 0L) * 1000)

    override fun setPlaybackMode(mode: PlaybackMode) {
        jobs += {
            if (mode != playbackMode) {
                cancelRadio()
                _playbackMode = mode
                interactionRevision++
                runCatching { modeFile?.writeAtomically(mode.name) }
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        jobs += {
            cancelRadio()
            if (sourceLookup != null) {
                position = positionMs.coerceAtLeast(0L)
                interactionRevision++
            } else if (now.song != null && started && !ended) {
                val target = positionMs.coerceAtLeast(0).let { requested ->
                    durationMs().takeIf { it > 0 }?.let(requested::coerceAtMost) ?: requested
                }
                command("seek", target / 1000.0, "absolute+exact")
                position = target
                interactionRevision++
            }
        }
    }

    override fun play(songs: List<Song>, index: Int, positionMs: Long) {
        jobs += select@{
            if (index in songs.indices) {
                sleepBlocked = false
                interactionRevision++
                if (positionMs == 0L && songs == now.queue && playPrepared(index)) {
                    radioSession.reset(now.queue)
                    return@select
                }
                cancelRadio()
                val queue = songs.toList()
                radioSession.reset(queue)
                now = NowPlaying(queue = queue, index = index)
                load(index, positionMs)
            }
        }
    }

    override fun toggle() {
        jobs += {
            sleepBlocked = false
            if (toggleOnPlayerThread()) interactionRevision++
        }
    }

    override fun next() {
        jobs += { sleepBlocked = false; if (nextOnPlayerThread()) interactionRevision++ }
    }

    override fun previous() {
        jobs += { sleepBlocked = false; if (previousOnPlayerThread()) interactionRevision++ }
    }

    override suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean =
        controls.control(expectedUrl, expectedIndex, action) { running && allowed() && (action != "play" || !sleepBlocked) }

    override suspend fun replaceQueue(expectedQueue: List<Song>?, songs: List<Song>, index: Int,
        positionMs: Long, playing: Boolean, mode: PlaybackMode, allowed: () -> Boolean): Boolean {
        if (!running || index !in songs.indices || songs.size > 1000) return false
        val preparedSource = if (expectedQueue == null) {
            try { withContext(sourceDispatcher) { dependencies.audioSource(songs[index].url) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return false }
        } else null
        val result = CompletableDeferred<Result<Boolean>>(currentCoroutineContext()[Job])
        pendingQueues += result
        try {
            if (!running) return false
            jobs += {
                if (result.isActive) try {
                    val live = now
                    val valid = running && allowed() && index in songs.indices && songs.size <= 1000 &&
                        (expectedQueue == null || (live.queue == expectedQueue && live.index == index &&
                            live.queue.take(index + 1) == songs.take(index + 1)))
                    var applied = valid
                    if (valid) {
                        cancelRadio()
                        _playbackMode = mode
                        runCatching { modeFile?.writeAtomically(mode.name) }
                        val queue = songs.toList()
                        radioSession.reset(queue)
                        if (expectedQueue != null) now = now.copy(queue = queue, index = index)
                        else {
                            command("set_property", "pause", true)
                            now = NowPlaying(queue = queue, index = index)
                            load(index, positionMs.coerceAtLeast(0), startPlaying = playing, source = preparedSource)
                            if (!playing && !ended) { command("set_property", "pause", true); now = now.copy(playing = false) }
                            applied = result.isActive && allowed() && now.error == null && !ended
                            if (applied) interactionRevision++
                        }
                    }
                    result.complete(Result.success(applied && now.error == null && (expectedQueue == null || allowed())))
                } catch (failure: Exception) { result.complete(Result.failure(failure)) }
            }
            return result.await().getOrThrow()
        } finally { pendingQueues -= result; result.cancel() }
    }

    private fun toggleOnPlayerThread(): Boolean {
        if (now.song == null) return false
        if (sourceLookup != null) {
            // Silence the outgoing file immediately; retain intent for the requested song.
            if (now.playing) command("set_property", "pause", true)
            now = now.copy(playing = !now.playing)
            return true
        }
        if (radioPending) {
            cancelRadio()
            return true
        }
        if (ended && playbackMode == PlaybackMode.RADIO && now.index == now.queue.lastIndex) {
            beginRadio()
            return true
        }
        if (now.error != null || ended) load(now.index, position) else {
            command("cycle", "pause")
            val paused = command("get_property", "pause").optBoolean("data", !now.playing)
            if (fading) spare?.command("set_property", "pause", paused)
            if (paused) {
                val nativePosition = command("get_property", "time-pos").opt("data") as? Number
                if (nativePosition != null) position = (nativePosition.toDouble() * 1000).toLong().coerceAtLeast(0)
            }
            now = now.copy(playing = !paused)
        }
        return true
    }

    private fun nextOnPlayerThread(): Boolean {
        if (now.song == null) return false
        val index = nextPlaybackIndex(playbackMode, now.index, now.queue.size, automatic = false)
        if (index != null && playPrepared(index)) return true
        cancelRadio()
        if (index != null) load(index, 0)
        else if (playbackMode == PlaybackMode.RADIO) beginRadio()
        else return false
        return true
    }

    private fun playPrepared(index: Int): Boolean {
        val item = upcoming ?: return false
        if (!appended || item.index != index || item.queue !== now.queue || stopAfterCurrent || sleepBlocked) return false
        // Keep mpv's already opened successor, including the demuxer's buffered bytes.
        if (command("playlist-play-index", 1).optString("error") != "success") return false
        command("playlist-remove", 0)
        command("set_property", "pause", false)
        upcoming = null; appended = false
        now = now.copy(index = index, playing = true, loading = true, audioQuality = item.source.quality)
        started = false; ended = false; position = 0; duration = 0
        lastEndReason = null; idleSince = 0; loadedAt = System.currentTimeMillis()
        return true
    }

    private fun previousOnPlayerThread(): Boolean {
        cancelRadio()
        when {
            position > 3_000 && sourceLookup != null -> position = 0
            position > 3_000 && !ended -> command("seek", 0, "absolute")
            now.index > 0 -> load(now.index - 1, 0)
            now.song != null && playbackMode == PlaybackMode.REPEAT_ALL -> load(now.queue.lastIndex, 0)
            else -> return false
        }
        return true
    }

    override fun close() {
        running = false
        radioScope.cancel()
        controls.close()
        alarmTask?.cancel(false); alarm.shutdownNow()
        pendingQueues.forEach { it.complete(Result.success(false)) }
        process?.destroy()
        spare?.process?.destroy()
    }

    private fun loop() {
        try {
            start()
        } catch (e: Exception) {
            // The error is shown once something is played, since that's when it matters.
            failure = e.message ?: "Couldn't start the audio engine."
        }
        while (running) {
            val job = jobs.poll(if (fading) 50 else 100, TimeUnit.MILLISECONDS)
            try {
                if (failure != null) {
                    if (job != null) {
                        job()
                    }
                    continue
                }
                job?.invoke()
                if (now.song != null && !ended && sourceLookup == null) tick()
            } catch (e: Exception) {
                if (running) now = now.copy(loading = false, playing = false, error = "The audio engine stopped.")
            }
        }
    }

    @Volatile private var failure: String? = null

    private fun start() {
        val exe = locateMpv() ?: error("mpv isn't installed, so the PC app can't play audio.")
        process = ProcessBuilder(
            // Keep system/user mpv scripts, gain, filters and forced sample rates out of app playback.
            listOf(exe) + ENGINE_ARGS + listOf("--volume=${level()}", "--af=${audioFilter()}", "--input-ipc-server=$pipeName") + extraArgs,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy(); spare?.process?.destroy() })
        repeat(100) {
            try {
                pipe = RandomAccessFile(pipeName, "rw")
                activePipeName = pipeName
                return
            } catch (e: Exception) {
                Thread.sleep(50)
            }
        }
        error("The audio engine didn't start.")
    }

    private fun load(index: Int, startMs: Long, startPlaying: Boolean = true, source: AudioSource? = null) {
        val generation = ++loadGeneration
        sourceLookup?.cancel()
        sourceLookup = null
        failure?.let {
            now = now.copy(index = index, playing = false, loading = false, error = it)
            return
        }
        now = now.copy(index = index, playing = startPlaying, loading = true, error = null, audioQuality = null)
        started = false
        ended = false
        position = startMs
        duration = 0L
        loadedAt = System.currentTimeMillis()
        lastEndReason = null
        idleSince = 0L
        val song = now.song ?: return
        if (source != null) {
            openSource(source)
            return
        }
        sourceLookup = radioScope.launch(sourceDispatcher) {
            val resolved = try { dependencies.audioSource(song.url) } catch (_: Exception) { null }
            jobs += apply@{
                if (!running || generation != loadGeneration) return@apply
                sourceLookup = null
                if (resolved == null) {
                    command("stop")
                    ended = true
                    now = now.copy(playing = false, loading = false, error = "Couldn't find this song's audio.")
                } else openSource(resolved)
            }
        }
    }

    private fun openSource(source: AudioSource) {
        loadedAt = System.currentTimeMillis()
        now = now.copy(audioQuality = source.quality)
        command("set_property", "pause", !now.playing)
        // Set start on the new entry. A preloaded predecessor has a file-local start option,
        // which mpv restores on unload and can therefore undo a global set_property here.
        val start = if (position > 0) (position / 1000.0).toString() else "none"
        command("loadfile", source.url, "replace", -1, JSONObject().put("start", start))
    }

    private fun tick() {
        val prepared = upcoming
        if (appended && prepared != null && command("get_property", "playlist-pos").optInt("data", 0) >= 1) {
            // mpv advanced its native playlist without closing the audio output.
            command("playlist-remove", 0)
            now = now.copy(index = prepared.index, audioQuality = prepared.source.quality)
            completedTrackRevision++
            upcoming = null; appended = false; nextLookup = null
            lastEndReason = null; started = true; loadedAt = System.currentTimeMillis()
        }
        val idle = command("get_property", "idle-active").optBoolean("data")
        val time = command("get_property", "time-pos").opt("data") as? Number
        if (idle) {
            if (idleSince == 0L) idleSince = System.currentTimeMillis()
            when {
                started && lastEndReason == "eof" -> {
                    if (fading && spareLoaded) finishFade() else onTrackCompleted()
                }
                lastEndReason == "error" || System.currentTimeMillis() - maxOf(loadedAt, idleSince) > 3_000 -> {
                    // Links can die when the network changes. Forget them so a retry fetches a new one.
                    Yt.forgetAudioUrls()
                    ended = true
                    now = now.copy(playing = false, loading = false, error = "Playback failed.")
                }
            }
            return
        }
        if (time != null) {
            idleSince = 0L
            duration = ((command("get_property", "duration").opt("data") as? Number)?.toDouble()?.times(1000))?.toLong() ?: duration
            started = true
            position = (time.toDouble() * 1000).toLong()
            positionAt = System.nanoTime()
            val paused = command("get_property", "pause").optBoolean("data")
            now = now.copy(playing = !paused, loading = false)
            try { prepareNext(); updateFade() }
            catch (_: Exception) { recoverTransition() }
        }
    }

    private fun onTrackCompleted() {
        completedTrackRevision++
        if (stopAfterCurrent) {
            cancelTransition()
            ended = true
            now = now.copy(playing = false, loading = false)
            return
        }
        cancelTransition()
        val next = nextPlaybackIndex(playbackMode, now.index, now.queue.size)
        when {
            next != null -> load(next, 0)
            playbackMode == PlaybackMode.RADIO -> beginRadio()
            else -> {
                ended = true
                position = 0
                now = now.copy(playing = false, loading = false)
            }
        }
    }

    private fun listeningPreferences(): ListeningPreferences = dataDir?.let {
        RegionPreferences(File(it, "listening-preferences.json")).settings
    } ?: ListeningPreferences()

    private fun cancelRadio() {
        cancelTransition()
        radioGeneration++
        radioJob?.cancel()
        radioJob = null
        if (radioPending) now = now.copy(playing = false, loading = false)
        radioPending = false
    }

    /** Resolve one successor on IO; never block the native command thread on recommendation work. */
    private fun prepareNext() {
        if (sleepBlocked || stopAfterCurrent || ended || now.loading || (!transitions.gapless && transitions.crossfadeSeconds == 0) ||
            nextLookup != null || upcoming != null || !now.playing || System.currentTimeMillis() < nextRetryAt) return
        val length = durationMs()
        // Keep one native gapless successor ready for manual Next as soon as playback settles.
        // A crossfade uses a second decoder, which is only needed near the end of the song.
        if (transitions.crossfadeSeconds > 0 &&
            (length <= 0 || length - position > maxOf(30_000L, transitions.crossfadeSeconds * 1000L + 5_000))) return
        val queue = now.queue
        val from = now.index
        val next = nextPlaybackIndex(playbackMode, from, queue.size) ?: return
        val song = queue[next]
        if (!listeningPreferences().allows(song)) return
        val generation = transitionGeneration
        nextLookup = radioScope.launch {
            val source = runCatching { dependencies.audioSource(song.url) }.getOrNull()
            jobs += apply@{
                if (generation != transitionGeneration) return@apply
                nextLookup = null
                if (source == null) { nextRetryAt = System.currentTimeMillis() + 5_000; return@apply }
                if (!running || stopAfterCurrent ||
                    now.queue !== queue || now.index != from) return@apply
                val item = Upcoming(queue, from, next, source)
                upcoming = item
                if (transitions.crossfadeSeconds > 0) {
                    try {
                        val channel = spare?.takeIf { it.process.isAlive } ?: openSpare().also { spare = it }
                        channel.command("set_property", "volume", 0)
                        channel.command("set_property", "pause", true)
                        channel.command("set_property", "start", "none")
                        channel.command("loadfile", source.url, "replace")
                        spareLoaded = true
                    } catch (_: Exception) { spare?.close(); spare = null; spareLoaded = false; upcoming = null }
                } else if (transitions.gapless) {
                    command("set_property", "prefetch-playlist", true)
                    command("loadfile", source.url, "append", -1, JSONObject().put("start", "none"))
                    appended = true
                }
            }
        }
    }

    private fun updateFade() {
        val item = upcoming ?: return
        val channel = spare ?: return
        if (sleepBlocked || !spareLoaded || transitions.crossfadeSeconds == 0 || stopAfterCurrent || now.loading) return
        val time = System.nanoTime() / 1_000_000
        if (!fading) {
            val incomingDuration = ((channel.command("get_property", "duration").opt("data") as? Number)?.toDouble()?.times(1000))?.toLong()
                ?: item.queue[item.index].durationSeconds * 1000
            if (incomingDuration <= 0) return
            val window = minOf(transitions.crossfadeSeconds * 1000L, durationMs() / 2, incomingDuration / 2).coerceAtLeast(100)
            if (!now.playing || durationMs() - position > window || !channel.ready()) return
            fading = true; fadeElapsedMs = 0; fadeLengthMs = window; fadeLastAt = time
            channel.command("set_property", "pause", false)
        }
        val elapsed = (time - fadeLastAt).coerceAtLeast(0)
        fadeLastAt = time
        if (!now.playing) return
        fadeElapsedMs += elapsed
        val progress = (fadeElapsedMs.toFloat() / fadeLengthMs).coerceIn(0f, 1f)
        val outgoing = level() * (1f - progress)
        val incoming = level() * progress
        command("set_property", "volume", outgoing)
        channel.command("set_property", "volume", incoming)
        lastFadeVolumes = outgoing to incoming
        if (progress >= 1f) finishFade()
    }

    private fun finishFade() {
        val item = upcoming ?: return
        val incoming = spare ?: return
        if (!incoming.ready()) { cancelTransition(); return }
        val outgoing = MpvAudioChannel(checkNotNull(process), checkNotNull(pipe), checkNotNull(activePipeName)) { !sleepBlocked }
        outgoing.command("stop")
        outgoing.command("set_property", "volume", level())
        synchronized(engineSwap) { process = incoming.process; pipe = incoming.pipe; activePipeName = incoming.name; spare = outgoing }
        command("set_property", "volume", level())
        upcoming = null; spareLoaded = false; fading = false; appended = false; nextLookup = null
        fadeElapsedMs = 0; lastFadeVolumes = 100f to 0f
        completedTrackRevision++
        now = now.copy(index = item.index, playing = !sleepBlocked, loading = false, audioQuality = item.source.quality)
        position = ((command("get_property", "time-pos").opt("data") as? Number)?.toDouble()?.times(1000))?.toLong() ?: 0
        duration = ((command("get_property", "duration").opt("data") as? Number)?.toDouble()?.times(1000))?.toLong() ?: 0
        started = true; ended = false; lastEndReason = null; idleSince = 0; loadedAt = System.currentTimeMillis()
    }

    private fun cancelTransition() {
        transitionGeneration++
        nextRetryAt = 0
        nextLookup?.cancel(); nextLookup = null
        if (appended && pipe != null) runCatching { command("playlist-clear") }
        if (spareLoaded) runCatching { spare?.command("stop") }
        if (fading && pipe != null) runCatching { command("set_property", "volume", level()) }
        upcoming = null; appended = false; spareLoaded = false; fading = false; fadeElapsedMs = 0
        lastFadeVolumes = 100f to 0f
    }

    private fun recoverTransition() {
        val replacement = spare?.takeIf { process?.isAlive != true && it.process.isAlive }
        if (replacement != null) {
            process?.destroy()
            runCatching { pipe?.close() }
            synchronized(engineSwap) { process = replacement.process; pipe = replacement.pipe; activePipeName = replacement.name; spare = null }
        }
        cancelTransition()
        spare?.close(); spare = null
        if (replacement != null) load(now.index, position, startPlaying = !sleepBlocked)
        else if (process?.isAlive == true) {
            command("set_property", "volume", level())
            now = now.copy(playing = !command("get_property", "pause").optBoolean("data", true), error = null)
        }
    }

    private fun openSpare(): MpvAudioChannel {
        val name = "$pipeName-fade-${System.nanoTime()}"
        val child = ProcessBuilder(listOf(checkNotNull(locateMpv())) + ENGINE_ARGS +
            listOf("--volume=0", "--af=${audioFilter()}", "--input-ipc-server=$name") + extraArgs)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            repeat(100) {
                check(running) { "Player closed." }
                try { return MpvAudioChannel(child, RandomAccessFile(name, "rw"), name) { !sleepBlocked } }
                catch (_: java.io.FileNotFoundException) { Thread.sleep(25) }
            }
            error("Prepared audio engine did not start.")
        } catch (failure: Exception) { child.destroy(); throw failure }
    }

    /** Recommendation requests never block the named-pipe loop, including pause and remote controls. */
    private fun beginRadio() {
        if (radioPending || playbackMode != PlaybackMode.RADIO || now.song == null) return
        loadGeneration++; sourceLookup?.cancel(); sourceLookup = null
        command("stop")
        ended = true
        position = 0L
        radioPending = true
        now = now.copy(playing = true, loading = true, error = null)
        val generation = ++radioGeneration
        val queue = now.queue
        val index = now.index
        val seeds = radioSession.seeds
        val exclusions = radioSession.exclusions(queue)
        radioJob = radioScope.launch {
            val result = try {
                Result.success(dependencies.radio(seeds, listeningPreferences(), queue, exclusions))
            } catch (cancelled: CancellationException) { return@launch
            } catch (failure: Exception) { Result.failure(failure) }
            jobs += apply@{
                if (!running || generation != radioGeneration || !radioPending || playbackMode != PlaybackMode.RADIO ||
                    now.queue !== queue || now.index != index) return@apply
                radioPending = false
                radioJob = null
                val preferences = listeningPreferences()
                val songs = result.getOrNull().orEmpty().filter { !exclusions.contains(it) && preferences.allows(it) }
                    .distinctBy(::radioSongId).take(RadioQueueSession.MAX_BATCH)
                if (songs.isEmpty()) {
                    now = now.copy(playing = false, loading = false,
                        error = if (result.isFailure) "Couldn't find similar music. Try again or choose another playback mode."
                        else "No new similar songs are available. Try again or choose another playback mode.")
                } else {
                    val appended = appendRadioQueue(queue, index, songs)
                    radioSession.remember(songs)
                    now = now.copy(queue = appended.songs)
                    load(appended.nextIndex, 0)
                }
            }
        }
    }

    /** Sends one command and waits for its reply, skipping the event messages mpv mixes in. */
    private fun command(vararg args: Any): JSONObject {
        val channel = pipe ?: error("Audio engine isn't running")
        val id = ++requestId
        val effective = when {
            args.size >= 3 && args[0] == "set_property" && args[1] == "pause" && args[2] == false && sleepBlocked ->
                args.toMutableList().also { it[2] = true }.toTypedArray()
            args.size >= 2 && args[0] == "cycle" && args[1] == "pause" && sleepBlocked -> arrayOf<Any>("set_property", "pause", true)
            else -> args
        }
        val message = JSONObject().put("command", JSONArray(effective)).put("request_id", id).toString() + "\n"
        channel.write(message.toByteArray())
        while (true) {
            val line = channel.readLine() ?: error("Audio engine closed")
            val reply = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (reply.optString("event") == "end-file") lastEndReason = reply.optString("reason")
            if (reply.optInt("request_id", -1) == id && reply.has("error")) return reply
        }
    }
}
