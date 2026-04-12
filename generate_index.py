#!/usr/bin/env python3

import argparse
import json
import time

from pathlib import Path

import catalog_base
import catalog_tts


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate the final app catalog in one step from the current source indices.",
    )
    parser.add_argument(
        "--language-index",
        default="app/src/main/assets/language_index.json",
        help="Path to the current v1 language index JSON.",
    )
    parser.add_argument(
        "--dictionary-index",
        default="app/src/main/assets/dictionary_index.json",
        help="Path to the current dictionary index JSON.",
    )
    parser.add_argument(
        "--voices",
        default="piper/voices.json",
        help="Path to Piper voices.json.",
    )
    parser.add_argument(
        "--output",
        default="app/src/main/assets/index.json",
        help="Where to write the merged catalog.",
    )
    parser.add_argument(
        "--generated-at",
        type=int,
        default=None,
        help="Explicit generatedAt timestamp. Defaults to the current output's generatedAt when present.",
    )
    parser.add_argument(
        "--oracle",
        default=None,
        help="Existing catalog used to preserve generatedAt and key ordering. Defaults to --output when it exists.",
    )
    parser.add_argument(
        "--piper-base-url",
        default=catalog_tts.PIPER_BASE_URL,
        help="Base URL used for Piper voice files.",
    )
    parser.add_argument(
        "--tts-base-url",
        default=catalog_tts.TTS_BASE_URL,
        help="Base URL used for shared eSpeak data.",
    )
    parser.add_argument(
        "--tts-version",
        type=int,
        default=catalog_tts.TTS_VERSION,
        help="Shared TTS asset version.",
    )
    parser.add_argument(
        "--espeak-data-dir",
        default=None,
        help="Path to espeak-ng-data directory used to build the shared zip.",
    )
    parser.add_argument(
        "--espeak-core-zip",
        default="tts/espeak-ng-data.zip",
        help="Local output path for the generated shared eSpeak zip.",
    )
    return parser.parse_args()


def load_existing_catalog(path: Path | None) -> dict | None:
    if path is None or not path.exists():
        return None
    try:
        current = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return current if isinstance(current, dict) else None


def resolve_generated_at(existing_catalog: dict | None, generated_at: int | None) -> int:
    if generated_at is not None:
        return generated_at
    if isinstance(existing_catalog, dict) and isinstance(existing_catalog.get("generatedAt"), int):
        return existing_catalog["generatedAt"]
    return int(time.time())


def preserve_order(current: dict, oracle: dict | None, key: str) -> None:
    if not isinstance(oracle, dict):
        return
    current_section = current.get(key)
    oracle_section = oracle.get(key)
    if not isinstance(current_section, dict) or not isinstance(oracle_section, dict):
        return

    reordered = {}
    for section_key in oracle_section:
        if section_key in current_section:
            reordered[section_key] = current_section[section_key]
    for section_key, value in current_section.items():
        if section_key not in reordered:
            reordered[section_key] = value
    current[key] = reordered


def resolve_oracle_catalog(args: argparse.Namespace) -> dict | None:
    if args.oracle:
        return load_existing_catalog(Path(args.oracle))
    output_path = Path(args.output)
    return load_existing_catalog(output_path)


def build_catalog(args: argparse.Namespace) -> dict:
    oracle_catalog = resolve_oracle_catalog(args)

    language_index = catalog_base.load_json(Path(args.language_index))
    dictionary_index = catalog_base.load_json(Path(args.dictionary_index))
    voices = catalog_tts.load_json(args.voices)

    espeak_core_zip_size = 0
    espeak_data_dir = catalog_tts.resolve_espeak_data_dir(args.espeak_data_dir)
    if espeak_data_dir is not None:
        espeak_core_zip_size = catalog_tts.build_espeak_core_zip(
            espeak_data_dir,
            Path(args.espeak_core_zip),
        )

    base_catalog = catalog_base.convert_v1_to_v2(language_index, dictionary_index)
    merged_catalog = catalog_tts.merge_tts(
        base_catalog=base_catalog,
        voices=voices,
        piper_base_url=args.piper_base_url,
        tts_base_url=args.tts_base_url,
        tts_version=args.tts_version,
        espeak_core_zip_size=espeak_core_zip_size,
    )

    preserve_order(merged_catalog, oracle_catalog, "languages")
    preserve_order(merged_catalog, oracle_catalog, "packs")
    merged_catalog["generatedAt"] = resolve_generated_at(oracle_catalog, args.generated_at)
    return merged_catalog


def main() -> None:
    args = parse_args()
    output_path = Path(args.output)
    catalog = build_catalog(args)
    output_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output_path}")
    print(f"languages={len(catalog['languages'])} packs={len(catalog['packs'])}")


if __name__ == "__main__":
    main()
