"""Install only official SDK tools, after explicit approval of the fetched SDK license."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

BASE = "https://dl.google.com/android/repository/"
PACKAGES = ["platforms;android-35", "build-tools;35.0.0", "platform-tools"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--accept-license-sha256")
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="metadata-", dir=args.work_dir))
    with urllib.request.urlopen(BASE + "repository2-1.xml", timeout=60) as response:
        metadata = response.read(8 * 1024 * 1024 + 1)
    require(len(metadata) <= 8 * 1024 * 1024, "仓库元数据异常过大")
    (run / "repository.xml").write_bytes(metadata)
    root = ET.fromstring(metadata)
    license_node = next(node for node in root.findall("license") if node.get("id") == "android-sdk-license")
    license_text = license_node.text.strip()
    license_bytes = license_text.encode("utf-8")
    digest = hashlib.sha256(license_bytes).hexdigest()
    license_file = run / "android-sdk-license.txt"
    license_file.write_bytes(license_bytes)
    print(f"License: {license_file}")
    print(f"License SHA256: {digest}")
    print("Source: https://dl.google.com/android/repository/repository2-1.xml")
    for name in PACKAGES + ["cmdline-tools;latest"]:
        matches = [p for p in root.findall("remotePackage") if p.get("path") == name and p.find("channelRef").get("ref") == "channel-0"]
        require(bool(matches), f"官方稳定仓库缺少 {name}")
        require(all(p.find("uses-license").get("ref") == "android-sdk-license" for p in matches), "工具涉及尚未审阅的其他许可，停止安装")
    if not args.accept_license_sha256:
        print("Inspection only. No license has been accepted; no SDK executable downloaded.")
        return
    require(digest == args.accept_license_sha256, "许可文本与审阅版本不一致，停止安装")
    require(args.sdk is not None and args.java_home is not None, "安装必须指定 --sdk 和 --java-home")
    require((args.java_home / "bin" / "java.exe").is_file(), "JDK 路径无效")
    sdk = args.sdk.resolve()
    destination = sdk / "cmdline-tools" / "latest"
    require(not destination.exists(), "命令行工具目录已存在，不覆盖已有安装")
    package = next(p for p in root.findall("remotePackage") if p.get("path") == "cmdline-tools;latest"
                   and p.find("channelRef").get("ref") == "channel-0")
    archive = next(a for a in package.findall("archives/archive") if a.findtext("host-os") == "windows")
    complete = archive.find("complete")
    relative = complete.findtext("url")
    require(relative and "/" not in relative and "\\" not in relative, "工具包 URL 非预期")
    expected_size = int(complete.findtext("size"))
    checksum = complete.find("checksum")
    algorithm = checksum.get("type", "sha1")
    require(algorithm in ("sha1", "sha256"), "不支持的官方校验算法")
    folder = Path(tempfile.mkdtemp(prefix="commandline-", dir=args.work_dir))
    archive_file = folder / relative
    hasher = hashlib.new(algorithm)
    size = 0
    with urllib.request.urlopen(BASE + relative, timeout=90) as response, archive_file.open("xb") as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            require(size <= expected_size, "下载超过官方声明大小")
            hasher.update(block)
            output.write(block)
    require(size == expected_size and hasher.hexdigest().lower() == checksum.text.strip().lower(), "下载校验失败，不解压或执行")
    extracted = folder / "extracted"
    extracted.mkdir()
    with zipfile.ZipFile(archive_file) as zipped:
        entries = zipped.infolist()
        require(len(entries) <= 20000 and sum(e.file_size for e in entries) <= 1024 * 1024 * 1024, "工具包解压大小异常")
        for entry in entries:
            path = PurePosixPath(entry.filename.replace("\\", "/"))
            require(not path.is_absolute() and ".." not in path.parts and not any(":" in p for p in path.parts), "工具包有不安全路径")
            require(not stat.S_ISLNK(entry.external_attr >> 16), "工具包包含符号链接，停止解压")
        zipped.extractall(extracted)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(extracted / "cmdline-tools", destination)
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(args.java_home)
    environment["ANDROID_HOME"] = str(sdk)
    sdkmanager = destination / "bin" / "sdkmanager.bat"
    print("Verified official archive. Installing only API 35, Build Tools 35.0.0 and platform-tools.", flush=True)
    subprocess.run(["cmd.exe", "/d", "/c", str(sdkmanager), f"--sdk_root={sdk}", *PACKAGES],
                   input="y\n", text=True, encoding="utf-8", errors="replace", check=True, env=environment)
    print(f"SDK ready: {sdk}")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (ValueError, OSError, StopIteration, subprocess.CalledProcessError) as error:
        print(f"SDK setup stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
