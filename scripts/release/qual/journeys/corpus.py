#!/usr/bin/env python3
"""Generate original, CC0 qualification text and an image-only scanned PDF.

Requires Pillow and DejaVu Sans (Ubuntu: python3-pil fonts-dejavu-core).
No downloaded documents, personal data or model files. Under 300 KiB in total.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from xml.sax.saxutils import escape

OPEN_FILE = 'JustSearch-journey-orchid-open.txt'
QUERY = 'lighthouse'
OCR_QUERY = 'cobalt marmalade'


def generate(root):
    from PIL import Image, ImageDraw, ImageFont
    root = Path(root)
    root.mkdir(parents=True, exist_ok=False)
    (root / OPEN_FILE).write_text('Orchid lighthouse release qualification.\nThe receiving viewer must display this filename.\n')
    (root / 'long-manual.md').write_text('# Harbour maintenance manual\n\n' + '\n\n'.join(
        f'## Section {n}\nThe amber harbour beacon is inspected each morning. '
        'Keep the maintenance journal beside the brass clock. ' * 6 for n in range(70)))
    (root / 'long-report.txt').write_text('\n'.join(
        f'Observation {n}: sapphire observatory instruments report a clear sky.' for n in range(350)))
    (root / 'inventory.csv').write_text('item,quantity,location\ncompass,3,harbour\nlantern,5,observatory\n')
    (root / 'bulletin.html').write_text('<!doctype html><html><title>Harbour bulletin</title><body><h1>Harbour bulletin</h1><p>The violet tugboat returns on Tuesday.</p></body></html>')
    (root / 'message.eml').write_text('From: keeper@example.invalid\nTo: crew@example.invalid\nSubject: Lighthouse inspection\nMIME-Version: 1.0\nContent-Type: text/plain; charset=utf-8\n\nThe turquoise telescope is ready for inspection.\n')
    with zipfile.ZipFile(root / 'schedule.docx', 'w', zipfile.ZIP_DEFLATED) as doc:
        doc.writestr('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        doc.writestr('_rels/.rels', '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        doc.writestr('word/document.xml', '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>The silver pelican inspection is scheduled for Friday.</w:t></w:r></w:p></w:body></w:document>')
    image = Image.new('RGB', (1240, 1754), 'white')
    draw = ImageDraw.Draw(image)
    font_path = ('C:/Windows/Fonts/arial.ttf' if __import__('os').name == 'nt'
                 else '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
    font = ImageFont.truetype(font_path, 42)
    for y, text in [(140, 'HARBOUR INSPECTION'), (260, 'Cobalt marmalade delivery record'),
                    (340, 'The warehouse received twelve crates.'), (420, 'Inspection complete. No damage found.')]:
        draw.text((90, y), text, fill='black', font=font)
    image.save(root / 'scanned-delivery.pdf', 'PDF', resolution=150, creationDate='D:20260101000000Z', modDate='D:20260101000000Z')
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.iterdir())}


def file_digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(block)
    return checksum.hexdigest()


def generate_soak(root, count=6000):
    """Original mixed long documents, deterministic bytes; generate once, reuse.

    About 650 MB, including many multi-page DOCX documents. The existing small
    corpus supplies the PDF/image and Office packaging templates. No downloaded
    material and no files from the owner's data are used.
    """
    root = Path(root)
    if root.exists():
        return verify_soak(root)
    generate(root)
    # The small generator predates the soak and ZIP's default timestamps vary.
    template = root / 'schedule.docx'
    with zipfile.ZipFile(template) as archive:
        entries = {i.filename: archive.read(i.filename) for i in archive.infolist()}
    def docx(path, text):
        body = ('<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
                + ''.join('<w:p><w:r><w:t>' + escape(p) + '</w:t></w:r></w:p>' for p in text.splitlines())
                + '</w:body></w:document>')
        with zipfile.ZipFile(path, 'w') as archive:
            for name, value in entries.items():
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, body if name == 'word/document.xml' else value)
    docx(template, 'The silver pelican inspection is scheduled for Friday.')
    for number in range(count):
        text = '\n'.join(
            f'Harbour report {number}, section {section}. The amber beacon inspection recorded '
            f'{(number * 37 + section * 13) % 10007} instrument readings. The brass clock and '
            'turquoise telescope guide coastal navigation. Maintenance staff compare weather, '
            'safety and delivery observations with the previous journal before sailing.'
            for section in range(400))
        suffix = ('.txt', '.md', '.html', '.csv', '.eml', '.docx')[number % 6]
        path = root / f'long-{number:05d}{suffix}'
        if suffix == '.docx':
            docx(path, text)
            continue
        if suffix == '.html':
            text = '<!doctype html><html><title>Harbour report</title><body>' + ''.join(
                '<p>' + escape(p) + '</p>' for p in text.splitlines()) + '</body></html>'
        elif suffix == '.csv':
            text = 'observation,description\n' + '\n'.join(f'{i},"{line}"' for i, line in enumerate(text.splitlines()))
        elif suffix == '.md':
            text = '# Harbour maintenance report\n\n' + text.replace('\n', '\n\n')
        elif suffix == '.eml':
            text = 'From: keeper@example.invalid\nTo: crew@example.invalid\nSubject: Long inspection report\nContent-Type: text/plain; charset=utf-8\n\n' + text
        path.write_text(text, encoding='utf-8')
    files = {p.name: {'sha256': file_digest(p), 'bytes': p.stat().st_size} for p in sorted(root.iterdir())}
    manifest = {'schemaVersion': 1, 'kind': 'justsearch-release-soak-v1', 'longDocuments': count, 'files': files}
    (root / 'soak-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def verify_soak(root):
    root = Path(root)
    manifest = json.loads((root / 'soak-manifest.json').read_text())
    long_files = [name for name in manifest.get('files', {}) if name.startswith('long-') and name[5:10].isdigit()]
    if (manifest.get('kind') != 'justsearch-release-soak-v1' or manifest.get('longDocuments', 0) < 6000
            or len(long_files) != manifest.get('longDocuments')):
        raise ValueError('J9 requires the full generated 6000-document soak corpus')
    for name, entry in manifest['files'].items():
        path = root / name
        if Path(name).name != name or path.is_symlink() or not path.is_file() or path.stat().st_size != entry['bytes'] or file_digest(path) != entry['sha256']:
            raise ValueError('Soak corpus file changed: ' + name)
    if {p.name for p in root.iterdir()} != set(manifest['files']) | {'soak-manifest.json'}:
        raise ValueError('Soak corpus contains unexpected files')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--soak', action='store_true', help='Generate/reuse the deterministic long mixed J9 corpus')
    args = parser.parse_args()
    print(json.dumps(generate_soak(args.directory) if args.soak else generate(args.directory), indent=2))
