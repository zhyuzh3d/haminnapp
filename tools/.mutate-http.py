#!/usr/bin/env python3
"""证明「连接复用」那几条断言真的在管这事。

每条变异都改在**真源文件**上(NativeHttpClient.kt) —— 这一层的判据是服务端侧
实际接受了几条 TCP 连接,只有在真机上跑才看得见,没法拿一份副本去替。所以这个
脚本必须能保证把文件还原回去:finally 之外还注册了 SIGTERM/SIGINT,被中途杀掉
(工具调用超时就是这么干的)也照样还原。

变异只要让那条断言**变红**就算被抓住,并把它红在哪一句打出来 —— 「跑完先看它被
哪条断言抓」比"红了"本身有用。

    python3 tools/.mutate-http.py
"""

from __future__ import annotations

import os
import pathlib
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app" / "src" / "main" / "java" / "life" / "airen" / "haminn" / "capability" / "NativeHttpClient.kt"
TEST_CLASS = "life.airen.haminn.NativeHttpConnectionReuseInstrumentedTest"
XML_GLOB = "app/build/outputs/androidTest-results/connected"

# (名字, 原串, 改成什么, 期望被哪条用例抓住)
MUTATIONS = (
    (
        "每个请求又新建一个客户端(连接池丢了)",
        "    private fun client(target: NetworkTarget, timeoutMs: Long): OkHttpClient =\n        baseClient.newBuilder()",
        "    private fun client(target: NetworkTarget, timeoutMs: Long): OkHttpClient =\n        OkHttpClient.Builder()",
        "sequentialRequestsReuseOneConnection",
    ),
    (
        "钉定 DNS 退回匿名 lambda(相等性=身份,Address 次次不同,池子次次落空)",
        ".dns(PinnedDns(target.uri.host!!, target.addresses))",
        ".dns(Dns { hostname -> if (!hostname.equals(target.uri.host, true)) throw UnknownHostException(\"Unexpected host\"); target.addresses })",
        "sequentialRequestsReuseOneConnection",
    ),
    (
        "闲置上限放回 5 分钟(池子会递出服务端早就关掉的连接)",
        "private const val CONNECTION_KEEP_ALIVE_SECONDS = 3L",
        "private const val CONNECTION_KEEP_ALIVE_SECONDS = 300L",
        "pooledConnectionIsNotHandedOutAfterAIdleGap",
    ),
)


def gradle_env() -> dict[str, str]:
    env = dict(os.environ)
    env["JAVA_HOME"] = "/opt/homebrew/opt/openjdk@17"
    return env


def run_tests() -> tuple[int, dict[str, str]]:
    """跑这一个测试类,返回(退出码, {用例名: 失败信息})。"""
    completed = subprocess.run(
        [
            "./gradlew", "--offline", "connectedDebugAndroidTest",
            f"-Pandroid.testInstrumentationRunnerArguments.class={TEST_CLASS}",
        ],
        cwd=ROOT, env=gradle_env(), capture_output=True, text=True, timeout=900,
    )
    failures: dict[str, str] = {}
    for path in sorted((ROOT / XML_GLOB).rglob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for child in case:
                if child.tag in {"failure", "error"}:
                    failures[case.get("name") or "?"] = (child.text or "").strip()
    return completed.returncode, failures


def first_line(text: str) -> str:
    for line in text.splitlines():
        if line.strip():
            return line.strip()
    return "(没有失败信息)"


def main() -> int:
    original = SOURCE.read_text(encoding="utf-8")
    restored = False

    def restore(*_):
        nonlocal restored
        if not restored:
            SOURCE.write_text(original, encoding="utf-8")
            restored = True
            print("\n[还原] NativeHttpClient.kt 已按原样写回", file=sys.stderr)
        raise SystemExit(130)

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, restore)

    print("== 变异前先确认门禁是绿的 ==")
    code, failures = run_tests()
    if code != 0:
        print(f"门禁本来就是红的,先修它再谈变异。失败:{failures}")
        return 1
    print("绿。\n")

    escaped = []
    try:
        for name, old, new, expected in MUTATIONS:
            if original.count(old) != 1:
                print(f"[跳过] 「{name}」锚点命中 {original.count(old)} 次,定位不准")
                escaped.append(name)
                continue
            SOURCE.write_text(original.replace(old, new), encoding="utf-8")
            print(f"== 变异:{name} ==")
            code, failures = run_tests()
            if code == 0:
                print(f"  ✗ 没被抓住 —— 这条断言不管这件事:{expected}\n")
                escaped.append(name)
            else:
                where = failures.get(expected)
                if where:
                    print(f"  ✓ 被 {expected} 抓住:{first_line(where)}\n")
                else:
                    print(f"  ✓ 变红了,但红在别的用例上:{sorted(failures)} —— 期望的是 {expected}\n")
                    if not failures:
                        escaped.append(f"{name}(只有构建失败,没有断言失败)")
    finally:
        if not restored:
            SOURCE.write_text(original, encoding="utf-8")
            restored = True
            print("[还原] NativeHttpClient.kt 已按原样写回")

    if escaped:
        print("有变异逃逸:" + ", ".join(escaped))
        return 1
    print(f"全部 {len(MUTATIONS)} 条变异都被抓住了。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
