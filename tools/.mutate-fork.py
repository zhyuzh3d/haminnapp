#!/usr/bin/env python3
"""Prove the fork-resolution assertions actually bite.

Each mutation is applied to a throwaway copy of haminn-agent.py, and the helper test suite is then
run against that copy. A mutation that leaves the suite green means the corresponding assertion is
not load-bearing, so this script reports it as a failure.

    python3 tools/.mutate-fork.py
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
AGENT = ROOT / "app" / "src" / "main" / "assets" / "agent" / "haminn-agent.py"
TEST = ROOT / "tools" / "agent_helper_test.py"
AGENT_RELATIVE = pathlib.Path("app/src/main/assets/agent/haminn-agent.py")
TEST_RELATIVE = pathlib.Path("tools/agent_helper_test.py")

MUTATIONS = (
    ("账本被忽略：自己发布的包也当成别人的",
     "and not matches_active and not published",
     "and not matches_active"),
    ("内容相同不再优先：两边标记都旧也照旧发问",
     "dirty and base_outdated and not matches_active",
     "dirty and base_outdated"),
    ("published 恒为真：外来包也不再发问",
     'published = isinstance(active_hash, str) and active_hash in published_hashes(manifest.get("happId"))',
     "published = True"),
    ("账本比较忽略大小写：不同指纹也算自己发布的",
     'and active_hash in published_hashes(manifest.get("happId"))',
     'and active_hash.lower() in published_hashes(manifest.get("happId"))'),
    ("账本不封顶：无限增长",
     'entry["published"] = hashes[-MAX_PUBLISHED_HASHES:]',
     'entry["published"] = hashes'),
    ("账本覆盖整份文件：别的 happ 事实被抹掉",
     "    path = happ_dev_path()\n    document = load_happ_dev()",
     "    path = happ_dev_path()\n    document = {}"),
    ("账本读取不过滤：手改进来的非字符串条目被当成指纹",
     "return [item for item in hashes if isinstance(item, str)]",
     "return hashes"),
    ("需要裁决时不再返回询问，直接当默认继续",
     "        if sys.stdin.isatty():",
     "        if True:"),
    ("改用设备代码前不备份：直接清空本地工作区",
     "        if path.is_file() and not (SKIPPED_LOCAL_ENTRIES & set(relative.parts)):",
     "        if False:"),
    ("采用设备版本时不先重置设备工作区（下载到的是残留树）",
     '    device.tool("haminn_reset_dev_workspace", {"appId": app_id, "requestId": str(uuid.uuid4())})\n'
     "    archive_path = backup.with_name(backup.stem + \"-device.zip\")\n"
     "    downloaded = download_dev_tree(device, app_id, output=archive_path)",
     '    archive_path = backup.with_name(backup.stem + "-device.zip")\n'
     "    downloaded = download_dev_tree(device, app_id, output=archive_path)\n"
     '    device.tool("haminn_reset_dev_workspace", {"appId": app_id, "requestId": str(uuid.uuid4())})'),
    ("发布前守卫被删：可以覆盖别人装上去的包",
     'if fork["forked"]:\n        raise RuntimeError',
     'if False:\n        raise RuntimeError'),
)


def run(source: str) -> subprocess.CompletedProcess:
    work = pathlib.Path(tempfile.mkdtemp(prefix="haminn-mutate-"))
    try:
        agent = work / AGENT_RELATIVE
        agent.parent.mkdir(parents=True, exist_ok=True)
        agent.write_text(source, encoding="utf-8")
        test = work / TEST_RELATIVE
        test.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(TEST, test)
        return subprocess.run([sys.executable, str(test)], capture_output=True, text=True)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def failing_tests(output: str) -> list[str]:
    names = []
    for line in output.splitlines():
        for marker in ("FAIL: ", "ERROR: "):
            if marker in line:
                names.append(line.split(marker, 1)[1].split(" ")[0].strip("()"))
    return names


def main() -> None:
    original = AGENT.read_text(encoding="utf-8")
    baseline = run(original)
    if baseline.returncode != 0:
        # Without this, a suite that is already red would make every mutation look "caught".
        print(baseline.stdout[-4000:])
        print(baseline.stderr[-4000:])
        raise SystemExit("baseline suite is already failing; fix that before mutating")
    print("baseline  green")
    survived: list[str] = []
    for label, before, after in MUTATIONS:
        if original.count(before) != 1:
            raise SystemExit(f"mutation anchor is not unique ({original.count(before)}): {label}")
        result = run(original.replace(before, after, 1))
        caught = failing_tests(result.stderr or result.stdout)
        if result.returncode == 0:
            survived.append(label)
            print(f"SURVIVED  {label}")
        else:
            print(f"caught    {label}  ->  {', '.join(caught) or 'suite failed'}")
    print()
    if survived:
        raise SystemExit(f"{len(survived)} mutation(s) survived: {survived}")
    print(f"all {len(MUTATIONS)} mutations were caught")


if __name__ == "__main__":
    main()
