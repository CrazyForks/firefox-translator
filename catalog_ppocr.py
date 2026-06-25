"""PaddleOCR v5 script-based recognizer mapping.

The PPOCR rec models are organised by script, not by language: one model
covers every language that uses that script. A language's recognizer is
derived from its ISO 15924 script, so adding a language gives it OCR for free.

The shared "cj" model handles Chinese (simplified + traditional) and Japanese
together. Korean uses its own recognizer.
"""

import catalog_base


PPOCR_ENGINE = "ppocr"
PPOCR_BUCKET_BASE = "ocr/1/PP-OCRv5"
PPOCR_INSTALL_BASE = "ppocr/PP-OCRv5"
PPOCR_V6_BUCKET_BASE = "ocr/1/PP-OCRv6"
PPOCR_V6_INSTALL_BASE = "ppocr/PP-OCRv6"
PPOCR_DETECTOR_PACK_ID = "ocr-ppocr-detector"

# Detector alternatives, lowest to highest priority. Older entries stay
# listed so existing installs keep counting as installed; clients download
# only the highest-priority file and offer it as an upgrade over older ones.
# The v6 tiny detector has its DBNet head folded to emit the probability
# map at 1/4 resolution (same boxes, ~35% faster than the v5 detector).
PPOCR_DETECTOR_FILENAMES = [
#    "PP-OCRv5_mobile_det_int8.mnn",
#    "det_quarter_int8.mnn",
    "PP-OCRv6_tiny_det_half_int8.mnn",
    "PP-OCRv6_tiny_det_int8.mnn",
]
PPOCR_V6_FILENAMES = {
    "PP-OCRv6_tiny_det_half_int8.mnn",
    "PP-OCRv6_tiny_det_int8.mnn",
    "PP-OCRv6_tiny_rec_int8.mnn",
    "PP-OCRv6_tiny_keys.txt",
    "PP-OCRv6_small_rec_int8.mnn",
    "PP-OCRv6_small_keys.txt",
    "hebrew_rec_int8.mnn",
    "hebrew_keys.txt",
    "indic_r2e10_int8.mnn",
    "indic_keys.txt",
    "ink_bold_int8.mnn",
}

# v6 recognizer upgrades per script slug: (model, keys). The unified v6
# model covers Latin and Chinese+Japanese; tiny has no kana so the cj slot
# gets the small tier. Other scripts stay on their v5 recognizers.
PPOCR_V6_RECOGNIZER_FILENAMES = {
    "latin": ("PP-OCRv6_tiny_rec_int8.mnn", "PP-OCRv6_tiny_keys.txt"),
    "cj": ("PP-OCRv6_small_rec_int8.mnn", "PP-OCRv6_small_keys.txt"),
}

# Scripts whose only recognizer is a v6 fine-tune (no v5 base): (model, keys).
# These cover languages PaddleOCR never shipped a recognizer for, which were
# tesseract-only before. `indic` is one merged model over Bengali, Gujarati,
# Kannada and Malayalam.
PPOCR_V6_NATIVE_RECOGNIZER_FILENAMES = {
    "hebrew": ("hebrew_rec_int8.mnn", "hebrew_keys.txt"),
    "indic": ("indic_r2e10_int8.mnn", "indic_keys.txt"),
}
# Per-strip ink model, shipped with the detector since it is script-agnostic and runs on
# every detected strip. Two channels: ch0 soft-alpha matte (color matting), ch1 per-pixel
# bold (typography weight). Optional at runtime — the engine loads it only if present.
PPOCR_INK_FILENAME = "ink_bold_int8.mnn"
PPOCR_SCRIPT_CLASSIFIER_FILENAME = "PULC_int8.mnn"
PPOCR_TEXTLINE_ORIENTATION_FILENAME = "textline_ori_x1_0_fp32.mnn"
PPOCR_TEXTLINE_ORIENTATION_FILENAME = "textline_ori_x0_25_wq8.mnn"

# Recognizer model filename per script slug.
PPOCR_RECOGNIZER_FILENAMES = {
    "arabic": "arabic_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "cyrillic": "cyrillic_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "devanagari": "devanagari_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "el": "el_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "eslav": "eslav_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "korean": "korean_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "latin": "latin_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "ta": "ta_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "te": "te_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "th": "th_PP-OCRv5_mobile_rec_infer_int8.mnn",
    "cj": "PP-OCRv5_mobile_rec_int8.mnn",
}

# ISO 15924 script code → PPOCR recognizer slug. PPOCR rec models are
# organised by script, so a language's recognizer follows directly from its
# script and a newly-added language gets OCR automatically. The one
# language-level distinction is East Slavic Cyrillic (see below).
#
# `indic` is one merged v6 recognizer over the four non-Devanagari Indic
# scripts; Devanagari keeps its dedicated v5 recognizer. `hebrew` and `indic`
# are v6 fine-tunes covering scripts PaddleOCR never shipped a recognizer for
# (tesseract-only before). The shared `cj` model handles Chinese (simplified +
# traditional) and Japanese; Korean has its own recognizer.
ISO_SCRIPT_TO_PPOCR = {
    "Arab": "arabic",
    "Beng": "indic",
    "Cyrl": "cyrillic",
    "Deva": "devanagari",
    "Grek": "el",
    "Gujr": "indic",
    "Hang": "korean",
    "Hans": "cj",
    "Hant": "cj",
    "Hebr": "hebrew",
    "Jpan": "cj",
    "Knda": "indic",
    "Latn": "latin",
    "Mlym": "indic",
    "Taml": "ta",
    "Telu": "te",
    "Thai": "th",
}

# East Slavic languages use the specialized `eslav` recognizer in place of the
# generic `cyrillic` one.
PPOCR_EAST_SLAVIC_LANGS = {"be", "ru", "uk"}

# Every recognizer slug that gets a pack, in stable output order.
PPOCR_RECOGNIZER_SLUGS = [
    "arabic", "cyrillic", "devanagari", "el", "eslav", "hebrew",
    "indic", "korean", "latin", "ta", "te", "th", "cj",
]

assert set(ISO_SCRIPT_TO_PPOCR.values()) | {"eslav"} == set(PPOCR_RECOGNIZER_SLUGS)


def ppocr_slug_for_language(code: str, script: str) -> str | None:
    """PPOCR recognizer slug covering `code`, or None if no recognizer fits.

    Derived from the ISO 15924 script, with East Slavic Cyrillic upgraded to
    the specialized `eslav` recognizer.
    """
    slug = ISO_SCRIPT_TO_PPOCR.get(script)
    if slug == "cyrillic" and code in PPOCR_EAST_SLAVIC_LANGS:
        return "eslav"
    return slug


def _keys_filename(script: str) -> str:
    return f"{script}_PP-OCRv5_keys.txt"


def _make_file(name: str, role: str, priority: int = 0, optional: bool = False) -> dict:
    if name in PPOCR_V6_FILENAMES:
        install_path = f"{PPOCR_V6_INSTALL_BASE}/{name}"
        mirror_path = f"{PPOCR_V6_BUCKET_BASE}/{name}"
    else:
        install_path = f"{PPOCR_INSTALL_BASE}/{name}"
        mirror_path = f"{PPOCR_BUCKET_BASE}/{name}"
    file = {
        "name": name,
        "sizeBytes": 0,
        "installPath": install_path,
        "url": f"https://offline-translator.davidv.dev/{mirror_path}",
        "mirrorPath": mirror_path,
        "role": role,
        "priority": priority,
    }
    if optional:
        file["optional"] = True
    return file


def add_ppocr_packs(catalog: dict) -> None:
    detector_files = [
        _make_file(name, "detector", priority)
        for priority, name in enumerate(PPOCR_DETECTOR_FILENAMES)
    ]
    catalog["packs"][PPOCR_DETECTOR_PACK_ID] = {
        "feature": "ocr",
        "engine": PPOCR_ENGINE,
        "role": "detector",
        "files": detector_files
        + [
            _make_file(PPOCR_SCRIPT_CLASSIFIER_FILENAME, "scriptClassifier"),
            _make_file(PPOCR_TEXTLINE_ORIENTATION_FILENAME, "textlineOrientation"),
            _make_file(PPOCR_INK_FILENAME, "ink", optional=True),
        ],
        "dependsOn": [],
    }

    languages = catalog["languages"]
    latin_pack_id = catalog_base.make_ocr_pack_id(PPOCR_ENGINE, "latin")
    for script in PPOCR_RECOGNIZER_SLUGS:
        pack_id = catalog_base.make_ocr_pack_id(PPOCR_ENGINE, script)
        depends_on = [PPOCR_DETECTOR_PACK_ID]
        if script != "latin":
            depends_on.append(latin_pack_id)
        # Recognizer and keys alternates must stay paired: the engine picks
        # the keys file whose priority matches the chosen recognizer's, so a
        # half-downloaded upgrade falls back to the older complete pair.
        if script in PPOCR_V6_NATIVE_RECOGNIZER_FILENAMES:
            rec_filename, keys_filename = PPOCR_V6_NATIVE_RECOGNIZER_FILENAMES[script]
            files = [
                _make_file(rec_filename, "recognizer"),
                _make_file(keys_filename, "keys"),
            ]
        else:
            rec_filename = PPOCR_RECOGNIZER_FILENAMES[script]
            keys_filename = _keys_filename(script)
            files = [
                _make_file(rec_filename, "recognizer"),
                _make_file(keys_filename, "keys"),
            ]
            if script in PPOCR_V6_RECOGNIZER_FILENAMES:
                v6_rec, v6_keys = PPOCR_V6_RECOGNIZER_FILENAMES[script]
                files.append(_make_file(v6_rec, "recognizer", priority=1))
                files.append(_make_file(v6_keys, "keys", priority=1))
        catalog["packs"][pack_id] = {
            "feature": "ocr",
            "engine": PPOCR_ENGINE,
            "role": "recognizer",
            "script": script,
            "files": files,
            "dependsOn": depends_on,
        }

    for lang, language in languages.items():
        slug = ppocr_slug_for_language(lang, language["meta"]["script"])
        if slug is None:
            continue
        ocr_assets = language["assets"].setdefault("ocr", {})
        ocr_assets[PPOCR_ENGINE] = catalog_base.make_ocr_pack_id(PPOCR_ENGINE, slug)
