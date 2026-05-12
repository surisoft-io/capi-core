#!/bin/sh
set -e

echo "Installing OPA CLI..."
wget -qO /usr/local/bin/opa https://openpolicyagent.org/downloads/v1.4.2/opa_linux_amd64_static
chmod +x /usr/local/bin/opa

POLICY_DIR="/policies"
OUTPUT_DIR="/output"
mkdir -p "$OUTPUT_DIR"

for rego in "$POLICY_DIR"/*.rego; do
    name=$(basename "$rego" .rego)
    package_path="capi/${name}/allow"
    data_file="$POLICY_DIR/${name}.json"
    if [ -f "$data_file" ]; then
        echo "Building Wasm bundle: $name (entrypoint: $package_path, data: ${name}.json)"
        opa build -t wasm -e "$package_path" "$rego" "$data_file" -o "$OUTPUT_DIR/${name}.tar.gz"
    else
        echo "Building Wasm bundle: $name (entrypoint: $package_path, no data)"
        opa build -t wasm -e "$package_path" "$rego" -o "$OUTPUT_DIR/${name}.tar.gz"
    fi
    echo "  -> ${name}.tar.gz ($(wc -c < "$OUTPUT_DIR/${name}.tar.gz") bytes)"
done

echo ""
echo "All bundles built:"
ls -la "$OUTPUT_DIR"/*.tar.gz