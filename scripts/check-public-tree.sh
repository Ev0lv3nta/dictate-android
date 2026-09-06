#!/usr/bin/env bash
set -euo pipefail

forbidden='dev[.]local[.]ykrecognizer|ru[.]yandex[.]androidkeyboard|StubRecognitionService|EmbeddedKeys|R5CW[0-9A-Z]+'
secret_prefix='s''k[-_][A-Za-z0-9_-]{20,}|AI''za[A-Za-z0-9_-]{25,}'

if git grep -n -I -E "$forbidden" -- ':!scripts/check-public-tree.sh'; then
    echo "Найдена устаревшая привязка или личный идентификатор" >&2
    exit 1
fi

if git grep -n -I -E "$secret_prefix" -- ':!scripts/check-public-tree.sh'; then
    echo "Найдено значение, похожее на API-ключ" >&2
    exit 1
fi

if git ls-files | grep -E '(^|/)(build|logs|recordings)/|[.]apk$|[.]keystore$|[.]jks$'; then
    echo "В Git попал локальный артефакт" >&2
    exit 1
fi

echo "Public tree check: PASS"
