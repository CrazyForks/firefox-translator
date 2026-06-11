"""PaddleOCR v5 script-based recognizer mapping.

The PPOCR rec models are organised by script, not by language: one model
covers every language that uses that script. Maps the script "slugs" used
in PaddleOCR filenames to the catalog's language codes.

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
    "PP-OCRv5_mobile_det_int8.mnn",
    "det_quarter_int8.mnn",
    "PP-OCRv6_tiny_det_quarter_int8.mnn",
]
PPOCR_V6_FILENAMES = {
    "PP-OCRv6_tiny_det_quarter_int8.mnn",
    "PP-OCRv6_tiny_rec_int8.mnn",
    "PP-OCRv6_tiny_keys.txt",
    "PP-OCRv6_small_rec_int8.mnn",
    "PP-OCRv6_small_keys.txt",
}

# v6 recognizer upgrades per script slug: (model, keys). The unified v6
# model covers Latin and Chinese+Japanese; tiny has no kana so the cj slot
# gets the small tier. Other scripts stay on their v5 recognizers.
PPOCR_V6_RECOGNIZER_FILENAMES = {
    "latin": ("PP-OCRv6_tiny_rec_int8.mnn", "PP-OCRv6_tiny_keys.txt"),
    "cj": ("PP-OCRv6_small_rec_int8.mnn", "PP-OCRv6_small_keys.txt"),
}
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

# Language codes (catalog keys) served by each PPOCR script.
#
# Specialization tiers: each language is assigned to its most suitable model.
# English uses the shared Latin model instead of the dedicated English model;
# `eslav` is preferred over `cyrillic` for Russian / Belarusian / Ukrainian.
# The fallback slugs (`latin`, `cyrillic`) cover the languages that don't have
# a script-or-language-specific model.
#
# Languages whose script is not covered by PPOCR at all (Bengali,
# Gujarati, Hebrew, Kannada, Malayalam) are absent here and stay
# tesseract-only.
PPOCR_SCRIPT_TO_LANGUAGES = {
    "arabic": ["ar", "fa"],
    "cyrillic": ["bg", "sr"],          # non-East-Slavic Cyrillic only
    "devanagari": ["hi"],
    "el": ["el"],
    "eslav": ["be", "ru", "uk"],       # specialized East Slavic Cyrillic
    "korean": ["ko"],
    "latin": [                         # all Latin-script langs, including en
        "az", "bs", "ca", "cs", "da", "de", "es", "et", "fi", "fr",
        "hr", "hu", "id", "is", "it", "lt", "lv", "ms", "nb", "nl",
        "nn", "no", "pl", "pt", "ro", "sk", "sl", "sq", "sv", "tr",
        "vi", "en",
    ],
    "ta": ["ta"],
    "te": ["te"],
    "th": ["th"],
    "cj": ["ja", "zh", "zh_hant"],
}


def language_to_ppocr_script() -> dict:
    """Reverse mapping: language code → ppocr script slug.

    A language only appears here if PPOCR has a recognizer that covers
    its script. Returned as a fresh dict on each call.
    """
    out = {}
    for script, langs in PPOCR_SCRIPT_TO_LANGUAGES.items():
        for lang in langs:
            if lang in out:
                raise ValueError(
                    f"Language {lang} mapped to two ppocr scripts: "
                    f"{out[lang]} and {script}"
                )
            out[lang] = script
    return out


def _keys_filename(script: str) -> str:
    return f"{script}_PP-OCRv5_keys.txt"


def _make_file(name: str, role: str, priority: int = 0) -> dict:
    if name in PPOCR_V6_FILENAMES:
        install_path = f"{PPOCR_V6_INSTALL_BASE}/{name}"
        mirror_path = f"{PPOCR_V6_BUCKET_BASE}/{name}"
    else:
        install_path = f"{PPOCR_INSTALL_BASE}/{name}"
        mirror_path = f"{PPOCR_BUCKET_BASE}/{name}"
    return {
        "name": name,
        "sizeBytes": 0,
        "installPath": install_path,
        "url": f"https://offline-translator.davidv.dev/{mirror_path}",
        "mirrorPath": mirror_path,
        "role": role,
        "priority": priority,
    }


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
        ],
        "dependsOn": [],
    }

    languages = catalog["languages"]
    latin_pack_id = catalog_base.make_ocr_pack_id(PPOCR_ENGINE, "latin")
    for script, langs in PPOCR_SCRIPT_TO_LANGUAGES.items():
        rec_filename = PPOCR_RECOGNIZER_FILENAMES[script]
        keys_filename = _keys_filename(script)
        pack_id = catalog_base.make_ocr_pack_id(PPOCR_ENGINE, script)
        depends_on = [PPOCR_DETECTOR_PACK_ID]
        if script != "latin":
            depends_on.append(latin_pack_id)
        # Recognizer and keys alternates must stay paired: the engine picks
        # the keys file whose priority matches the chosen recognizer's, so a
        # half-downloaded upgrade falls back to the older complete pair.
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
        for lang in langs:
            if lang not in languages:
                continue
            ocr_assets = languages[lang]["assets"].setdefault("ocr", {})
            ocr_assets[PPOCR_ENGINE] = pack_id
