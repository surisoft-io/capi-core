#!/bin/sh
set -e

echo "Installing OPA CLI..."
wget -qO /usr/local/bin/opa https://openpolicyagent.org/downloads/v1.4.2/opa_linux_amd64_static
chmod +x /usr/local/bin/opa

POLICY_DIR="/policies"
OUTPUT_DIR="/output"
mkdir -p "$OUTPUT_DIR"

cd "$POLICY_DIR"

# Build ONE combined bundle containing every policy + every data file.
#
# Two layouts coexist here:
#  1. Top-level flat files (e.g. admin_only.rego + admin_only.json) — entrypoints
#     declared explicitly via -e capi/<name>/allow.
#  2. Nested directories matching the rego package (e.g. capi/authz/dev/policy.rego
#     + capi/authz/dev/data.json) — entrypoints declared inline via the rego's
#     `# METADATA / entrypoint: true` annotation. OPA discovers these
#     automatically when the directory is passed as a source path.
#
# Passing `.` (instead of `*.rego *.json`) tells `opa build` to walk the tree
# recursively, picking up both layouts in one pass. Each .json file's contents
# are loaded into the bundle's data document at the path corresponding to its
# directory (so capi/authz/dev/data.json lands at data.capi.authz.dev.*).
#
# Result: $OUTPUT_DIR/bundle.tar.gz with one policy.wasm (multi-entrypoint),
# one merged data.json, and a .manifest listing all entrypoints.
#
# OPA's recommended layout — see
# https://www.openpolicyagent.org/docs/management-bundles
ENTRYPOINTS=""
for rego in *.rego; do
    name=$(basename "$rego" .rego)
    ENTRYPOINTS="$ENTRYPOINTS -e capi/${name}/allow"
done

echo "Building combined Wasm bundle (top-level entrypoints:${ENTRYPOINTS}; nested entrypoints discovered via # METADATA annotations)"
# shellcheck disable=SC2086 # we want word-splitting on $ENTRYPOINTS
opa build -t wasm $ENTRYPOINTS . -o "$OUTPUT_DIR/bundle.tar.gz"

echo ""
echo "Combined bundle built:"
ls -la "$OUTPUT_DIR/bundle.tar.gz"
echo ""
echo "Bundle contents:"
tar tzf "$OUTPUT_DIR/bundle.tar.gz"