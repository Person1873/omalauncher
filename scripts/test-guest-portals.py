#!/usr/bin/env python3
"""Protocol regressions against the real GLib service on a private session bus.
Run: python3 scripts/test-guest-portals.py (starts its own activation-free bus).
Requires a C compiler, pkg-config, gio-2.0, gio-unix-2.0 and gdbus; no Python dependencies.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import shlex
import shutil
import sys
from portal_test_backend import FakeAndroid
from portal_test_legacy import FakeLegacyBridge

ROOT = Path(__file__).resolve().parents[1]
BUS = 'org.freedesktop.portal.Desktop'
PATH = '/org/freedesktop/portal/desktop'
SETTINGS = 'org.freedesktop.portal.Settings'


class PortalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not os.environ.get('DBUS_SESSION_BUS_ADDRESS'):
            raise RuntimeError('Run this test under dbus-run-session')
        cls.scratch = tempfile.TemporaryDirectory(prefix='omalauncher-portals-')
        cls.runtime = Path(cls.scratch.name)
        cls.appearance = cls.runtime / 'omalauncher-color-scheme'
        flags = subprocess.check_output(['pkg-config', '--cflags', '--libs', 'gio-2.0', 'gio-unix-2.0'], text=True).split()
        flags += shlex.split(os.environ.get('PORTAL_TEST_CFLAGS', ''))
        cls.binary = cls.runtime / 'portal'
        subprocess.run(['cc', '-Wall', '-Wextra', '-Wno-unused-parameter', '-O0', '-g',
                        str(ROOT / 'guest/omalauncher-portal/omalauncher-portal.c'), str(ROOT / 'guest/omalauncher-portal/portal-settings.c'), str(ROOT / 'guest/omalauncher-portal/portal-actions.c'),
 str(ROOT / 'guest/omalauncher-portal/portal-transport.c'), '-o', str(cls.binary)] + flags, check=True)
        cls.log = (cls.runtime / 'service.log').open('w+')
        cls.client = cls.runtime / 'client'
        subprocess.run(['cc', '-Wall', '-Wextra', '-Wno-unused-parameter', '-O0', '-g', str(ROOT / 'scripts/portal-test-client.c'), '-o', str(cls.client)] + flags, check=True)
        cls.serializer = cls.runtime / 'gtk-serializer'
        gtk_flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'gtk+-unix-print-3.0'], text=True))
        subprocess.run(['cc', '-Wall', '-O0', '-g', str(ROOT / 'guest/omalauncher-portal/portal-print-dialog.c'), '-o', str(cls.serializer), *gtk_flags], check=True)
        cls.print_helper = cls.runtime / 'print-helper'
        shutil.copyfile(ROOT / 'scripts/portal_test_print_dialog.py', cls.print_helper)
        cls.print_helper.chmod(0o700)
        cls.backend = FakeAndroid(cls.runtime)
        cls.legacy = FakeLegacyBridge(cls.runtime)
        # Optional explicit command exercises a shipped ARM64 binary under QEMU
        # while retaining the same private bus, client and endpoint doubles.
        portal_command = shlex.split(os.environ.get('PORTAL_TEST_PORTAL_COMMAND', '')) or [str(cls.binary)]
        cls.service = subprocess.Popen(portal_command, env={**os.environ, 'XDG_RUNTIME_DIR': str(cls.runtime), 'G_DEBUG': 'fatal-warnings', 'GIO_USE_VFS': 'local', 'OMALAUNCHER_PRINT_HELPER': str(cls.print_helper), 'PORTAL_TEST_GTK_SERIALIZER': str(cls.serializer)}, stdout=cls.log, stderr=cls.log)
        for _ in range(60):
            if cls.service.poll() is not None:
                cls.log.seek(0)
                raise RuntimeError(cls.log.read())
            result = subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus', '--object-path', '/org/freedesktop/DBus', '--method', 'org.freedesktop.DBus.GetConnectionUnixProcessID', BUS], text=True, capture_output=True, timeout=5)
            if result.returncode == 0:
                if str(cls.service.pid) not in result.stdout:
                    raise RuntimeError('private bus name belongs to a different portal process: ' + result.stdout)
                break
            time.sleep(.025)
        else:
            raise RuntimeError('portal did not acquire its bus name')

    @classmethod
    def tearDownClass(cls):
        cls.legacy.close()
        cls.backend.close()
        cls.service.terminate()
        cls.service.wait(timeout=5)
        cls.log.close()
        cls.scratch.cleanup()

    @classmethod
    def dbus(cls, method, *args):
        return subprocess.run(['gdbus', 'call', '--session', '--dest', BUS, '--object-path', PATH, '--method', method, *args], text=True, capture_output=True, timeout=5)

    def call(self, method, *args):
        result = self.dbus(method, *args)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def setUp(self):
        self.appearance.unlink(missing_ok=True)
        self.legacy.configure()

    def native(self, case, *args):
        result = subprocess.run([str(self.client), case, *map(str, args)], text=True, capture_output=True, timeout=8)
        if result.returncode:
            self.log.flush(); self.log.seek(0)
            self.fail(result.stdout + result.stderr + '\nSERVICE:\n' + self.log.read())
        self.assertIsNone(self.service.poll(), 'portal died')
        self.assertFalse(self.backend.errors, self.backend.errors)
        self.assertFalse(self.legacy.errors, self.legacy.errors)

    def assert_no_picker_fifos(self):
        deadline = time.monotonic() + 3
        while list(self.runtime.glob('pick.*')) and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertFalse(list(self.runtime.glob('pick.*')), 'cancelled picker leaked its reply FIFO')

    def test_legacy_filechooser_open_multiple_roundtrip(self):
        paths = [self.runtime / 'first document.txt', self.runtime / 'second.txt']
        self.legacy.configure(paths)
        self.native('files-open', *paths)
        self.assertIn('\topen\t1\t', self.legacy.records[-1])

    def test_legacy_filechooser_save_preserves_folder_and_name(self):
        path = self.runtime / 'saved document.txt'
        self.legacy.configure([path])
        self.native('files-save', path)
        self.assertIn(f'\tsave\t0\t{self.runtime}\t{path.name}\t', self.legacy.records[-1])

    def test_legacy_filechooser_cancel(self):
        self.native('files-cancel')

    def test_legacy_filechooser_close_is_owner_bound_and_releases_fifo(self):
        self.legacy.configure(hold=True)
        self.native('files-close')
        self.assert_no_picker_fifos()

    def test_legacy_filechooser_caller_exit_releases_fifo(self):
        self.legacy.configure(hold=True)
        self.native('files-gone')
        self.assert_no_picker_fifos()

    def test_legacy_filechooser_invalid_token_does_not_kill_service(self):
        self.native('files-invalid')

    def test_legacy_filechooser_pending_requests_are_bounded(self):
        self.legacy.configure(hold=True)
        self.native('files-limit')
        self.assert_no_picker_fifos()

    def test_legacy_notifications_still_use_the_original_bridge(self):
        self.native('legacy-notify')
        with self.legacy.condition:
            self.assertTrue(self.legacy.condition.wait_for(lambda: any('Legacy title' in line for line in self.legacy.records), timeout=2))

    def test_uri_real_dbus_to_socket_roundtrip(self):
        self.native('uri')
        self.assertTrue(any(fields.get('uri') == 'https://example.org/portal-test' for fields, _ in self.backend.records))

    def test_email_fields_and_attachment_descriptors_roundtrip(self):
        attachments = [self.runtime / 'mail first.txt', self.runtime / 'mail second.txt']
        for path, content in zip(attachments, (b'MAIL-FIRST', b'MAIL-SECOND')):
            path.write_bytes(content)
        self.native('email', *attachments)
        index = next(i for i in range(len(self.backend.records) - 1, -1, -1)
                     if self.backend.records[i][0]['operation'] == 'email')
        fields, contents = self.backend.records[index]
        self.assertEqual(fields['address'], 'legacy@example.org')
        self.assertEqual([fields[f'addresses-{i}'] for i in range(2)], ['first@example.org', 'second@example.org'])
        self.assertEqual(fields['cc-0'], 'copy@example.org')
        self.assertEqual(fields['bcc-0'], 'hidden@example.org')
        self.assertEqual(fields['subject'], 'Portal email fixture')
        self.assertEqual(fields['body'], 'First line\noperation=print\nLast line')
        self.assertEqual(contents, [b'MAIL-FIRST', b'MAIL-SECOND'])
        self.assertEqual([(st.st_dev, st.st_ino) for st in self.backend.fd_stats[index]],
                         [(path.stat().st_dev, path.stat().st_ino) for path in attachments])

    def test_email_lists_over_64_are_not_truncated(self):
        attachment = self.runtime / 'mail many.txt'; attachment.write_text('attachment')
        self.native('email-many', attachment)
        fields = next(fields for fields, _ in reversed(self.backend.records) if fields.get('operation') == 'email')
        for key in ('addresses', 'cc', 'bcc'):
            actual = [fields[f'{key}-{i}'] for i in range(65)]
            self.assertEqual(actual, [f'{key}-{i}@example.org' for i in range(65)])

    def test_email_rejects_wrong_attachment_list_types(self):
        before = len(self.backend.records)
        for kind in ('ai', 'as', 's', 'u'):
            with self.subTest(kind=kind):
                self.native('email-invalid-option', 'attachment_fds', kind)
        self.assertEqual(len(self.backend.records), before, 'invalid attachments reached Android')

    def test_email_rejects_mistyped_compose_fields(self):
        before = len(self.backend.records)
        for key in ('address', 'addresses', 'cc', 'bcc', 'subject', 'body'):
            with self.subTest(key=key):
                self.native('email-invalid-option', key, 'u')
        self.assertEqual(len(self.backend.records), before, 'invalid compose fields reached Android')

    def test_email_optional_fields_can_be_absent_or_empty(self):
        for case in ('email-empty', 'email-empty-list'):
            with self.subTest(case=case):
                before = len(self.backend.records)
                self.native(case)
                self.assertEqual(len(self.backend.records), before + 1)
                fields, contents = self.backend.records[-1]
                self.assertEqual(fields['operation'], 'email')
                self.assertEqual(fields['fd-count'], '0')
                self.assertEqual(contents, [])

    def test_email_accepts_eight_attachment_capabilities(self):
        source = self.runtime / 'mail eight.txt'; source.write_bytes(b'EIGHT-ATTACHMENTS')
        before = len(self.backend.records)
        self.native('email', *([source] * 8))
        self.assertEqual(len(self.backend.records), before + 1)
        fields, contents = self.backend.records[-1]
        self.assertEqual(fields['fd-count'], '8')
        self.assertEqual(contents, [b'EIGHT-ATTACHMENTS'] * 8)

    def test_email_invalid_fd_batches_release_handles_and_descriptors(self):
        source = self.runtime / 'mail rejected.txt'; source.write_bytes(b'VALID-AFTER-REJECTION')
        original = source.stat()
        for kind in ('bad-index', 'directory', 'too-many', 'missing-list'):
            with self.subTest(kind=kind):
                before = len(self.backend.records)
                self.native('email-invalid-fds', kind, source)
                # Seventy rejected batches must not reach Android; the final
                # valid request reuses their handle and is the sole new packet.
                self.assertEqual(len(self.backend.records), before + 1)
                self.assertEqual(self.backend.records[-1][1], [b'VALID-AFTER-REJECTION'])
                deadline = time.monotonic() + 3
                while True:
                    retained = []
                    for fd in Path(f'/proc/{self.service.pid}/fd').iterdir():
                        try:
                            actual = fd.stat()
                        except FileNotFoundError:
                            continue
                        if (actual.st_dev, actual.st_ino) == (original.st_dev, original.st_ino):
                            retained.append(fd.name)
                    if not retained or time.monotonic() >= deadline:
                        break
                    time.sleep(.01)
                self.assertFalse(retained, f'portal leaked document descriptors: {retained}')

    def test_open_file_accepts_directory_descriptor(self):
        self.native('fd', self.runtime)
        index = next(i for i in range(len(self.backend.records) - 1, -1, -1)
                     if self.backend.records[i][0]['operation'] == 'open-file')
        received = self.backend.fd_stats[index][0]
        self.assertEqual((received.st_dev, received.st_ino), (self.runtime.stat().st_dev, self.runtime.stat().st_ino))

    def test_print_token_is_owner_bound_and_single_use(self):
        document = self.runtime / 'print.ps'
        document.write_bytes(b'%!PS-Adobe-3.0\nshowpage\n')
        self.native('print-token', document)

    def test_cancelled_print_selection_has_no_token(self):
        self.native('print-cancel')

    def test_pending_print_dialog_close_is_owner_bound(self):
        self.native('print-close')

    def test_message_attached_fd_is_transferred_not_an_integer(self):
        source = self.runtime / 'document.txt'
        source.write_bytes(b'FD-PAYLOAD')
        self.native('fd', source)
        self.assertTrue(any(contents == [b'FD-PAYLOAD'] for fields, contents in self.backend.records if fields['operation'] == 'open-file'))

    def test_bad_fd_index_is_rejected(self):
        source = self.runtime / 'bad-fd.txt'; source.write_text('data')
        self.native('bad-fd', source)

    def test_request_close_is_caller_owned(self):
        self.native('cancel')

    def test_duplicate_handle_does_not_corrupt_original(self):
        self.native('duplicate')

    def test_bad_handle_token_is_rejected(self):
        self.native('bad-token')

    def test_inhibit_has_no_response_and_releases_on_close(self):
        before = self.backend.closed
        self.native('inhibit')
        self.assertTrue(self.backend.wait_closed(before + 1), 'inhibitor transport not closed')

    def test_inhibit_releases_on_caller_exit(self):
        before = self.backend.closed
        self.native('inhibit-exit')
        self.assertTrue(self.backend.wait_closed(before + 1), 'caller exit leaked inhibitor transport')

    def test_notification_action_preserves_target_variant(self):
        self.native('notification')

    def test_notification_outlives_sender_and_new_instance_can_withdraw(self):
        self.native('notification-exit')
        before = self.backend.closed
        self.native('notification-withdraw')
        self.assertTrue(self.backend.wait_closed(before + 1), 'new instance did not withdraw persistent notification')

    def test_notification_replace_retires_both_connections(self):
        before = self.backend.closed
        self.native('notification-replace')
        self.assertTrue(self.backend.wait_closed(before + 2), 'replacement left old notification connection alive')

    def test_settings_version(self):
        self.assertIn('uint32 2', self.call('org.freedesktop.DBus.Properties.Get', SETTINGS, 'version'))

    def test_read_one_no_preference(self):
        self.assertIn('<uint32 0>', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', 'color-scheme'))

    def test_read_one_dark_and_light(self):
        for text, expected in [('dark\n', 1), ('light\n', 2), ('garbage\n', 0)]:
            with self.subTest(text=text):
                self.appearance.write_text(text)
                self.assertIn(f'<uint32 {expected}>', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', 'color-scheme'))

    def test_legacy_read_is_double_wrapped(self):
        self.appearance.write_text('dark\n')
        self.assertIn('<<uint32 1>>', self.call(SETTINGS + '.Read', 'org.freedesktop.appearance', 'color-scheme'))

    def test_read_all_namespace_filter(self):
        for pattern in ['[]', "['org.freedesktop.appearance']", "['org.freedesktop.*']", "['*']", "['']"]:
            self.assertIn('color-scheme', self.call(SETTINGS + '.ReadAll', pattern))
        self.assertNotIn('color-scheme', self.call(SETTINGS + '.ReadAll', "['org.other.*']"))

    def test_unknown_setting(self):
        for ns, key in [('org.unknown', 'color-scheme'), ('org.freedesktop.appearance', 'missing')]:
            result = self.dbus(SETTINGS + '.ReadOne', ns, key)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('org.freedesktop.portal.Error.NotFound', result.stderr)

    def test_atomic_replace_notifies(self):
        self.appearance.write_text('dark\n')
        monitor = subprocess.Popen(['gdbus', 'monitor', '--session', '--dest', BUS, '--object-path', PATH], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert monitor.stdout is not None
        try:
            import select
            # Readiness is gdbus' name-owner announcement, not a blind delay.
            ready = False
            until = time.monotonic() + 3
            while time.monotonic() < until:
                if select.select([monitor.stdout], [], [], .1)[0]:
                    if 'is owned by' in monitor.stdout.readline():
                        ready = True
                        break
            self.assertTrue(ready, 'signal monitor not ready')
            tmp = self.appearance.with_suffix('.tmp')
            tmp.write_text('light\n')
            tmp.replace(self.appearance)
            until = time.monotonic() + 3
            seen = ''
            while time.monotonic() < until:
                if select.select([monitor.stdout], [], [], .1)[0]:
                    seen += monitor.stdout.readline()
                    if 'SettingChanged' in seen and 'uint32 2' in seen:
                        break
            self.assertIn('SettingChanged', seen)
            self.assertIn('uint32 2', seen)
        finally:
            monitor.terminate()
            monitor.communicate(timeout=5)

    def test_existing_filechooser_and_notifications(self):
        self.assertIn('uint32', self.call('org.freedesktop.DBus.Properties.Get', 'org.freedesktop.portal.FileChooser', 'version'))
        result = subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.freedesktop.Notifications', '--object-path', '/org/freedesktop/Notifications', '--method', 'org.freedesktop.Notifications.GetCapabilities'], text=True, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('body', result.stdout)


if __name__ == '__main__':
    if os.environ.get('OMALAUNCHER_PRIVATE_TEST_BUS') != '1':
        sys.exit(subprocess.call(['dbus-run-session', '--config-file=' + str(ROOT / 'scripts/portal-test-bus.conf'), '--', sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]], env={**os.environ, 'OMALAUNCHER_PRIVATE_TEST_BUS': '1'}))
    unittest.main(verbosity=2)
