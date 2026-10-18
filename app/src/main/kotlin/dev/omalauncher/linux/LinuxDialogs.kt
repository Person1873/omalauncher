package dev.omalauncher.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.omalauncher.App
import dev.omalauncher.R

/**
 * A Linux app's question as an Android notification: an app whose card was
 * swiped away but that asked something first ("Save changes?") has no card to
 * ask it in. Its dialog is read through accessibility (AT-SPI) by the guest's
 * omalauncher-dialog, so the notification carries the dialog's own buttons and
 * answers in the app; tapping the notification brings the window back instead.
 */
class LinuxDialogs(
    private val context: Context,
    proot: Proot,
) {
    private val shell = GuestShell(proot)
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    data class Dialog(
        val title: String,
        val buttons: List<String>,
    )

    /** [pid]'s dialog, the window named [title] if given; null when it has none, or doesn't say (no accessibility). */
    suspend fun read(
        pid: Int,
        title: String?,
    ): Dialog? {
        val command = "$HELPER list $pid" + (title?.let { " " + shellQuote(it) } ?: "")
        // The accessibility registry can miss the first call after it starts.
        repeat(2) {
            val result = runCatching { shell.run(command) }.getOrNull() ?: return@repeat
            val lines = result.out.lines().filter { it.isNotEmpty() }
            if (result.code == 0 && lines.size >= 2) return Dialog(lines.first(), lines.drop(1))
        }
        return null
    }

    suspend fun press(
        pid: Int,
        title: String,
        button: String,
    ) = runCatching { shell.run("$HELPER press $pid ${shellQuote(title)} ${shellQuote(button)}").code == 0 }.getOrDefault(false)

    /** Asks about [handle]'s window, which [app] left open: with [dialog]'s buttons, or just a way back to it ([open]). */
    fun post(
        handle: String,
        app: String,
        pid: Int,
        dialog: Dialog?,
        open: Intent,
    ) {
        val nm = nm
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux apps asking", NotificationManager.IMPORTANCE_HIGH))
        val id = idOf(handle)
        val builder =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(dialog?.title?.ifBlank { null } ?: app)
                .setContentText(if (dialog != null) "$app is asking before it closes" else "$app didn't close")
                .setSubText(app)
                .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
        // Android shows three actions at most; dialogs put their main answers first.
        dialog?.buttons?.take(MAX_ACTIONS)?.forEachIndexed { i, button ->
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // Cancel means not closing after all: it opens the window, where it's pressed (see LinuxWindowActivity).
            // Android only lets a notification's action start an activity itself, not through a receiver.
            val pending =
                if (isCancel(button)) {
                    PendingIntent.getActivity(context, id + i + 1, Intent(open).withAnswer(handle, pid, dialog.title, button), flags)
                } else {
                    PendingIntent.getBroadcast(context, id + i + 1, Intent(context, LinuxDialogReceiver::class.java).withAnswer(handle, pid, dialog.title, button), flags)
                }
            builder.addAction(Notification.Action.Builder(null, button.replace("&", ""), pending).build())
        }
        nm.notify(id, builder.build())
    }

    fun cancel(handle: String) = nm.cancel(idOf(handle))

    private fun Intent.withAnswer(
        handle: String,
        pid: Int,
        title: String,
        button: String,
    ) = putExtra(EXTRA_HANDLE, handle).putExtra(EXTRA_PID, pid).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BUTTON, button)

    /** A dialog's way of not going ahead; apps mark it only by its label (its mnemonic aside). */
    private fun isCancel(button: String) = button.replace("&", "").replace("_", "").trim().equals("Cancel", ignoreCase = true)

    private fun idOf(handle: String) = BASE_ID + (handle.hashCode() and 0xFFFF) * 4

    companion object {
        const val HELPER = "/usr/local/lib/omalauncher/omalauncher-dialog"
        private const val CHANNEL = "linux-dialogs"
        private const val MAX_ACTIONS = 3

        /** Clear of the launcher's own ids and of LinuxNotifications'. */
        private const val BASE_ID = 0x4D000000

        const val EXTRA_HANDLE = "handle"
        const val EXTRA_PID = "pid"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BUTTON = "button"
    }
}

/** A button on a [LinuxDialogs] notification: pressed in the app's dialog. */
class LinuxDialogReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val handle = intent.getStringExtra(LinuxDialogs.EXTRA_HANDLE) ?: return
        val title = intent.getStringExtra(LinuxDialogs.EXTRA_TITLE) ?: return
        val button = intent.getStringExtra(LinuxDialogs.EXTRA_BUTTON) ?: return
        val pid = intent.getIntExtra(LinuxDialogs.EXTRA_PID, 0)
        val pending = goAsync()
        (context.applicationContext as App).runtime.answerDialog(handle, pid, title, button) { pending.finish() }
    }
}
