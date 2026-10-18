/*
 * omalauncher-dialog: reads and answers a Linux app's dialog through
 * accessibility (AT-SPI), for the launcher to put its question in an Android
 * notification ("Save changes?" with Save, Discard and Cancel) after the
 * app's card was swiped away.
 *
 *   omalauncher-dialog list <pid> [title]
 *       The app's dialog: its title on the first line, then its buttons, one
 *       per line. With a title, the window of that name; without, the app's
 *       alert or dialog. Pid 0 looks in every app. Exits 1 when there's none.
 *   omalauncher-dialog press <pid> <title> <button>
 *       Presses that button in the window of that title. Exits 1 if it isn't there.
 *
 * Apps only answer while the accessibility bus is up; Qt also wants
 * QT_LINUX_ACCESSIBILITY_ALWAYS_ON, which the launcher sets for every app.
 */
#include <atspi/atspi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Deep enough for any dialog's buttons, shallow enough that a huge window can't keep us long. */
#define MAX_DEPTH 30

/*
 * The first question starts the registry, which asks running apps to register
 * again; they do a moment later, and the news only arrives through the main loop.
 */
#define FIND_TRIES 15
#define FIND_PAUSE_US 200000

/* Waits [us], handling what the bus sends meanwhile (apps registering). */
static void pump(gint64 us) {
    gint64 end = g_get_monotonic_time() + us;
    while (g_get_monotonic_time() < end) {
        while (g_main_context_iteration(NULL, FALSE)) {}
        g_usleep(10000);
    }
}

static gboolean is_button(AtspiAccessible *acc) {
    AtspiRole role = atspi_accessible_get_role(acc, NULL);
    if (role != ATSPI_ROLE_PUSH_BUTTON && role != ATSPI_ROLE_TOGGLE_BUTTON) return FALSE;
    AtspiStateSet *states = atspi_accessible_get_state_set(acc);
    gboolean usable = atspi_state_set_contains(states, ATSPI_STATE_SHOWING) && atspi_state_set_contains(states, ATSPI_STATE_SENSITIVE);
    g_object_unref(states);
    return usable;
}

/* The buttons under [acc], in the order the app lists them; each a new reference. */
static void buttons(AtspiAccessible *acc, int depth, GPtrArray *out) {
    if (depth > MAX_DEPTH) return;
    if (is_button(acc)) {
        gchar *name = atspi_accessible_get_name(acc, NULL);
        if (name && *name) g_ptr_array_add(out, g_object_ref(acc));
        g_free(name);
        return;
    }
    gint n = atspi_accessible_get_child_count(acc, NULL);
    for (gint i = 0; i < n; i++) {
        AtspiAccessible *child = atspi_accessible_get_child_at_index(acc, i, NULL);
        if (!child) continue;
        buttons(child, depth + 1, out);
        g_object_unref(child);
    }
}

/* The app's window named [title]; without one, its alert or dialog, the newest if there are several. */
static AtspiAccessible *dialog_of(AtspiAccessible *app, const gchar *title) {
    AtspiAccessible *found = NULL;
    gint n = atspi_accessible_get_child_count(app, NULL);
    for (gint i = 0; i < n; i++) {
        AtspiAccessible *win = atspi_accessible_get_child_at_index(app, i, NULL);
        if (!win) continue;
        gboolean match;
        if (title && *title) {
            gchar *name = atspi_accessible_get_name(win, NULL);
            match = g_strcmp0(name, title) == 0;
            g_free(name);
        } else {
            AtspiRole role = atspi_accessible_get_role(win, NULL);
            match = role == ATSPI_ROLE_ALERT || role == ATSPI_ROLE_DIALOG || role == ATSPI_ROLE_FILE_CHOOSER;
        }
        if (match) {
            if (found) g_object_unref(found);
            found = win;
        } else {
            g_object_unref(win);
        }
    }
    return found;
}

/* [pid]'s dialog (see dialog_of), or with pid 0, the first app's that has one. */
static AtspiAccessible *find_dialog(guint pid, const gchar *title) {
    AtspiAccessible *desktop = atspi_get_desktop(0);
    AtspiAccessible *found = NULL;
    gint n = atspi_accessible_get_child_count(desktop, NULL);
    for (gint i = 0; i < n && !found; i++) {
        AtspiAccessible *app = atspi_accessible_get_child_at_index(desktop, i, NULL);
        if (!app) continue;
        if (pid == 0 || atspi_accessible_get_process_id(app, NULL) == pid) found = dialog_of(app, title);
        g_object_unref(app);
    }
    g_object_unref(desktop);
    return found;
}

int main(int argc, char **argv) {
    if (argc < 3 || (strcmp(argv[1], "list") != 0 && strcmp(argv[1], "press") != 0) || (strcmp(argv[1], "press") == 0 && argc < 5)) {
        fprintf(stderr, "usage: %s list <pid> [title] | press <pid> <title> <button>\n", argv[0]);
        return 2;
    }
    if (atspi_init() < 0) return 1;
    guint pid = (guint) strtoul(argv[2], NULL, 10);
    const gchar *title = argc > 3 ? argv[3] : NULL;
    AtspiAccessible *dialog = NULL;
    for (int i = 0; i < FIND_TRIES && !dialog; i++) {
        if (i > 0) pump(FIND_PAUSE_US);
        dialog = find_dialog(pid, title);
    }
    if (!dialog) return 1;

    GPtrArray *found = g_ptr_array_new_with_free_func(g_object_unref);
    buttons(dialog, 0, found);
    int status = 1;
    if (strcmp(argv[1], "list") == 0) {
        gchar *name = atspi_accessible_get_name(dialog, NULL);
        printf("%s\n", name ? name : "");
        g_free(name);
        for (guint i = 0; i < found->len; i++) {
            gchar *label = atspi_accessible_get_name(found->pdata[i], NULL);
            printf("%s\n", label);
            g_free(label);
        }
        status = found->len > 0 ? 0 : 1;
    } else {
        for (guint i = 0; i < found->len && status != 0; i++) {
            gchar *label = atspi_accessible_get_name(found->pdata[i], NULL);
            if (g_strcmp0(label, argv[4]) == 0) {
                AtspiAction *action = atspi_accessible_get_action_iface(found->pdata[i]);
                if (action && atspi_action_do_action(action, 0, NULL)) status = 0;
                if (action) g_object_unref(action);
            }
            g_free(label);
        }
    }
    g_ptr_array_unref(found);
    g_object_unref(dialog);
    fflush(stdout);
    return status;
}
