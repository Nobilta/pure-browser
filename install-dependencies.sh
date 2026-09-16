#!/usr/bin/env bash
# Check/install the toolchain used by the project. Existing installations are reused.
# The script is deliberately conservative: it does not modify ~/.zshrc or Cargo config.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v java >/dev/null 2>&1; then
    if command -v brew >/dev/null 2>&1; then
        echo "未找到 Java，使用 Homebrew 安装 Temurin。"
        brew install --cask temurin
    elif command -v winget >/dev/null 2>&1; then
        echo "未找到 Java，使用 winget 安装 Temurin 21。"
        winget install --id EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements
    else
        echo "未找到 Java 17+，请先安装 JDK。" >&2
        echo "macOS: brew install --cask temurin；Windows: winget install EclipseAdoptium.Temurin.21.JDK" >&2
        exit 1
    fi
fi

if ! command -v rustc >/dev/null 2>&1 || ! command -v cargo >/dev/null 2>&1; then
    if command -v rustup >/dev/null 2>&1; then
        echo "未找到完整 Rust 工具链，使用 rustup 默认工具链。"
        rustup toolchain install stable
    else
        echo "未找到 Rust/rustup，请从 https://rustup.rs 安装。" >&2
        exit 1
    fi
fi

if ! command -v rustup >/dev/null 2>&1; then
    echo "未找到 rustup；cargo target 管理需要它。" >&2
    exit 1
fi

rustup target add aarch64-linux-android
rustup target add x86_64-linux-android || true

if ! bash "$SCRIPT_DIR/rust/resolve-android-ndk.sh" "$SCRIPT_DIR" >/dev/null; then
    echo "未找到 Android NDK，运行 ./install-ndk.sh 安装。" >&2
    exit 1
fi

echo "依赖检查完成："
java -version 2>&1 | head -2
rustc --version
cargo --version
echo "NDK: $(bash "$SCRIPT_DIR/rust/resolve-android-ndk.sh" "$SCRIPT_DIR")"
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) echo "下一步（Git Bash）：bash build-and-test.sh" ;;
    *) echo "下一步：./build-and-test.sh" ;;
esac
