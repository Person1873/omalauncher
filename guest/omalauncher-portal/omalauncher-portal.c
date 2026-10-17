/*
 * omalauncher-portal: the file chooser portal (org.freedesktop.portal.FileChooser)
 * for Linux apps in omalauncher.
 *
 * It's the portal service itself, not a backend for xdg-desktop-portal: that
 * one identifies each caller through /proc/<pid>/root, which under proot
 * names the host path and can't be opened, so it refuses every call.
 *
 * OpenFile and SaveFile ask the launcher's Files app to pick (through the
 * open bridge FIFO, as omarchy-edit asks the editor), and the picked files
 * come back as file URIs in the request's Response signal. Picking happens in
 * Linux's own file system, so the paths are real.
 *
 * Request, one line on the bridge:
 *   omarchy-pick:<reply fifo>\t<open|folder|save>\t<1|0 multiple>\t<folder>\t<name>\t<title>
 * Reply, on the reply FIFO: the picked paths, one per line; an empty line is a cancel.
 */
#include <errno.h>
#include <fcntl.h>
#include <gio/gio.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define BUS_NAME "org.freedesktop.portal.Desktop"
#define OBJECT_PATH "/org/freedesktop/portal/desktop"

static const char *runtime_dir;
static GDBusConnection *bus;
static GDBusNodeInfo *request_info;

static const gchar introspection_xml[] =
    "<node>"
    "  <interface name='org.freedesktop.portal.FileChooser'>"
    "    <method name='OpenFile'>"
    "      <arg type='s' name='parent_window' direction='in'/>"
    "      <arg type='s' name='title' direction='in'/>"
    "      <arg type='a{sv}' name='options' direction='in'/>"
    "      <arg type='o' name='handle' direction='out'/>"
    "    </method>"
    "    <method name='SaveFile'>"
    "      <arg type='s' name='parent_window' direction='in'/>"
    "      <arg type='s' name='title' direction='in'/>"
    "      <arg type='a{sv}' name='options' direction='in'/>"
    "      <arg type='o' name='handle' direction='out'/>"
    "    </method>"
    "    <property name='version' type='u' access='read'/>"
    "  </interface>"
    "</node>";

static const gchar request_xml[] =
    "<node>"
    "  <interface name='org.freedesktop.portal.Request'>"
    "    <method name='Close'/>"
    "    <signal name='Response'>"
    "      <arg type='u' name='response'/>"
    "      <arg type='a{sv}' name='results'/>"
    "    </signal>"
    "  </interface>"
    "</node>";

/* Tabs and newlines would break the one-line request. */
static gchar *field(const gchar *s) {
    gchar *f = g_strdup(s ? s : "");
    for (gchar *p = f; *p; p++)
        if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
    return f;
}

/* An "ay" option (a NUL-terminated path), or NULL. */
static gchar *bytes_option(GVariant *options, const char *key) {
    GVariant *v = g_variant_lookup_value(options, key, G_VARIANT_TYPE_BYTESTRING);
    if (!v) return NULL;
    /* Usually NUL-terminated; take it either way. */
    gsize len = 0;
    const gchar *data = g_variant_get_fixed_array(v, &len, 1);
    gchar *s = g_strndup(data, len);
    g_variant_unref(v);
    return s;
}

/* Asks the launcher; returns the picked paths (NULL-terminated, maybe empty), or NULL if it couldn't ask. */
static gchar **pick(const char *mode, gboolean multiple, const char *folder, const char *name, const char *title) {
    gchar *reply = g_strdup_printf("%s/pick.XXXXXX", runtime_dir);
    int tmp = g_mkstemp(reply);
    if (tmp < 0) return NULL;
    close(tmp);
    unlink(reply);
    if (mkfifo(reply, 0600) != 0) return NULL;

    gchar *f = field(folder), *n = field(name), *t = field(title);
    gchar *line = g_strdup_printf("omarchy-pick:%s\t%s\t%d\t%s\t%s\t%s\n", reply, mode, multiple ? 1 : 0, f, n, t);
    g_free(f), g_free(n), g_free(t);

    /* Not blocking: with no launcher reading the bridge, there's nobody to ask. */
    gchar *bridge = g_strdup_printf("%s/open.fifo", runtime_dir);
    int fd = open(bridge, O_WRONLY | O_NONBLOCK);
    g_free(bridge);
    gboolean sent = fd >= 0 && write(fd, line, strlen(line)) == (ssize_t) strlen(line);
    if (fd >= 0) close(fd);
    g_free(line);
    if (!sent) {
        unlink(reply);
        g_free(reply);
        return NULL;
    }

    /* Blocks until the launcher answers: the dialog is open for as long as it takes. */
    gchar *answer = NULL;
    g_file_get_contents(reply, &answer, NULL, NULL);
    unlink(reply);
    g_free(reply);
    gchar **lines = g_strsplit(answer ? answer : "", "\n", -1);
    g_free(answer);
    GPtrArray *paths = g_ptr_array_new();
    for (gchar **l = lines; *l; l++)
        if (**l == '/') g_ptr_array_add(paths, g_strdup(*l));
    g_strfreev(lines);
    g_ptr_array_add(paths, NULL);
    return (gchar **) g_ptr_array_free(paths, FALSE);
}

/* One dialog: its Request object, and what the app asked. */
typedef struct {
    gchar *sender, *path, *mode, *folder, *name, *title;
    gboolean multiple;
    guint registration;
    gint closed; /* the app closed the request: no Response */
} Request;

static void request_free(Request *r) {
    g_free(r->sender), g_free(r->path), g_free(r->mode), g_free(r->folder), g_free(r->name), g_free(r->title);
    g_free(r);
}

static void request_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                         GVariant *params, GDBusMethodInvocation *invocation, gpointer data) {
    /* Close: the app gave up. The Files app stays open until it's answered or dismissed; nothing is sent. */
    g_atomic_int_set(&((Request *) data)->closed, 1);
    g_dbus_method_invocation_return_value(invocation, NULL);
}

static const GDBusInterfaceVTable request_vtable = {request_call, NULL, NULL, {0}};

/* Waits for the launcher in a thread of its own (several dialogs can be open), then answers on the Request. */
static void run_request(GTask *task, gpointer source, gpointer data, GCancellable *cancellable) {
    Request *r = data;
    gchar **paths = pick(r->mode, r->multiple, r->folder, r->name, r->title);
    GVariantBuilder results;
    g_variant_builder_init(&results, G_VARIANT_TYPE_VARDICT);
    guint response = 2; /* other: the launcher couldn't be asked */
    if (paths) {
        response = paths[0] ? 0 : 1; /* picked, or cancelled */
        GVariantBuilder uris;
        g_variant_builder_init(&uris, G_VARIANT_TYPE_STRING_ARRAY);
        for (gchar **p = paths; *p; p++) {
            gchar *uri = g_filename_to_uri(*p, NULL, NULL);
            if (uri) g_variant_builder_add(&uris, "s", uri);
            g_free(uri);
        }
        g_variant_builder_add(&results, "{sv}", "uris", g_variant_builder_end(&uris));
        g_strfreev(paths);
    }
    GVariant *body = g_variant_new("(u@a{sv})", response, g_variant_builder_end(&results));
    if (g_atomic_int_get(&r->closed))
        g_variant_unref(g_variant_ref_sink(body));
    else
        g_dbus_connection_emit_signal(bus, r->sender, r->path, "org.freedesktop.portal.Request", "Response", body, NULL);
    g_dbus_connection_unregister_object(bus, r->registration);
    g_task_return_boolean(task, TRUE);
}

/* The Request's object path, as clients predict it from their handle_token to subscribe before calling. */
static gchar *request_path(const gchar *sender, GVariant *options) {
    static guint counter;
    const gchar *token = NULL;
    g_variant_lookup(options, "handle_token", "&s", &token);
    gchar *own = token ? NULL : g_strdup_printf("omalauncher%u", g_atomic_int_add(&counter, 1));
    gchar *name = g_strdup(sender + (sender[0] == ':'));
    for (gchar *c = name; *c; c++)
        if (*c == '.') *c = '_';
    gchar *path = g_strdup_printf("%s/request/%s/%s", OBJECT_PATH, name, token ? token : own);
    g_free(name), g_free(own);
    return path;
}

static void method_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                        GVariant *params, GDBusMethodInvocation *invocation, gpointer user_data) {
    const gchar *parent, *title;
    GVariant *options;
    g_variant_get(params, "(&s&s@a{sv})", &parent, &title, &options);

    Request *r = g_new0(Request, 1);
    gboolean save = g_strcmp0(method, "SaveFile") == 0;
    gboolean directory = FALSE;
    g_variant_lookup(options, "multiple", "b", &r->multiple);
    g_variant_lookup(options, "directory", "b", &directory);
    r->multiple = r->multiple && !save;
    r->mode = g_strdup(save ? "save" : directory ? "folder" : "open");
    r->folder = bytes_option(options, "current_folder");
    const gchar *suggested = NULL;
    g_variant_lookup(options, "current_name", "&s", &suggested);
    r->name = g_strdup(suggested ? suggested : "");
    gchar *file = save ? bytes_option(options, "current_file") : NULL;
    if (file) {
        /* Saving over an existing file: start in its folder, with its name. */
        g_free(r->folder), g_free(r->name);
        r->folder = g_path_get_dirname(file);
        r->name = g_path_get_basename(file);
        g_free(file);
    }
    if (!r->folder) r->folder = g_strdup("");
    r->title = g_strdup(title);
    r->sender = g_strdup(sender);
    r->path = request_path(sender, options);
    g_variant_unref(options);

    GError *error = NULL;
    r->registration = g_dbus_connection_register_object(conn, r->path, request_info->interfaces[0], &request_vtable, r, NULL, &error);
    if (!r->registration) {
        g_dbus_method_invocation_return_gerror(invocation, error);
        g_error_free(error);
        request_free(r);
        return;
    }
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(o)", r->path));
    GTask *task = g_task_new(NULL, NULL, NULL, NULL);
    g_task_set_task_data(task, r, (GDestroyNotify) request_free);
    g_task_run_in_thread(task, run_request);
    g_object_unref(task);
}

static GVariant *get_property(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *property,
                              GError **error, gpointer user_data) {
    return g_variant_new_uint32(4);
}

static const GDBusInterfaceVTable vtable = {method_call, get_property, NULL, {0}};

static void bus_acquired(GDBusConnection *conn, const gchar *name, gpointer user_data) {
    bus = conn;
    request_info = g_dbus_node_info_new_for_xml(request_xml, NULL);
    GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(introspection_xml, NULL);
    GError *error = NULL;
    if (!g_dbus_connection_register_object(conn, OBJECT_PATH, info->interfaces[0], &vtable, NULL, NULL, &error)) {
        g_printerr("omalauncher-portal: %s\n", error->message);
        g_error_free(error);
    }
    g_dbus_node_info_unref(info);
}

static void name_lost(GDBusConnection *conn, const gchar *name, gpointer user_data) {
    g_printerr("omalauncher-portal: couldn't own %s\n", name);
    g_main_loop_quit(user_data);
}

int main(void) {
    runtime_dir = g_getenv("XDG_RUNTIME_DIR");
    if (!runtime_dir) runtime_dir = "/run/user/0";
    GMainLoop *loop = g_main_loop_new(NULL, FALSE);
    g_bus_own_name(G_BUS_TYPE_SESSION, BUS_NAME, G_BUS_NAME_OWNER_FLAGS_NONE, bus_acquired, NULL, name_lost, loop, NULL);
    g_main_loop_run(loop);
    return 0;
}
