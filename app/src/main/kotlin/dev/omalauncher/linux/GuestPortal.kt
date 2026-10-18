package dev.omalauncher.linux

import android.content.Context
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import java.io.FileNotFoundException

/**
 * The guest's portal program and GTK3 module (guest/omalauncher-portal, built
 * into the APK's assets by scripts/guest-portal/build.sh): file dialogs pick
 * through the Files app, notifications become Android ones. Installed under
 * /usr/local, away from pacman, with D-Bus service files the launcher's bus
 * looks at first (see [SessionBus]); every app gets the environment that turns
 * them on (see [ENV]). With them, omalauncher-dialog, which reads and answers
 * apps' dialogs through accessibility (see [LinuxDialogs]), and the
 * accessibility bus's config that lets apps on it under proot.
 */
class GuestPortal(
    private val context: Context,
    private val proot: Proot,
) {
    fun managed(): List<Managed> {
        val root = proot.rootfs
        // A build without the binaries (they're built separately) installs nothing; apps keep their own dialogs.
        val binaries =
            listOf(PROGRAM to "omalauncher-portal", MODULE to "libomalauncher-filechooser.so", LinuxDialogs.HELPER to "omalauncher-dialog").map { (path, asset) ->
                val bytes =
                    try {
                        context.assets.open("guest-portal/$asset").use { it.readBytes() }
                    } catch (_: FileNotFoundException) {
                        return emptyList()
                    }
                Managed(File(root, path.removePrefix("/")), Policy.Owned, executable = true) { bytes }
            }
        val services =
            listOf("org.freedesktop.portal.Desktop", "org.freedesktop.Notifications").map { name ->
                Managed.text(File(root, "$SERVICES/$name.service".removePrefix("/")), Policy.Owned) {
                    "[D-BUS Service]\nName=$name\nExec=$PROGRAM\n"
                }
            }
        // AT-SPI's bus takes this over its packaged config: the same bus, but letting apps in the way the session
        // bus does (proot's fake root doesn't match the real uid the socket reports).
        val a11y =
            Managed.text(File(root, A11Y_CONFIG.removePrefix("/")), Policy.Owned) {
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>accessibility</type>
                  <servicedir>/usr/share/dbus-1/accessibility-services</servicedir>
                  <auth>ANONYMOUS</auth>
                  <allow_anonymous/>
                  <listen>unix:dir=/tmp</listen>
                  <policy context="default">
                    <allow user="*"/>
                    <allow send_destination="*"/>
                    <allow receive_type="method_call"/>
                    <allow receive_type="method_return"/>
                    <allow receive_type="error"/>
                    <allow receive_type="signal"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n"
            }
        return binaries + services + a11y
    }

    companion object {
        const val PROGRAM = "/usr/local/lib/omalauncher/omalauncher-portal"
        const val MODULE = "/usr/local/lib/gtk-3.0/modules/libomalauncher-filechooser.so"

        /** at-spi-bus-launcher's config, read before the packaged one in /usr/share/defaults. */
        const val A11Y_CONFIG = "/etc/at-spi2/accessibility.conf"

        /** Where the bus finds its services, before the standard places (a pacman-installed xdg-desktop-portal). */
        const val SERVICES = "/usr/local/share/dbus-1/services"

        /**
         * Every guest process's: GTK3 loads the module, which sends its plain file dialogs through the portal;
         * GTK3's native dialog, GTK4 and Firefox ask the portal (GTK_USE_PORTAL, GDK_DEBUG); Qt does with its
         * xdgdesktopportal theme. Each falls back to its own dialog when there's no portal to ask. Qt only
         * describes its widgets to accessibility when a screen reader asks, unless told to always.
         */
        val ENV =
            listOf(
                "GTK_MODULES=omalauncher-filechooser",
                "GTK_PATH=/usr/local/lib/gtk-3.0",
                "GTK_USE_PORTAL=1",
                "GDK_DEBUG=portals",
                "QT_QPA_PLATFORMTHEME=xdgdesktopportal",
                "QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1",
            )
    }
}
