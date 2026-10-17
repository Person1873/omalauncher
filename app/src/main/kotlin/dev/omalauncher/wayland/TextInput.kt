package dev.omalauncher.wayland

/**
 * zwp_text_input_manager_v3: how a client says a text field has focus.
 * GTK and Qt enable their text input when one does and disable it when it
 * loses focus, which is when the launcher shows and hides the soft keyboard,
 * and they say what the field is for (a number, a phone number), which picks
 * the keyboard. Typing still arrives as key events; no text is composed through it.
 */
class TextInputManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_text_input_manager_v3"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                val input = client.register(TextInput(client, args.newId(), version))
                // Made while its client has the keyboard: it's in the focused surface already.
                client.server.input.keyboardFocus?.takeIf { it.client === client }?.let(input::enter)
            }
        }
    }
}

class TextInput(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_text_input_v3"

    /** The surface it's in, told by enter; a client only enables a text input that's in one. */
    private var focus: Surface? = null
    private var pendingEnabled = false
    private var enabled = false
    private var pendingPurpose = 0
    private var purpose = 0
    private var commits = 0

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> pendingEnabled = true
            2 -> pendingEnabled = false
            5 -> { // set_content_type: hint, then purpose
                args.uint()
                pendingPurpose = args.uint()
            }
            7 -> commit()
            else -> Unit // surrounding text, change cause, cursor rectangle: keys carry the typing
        }
    }

    private fun commit() {
        commits++
        val surface = focus
        if ((pendingEnabled != enabled || (enabled && pendingPurpose != purpose)) && surface != null) {
            enabled = pendingEnabled
            purpose = pendingPurpose
            client.server.windowManager.textInputChanged(surface, enabled, purpose)
        }
        // The next field to enable starts as plain text, unless it says otherwise.
        if (!pendingEnabled) pendingPurpose = 0
        send(event(5).uint(commits)) // done: nothing pending from us
    }

    fun enter(surface: Surface) {
        if (focus === surface) return
        focus?.let(::leave)
        focus = surface
        send(event(0).obj(surface.id))
    }

    fun leave(surface: Surface) {
        if (focus !== surface) return
        // A destroyed surface is gone for the client too: naming it in an event is a fatal protocol error.
        if (!surface.destroyed) send(event(1).obj(surface.id))
        focus = null
        // Leaving disables it, whatever the client last said.
        if (enabled) {
            enabled = false
            pendingEnabled = false
            client.server.windowManager.textInputChanged(surface, false, 0)
        }
    }

    override fun onDestroy() {
        focus?.let(::leave)
    }
}
