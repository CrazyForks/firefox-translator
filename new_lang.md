## Voices

To add new voices to existing languages:

1. Add an `EXTRA_TTS_VOICES` entry in `catalog_tts.py`
2. Add files to the local bucket (`bucket/tts/1/<family>/<locale>/<voice>/<quality>/`, matching the `installPath`)
3. Regenerate catalogs:
   ```
   python3 generate_index.py --mode internal
   python3 generate_index.py --mode public --base-url https://offline-translator.davidv.dev
   ```
4. Generate the audio sample `translator-rs` (maybe this should move to the app???)
   ```
   cargo run --release --bin bucket_samples -- \
     --index <fresh index_v3.json> --bucket bucket --lang lang
   ./convert.sh
   cp output.opus bucket/samples_ogg/lang/
   ```
5. Re-run the `--mode public` indexing so the sample gets picked up
6. Regenerate the samples site in `translator-rs`
   ```
   python3 scripts/generate_samples_site.py index_v3.json \
     --samples-root bucket/samples_ogg \
     --output bucket/samples.html
   ```
7. Sync bucket to remote

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
