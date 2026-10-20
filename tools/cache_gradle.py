"""Cache the pinned official Gradle ZIP using Python's system proxy support."""
import argparse
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request

URL = "https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip"
SHA256 = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"


def base36(value):
    result = ""
    while value:
        value, remainder = divmod(value, 36)
        result = "0123456789abcdefghijklmnopqrstuvwxyz"[remainder] + result
    return result or "0"


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="仅缓存带官方 SHA-256 校验的 Gradle 8.11.1，不修改 Wrapper URL 或禁用校验。")
    parser.add_argument("--downloads-dir", type=Path, required=True)
    parser.add_argument("--gradle-home", type=Path, default=Path.home() / ".gradle")
    args = parser.parse_args()
    url_hash = base36(int(hashlib.md5(URL.encode("utf-8")).hexdigest(), 16))
    destination = args.gradle_home / "wrapper" / "dists" / "gradle-8.11.1-bin" / url_hash / "gradle-8.11.1-bin.zip"
    if destination.exists():
        if checksum(destination) != SHA256:
            raise ValueError("目标缓存存在但校验不符，未覆盖；请检查该缓存文件")
        print(f"Verified cached Gradle ZIP: {destination}")
        return
    args.downloads_dir.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(prefix="gradle-", dir=args.downloads_dir))
    archive = folder / "gradle-8.11.1-bin.zip"
    size = 0
    with urllib.request.urlopen(URL, timeout=90) as response, archive.open("xb") as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            if size > 256 * 1024 * 1024:
                raise ValueError("下载文件异常过大，停止")
            output.write(block)
    if checksum(archive) != SHA256:
        raise ValueError("Gradle 下载的 SHA-256 校验失败，未安装或解压")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with archive.open("rb") as source, destination.open("xb") as target:
        shutil.copyfileobj(source, target)
    print(f"Verified official Gradle ZIP cached: {destination}")
    print("Now run gradlew normally; Wrapper still performs its own integrity check and extraction.")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"Gradle cache setup stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
