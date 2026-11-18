package app.autopil.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.autopil.core.agent.ActionPacer
import app.autopil.core.agent.AgentRunner
import app.autopil.core.agent.Outcome
import app.autopil.core.parse.Command
import app.autopil.core.parse.CommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class AutopilAccessibilityService : android.accessibilityservice.AccessibilityService(), AutopilHub.Controls {
    private val prefs by lazy { Prefs(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val stop = AtomicBoolean(false)
    private val parser = CommandParser()
    private val journal = ArrayDeque<String>()
    private var job: Job? = null
    private lateinit var speaker: Speaker
    private lateinit var hud: OverlayHud
    private lateinit var port: PhonePort
    private lateinit var runner: AgentRunner
    private lateinit var speech: SpeechLoop

    override fun onServiceConnected() {
        super.onServiceConnected()
        speaker = Speaker(this)
        hud = OverlayHud(this) { listenOnce() }
        hud.show()
        port = PhonePort(
            service = this,
            speaker = speaker,
            hud = hud,
            apps = AppIndex(this),
            contacts = ContactDirectory(this),
            onSpoken = { text -> AutopilHub.update { it.copy(status = text) } },
            onLog = { line -> AutopilHub.update { it.copy(log = remember(line)) } },
        )
        runner = AgentRunner(
            port = port,
            pacer = ActionPacer(
                beforeMs = { prefs.beforeMs },
                afterMs = { prefs.afterMs },
                shouldAbort = { stop.get() },
            ),
        )
        speech = SpeechLoop(
            context = this,
            prefs = prefs,
            onText = { submit(it) },
            onStatus = { partial ->
                AutopilHub.update { it.copy(heard = partial, listening = true) }
                if (!speaker.speaking) hud.setStatus(partial.take(90))
            },
            isSpeaking = { speaker.speaking },
            isAwaitingConfirm = { runner.isWaiting },
        )
        port.speech = speech
        AutopilHub.controls = this
        AutopilHub.update {
            it.copy(serviceOn = true, listening = true, status = getString(R.string.status_on))
        }
        hud.setStatus(getString(R.string.status_on))
        foreground()
        speech.start()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun listenOnce() {
        if (!::speech.isInitialized) return
        foreground()
        speech.listenOnce()
    }

    override fun refreshListening() {
        if (!::speech.isInitialized) return
        foreground()
        speech.start()
    }

    override fun submit(text: String) {
        if (!::runner.isInitialized) return
        scope.launch {
            mutex.withLock {
                val command = parser.parse(text)
                val stopCommand = command is Command.Stop || command is Command.Reject
                val keepPrompt = command is Command.Confirm || command is Command.Skip || stopCommand
                AutopilHub.update {
                    it.copy(heard = text, prompt = if (keepPrompt) it.prompt else "")
                }
                val active = job
                val replace = stopCommand || !runner.isWaiting
                if (replace && active != null && active.isActive) {
                    stop.set(true)
                    active.cancel()
                    active.join()
                    runner.abandon()
                    stop.set(false)
                }
                job = launch {
                    show(runner.onUtterance(text))
                }
            }
        }
    }

    override fun onDestroy() {
        AutopilHub.controls = null
        AutopilHub.update { AutopilHub.State(status = getString(R.string.status_off)) }
        if (::speech.isInitialized) speech.stop()
        if (::hud.isInitialized) hud.hide()
        scope.cancel()
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    private fun show(outcome: Outcome) {
        val prompt = (outcome as? Outcome.Waiting)?.prompt.orEmpty()
        AutopilHub.update { state ->
            state.copy(
                prompt = prompt,
                status = if (prompt.isNotBlank()) prompt else state.status,
                serviceOn = true,
            )
        }
        if (prompt.isNotBlank()) hud.setStatus(prompt.take(90))
    }

    private fun remember(line: String): List<String> {
        journal.addFirst(line.take(160))
        while (journal.size > 30) journal.removeLast()
        return journal.toList()
    }

    private fun foreground() {
        val channelId = "autopil-listen"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.notification_text)
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        val microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= 30 && microphone) {
                startForeground(NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTE_ID, notification)
            }
        } catch (_: SecurityException) {
            // The accessibility service keeps running. The screen explains a missing microphone grant.
        }
    }

    private companion object {
        const val NOTE_ID = 71
    }
}
