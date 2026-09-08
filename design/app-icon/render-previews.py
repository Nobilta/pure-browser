#!/usr/bin/env python3
"""Render this project's path-only Android launcher vectors into a portable SVG preview.

The layer viewport is 108dp and the visible adaptive mask spans its central 72dp.
This is a preview exporter for the supplied path/linear-gradient resources, not a
general-purpose VectorDrawable renderer. Uses only the Python standard library.
"""
import html
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
PATH_ATTRIBUTES = {
    'pathData': 'd', 'fillColor': 'fill', 'fillAlpha': 'fill-opacity',
    'strokeColor': 'stroke', 'strokeAlpha': 'stroke-opacity', 'strokeWidth': 'stroke-width',
    'strokeLineCap': 'stroke-linecap', 'strokeLineJoin': 'stroke-linejoin',
}


def paths(source, color=None):
    result = []
    for node in ET.parse(source).getroot():
        assert node.tag == 'path', 'Only path-based vectors are supported'
        attributes = {'fill': 'none'}
        for key, value in node.attrib.items():
            name = key.removeprefix(ANDROID)
            if name not in PATH_ATTRIBUTES:
                raise ValueError('Unsupported vector attribute: ' + name)
            if value == '@android:color/transparent':
                value = 'none'
            elif value.startswith('#') and len(value) == 9:
                assert value[1:3] == 'FF', 'Only opaque ARGB colors are supported'
                value = '#' + value[3:]
            if color and name in ('fillColor', 'strokeColor') and value != 'none':
                value = color
            attributes[PATH_ATTRIBUTES[name]] = value
        result.append('<path ' + ' '.join(f'{key}="{html.escape(value, quote=True)}"'
                                         for key, value in attributes.items()) + '/>')
    return ''.join(result)


def svg(resources, identity, mask='rounded', themed=False):
    if mask == 'circle':
        mask_path = '<circle cx="54" cy="54" r="36"/>'
    else:
        mask_path = '<rect x="18" y="18" width="72" height="72" rx="16"/>'
    if themed:
        defs = ''
        background = '#D5EDE3'
        foreground = paths(resources / 'drawable/ic_launcher_monochrome.xml', '#264F47')
    else:
        gradient = ET.parse(resources / 'drawable/ic_launcher_background.xml').getroot().find('gradient')
        coords = {'45': (0, 108, 108, 0), '315': (0, 0, 108, 108)}[gradient.get(ANDROID + 'angle')]
        x1, y1, x2, y2 = coords
        stops = ''.join(f'<stop offset="{offset}" stop-color="{gradient.get(ANDROID + name)}"/>'
                        for offset, name in (('0', 'startColor'), ('.5', 'centerColor'), ('1', 'endColor')))
        defs = (f'<linearGradient id="{identity}-gradient" gradientUnits="userSpaceOnUse" '
                f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
        background = f'url(#{identity}-gradient)'
        foreground = paths(resources / 'drawable/ic_launcher_foreground.xml')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" role="img" aria-label="{identity}">'
            f'<defs>{defs}<clipPath id="{identity}-mask">{mask_path}</clipPath></defs>'
            f'<g clip-path="url(#{identity}-mask)"><rect width="108" height="108" fill="{background}"/>'
            f'{foreground}</g></svg>')


sources = {'current': ROOT / 'legacy/android', 'leaf-p': PROJECT / 'app/src/main/res'}
cards = []
for name, source in sources.items():
    normal = svg(source, name)
    (ROOT / (name + '.svg')).write_text(normal)
    circle = svg(source, name + '-circle', mask='circle')
    themed = svg(source, name + '-themed', mask='circle', themed=True)
    code, title, description = (
        ('A', '旧版图标', '浏览器窗口 · P 字母 · 叶片') if name == 'current'
        else ('B', '青叶 P · 已启用', 'P 字母 · 叶片 · 深青绿')
    )
    cards.append(f'''<article class="card">
      <div class="card-heading"><span class="letter">{code}</span><h2>{title}</h2></div>
      <div class="hero-icon" data-name="{name}">{normal}</div>
      <p class="description">{description}</p>
      <div class="samples">
        <div><div class="sample circle">{circle}</div><span>圆形</span></div>
        <div><div class="sample themed">{themed}</div><span>主题图标</span></div>
        <div><div class="sample small">{svg(source, name + '-48')}</div><span>48 px</span></div>
        <div><div class="sample tiny">{svg(source, name + '-32')}</div><span>32 px</span></div>
      </div>
    </article>''')

document = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pure · 应用图标对比</title>
<style>
*{box-sizing:border-box} body{margin:0;background:#eff4f1;color:#143e37;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;transition:background .2s,color .2s}
main{max-width:1160px;margin:auto;padding:48px 48px 32px}.top{display:flex;justify-content:space-between;align-items:flex-end;gap:24px}
.eyebrow{font-size:13px;font-weight:600;letter-spacing:.22em;color:#62847a;margin:0 0 10px}h1{font-size:32px;font-weight:600;letter-spacing:-.02em;margin:0}
.controls{display:flex;gap:6px;border:1px solid #cbdcd3;border-radius:30px;padding:5px}button{font:inherit;font-size:13px;padding:8px 15px;border:0;border-radius:24px;background:transparent;color:inherit;cursor:pointer}button.active{background:#174f43;color:white}
.cards{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-top:32px}.card{background:#fff;border:1px solid #dee9e2;border-radius:28px;padding:24px 28px 26px}
.card-heading{display:flex;align-items:center;gap:12px}.letter{display:inline-grid;place-items:center;width:28px;height:28px;font-size:13px;border:1px solid #cfe0d7;border-radius:50%;color:#62847a}h2{font-size:18px;font-weight:600;margin:0}
.hero-icon{width:208px;height:208px;margin:32px auto 22px;filter:drop-shadow(0 12px 12px #103f3420)}svg{display:block;width:100%;height:100%}
.description{text-align:center;font-size:13px;letter-spacing:.02em;color:#668076;margin:0 0 26px}.samples{border-top:1px solid #e5eee9;padding-top:24px;display:grid;grid-template-columns:repeat(4,1fr);gap:10px;text-align:center}.samples>div{display:flex;flex-direction:column;align-items:center}.sample{width:56px;height:56px;display:flex;align-items:center;justify-content:center}.small svg{width:48px;height:48px}.tiny svg{width:32px;height:32px}.samples span{color:#799086;font-size:11px;margin-top:12px}
.note{font-size:13px;color:#688276;margin:24px 2px 0;line-height:1.7}body.dark{background:#101e1b;color:#e6f3ed}body.dark .card{background:#1b2c26;border-color:#30483c}body.dark .description,body.dark .note,body.dark .eyebrow{color:#a1baac}body.dark .samples{border-color:#324a3f}body.dark .controls{border-color:#456653}body.dark button.active{background:#c6efda;color:#123c2e}
@media(max-width:680px){main{padding:28px 20px}.top{align-items:flex-start;flex-direction:column}.cards{grid-template-columns:1fr}.hero-icon{margin-top:24px}}
</style></head><body><main>
<div class="top"><div><p class="eyebrow">PURE BROWSER / ICON STUDY</p><h1>应用图标对比</h1></div>
<div class="controls" aria-label="预览背景"><button type="button" class="active" aria-pressed="true" data-theme="light">浅色背景</button><button type="button" aria-pressed="false" data-theme="dark">深色背景</button></div></div>
<section class="cards">''' + ''.join(cards) + '''</section>
<p class="note">B 保留了 Pure 的青绿色和叶片元素，用更清晰的 P 形轮廓提升小尺寸辨识度。</p>
</main><script>document.querySelectorAll('[data-theme]').forEach(button=>button.addEventListener('click',()=>{document.body.classList.toggle('dark',button.dataset.theme==='dark');document.querySelectorAll('[data-theme]').forEach(item=>{const selected=item===button;item.classList.toggle('active',selected);item.setAttribute('aria-pressed',String(selected))})}));</script>
</body></html>'''
(ROOT / 'comparison.html').write_text(document)
print('Exported current.svg, leaf-p.svg and comparison.html')
