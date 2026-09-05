#!/usr/bin/env bash
# Fetches or verifies libLiteRtDispatch_Qualcomm.so for Qualcomm NPU support in LiteRT-LM.
set -euo pipefail

DEST_DIR="app/src/main/jniLibs/arm64-v8a"
TARGET_SO="${DEST_DIR}/libLiteRtDispatch_Qualcomm.so"

mkdir -p "${DEST_DIR}"

if [ -s "${TARGET_SO}" ]; then
    echo "Found staged LiteRT Qualcomm dispatch library at: ${TARGET_SO}"
    exit 0
fi

echo "Downloading official LiteRT NPU runtime libraries from Google AI Edge (v2.2.0)..."
TMP_ZIP=$(mktemp)
trap 'rm -f "${TMP_ZIP}"' EXIT

curl --fail -sSL "https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_npu_runtime_libraries.zip" -o "${TMP_ZIP}"

TMP_SO=$(mktemp)
unzip -p "${TMP_ZIP}" qualcomm_runtime_v79/src/main/jni/arm64-v8a/libLiteRtDispatch_Qualcomm.so > "${TMP_SO}"

if [ ! -s "${TMP_SO}" ]; then
    rm -f "${TMP_SO}"
    echo "Error: Extracted library is empty or missing from archive" >&2
    exit 1
fi

mv "${TMP_SO}" "${TARGET_SO}"

echo "Successfully staged ${TARGET_SO}"
