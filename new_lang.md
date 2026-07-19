- `LANGUAGE_NAMES` + `LANGUAGE_SCRIPTS` in `catalog_upstream.py`
- a `TTS_SAMPLES` rainbow-passage entry in `catalog_tts_samples.py`
- the espeak `<code>_dict`

## Voices

To add new voices to existing languages:

1. Add an `EXTRA_TTS_VOICES` entry in `catalog_tts.py`
2. Add files to the local bucket (`bucket/tts/1/<family>/<locale>/<voice>/<quality>/`, matching the `installPath`)
3. Convert the voice to int8 MNN and put only the `.mnn` in the bucket
   ```
   cd translator-rs
   cargo run --release --features model-convert --bin onnx_to_mnn -- model.onnx model.mnn 8
   ```
4. Regenerate catalogs:
   ```
   python3 generate_index.py --mode internal
   python3 generate_index.py --mode public --base-url https://offline-translator.davidv.dev
   ```
5. Generate the audio sample `translator-rs` (maybe this should move to the app???)
   ```
   cargo run --release --bin bucket_samples -- \
     --index <fresh index_v3.json> --bucket bucket --lang lang
   ./convert.sh
   cp output.opus bucket/samples_ogg/lang/
   ```
6. Re-run the `--mode public` indexing so the sample gets picked up
7. Regenerate the samples site in `translator-rs`
   ```
   python3 scripts/generate_samples_site.py index_v3.json \
     --samples-root bucket/samples_ogg \
     --output bucket/samples.html
   ```
8. Sync bucket to remote

### MMS voices

`willwade/mms-tts-multilingual-models-onnx` has most voices already exported as ONNX, if missing, then export from the `facebook/mms-tts-*` checkpoint:

```
cd translator-rs
uv run --with torch --with transformers --with onnx --with onnxruntime --with numpy \
  scripts/export_mms_onnx.py facebook/mms-tts-<code> --out-dir <dir> --sample-text "<text>"
```

## Dictionaries

Dictionaries come from `tarkka`. A `dict-<code>` pack is only emitted for a language that already exists in the catalog, so add the language first if it's new.

1. Copy the new `.dict` files into the local bucket (`bucket/dictionaries/1/`, matching the `installPath`)
2. Copy tarkka's `index.json` as `data_sources/dictionary_index.json`
3. Regenerate catalogs:
   ```
   python3 generate_index.py --mode internal
   python3 generate_index.py --mode public --base-url https://offline-translator.davidv.dev
   ```
4. Sync bucket to remote
