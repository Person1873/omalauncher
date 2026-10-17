package dev.omalauncher.linux

import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "dbus"

/** Restarts of a crashing bus back off up to this. */
private const val MAX_BACKOFF_MS = 60_000L

/**
 * D-Bus for Linux apps: one session bus they all share (every guest process
 * gets its address, see [Proot]), which desktop apps expect and some abort
 * without, and which portals (file dialogs) are reached through. Also an empty
 * system bus: nothing serves NetworkManager or the like on it, so apps that ask
 * get a clean failure instead of aborting on a missing socket (PrusaSlicer's
 * Wi-Fi dialog). Both run in one guest process of their own: a daemon started
 * by an app would end with that app's proot.
 *
 * Logins are anonymous: D-Bus normally checks the uid a client claims against
 * the socket's peer credentials, and every proot fakes uid 0 to its own
 * processes while the kernel reports the app's real uid, so a client in
 * another proot (any app) was always refused. The socket is in the launcher's
 * private storage, and everyone in the guest is root anyway.
 */
class SessionBus(
    private val proot: Proot,
) {
    @Volatile
    private var running = false
    private var started = false

    /** Starts the buses, or resumes them after [stop]. */
    fun start() {
        running = true
        if (started) return
        started = true
        proot.hostFile(CONFIG).writeText(config)
        thread(name = "dbus", isDaemon = true) { supervise() }
    }

    /** Stops restarting the buses; the caller ends the running ones along with the other guest processes. */
    fun stop() {
        running = false
    }

    private fun supervise() {
        var backoff = 1000L
        while (true) {
            while (!running) Thread.sleep(1000)
            val started = SystemClock.elapsedRealtime()
            val code = runCatching { proot.run(SCRIPT) { Log.i(TAG, it) } }.getOrElse { -1 }
            if (SystemClock.elapsedRealtime() - started > MAX_BACKOFF_MS) backoff = 1000L
            Log.w(TAG, "dbus-daemon exited $code; restarting in ${backoff / 1000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Both buses: a session bus's policy, the standard service dirs (portals start on demand), anonymous logins. */
    private val config =
        """
        <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
         "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
        <busconfig>
          <type>session</type>
          <keep_umask/>
          <listen>$ADDRESS</listen>
          <auth>ANONYMOUS</auth>
          <allow_anonymous/>
          <standard_session_servicedirs/>
          <policy context="default">
            <allow send_destination="*" eavesdrop="true"/>
            <allow eavesdrop="true"/>
            <allow own="*"/>
          </policy>
        </busconfig>
        """.trimIndent() + "\n"

    companion object {
        /** The session bus's address, as every guest process gets it. */
        const val ADDRESS = "unix:path=${Proot.GUEST_RUNTIME_DIR}/bus"

        /** Where [config] goes in the guest; --address overrides its listen for the system bus. */
        private const val CONFIG = "/tmp/omalauncher-dbus.conf"

        // The same config for the system bus: it lets everyone in, and under proot everyone is root anyway.
        // The forked system bus ends with this proot, when the session bus does.
        private const val SCRIPT =
            "rm -f ${Proot.GUEST_RUNTIME_DIR}/bus /run/dbus/system_bus_socket && mkdir -p /run/dbus && " +
                "dbus-daemon --config-file=$CONFIG --fork --nopidfile --address=unix:path=/run/dbus/system_bus_socket && " +
                "exec dbus-daemon --config-file=$CONFIG --nofork --nopidfile --address=$ADDRESS"
    }
}
