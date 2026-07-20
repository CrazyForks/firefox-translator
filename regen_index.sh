python3 generate_index.py
python3 download_bucket.py --output ../bucket/
python3 generate_index.py --mode public --base-url https://offline-translator.davidv.dev
cp app/src/main/assets/index_v5.json ../bucket/index_v5.json
echo 'sync bucket now'
