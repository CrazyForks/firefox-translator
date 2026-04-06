#!/usr/bin/env python3
import asyncio
import json
import os
import time

import aiohttp

MODELS_MANIFEST_URL = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
MODELS_MANIFEST_CACHE = "data/models_manifest_v2.json"
MODEL_SIZE_CACHE = "data/model_sizes_v2.json"
TESSERACT_BASE_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/refs/heads/main"
DICTIONARY_BASE_URL = "https://translator.davidv.dev/dictionaries"
DICT_VERSION = 1

LANGUAGE_NAMES = {
    'ar': 'Arabic',
    'az': 'Azerbaijani',
    'be': 'Belarusian',
    'bg': 'Bulgarian',
    'bn': 'Bengali',
    'bs': 'Bosnian',
    'ca': 'Catalan',
    'cs': 'Czech',
    'da': 'Danish',
    'de': 'German',
    'el': 'Greek',
    'en': 'English',
    'es': 'Spanish',
    'et': 'Estonian',
    'fa': 'Persian',
    'fi': 'Finnish',
    'fr': 'French',
    'gu': 'Gujarati',
    'he': 'Hebrew',
    'hi': 'Hindi',
    'hr': 'Croatian',
    'hu': 'Hungarian',
    'id': 'Indonesian',
    'is': 'Icelandic',
    'it': 'Italian',
    'ja': 'Japanese',
    'kn': 'Kannada',
    'ko': 'Korean',
    'lt': 'Lithuanian',
    'lv': 'Latvian',
    'ml': 'Malayalam',
    'ms': 'Malay',
    'nb': 'Norwegian Bokmål',
    'nl': 'Dutch',
    'nn': 'Norwegian Nynorsk',
    'no': 'Norwegian',
    'pl': 'Polish',
    'pt': 'Portuguese',
    'ro': 'Romanian',
    'ru': 'Russian',
    'sk': 'Slovak',
    'sl': 'Slovenian',
    'sq': 'Albanian',
    'sr': 'Serbian',
    'sv': 'Swedish',
    'ta': 'Tamil',
    'te': 'Telugu',
    'th': 'Thai',
    'tr': 'Turkish',
    'uk': 'Ukrainian',
    'vi': 'Vietnamese',
    'zh': 'Chinese (简体)',
    'zh_hant': 'Chinese (繁體)',
}

TESSERACT_LANGUAGE_MAPPINGS = {
    'ar': 'ara',
    'az': 'aze',
    'be': 'bel',
    'bg': 'bul',
    'bn': 'ben',
    'bs': 'bos',
    'ca': 'cat',
    'cs': 'ces',
    'da': 'dan',
    'de': 'deu',
    'el': 'ell',
    'en': 'eng',
    'es': 'spa',
    'et': 'est',
    'fa': 'fas',
    'fi': 'fin',
    'fr': 'fra',
    'gu': 'guj',
    'he': 'heb',
    'hi': 'hin',
    'hr': 'hrv',
    'hu': 'hun',
    'id': 'ind',
    'is': 'isl',
    'it': 'ita',
    'ja': 'jpn',
    'kn': 'kan',
    'ko': 'kor',
    'lt': 'lit',
    'lv': 'lav',
    'ml': 'mal',
    'ms': 'msa',
    'nb': 'nor',
    'nl': 'nld',
    'nn': 'nor',
    'no': 'nor',
    'pl': 'pol',
    'pt': 'por',
    'ro': 'ron',
    'ru': 'rus',
    'sk': 'slk',
    'sl': 'slv',
    'sq': 'sqi',
    'sr': 'srp',
    'sv': 'swe',
    'ta': 'tam',
    'te': 'tel',
    'th': 'tha',
    'tr': 'tur',
    'uk': 'ukr',
    'vi': 'vie',
    'zh': 'chi_sim',
    'zh_hant': 'chi_tra',
}

SHORT_DISPLAY_NAMES = {
    'zh': '简体',
    'zh_hant': '繁體',
    'nb': 'Bokmål',
    'nn': 'Nynorsk',
}

LANGUAGE_SCRIPTS = {
    'ar': 'Arab',
    'az': 'Latn',
    'be': 'Cyrl',
    'bg': 'Cyrl',
    'bn': 'Beng',
    'bs': 'Latn',
    'ca': 'Latn',
    'cs': 'Latn',
    'da': 'Latn',
    'de': 'Latn',
    'el': 'Grek',
    'en': 'Latn',
    'es': 'Latn',
    'et': 'Latn',
    'fa': 'Arab',
    'fi': 'Latn',
    'fr': 'Latn',
    'gu': 'Gujr',
    'he': 'Hebr',
    'hi': 'Deva',
    'hr': 'Latn',
    'hu': 'Latn',
    'id': 'Latn',
    'is': 'Latn',
    'it': 'Latn',
    'ja': 'Jpan',  # compound: Hira + Hans (handled in Rust)
    'kn': 'Knda',
    'ko': 'Hang',
    'lt': 'Latn',
    'lv': 'Latn',
    'ml': 'Mlym',
    'ms': 'Latn',
    'nb': 'Latn',
    'nl': 'Latn',
    'nn': 'Latn',
    'no': 'Latn',
    'pl': 'Latn',
    'pt': 'Latn',
    'ro': 'Latn',
    'ru': 'Cyrl',
    'sk': 'Latn',
    'sl': 'Latn',
    'sq': 'Latn',
    'sr': 'Cyrl',
    'sv': 'Latn',
    'ta': 'Taml',
    'te': 'Telu',
    'th': 'Thai',
    'tr': 'Latn',
    'uk': 'Cyrl',
    'vi': 'Latn',
    'zh': 'Hans',
    'zh_hant': 'Hant',
}

MODEL_TYPE_PRIORITY = {
    'tiny': 1,
    'base': 2,
    'base-memory': 3,
}

MANIFEST_FILE_TYPES = {
    'model': 'model',
    'lexicalShortlist': 'lex',
    'vocab': 'vocab',
    'srcVocab': 'srcVocab',
    'trgVocab': 'tgtVocab',
}


def get_best_model_type(model_types: set[str]) -> str:
    if 'base-memory' in model_types:
        return 'base-memory'
    if 'base' in model_types:
        return 'base'
    if 'tiny' in model_types:
        return 'tiny'
    raise ValueError(f"No valid model type found in {model_types}")


def strip_compression_suffix(filename: str) -> str:
    if filename.endswith(".gz"):
        return filename[:-3]
    return filename


def load_json_cache(cache_file: str, default):
    if os.path.exists(cache_file):
        with open(cache_file) as f:
            return json.load(f)
    return default


def save_json_cache(cache_file: str, data) -> None:
    os.makedirs(os.path.dirname(cache_file), exist_ok=True)
    with open(cache_file, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)


async def fetch_manifest() -> dict:
    cached = load_json_cache(MODELS_MANIFEST_CACHE, None)
    if cached is not None:
        return cached

    async with aiohttp.ClientSession() as session:
        async with session.get(MODELS_MANIFEST_URL) as resp:
            resp.raise_for_status()
            manifest = await resp.json()

    save_json_cache(MODELS_MANIFEST_CACHE, manifest)
    return manifest


def select_best_entry(entries: list[dict]) -> dict:
    return max(entries, key=lambda entry: MODEL_TYPE_PRIORITY.get(entry.get("architecture", ""), 0))


def build_pair_files(manifest: dict) -> dict[tuple[str, str], dict]:
    pair_files = {}

    for pair_key, entries in manifest["models"].items():
        best_entry = select_best_entry(entries)
        src, tgt = best_entry["sourceLanguage"], best_entry["targetLanguage"]

        if src not in LANGUAGE_NAMES or tgt not in LANGUAGE_NAMES:
            print(f"Skipping unsupported pair {pair_key}: {src}->{tgt}")
            continue

        files = {}
        for manifest_file_type, file_type in MANIFEST_FILE_TYPES.items():
            file_info = best_entry["files"].get(manifest_file_type)
            if file_info is None:
                continue

            path = file_info["path"]
            files[file_type] = {
                "name": strip_compression_suffix(os.path.basename(path)),
                "size": 0,
                "path": path,
                "fileType": file_type,
                "modelType": best_entry["architecture"],
            }

        pair_files[(src, tgt)] = files

    return pair_files


def collect_model_paths(pair_files: dict[tuple[str, str], dict]) -> set[str]:
    return {entry["path"] for files in pair_files.values() for entry in files.values()}


async def fetch_file_size(session: aiohttp.ClientSession, base_url: str, path: str, semaphore: asyncio.Semaphore) -> tuple[str, int]:
    url = f"{base_url.rstrip('/')}/{path}"
    async with semaphore:
        try:
            async with session.head(url) as resp:
                if resp.status == 200:
                    return path, int(resp.headers.get("Content-Length", 0))
                print(f"Warning: HEAD failed for {path}: {resp.status}")
        except Exception as exc:
            print(f"Warning: HEAD error for {path}: {exc}")
    return path, 0


async def get_model_sizes(base_url: str, pair_files: dict[tuple[str, str], dict]) -> dict[str, int]:
    cache = load_json_cache(MODEL_SIZE_CACHE, {})
    paths = sorted(collect_model_paths(pair_files))
    missing_paths = [path for path in paths if path not in cache]

    if missing_paths:
        timeout = aiohttp.ClientTimeout(total=60)
        semaphore = asyncio.Semaphore(16)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            tasks = [fetch_file_size(session, base_url, path, semaphore) for path in missing_paths]
            for path, size in await asyncio.gather(*tasks):
                cache[path] = size
        save_json_cache(MODEL_SIZE_CACHE, cache)

    return {path: int(cache.get(path, 0)) for path in paths}


def apply_model_sizes(pair_files: dict[tuple[str, str], dict], model_sizes: dict[str, int]) -> None:
    for files in pair_files.values():
        for entry in files.values():
            entry["size"] = model_sizes.get(entry["path"], 0)


def build_language_data(pair_files: dict[tuple[str, str], dict]) -> tuple[dict, dict, set]:
    from_english = {}
    to_english = {}

    for (src, tgt), files in pair_files.items():
        if "model" not in files:
            continue
        if src != "en" and tgt != "en":
            continue

        model = files["model"]
        lex = files.get("lex")
        vocab = files.get("vocab")
        src_vocab = files.get("srcVocab", vocab)
        tgt_vocab = files.get("tgtVocab", vocab)

        if not all([model, lex, src_vocab, tgt_vocab]):
            print(f"Warning: incomplete files for {src}->{tgt}, skipping")
            continue

        entry = {
            "model": model,
            "srcVocab": src_vocab,
            "tgtVocab": tgt_vocab,
            "lex": lex,
        }

        if src == "en":
            from_english[tgt] = entry
        else:
            to_english[src] = entry

    all_languages = set(from_english.keys()) | set(to_english.keys()) | {"en"}

    return from_english, to_english, all_languages


async def get_tessdata_sizes(languages: set[str]) -> dict[str, int]:
    sizes = {}
    async with aiohttp.ClientSession() as session:
        for lang_code in sorted(languages):
            tess_name = TESSERACT_LANGUAGE_MAPPINGS.get(lang_code)
            if tess_name is None:
                continue

            url = f"{TESSERACT_BASE_URL}/{tess_name}.traineddata"
            try:
                async with session.head(url) as resp:
                    if resp.status == 200:
                        sizes[lang_code] = int(resp.headers.get("Content-Length", 0))
                    else:
                        print(f"Warning: tessdata HEAD failed for {lang_code}: {resp.status}")
                        sizes[lang_code] = 0
            except Exception as exc:
                print(f"Warning: tessdata HEAD error for {lang_code}: {exc}")
                sizes[lang_code] = 0
    return sizes



DICTIONARY_CODE_OVERRIDES = {
    'zh_hant': 'zh',
    'bs': 'hr',
    'sr': 'hr',
}

EXTRA_FILES = {
    'ja': ['mucab.bin'],
}


def format_direction(entry: dict) -> dict:
    return {
        "model": {"name": entry["model"]["name"], "sizeBytes": entry["model"]["size"], "path": entry["model"]["path"]},
        "srcVocab": {"name": entry["srcVocab"]["name"], "sizeBytes": entry["srcVocab"]["size"], "path": entry["srcVocab"]["path"]},
        "tgtVocab": {"name": entry["tgtVocab"]["name"], "sizeBytes": entry["tgtVocab"]["size"], "path": entry["tgtVocab"]["path"]},
        "lex": {"name": entry["lex"]["name"], "sizeBytes": entry["lex"]["size"], "path": entry["lex"]["path"]},
    }


def generate_index_json(
    from_english: dict,
    to_english: dict,
    all_languages: set[str],
    tessdata_sizes: dict[str, int],
    translation_models_base_url: str,
) -> dict:
    languages = []
    for lang_code in sorted(all_languages):
        if lang_code not in LANGUAGE_NAMES:
            continue

        lang = {
            "code": lang_code,
            "name": LANGUAGE_NAMES[lang_code],
            "shortName": SHORT_DISPLAY_NAMES.get(lang_code, LANGUAGE_NAMES[lang_code]),
            "tessName": TESSERACT_LANGUAGE_MAPPINGS[lang_code],
            "script": LANGUAGE_SCRIPTS[lang_code],
            "dictionaryCode": DICTIONARY_CODE_OVERRIDES.get(lang_code, lang_code),
            "tessdataSizeBytes": tessdata_sizes.get(lang_code, 0),
            "toEnglish": format_direction(to_english[lang_code]) if lang_code in to_english else None,
            "fromEnglish": format_direction(from_english[lang_code]) if lang_code in from_english else None,
            "extraFiles": EXTRA_FILES.get(lang_code, []),
        }
        languages.append(lang)

    return {
        "version": 1,
        "updatedAt": int(time.time()),
        "translationModelsBaseUrl": translation_models_base_url,
        "tesseractModelsBaseUrl": TESSERACT_BASE_URL,
        "dictionaryBaseUrl": DICTIONARY_BASE_URL,
        "dictionaryVersion": DICT_VERSION,
        "languages": languages,
    }


async def main():
    print("Fetching model manifest...")
    manifest = await fetch_manifest()
    base_url = manifest["baseUrl"].rstrip("/")
    print(f"Manifest generated at {manifest['generated']}")

    pair_files = build_pair_files(manifest)
    print(f"Built file map for {len(pair_files)} language pairs")

    print("Fetching model file sizes...")
    model_sizes = await get_model_sizes(base_url, pair_files)
    apply_model_sizes(pair_files, model_sizes)

    from_english, to_english, all_languages = build_language_data(pair_files)
    print(f"Languages: {len(all_languages)}, from_english: {len(from_english)}, to_english: {len(to_english)}")

    print("Fetching tessdata sizes...")
    tessdata_sizes = await get_tessdata_sizes(all_languages)

    index = generate_index_json(from_english, to_english, all_languages, tessdata_sizes, base_url)

    output_file = "index.json"
    with open(output_file, "w") as f:
        json.dump(index, f, indent=2, sort_keys=False)

    print(f"Generated {output_file} with {len(index['languages'])} languages")


if __name__ == "__main__":
    asyncio.run(main())
