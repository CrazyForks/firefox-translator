"""PaddleOCR v5 script-based recognizer mapping.

The PPOCR rec models are organised by script, not by language: one model
covers every language that uses that script. Maps the script "slugs" used
in PaddleOCR filenames to the catalog's language codes.

Pack emission is deliberately not wired up yet (URLs not finalised); see
the TODO at the bottom of this file for the next step.
"""

PPOCR_DETECTOR_FILENAME = "PP-OCRv5_mobile_det_fp16.onnx"

# Recognizer model filename per script slug. The "cjk" slug uses the shared
# PP-OCRv5 model that handles Japanese + Chinese together.
PPOCR_RECOGNIZER_FILENAMES = {
    "arabic": "arabic_PP-OCRv5_mobile_rec_infer.onnx",
    "cyrillic": "cyrillic_PP-OCRv5_mobile_rec_infer.onnx",
    "devanagari": "devanagari_PP-OCRv5_mobile_rec_infer.onnx",
    "el": "el_PP-OCRv5_mobile_rec_infer.onnx",
    "en": "en_PP-OCRv5_mobile_rec_infer.onnx",
    "eslav": "eslav_PP-OCRv5_mobile_rec_infer.onnx",
    "korean": "korean_PP-OCRv5_mobile_rec_infer.onnx",
    "latin": "latin_PP-OCRv5_mobile_rec_infer.onnx",
    "ta": "ta_PP-OCRv5_mobile_rec_infer.onnx",
    "te": "te_PP-OCRv5_mobile_rec_infer.onnx",
    "th": "th_PP-OCRv5_mobile_rec_infer.onnx",
    "cjk": "PP-OCRv5_mobile_rec_fp16.onnx",
}

# Language codes (catalog keys) served by each PPOCR script.
#
# Specialization tiers: each language is assigned to its most specialized
# model. `en` is preferred over `latin` for English; `eslav` is preferred
# over `cyrillic` for Russian / Belarusian / Ukrainian. The fallback slugs
# (`latin`, `cyrillic`) cover only the languages that don't have a
# script-or-language-specific model.
#
# Languages whose script is not covered by PPOCR at all (Bengali,
# Gujarati, Hebrew, Kannada, Malayalam) are absent here and stay
# tesseract-only.
PPOCR_SCRIPT_TO_LANGUAGES = {
    "arabic": ["ar", "fa"],
    "cyrillic": ["bg", "sr"],          # non-East-Slavic Cyrillic only
    "devanagari": ["hi"],
    "el": ["el"],
    "en": ["en"],                      # specialized; `latin` excludes en
    "eslav": ["be", "ru", "uk"],       # specialized East Slavic Cyrillic
    "korean": ["ko"],
    "latin": [                         # all Latin-script langs except en
        "az", "bs", "ca", "cs", "da", "de", "es", "et", "fi", "fr",
        "hr", "hu", "id", "is", "it", "lt", "lv", "ms", "nb", "nl",
        "nn", "no", "pl", "pt", "ro", "sk", "sl", "sq", "sv", "tr",
        "vi",
    ],
    "ta": ["ta"],
    "te": ["te"],
    "th": ["th"],
    "cjk": ["ja", "zh", "zh_hant"],
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


# TODO: when the PPOCR pack URLs are ready, add a function similar to
# catalog_doc_detect.add_doc_detect_pack that:
#  1. Emits one pack per script in PPOCR_RECOGNIZER_FILENAMES, with
#     `feature: "ocr"`, `engine: "ppocr"`, `language: <script slug>`,
#     and `dependsOn: [<detector pack id>]`.
#  2. Emits one detector pack referencing PPOCR_DETECTOR_FILENAME.
#  3. For every language in PPOCR_SCRIPT_TO_LANGUAGES[script], adds the
#     pack id to that language's `assets.ocr` map under engine key "ppocr".
#  4. Optionally flips `preferredOcrEngine` to "ppocr" for those languages
#     (decide policy before generation).
