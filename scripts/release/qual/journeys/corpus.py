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
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 42)
    for y, text in [(140, 'HARBOUR INSPECTION'), (260, 'Cobalt marmalade delivery record'),
                    (340, 'The warehouse received twelve crates.'), (420, 'Inspection complete. No damage found.')]:
        draw.text((90, y), text, fill='black', font=font)
    image.save(root / 'scanned-delivery.pdf', 'PDF', resolution=150, creationDate='D:20260101000000Z', modDate='D:20260101000000Z')
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.iterdir())}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    print(json.dumps(generate(args.directory), indent=2))
