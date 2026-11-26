#!/usr/bin/env python3
"""Verify local previews, original-text switching and responsive controls on a dedicated emulator."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.mybrowser')
parser.add_argument('--debug-webview', action='store_true', help='Read rendered text through debug WebView when Android accessibility snapshots omit descendants')
parser.add_argument('--output', type=Path, default=ROOT / 'results/local-text-preview')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Use a dedicated test emulator'
assert not args.debug_webview or args.package.endswith('.debug'), 'WebView inspection requires a debug build'
ux.ADB = ['adb', '-s', args.serial]
ux.PACKAGE = args.package
args.output.mkdir(parents=True, exist_ok=True)
shell_user = ['su', '2000'] if ux.adb('shell', 'id', '-u') == '0' else []
created = []
apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
result = {'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': [], 'passed': False}


def record(message):
    result['checks'].append(message)
    print('PASS:', message, flush=True)


def contains(fragment):
    deadline = time.monotonic() + 12
    while True:
        # Keep the probe connected while WebView publishes its asynchronous text tree.
        if args.debug_webview:
            found = fragment in ux.webview_text()
        else:
            root, _ = ux.nodes(await_labels=[fragment], timeout_ms=2000)
            found = any(fragment in n.get('text', '') for n in root.iter('node') if ux.visible(n))
        if found:
            return
        assert time.monotonic() < deadline, 'Missing text: ' + fragment
        time.sleep(.2)


def shot(name):
    (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
    (args.output / (name + '.png')).write_bytes(subprocess.check_output(
        ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.4)


def open_file(extension, content, mime, action='VIEW', encoding='utf-8', name=None):
    name = name or 'pure-preview-regression.' + extension
    local = args.output / name
    local.write_bytes(content.encode(encoding))
    path = '/sdcard/Download/' + name
    created.append((local, path))
    ux.adb('push', str(local), path)
    uri = 'content://com.android.externalstorage.documents/document/primary%3ADownload%2F' + name
    activity = 'MainActivity' if mime == 'text/html' or action == 'SEND' else 'text.PlainTextActivity'
    cmd = ['am', 'start', '-W', '-n', ux.PACKAGE + '/com.mybrowser.' + activity,
           '-a', 'android.intent.action.' + action, '-t', mime, '--grant-read-uri-permission']
    # am does not migrate EXTRA_STREAM into ClipData like an Android sender does.
    # Carry the same URI in data so ActivityManager grants real access to the recipient.
    cmd += ['-d', uri]
    if action == 'SEND':
        cmd += ['--eu', 'android.intent.extra.STREAM', uri]
    ux.adb('shell', *shell_user, *cmd)
    time.sleep(.5)


def toggle():
    ux.tap('Show original')
    ux.expect('Preview', timeout=10)


def find_text(value, expected):
    root, _ = ux.nodes()
    field = next((n for n in root.iter('node') if ux.visible(n)
                  and n.get('class') == 'android.widget.EditText'), None)
    if field is None:
        ux.tap_resource('ui_find_in_page')
        field = next(n for n in ux.nodes()[0].iter('node') if ux.visible(n)
                     and n.get('class') == 'android.widget.EditText')
    ux.tap_node(field)
    ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
           'com.mybrowser.validation.FastUiDump', 'setText', base64.b64encode(value.encode()).decode())
    ux.expect(expected)


def controls_fit():
    root, _ = ux.nodes()
    width = max(ux.bounds(n)[2] for n in root.iter('node'))
    for key in ('text_show_preview', 'plain_text_copy'):
        labels = ux.resource_labels(key)
        node = next(n for n in root.iter('node') if ux.visible(n) and n.get('text') in labels)
        x1, y1, x2, y2 = ux.bounds(node)
        assert 0 <= x1 < x2 <= width and y2 > y1, (key, ux.bounds(node))


try:
    ux.adb('shell', 'wm', 'size', 'reset')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    ux.adb('shell', 'cmd', 'uimode', 'night', 'no')
    ux.launch()  # Complete the welcome dialog before opening standalone text activities.
    markdown = '# Markdown 中文 😀\n\n- **重点**\n\n| 名称 | 值 |\n| --- | --- |\n| 项目 | 42 |\n\n```html\n<b>代码</b>\n```\n\n<script>alert("INERT")</script>'
    open_file('md', markdown, 'text/plain')
    contains('Markdown 中文 😀')
    contains('重点')
    contains('42')
    shot('markdown-light')
    toggle()
    contains('# Markdown 中文 😀')
    shot('markdown-original')
    ux.tap('Preview')
    contains('42')
    record('Markdown headings, list, table, escaped code/HTML and raw/preview round trip')
    back()

    open_file('md', '# needle\n\nneedle in **bold**\n\n' + '\n\n'.join('line %d' % i for i in range(80)) + '\n\nneedle at end', 'text/plain')
    find_text('needle', '1/3')
    ux.tap_resource('ui_next')
    ux.expect('2/3')
    ux.tap_resource('ui_previous')
    ux.expect('1/3')
    ux.tap_resource('ui_previous')
    ux.expect('3/3')
    shot('find-last-match')
    toggle()
    ux.expect('1/3')
    find_text('**bold**', '1/1')
    ux.tap('Preview')
    ux.expect('0/0')
    find_text('needle', '1/3')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    ux.adb('shell', 'wm', 'size', '840x1920')
    ux.expect('1/3', timeout=12)
    shot('find-320dp-200percent-ime')
    ux.tap_resource('ui_close')
    ux.expect('1/3', present=False)
    record('Text find counts, next/previous/wrap, raw/preview re-search, no match, and recreation at 320dp/200%')
    back()
    ux.adb('shell', 'wm', 'size', 'reset')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')

    csv = 'name,note,empty\r\n"中文,😀","needle\nline ""quoted""",\r\nsecond,needle,last'
    open_file('csv', csv, 'text/csv')
    contains('中文,😀')
    contains('line "quoted"')
    shot('csv-table')
    find_text('needle', '1/2')
    ux.tap_resource('ui_next')
    ux.expect('2/2')
    ux.tap_resource('ui_close')
    toggle()
    contains(csv.replace('\r\n', '\n'))
    ux.tap('Preview')
    contains('中文,😀')
    record('CSV quoted commas, escaped quotes, embedded newline, empty cells, find and original round trip')
    back()

    open_file('tsv', 'name\tnote\n中文\t"tab\tinside"\n', 'text/tab-separated-values')
    contains('tab')
    shot('tsv-table')
    toggle()
    contains('name\tnote')
    record('TSV renders as a table and keeps original tabs')
    back()

    open_file('csv', 'a,"unfinished', 'text/csv')
    ux.expect('The table format is invalid. Showing the original text.')
    contains('a,"unfinished')
    record('Malformed CSV reports its format and preserves original text')
    back()

    dense = ',' * 50000 + 'FINAL_SENTINEL'
    open_file('csv', dense, 'text/csv')
    ux.expect('The table is too large to preview. Showing the complete original text.')
    find_text('FINAL_SENTINEL', '1/1')
    ux.tap_resource('ui_close')
    toggle()
    find_text('FINAL_SENTINEL', '1/1')
    ux.tap_resource('ui_close')
    record('Dense CSV falls back to complete original, including searchable final content')
    back()

    for ext, mime, body in [
        ('yaml', 'application/yaml', 'name: "中文 # literal"\ndescription: It\'s fine\nmessage: He said \'hello\nenabled: true\nport: 8080 # comment\n'),
        ('toml', 'application/toml', '[server]\nname = "中文 # literal"\nport = 8080 # comment\n'),
        ('ini', 'text/x-ini', '[server]\n; comment\nname=中文\nauthor=O\'Reilly\nport=8080\n'),
        ('properties', 'text/x-properties', '! comment\nname=中文\nauthor=O\'Reilly\nquote=\'literal\nport:8080\n'),
    ]:
        open_file(ext, body, mime)
        contains('中文')
        shot(ext + '-highlighted')
        toggle()
        contains(body.rstrip('\n'))
        ux.tap('Preview')
        contains('中文')
        record(ext + ' highlights and preserves original text')
        back()

    open_file('yaml', '#' + 'a' * 999998 + '😀BUDGET_END', 'application/yaml')
    find_text('😀BUDGET_END', '1/1')
    ux.tap_resource('ui_close')
    shot('yaml-budget-unicode')
    toggle()
    find_text('😀BUDGET_END', '1/1')
    ux.tap_resource('ui_close')
    record('A supplementary character at the highlight budget stays intact in preview and original')
    back()

    original = '{"big":123456789012345678901234567890,"name":"中文😀","ok":true,"empty":null}'
    # A cold SEND also loads MainActivity's page before forwarding to the viewer.
    # Its later page commit must not capture a thumbnail from the hidden window.
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    open_file('json', original, 'application/json', action='SEND')
    contains('123456789012345678901234567890')
    shot('json-light')
    # Read the short snackbar immediately; a separate probe tap adds VM startup/shutdown time.
    x1, y1, x2, y2 = ux.bounds(ux.match(ux.nodes()[0], 'Copy'))
    ux.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    ux.expect('Copied')
    toggle()
    contains(original)
    shot('json-original')
    ux.tap('Preview')
    contains('123456789012345678901234567890')
    record('Cold JSON SEND, formatting, precise large number, original text and copy action')
    back()

    xml = '<?xml version="1.0"?>\n<root name="中文😀">\n  <!-- comment -->\n  <item enabled="true">value &amp; text</item>\n</root>'
    open_file('xml', xml, 'application/xml')
    contains('root name=')
    shot('xml-light')
    toggle()
    contains(xml)
    ux.tap('Preview')
    ux.adb('shell', 'cmd', 'uimode', 'night', 'yes')
    contains('root name=')
    shot('xml-dark')
    toggle()
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    ux.adb('shell', 'wm', 'size', '840x1920')
    ux.expect('Preview', timeout=10)
    controls_fit()
    shot('xml-original-320dp-200percent')
    ux.tap('Preview')
    contains('root name=')
    shot('xml-preview-320dp-200percent')
    record('XML highlighting, original preservation, dark theme, recreation and 320dp/200% actions')
    back()
    ux.adb('shell', 'wm', 'size', 'reset')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    ux.adb('shell', 'cmd', 'uimode', 'night', 'no')

    long_markdown = '# Long file name\n\n**Preview available**'
    open_file('md', long_markdown, 'text/plain', name='pure-preview-' + 'a' * 120 + '.md')
    ux.expect('Preview available')
    toggle()
    contains(long_markdown)
    record('Long generic-text filename retains its Markdown extension for preview detection')
    back()

    for label, invalid in [('syntax', '{"a":1,}'), ('unicode', r'{"name":"\uZZZZ"}')]:
        open_file('json', invalid, 'application/json')
        ux.expect('Invalid JSON. Showing the original text.', timeout=10)
        contains(invalid)
        shot('json-invalid-' + label)
        toggle()
        contains(invalid)
        ux.tap('Preview')
        ux.expect('Invalid JSON. Showing the original text.')
        record('Malformed JSON (' + label + ') shows original text and survives preview switching')
        back()

    encoding_html = '<meta name=description content="example charset=gbk"><meta data-charset=gbk charset=utf-16><h1>编码声明 中文 😀</h1>'
    open_file('html', encoding_html, 'text/html')
    ux.expect('编码声明 中文 😀', timeout=10)
    toggle()
    contains(encoding_html)
    ux.tap('Preview')
    ux.expect('编码声明 中文 😀')
    record('HTML source matches rendered Chinese/emoji with non-encoding attributes and a UTF-16 meta label')

    html = '<meta charset=gb18030><meta name="viewport" content="width=device-width, initial-scale=1"><h1>本地 HTML 中文</h1><button onclick="this.textContent=\'COUNT 1\'">COUNT 0</button><textarea aria-label="Clipboard"></textarea>'
    open_file('html', html, 'text/html', encoding='gb18030')
    ux.expect('本地 HTML 中文', timeout=10)
    ux.tap('COUNT 0')
    ux.expect('COUNT 1')
    # Pasting confirms that Copy used the unformatted original JSON.
    ux.tap_node(next(n for n in ux.nodes()[0].iter('node')
                     if ux.visible(n) and n.get('class') == 'android.widget.EditText'))
    ux.adb('shell', 'input', 'keyevent', '279')
    contains(original)
    back()  # dismiss the IME
    shot('html-rendered')
    toggle()
    contains(html)
    find_text('COUNT', '1/2')
    ux.tap_resource('ui_next')
    ux.expect('2/2')
    ux.tap_resource('ui_close')
    shot('html-original')
    ux.tap('Preview')
    ux.expect('COUNT 1')
    contains(original)
    record('HTML source is decoded from original bytes; return preserves script/form state; copy is original JSON')

    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    ux.adb('shell', 'wm', 'size', '840x1920')
    # MainActivity restores the document on a font-configuration change. Set state
    # after that recreation, then verify that source/preview itself preserves it.
    ux.expect('COUNT 0', timeout=10)
    ux.tap('COUNT 0')
    ux.expect('COUNT 1')
    toggle()
    controls_fit()
    shot('html-original-320dp-200percent')
    ux.tap('Preview')
    ux.expect('COUNT 1')
    record('HTML source action and return work at 320dp/200%')
    ux.tap('Homepage')
    ux.expect('Show original', present=False)
    record('Source action disappears after leaving the local document')
    result['passed'] = True
finally:
    shot('final')
    (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    ux.adb('shell', 'wm', 'size', 'reset')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    ux.adb('shell', 'cmd', 'uimode', 'night', 'no')
    for local, path in created:
        ux.adb('shell', 'rm', '-f', path)
        local.unlink(missing_ok=True)
