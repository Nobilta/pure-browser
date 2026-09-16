"""Host-side UTF-8 policy for the validation harness.

Device output and every artifact the harness writes contain Chinese labels. Python only defaults
to UTF-8 when the host locale says so, and Windows usually does not, where a legacy code page
would then be used for file IO and for decoding subprocess output.

Scripts that a person launches directly call ensure_utf8() from their __main__ block, which
re-executes them in UTF-8 mode. run-regressions.py additionally exports PYTHONUTF8 and
PYTHONIOENCODING to every stage script it spawns, and each script names its encodings explicitly,
so a stage run never depends on the host locale.
"""
import locale
import os
import sys


def ensure_utf8():
    if sys.flags.utf8_mode or os.environ.get('PYTHONUTF8') == '1':
        return
    if locale.getpreferredencoding(False).lower().replace('_', '-') in ('utf-8', 'utf8'):
        return
    os.environ['PYTHONUTF8'] = '1'
    try:
        os.execv(sys.executable, [sys.executable, *sys.argv])
    except OSError:
        # Keep running without the restarted interpreter; explicit encodings still protect the
        # artifacts this harness writes.
        pass
