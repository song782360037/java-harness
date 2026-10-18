"""逐个补齐 Remotion 运行所需的缺失依赖。

npm 在当前沙箱环境下会因批量删除保护而中断，导致依赖树不完整。
本脚本反复启动 Remotion CLI，解析 "Cannot find module 'X'"，
从 npmmirror 拉取对应 tarball 解压到 node_modules，直到 CLI 可正常启动。
"""
import json
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

PROJ = r"E:\projects\java-harness\remotion-videos"
NM = os.path.join(PROJ, "node_modules")
REG = "https://registry.npmmirror.com"
NODE = r"C:\Users\song\.workbuddy\binaries\node\versions\22.22.2-6\node.exe"

ENTRY = os.path.join(NM, "@remotion", "cli", "remotion-cli.js")


def fetch(url: str, timeout: int = 40) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "deps-fixer"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def latest_version(name: str) -> str:
    meta = json.loads(fetch(f"{REG}/{name}"))
    return meta["dist-tags"]["latest"]


def install_pkg(name: str) -> str:
    ver = latest_version(name)
    base = name.split("/")[-1]
    url = f"{REG}/{name}/-/{base}-{ver}.tgz"
    data = fetch(url)
    tmp = os.path.join(PROJ, ".tmp", f"{base}.tgz")
    os.makedirs(os.path.dirname(tmp), exist_ok=True)
    with open(tmp, "wb") as f:
        f.write(data)
    dest = os.path.join(NM, *name.split("/"))
    with tarfile.open(tmp, "r:gz") as tf:
        members = []
        for m in tf.getmembers():
            if not m.name.startswith("package/"):
                continue
            m.name = m.name[len("package/"):]
            if not m.name:
                continue
            members.append(m)
        os.makedirs(dest, exist_ok=True)
        tf.extractall(dest, members=members)
    return f"{name}@{ver}"


def probe() -> str:
    p = subprocess.run(
        [NODE, ENTRY, "--version"],
        capture_output=True,
        text=True,
        cwd=PROJ,
        timeout=180,
        encoding="utf-8",
        errors="replace",
    )
    return (p.stdout or "") + (p.stderr or "")


def main() -> int:
    for i in range(1, 61):
        out = probe()
        m = re.search(r"Cannot find module '([^']+)'", out)
        if not m:
            print(f"[OK] 第 {i} 轮：CLI 已可启动")
            print(out.strip()[-800:])
            return 0
        name = m.group(1)
        if name.startswith(".") or name.startswith("\\") or name.startswith("/"):
            print(f"[ERR] 出现相对路径缺失，需人工处理: {name}")
            print(out.strip()[-900:])
            return 1
        if name.startswith("@"):
            parts = name.split("/")
            pkg = "/".join(parts[:2])
        else:
            pkg = name.split("/")[0]
        try:
            info = install_pkg(pkg)
            print(f"[{i}] 已补齐 {info}")
        except Exception as e:  # noqa: BLE001
            print(f"[{i}] 补齐 {pkg} 失败: {e}")
            print(out.strip()[-600:])
            return 1
    print("[ERR] 超过最大轮次")
    return 1


if __name__ == "__main__":
    sys.exit(main())
