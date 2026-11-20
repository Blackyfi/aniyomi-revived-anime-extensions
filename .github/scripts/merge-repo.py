import html
import sys
import json
from pathlib import Path
import shutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
import inspector  # noqa: E402

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath(sys.argv[2])

to_delete: list[str] = json.loads(sys.argv[1])

with LOCAL_REPO.joinpath("index.min.json").open() as local_index_file:
    local_index = json.load(local_index_file)

# Only drop what this run replaced (a new build) or what no longer exists in the tree, so a
# run that built less than it planned can never empty the published repo.
NAMESPACE = "eu.kanade.tachiyomi.animeextension."
SRC_DIR = LOCAL_REPO.parent.joinpath("src")
rebuilt = {item["pkg"].removeprefix(NAMESPACE) for item in local_index}
to_delete = [
    module for module in to_delete
    if module in rebuilt or not SRC_DIR.joinpath(*module.split(".", 1)).is_dir()
]

for module in to_delete:
    apk_name = f"aniyomi-{module}-v*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(file.name)
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(file.name)
        file.unlink(missing_ok=True)

shutil.copytree(src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok = True)
shutil.copytree(src=LOCAL_REPO.joinpath("icon"), dst=REMOTE_REPO.joinpath("icon"), dirs_exist_ok = True)

with REMOTE_REPO.joinpath("index.json").open() as remote_index_file:
    remote_index = json.load(remote_index_file)

index = [
    item for item in remote_index
    if item["pkg"].removeprefix(NAMESPACE) not in to_delete
]
index.extend(local_index)
index.sort(key=lambda x: x["pkg"])

# Entries of extensions not rebuilt in this run are carried over as published. Re-read their
# sources from the current tree so fixes to the inspector reach every entry, not only new builds.
for item in index:
    apk_lang, _, module = item["pkg"].removeprefix(NAMESPACE).partition(".")
    module_dir = SRC_DIR.joinpath(apk_lang, module)
    if not module_dir.is_dir():
        continue
    try:
        sources = inspector.inspect_module(str(module_dir))
    except Exception as e:  # best-effort metadata, never fail the publish
        print(f"  inspect failed for {item['pkg']}: {e}")
        continue
    item["sources"] = sources
    item["lang"] = inspector.index_lang(apk_lang, sources)

with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

for item in index:
    for source in item["sources"]:
        source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')
