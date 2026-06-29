#!/usr/bin/env python3
"""Build a translation-context viewer for exported UI SVGs.

For each `<text data-source="...">` in an exported screen, recover the `R.string` key it came from
by matching the rendered English against `res/values/strings.xml` (format strings matched by regex),
then emit a viewer that can flip every label between languages and deep-link each one to Weblate.

Usage:
    tools/i18n_viewer.py <screen.svg> [more.svg ...] [--res app/src/main/res]
                         [--weblate offline-translator/offline-translator]

Writes next to each SVG: `<screen>.i18n.html` + `<screen>.keys.json` (data-key -> R.string key),
and one shared `strings.json` (key -> {lang: value}) in the SVG's directory. Serve over HTTP:

    python3 -m http.server 9999      # in the ui-export dir
    # open http://localhost:9999/<screen>.i18n.html
"""
import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

WEBLATE_BASE = "https://hosted.weblate.org/browse"
# Android resource qualifier -> Weblate language code (only the non-identity ones).
QUALIFIER_TO_WEBLATE = {"zh-rCN": "zh_Hans"}

FORMAT_SPEC = re.compile(r"%(\d+\$)?[-#+ 0,(]?\d*(?:\.\d+)?[a-zA-Z%]")


def android_unescape(raw: str) -> str:
    """Collapse an Android string resource body to the text the user actually sees."""
    s = raw.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    s = s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
    return re.sub(r"\s+", " ", s).strip()


def load_strings(values_xml: Path) -> dict[str, str]:
    """key -> visible English value, dropping translatable=false entries."""
    root = ET.parse(values_xml).getroot()
    out = {}
    for s in root.findall("string"):
        if s.get("translatable") == "false":
            continue
        out[s.get("name")] = android_unescape("".join(s.itertext()))
    return out


def load_all_locales(res_dir: Path) -> dict[str, dict[str, str]]:
    """key -> {lang: value} across values/ (en) and every values-<qualifier>/."""
    by_key: dict[str, dict[str, str]] = {}
    for xml in sorted(res_dir.glob("values*/strings.xml")):
        qualifier = xml.parent.name
        lang = "en" if qualifier == "values" else QUALIFIER_TO_WEBLATE.get(
            qualifier[len("values-"):], qualifier[len("values-"):]
        )
        for key, val in load_strings(xml).items():
            by_key.setdefault(key, {})[lang] = val
    return by_key


class Resolver:
    """Recovers an R.string key from a rendered English label."""

    def __init__(self, english: dict[str, str]):
        self.exact: dict[str, list[str]] = {}
        for key, val in english.items():
            self.exact.setdefault(val, []).append(key)
        # Keys whose value carries a real format specifier: match the rendered text against a regex
        # built by escaping the literal segments and turning each specifier into a wildcard.
        self.patterns: list[tuple[re.Pattern, str]] = []
        for key, val in english.items():
            specs = list(FORMAT_SPEC.finditer(val))
            if not any(m.group(0) != "%%" for m in specs):
                continue
            parts, last = [], 0
            for m in specs:
                parts.append(re.escape(val[last:m.start()]))
                parts.append("%" if m.group(0) == "%%" else ".+?")
                last = m.end()
            parts.append(re.escape(val[last:]))
            self.patterns.append((re.compile("^" + "".join(parts) + "$"), key))

    def resolve(self, text: str) -> tuple[str | None, str]:
        """Return (key, status). status in exact|format|ambiguous|miss."""
        text = re.sub(r"\s+", " ", text).strip()
        hit = self.exact.get(text)
        if hit:
            return (hit[0], "exact" if len(hit) == 1 else "ambiguous")
        for rx, key in self.patterns:
            if rx.match(text):
                return (key, "format")
        return (None, "miss")


def _local(tag: str) -> str:
    """Local name of a (possibly namespaced) ElementTree tag, e.g. '{...}text' -> 'text'."""
    return tag.rsplit("}", 1)[-1]


def _style(el: ET.Element) -> tuple:
    """Lines of one wrapped paragraph share transform, left edge, and font styling."""
    return tuple(el.get(k, "") for k in ("transform", "x", "font-size", "font-weight", "font-style", "fill"))


def process_svg(svg_path: Path, resolver: Resolver) -> tuple[dict[str, str | None], dict[str, int]]:
    """Map each <text data-key> to its R.string key; return (keys, status counts).

    A wrapped paragraph is drawn as several same-styled <text> lines whose own fragments match no
    string. After matching each line individually, runs of consecutive same-styled misses are
    greedily re-joined and matched as a whole, sharing the recovered key across their lines.
    """
    root = ET.parse(svg_path).getroot()
    recs = [el for el in root.iter() if _local(el.tag) == "text" and "data-key" in el.attrib]
    src = [el.get("data-source", "") for el in recs]  # ElementTree already unescaped the entities

    keys: dict[str, str | None] = {}
    status: dict[str, str] = {}
    for el, s in zip(recs, src):
        key, st = resolver.resolve(s)
        keys[el.get("data-key")] = key
        status[el.get("data-key")] = st

    i, n = 0, len(recs)
    while i < n:
        if status[recs[i].get("data-key")] != "miss":
            i += 1
            continue
        j = i + 1
        while (
            j < n
            and status[recs[j].get("data-key")] == "miss"
            and _style(recs[j]) == _style(recs[i])
            and float(recs[j].get("y", 0)) > float(recs[j - 1].get("y", 0))
        ):
            j += 1
        k = i
        while k < j:
            for end in range(j, k + 1, -1):  # longest paragraph first; single lines stay miss
                joined = " ".join(src[k:end])
                key, st = resolver.resolve(joined)
                if key and st in ("exact", "format"):
                    for t in range(k, end):
                        keys[recs[t].get("data-key")] = key
                        status[recs[t].get("data-key")] = st
                    k = end
                    break
            else:
                k += 1
        i = j

    stats = {"exact": 0, "format": 0, "ambiguous": 0, "miss": 0}
    for el, s in zip(recs, src):
        dk = el.get("data-key")
        stats[status[dk]] += 1
        if status[dk] in ("ambiguous", "miss"):
            print(f"  [{status[dk]}] {dk}: {s!r} -> {keys[dk]}", file=sys.stderr)
    return keys, stats


def export_sections(svg_path: Path, inkscape: str) -> list[Path]:
    """Render each `<g id="section-*">` to its own cropped PNG, over the screen's background color
    (the first full-screen rect fill) so the translucent cards composite as they do on device."""
    root = ET.parse(svg_path).getroot()
    ids = [
        el.get("id") for el in root.iter()
        if _local(el.tag) == "g" and (el.get("id") or "").startswith("section-")
    ]
    if not ids:
        return []
    bg = next((el.get("fill") for el in root.iter() if _local(el.tag) == "rect" and el.get("fill")), "#000000")
    out = []
    for sid in ids:
        png = svg_path.parent / f"{svg_path.stem}.{sid[len('section-'):]}.png"
        subprocess.run(
            [inkscape, str(svg_path), f"--export-id={sid}", "--export-id-only",
             f"--export-background={bg}", "--export-background-opacity=1",
             "--export-type=png", f"--export-filename={png}"],
            check=True, capture_output=True,
        )
        out.append(png)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("svgs", nargs="+", type=Path)
    ap.add_argument("--res", type=Path, default=Path("app/src/main/res"))
    ap.add_argument("--weblate", default="offline-translator/offline-translator")
    ap.add_argument("--sections", action="store_true",
                    help="also render each <g id=section-*> to a cropped PNG (needs inkscape)")
    ap.add_argument("--inkscape", default="inkscape")
    args = ap.parse_args()

    english = load_strings(args.res / "values" / "strings.xml")
    locales = load_all_locales(args.res)
    resolver = Resolver(english)
    langs = sorted({lang for v in locales.values() for lang in v})
    template = (Path(__file__).resolve().parent / "i18n_viewer_template.html").read_text()

    cards: dict[Path, list[Path]] = {}
    keymap: dict[Path, dict[str, set[str]]] = {}  # out_dir -> {R.string key -> {screen names}}
    for svg in args.svgs:
        keys, stats = process_svg(svg, resolver)
        out_dir = svg.parent
        (out_dir / "strings.json").write_text(json.dumps(locales, ensure_ascii=False))
        (svg.with_suffix(".keys.json")).write_text(json.dumps(keys, ensure_ascii=False))
        screens = keymap.setdefault(out_dir, {})
        for key in keys.values():
            if key:
                screens.setdefault(key, set()).add(svg.stem)
        page = (
            template.replace("__NAME__", svg.name)
            .replace("__KEYS__", svg.with_suffix(".keys.json").name)
            .replace("__WEBLATE__", args.weblate)
            .replace("__LANGS__", json.dumps(langs))
        )
        svg.with_suffix(".i18n.html").write_text(page)
        cards.setdefault(out_dir, []).append(svg)
        msg = (
            f"{svg.name}: {stats['exact']} exact, {stats['format']} format, "
            f"{stats['ambiguous']} ambiguous, {stats['miss']} miss -> {svg.with_suffix('.i18n.html').name}"
        )
        if args.sections:
            pngs = export_sections(svg, args.inkscape)
            msg += f" (+{len(pngs)} section PNGs)" if pngs else " (no sections)"
        print(msg)

    here = Path(__file__).resolve().parent
    index_template = (here / "i18n_index_template.html").read_text()
    changes_template = (here / "i18n_changes_template.html").read_text()
    store_js = (here / "i18n_store.js").read_text()
    shared_css = (here / "i18n.css").read_text()
    for out_dir, svgs in cards.items():
        html = []
        for svg in sorted(svgs, key=lambda p: p.name):
            base = svg.with_suffix(".i18n.html").name
            html.append(
                f'  <a class="card" href="{base}" data-base="{base}">'
                f'<div class="thumb"><img src="{svg.name}" alt="{svg.stem}"></div>'
                f'<div class="name">{svg.stem}</div></a>'
            )
        page = index_template.replace("__CARDS__", "\n".join(html)).replace("__LANGS__", json.dumps(langs))
        (out_dir / "index.html").write_text(page)
        (out_dir / "i18n_store.js").write_text(store_js)
        (out_dir / "i18n.css").write_text(shared_css)
        (out_dir / "changes.html").write_text(changes_template.replace("__WEBLATE__", args.weblate))
        screens = {k: sorted(v) for k, v in sorted(keymap.get(out_dir, {}).items())}
        (out_dir / "keymap.json").write_text(json.dumps(screens, ensure_ascii=False))
        print(f"{out_dir}/index.html: {len(svgs)} screens")
    return 0


if __name__ == "__main__":
    sys.exit(main())
