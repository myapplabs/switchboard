#!/usr/bin/env python3
"""Build the Website's Terms of Use and Privacy Policy pages from legal/*.md (the one source; the App
shows the same files). Run after editing either file; CI checks the pages are up to date.

    python3 legal/build_pages.py            # write passkey-domain/public/{terms,privacy}.html
    python3 legal/build_pages.py --check    # fail if they're out of date
"""
import html
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PUBLIC = ROOT / "passkey-domain" / "public"
# file name → (heading, browser tab title)
PAGES = {
    "terms": ("Terms of Use", "Terms of Use · Switchboard"),
    "privacy": ("Privacy Policy", "Privacy Policy · Switchboard"),
    "delete-account": ("Delete your Switchboard account", "Delete your account · Switchboard"),
}


def inline(text: str) -> str:
    t = html.escape(text, quote=False)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"`(.+?)`", r"<code>\1</code>", t)
    t = re.sub(r"\b([\w.+-]+@applabs\.llc)\b", r'<a href="mailto:\1">\1</a>', t)
    t = re.sub(r"\b(github\.com/myapplabs/switchboard)\b", r'<a href="https://\1">\1</a>', t)
    return t


def body(markdown: str) -> str:
    out, para, items, ordered = [], [], [], [False]

    def flush():
        if para:
            out.append(f"<p>{inline(' '.join(para))}</p>")
            para.clear()
        if items:
            tag = "ol" if ordered[0] else "ul"
            out.append(f"<{tag}>" + "".join(f"<li>{inline(i)}</li>" for i in items) + f"</{tag}>")
            items.clear()

    for line in markdown.splitlines():
        if not line.strip():
            flush()
        elif line.startswith("# "):
            continue  # the page title, shown in the header
        elif m := re.match(r"(#{2,6}) (.*)", line):
            flush()
            out.append(f"<h2>{inline(m.group(2))}</h2>")
        elif m := re.match(r"(- |\d+\. )(.*)", line):
            if para:
                flush()
            ordered[0] = m.group(1) != "- "
            items.append(m.group(2))
        else:
            if items:
                flush()
            para.append(line.strip())
    flush()
    return "\n".join(out)


def page(name: str, title: str, tab: str) -> str:
    md = (ROOT / "legal" / f"{name}.md").read_text()
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{tab}</title>
<meta name="description" content="{title}: Switchboard, by AppLabs, LLC.">
<meta name="theme-color" content="#0B0D10">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="/style.css">
</head>
<body>
<!-- Generated from legal/{name}.md by legal/build_pages.py. Don't edit. -->
<div class="wrap">
<header class="top">
  <a class="brand" href="/">Switchboard</a>
  <span class="status"><a href="/terms.html">Terms</a> · <a href="/privacy.html">Privacy</a></span>
</header>
<main class="legal">
<h1>{title}</h1>
{body(md)}
</main>
</div>
<footer><div class="wrap">
  <span>Switchboard: Agent Hub · by <a href="https://applabs.llc">AppLabs, LLC</a> · <a href="https://github.com/myapplabs/switchboard">Source on GitHub</a></span>
  <span class="mono"><a href="/terms.html">Terms of Use</a> · <a href="/privacy.html">Privacy Policy</a> · <a href="/delete-account.html">Delete your account</a></span>
</div></footer>
</body>
</html>
"""


def main() -> int:
    check = "--check" in sys.argv
    stale = []
    for name, (title, tab) in PAGES.items():
        target = PUBLIC / f"{name}.html"
        text = page(name, title, tab)
        if check:
            if not target.exists() or target.read_text() != text:
                stale.append(target.name)
        else:
            target.write_text(text)
            print(f"wrote {target.relative_to(ROOT)}")
    if stale:
        print("Out of date (run python3 legal/build_pages.py):", ", ".join(stale))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
