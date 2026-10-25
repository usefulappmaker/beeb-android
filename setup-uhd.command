#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
twinvine_dir="${TWINVINE_DIR:-$HOME/TwinVine}"
python_bin="${BEEB_SETUP_PYTHON:-$PWD/.venv-setup/bin/python}"
if [[ ! -x "$python_bin" && -z "${BEEB_SETUP_PYTHON:-}" ]]; then
    python_bin="$twinvine_dir/.venv/bin/python"
fi
if [[ ! -x "$python_bin" ]]; then
    echo 'Create the setup-helper environment first; see SETUP-UHD.md, step 2.'
    exit 1
fi
exec "$python_bin" scripts/setup-uhd.py --twinvine "$twinvine_dir" "$@"
