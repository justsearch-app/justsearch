`predecessor-bookmark-ocr.png` is the unchanged native popup crop from the
independent W4a verifier's retained evidence:
`w4a-verify2/wayland/predecessor-setup/run/J4-restarted-bookmark-ocr.png`.
It contains the installed JustSearch Library bookmark and hover tooltip.

Tesseract `--psm 6` reads “Ubrary”; `--psm 11` reads “Library”. The regression
runs the real OCR tool against this exact image, without a display or app.
