#!/usr/bin/env python3
"""Generate an HTML page to live-edit the text of an exported UI SVG.

Usage: svg_text_demo.py <screen.svg> [out.html]

Writes a small page (from svg_editor_template.html) next to the SVG that fetches it and lets you
click any label to edit it in place. The SVG is loaded externally, not inlined, so serve the folder
over HTTP and open the page from there (fetch is blocked on file://):

    python3 -m http.server 9999      # in the ui-export dir
    # open http://localhost:9999/<screen>.demo.html
"""
import sys
from pathlib import Path

TEMPLATE = Path(__file__).resolve().parent / "svg_editor_template.html"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    svg_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else svg_path.with_suffix(".demo.html")

    page = TEMPLATE.read_text().replace("__NAME__", svg_path.name)
    out_path.write_text(page)
    print(f"wrote {out_path} (loads {svg_path.name})")


if __name__ == "__main__":
    main()
