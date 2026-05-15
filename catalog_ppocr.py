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
PPOCR_DETECTOR_PACK_ID = "ocr-ppocr-detector"

PPOCR_DETECTOR_FILENAME = "PP-OCRv5_mobile_det_fp16.mnn"
PPOCR_SCRIPT_CLASSIFIER_FILENAME = "PULC.mnn"

# Recognizer model filename per script slug.
PPOCR_RECOGNIZER_FILENAMES = {
    "arabic": "arabic_PP-OCRv5_mobile_rec_infer.mnn",
    "cyrillic": "cyrillic_PP-OCRv5_mobile_rec_infer.mnn",
    "devanagari": "devanagari_PP-OCRv5_mobile_rec_infer.mnn",
    "el": "el_PP-OCRv5_mobile_rec_infer.mnn",
    "eslav": "eslav_PP-OCRv5_mobile_rec_infer.mnn",
    "korean": "korean_PP-OCRv5_mobile_rec_infer.mnn",
    "latin": "latin_PP-OCRv5_mobile_rec_infer.mnn",
    "ta": "ta_PP-OCRv5_mobile_rec_infer.mnn",
    "te": "te_PP-OCRv5_mobile_rec_infer.mnn",
    "th": "th_PP-OCRv5_mobile_rec_infer.mnn",
    "cj": "PP-OCRv5_mobile_rec_fp16.mnn",
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


def _make_file(name: str) -> dict:
    install_path = f"{PPOCR_INSTALL_BASE}/{name}"
    mirror_path = f"{PPOCR_BUCKET_BASE}/{name}"
    return {
        "name": name,
        "sizeBytes": 0,
        "installPath": install_path,
        "url": f"https://offline-translator.davidv.dev/{mirror_path}",
        "mirrorPath": mirror_path,
    }


def add_ppocr_packs(catalog: dict) -> None:
    catalog["packs"][PPOCR_DETECTOR_PACK_ID] = {
        "feature": "ocr",
        "engine": PPOCR_ENGINE,
        "role": "detector",
        "files": [
            _make_file(PPOCR_DETECTOR_FILENAME),
            _make_file(PPOCR_SCRIPT_CLASSIFIER_FILENAME),
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
        catalog["packs"][pack_id] = {
            "feature": "ocr",
            "engine": PPOCR_ENGINE,
            "role": "recognizer",
            "script": script,
            "files": [
                _make_file(rec_filename),
                _make_file(keys_filename),
            ],
            "dependsOn": depends_on,
        }
        for lang in langs:
            if lang not in languages:
                continue
            ocr_assets = languages[lang]["assets"].setdefault("ocr", {})
            ocr_assets[PPOCR_ENGINE] = pack_id
