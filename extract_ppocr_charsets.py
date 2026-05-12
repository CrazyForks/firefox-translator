#!/usr/bin/env python3
"""Extract the PaddleOCRv5 recognizer character dictionaries out of the
upstream inference.yml files and drop them as <slug>_keys.txt alongside
the .onnx models in the bucket.

Run once after refreshing the PPOCR ONNX exports. Reads from
/tmp/ppocrv5_cache (the export script's output) and writes to
~/AndroidStudioProjects/bucket/ocr/1/PP-OCRv5.
"""

from pathlib import Path

import yaml

from catalog_ppocr import PPOCR_RECOGNIZER_FILENAMES

CACHE_DIR = Path("/tmp/ppocrv5_cache")
BUCKET_DIR = Path.home() / "AndroidStudioProjects/bucket/ocr/1/PP-OCRv5"


def cache_inference_yml(script: str, onnx_filename: str) -> Path:
    if script == "cjk":
        outer = "PP-OCRv5_mobile_rec"
    else:
        stem = onnx_filename.removesuffix(".onnx")
        outer = stem.removesuffix("_infer")
    return CACHE_DIR / outer / f"{outer}_infer" / "inference.yml"


def extract_charset(yml_path: Path) -> list[str]:
    with yml_path.open() as f:
        data = yaml.safe_load(f)
    chars = data["PostProcess"]["character_dict"]
    if not isinstance(chars, list):
        raise ValueError(f"{yml_path}: character_dict is not a list")
    return [str(ch) for ch in chars]


def keys_filename(script: str) -> str:
    return f"{script}_PP-OCRv5_keys.txt"


def write_charset(target: Path, chars: list[str]) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8") as f:
        for ch in chars:
            f.write(ch)
            f.write("\n")


def main() -> None:
    if not CACHE_DIR.exists():
        raise SystemExit(f"cache dir not found: {CACHE_DIR}")
    if not BUCKET_DIR.exists():
        raise SystemExit(f"bucket dir not found: {BUCKET_DIR}")

    for script, onnx_filename in sorted(PPOCR_RECOGNIZER_FILENAMES.items()):
        yml_path = cache_inference_yml(script, onnx_filename)
        if not yml_path.exists():
            print(f"skip {script}: no {yml_path}")
            continue
        chars = extract_charset(yml_path)
        out_path = BUCKET_DIR / keys_filename(script)
        write_charset(out_path, chars)
        print(f"{script}: wrote {len(chars)} chars to {out_path}")


if __name__ == "__main__":
    main()
