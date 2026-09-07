#!/usr/bin/env bash
set -euo pipefail
# Print paths only. Never echo matching credentials into CI logs.
pattern='s''k[-_][A-Za-z0-9_-]{20,}|AI''za[A-Za-z0-9_-]{25,}|gh[pousr]_[A-Za-z0-9]{30,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
if git grep -l -I -E "$pattern" -- ':!scripts/check-public-tree.sh'; then
    echo 'Potential credential in tracked content; inspect locally.' >&2
    exit 1
fi
if git ls-files | grep -E '(^|/)(build|logs|recordings)/|[.](apk|keystore|jks|p12|pcm|wav|m4a)$|(^|/)local[.]properties$'; then
    echo 'Private build artifact in Git.' >&2
    exit 1
fi
echo 'Public tree check: PASS'
