DOC_DETECT_KIND = "doc_detect"
DOC_DETECT_PACK_ID = "support-doc_detect-v1"
DOC_DETECT_FILENAME = "docaligner_lcnet050.onnx"
DOC_DETECT_INSTALL_PATH = f"ocr/docrec/{DOC_DETECT_FILENAME}"
DOC_DETECT_URL = (
    "https://drive.usercontent.google.com/download"
    "?id=1J7cRuupeEIudYrH_CCSV9WvFfu9JM_qU&export=download&confirm=t"
)
DOC_DETECT_SIZE_BYTES = 4_911_217


def add_doc_detect_pack(catalog: dict) -> None:
    catalog["packs"][DOC_DETECT_PACK_ID] = {
        "feature": "support",
        "kind": DOC_DETECT_KIND,
        "files": [
            {
                "name": DOC_DETECT_FILENAME,
                "sizeBytes": DOC_DETECT_SIZE_BYTES,
                "installPath": DOC_DETECT_INSTALL_PATH,
                "url": DOC_DETECT_URL,
                "sourcePath": DOC_DETECT_FILENAME,
                "role": "model",
                "priority": 0,
            },
        ],
        "dependsOn": [],
    }
