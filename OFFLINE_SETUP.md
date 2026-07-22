# Offline language files setup

This guide explains how to pre-download the model files on your computer and transfer them to your Android device for offline use with the Translator app.

You only need this guide if you want to download files on another device and then copy them to where you intend to use the translator. In the normal case the app downloads everything itself.

On the app, you will need to enable in: Settings &rarr; Advanced &rarr; "Use external storage"

The downloaded files go into `Documents/dev.davidv.translator/`, uncompressed.

You **must** have both translation directions (lang &harr; English) or the language won't show up as available.

## The easy way: `download_offline.py`

`download_offline.py` reads the served catalog (`index_v6.json`) and pulls the prebuilt files straight from the CDN into the exact layout the app expects:

```
./download_offline.py de fr sr zh --all --output dev.davidv.translator
```

- `--all` gets translation, dictionary, OCR and TTS. Drop it and pass only the features you want: `--ocr`, `--tts`, `--dictionary` (translation is always included).

Then copy the resulting `dev.davidv.translator/` directory to `Documents/` on the device.

> Do not use `download_bucket.py` for this. That one mirrors the *upstream* sources to rebuild the CDN and is a maintainer tool, not an installer.

## Directory Structure

If you prefer to lay the files out by hand, this is what the app looks for. All models are MNN (`.mnn`); the app never loads `.onnx` at runtime (an `.onnx` file would trigger the on-device conversion screen on startup).

```
dev.davidv.translator/
├── bin/
│   ├── model.ende.intgemm.alphas.bin     # translation
│   ├── vocab.ende.spm
│   ├── lex.50.50.ende.s2t.bin
│   ├── ... (both directions per language pair)
│   ├── piper/                            # TTS voices
│   │   └── de/de_DE/thorsten/medium/de_DE-thorsten-medium.mnn (+ .onnx.json)
│   └── espeak-ng-data/                   # TTS phoneme data
│       ├── .install-info.json            # {"version": 1}
│       ├── de_dict                       # one per TTS language
│       └── ... (extracted espeak-ng-data.zip)
├── dictionaries/
│   └── de.dict
└── ppocr/                                # OCR (PP-OCR, v6 preferred over v5)
    ├── PP-OCRv5/
    └── PP-OCRv6/
```

## How to get the files by hand

Every file's download URL and its install path are listed in the served catalog at `https://offline-translator.davidv.dev/index_v6.json` (also bundled at `app/src/main/assets/index_v6.json`). For each pack, `files[].url` is where to download from and `files[].installPath` is where it goes under `dev.davidv.translator/`.

Notes:

- Translation files are served gzipped (`.gz`); decompress them so the on-disk name matches `installPath`.
- A language's OCR recognizer depends on the shared detector pack; follow each pack's `dependsOn` so you don't miss it.

`download_offline.py` does all of this for you, so hand-assembly is only worth it for a one-off file.

## Verification

After copying files, restart the Translator app. Available languages, and their OCR/TTS icons, appear automatically.
