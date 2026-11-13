package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.dictation.audio.AudioSource
import app.trackly.dictation.audio.AudioSourceFactory
import app.trackly.dictation.audio.AudioUnavailableException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin

/** Micro factice : rend les morceaux qu'on lui donne, puis attend l'arrêt (comme un vrai micro, qui bloque). */
class FakeAudioSource(override val sampleRate: Int = 24_000) : AudioSource {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val end = ShortArray(0)
    @Volatile var closed = false
        private set
    @Volatile var stopped = false
        private set

    /** Ajoute [seconds] secondes de ton, en morceaux de 100 ms. */
    fun speak(seconds: Double, hz: Double = 220.0): FakeAudioSource {
        val perChunk = sampleRate / 10
        val chunks = (seconds * 10).toInt()
        repeat(chunks) { c ->
            queue.add(ShortArray(perChunk) { i -> (8_000 * sin(2 * PI * hz * (c * perChunk + i) / sampleRate)).toInt().toShort() })
        }
        return this
    }

    override fun read(buffer: ShortArray): Int {
        val next = queue.take()
        if (next === end) {
            queue.add(end)
            return -1
        }
        next.copyInto(buffer)
        return next.size
    }

    override fun stop() {
        stopped = true
        queue.clear() // un vrai micro arrêté ne rend plus rien
        queue.add(end)
    }

    override fun close() {
        closed = true
        stop()
    }
}

/** Fabrique de micros factices. */
class FakeMic(private val source: FakeAudioSource? = FakeAudioSource().speak(2.0)) : AudioSourceFactory {
    var opened = 0
    override fun open(): AudioSource {
        opened++
        return source ?: throw AudioUnavailableException("micro occupé")
    }
}

/** Connexion en direct factice : enregistre ce qui est envoyé, et joue un scénario au commit. */
class FakeLiveConnection(private val onCommit: (FakeLiveConnection) -> Unit) : LiveConnection {
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveEvent> = channel.receiveAsFlow()
    val log = CopyOnWriteArrayList<String>()
    val audioBytes get() = log.filter { it.startsWith("audio:") }.sumOf { it.removePrefix("audio:").toInt() }
    @Volatile var closed = false

    fun emit(event: LiveEvent) {
        channel.trySend(event)
    }

    override fun sendAudio(pcm: ByteArray): Boolean {
        log += "audio:${pcm.size}"
        return true
    }

    override fun commit(): Boolean {
        log += "commit"
        onCommit(this)
        return true
    }

    override fun close() {
        closed = true
        channel.close()
    }
}

class FakeLive : LiveTranscriber {
    var error: AssistantError? = null
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val configs = CopyOnWriteArrayList<LiveConfig>()
    var connection: FakeLiveConnection? = null

    /** Scénario après le commit : texte final par défaut. */
    var script: (FakeLiveConnection) -> Unit = { c ->
        c.emit(LiveEvent.Committed("item_1"))
        c.emit(LiveEvent.Completed("item_1", "Comme snack j’ai pris une barre Nuvo", seconds = null))
    }

    override suspend fun connect(config: LiveConfig): LiveConnection {
        configs += config
        gate?.await()
        error?.let { throw AssistantException(it) }
        return FakeLiveConnection { script(it) }.also { connection = it }
    }
}

class FakeFile : FileTranscriber {
    var result: () -> FileTranscription = { FileTranscription("Une barre Nuvo et une pomme", seconds = null) }
    var error: AssistantError? = null
    val calls = CopyOnWriteArrayList<Pair<ByteArray, FileTranscriptionConfig>>()

    override suspend fun transcribe(wav: ByteArray, config: FileTranscriptionConfig): FileTranscription {
        calls += wav to config
        error?.let { throw AssistantException(it) }
        return result()
    }
}

class RecordingUsage : TranscriptionUsage {
    val records = CopyOnWriteArrayList<Triple<String, Long, Instant>>()
    override suspend fun record(model: String, audioMs: Long, at: Instant) {
        records += Triple(model, audioMs, at)
    }
}
