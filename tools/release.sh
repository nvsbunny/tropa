#!/usr/bin/env bash
# Выпуск новой версии Тропы.
#
#   1. Поднимите tropa.versionCode (на 1) и tropa.versionName в gradle.properties.
#   2. ./tools/release.sh "Что нового — одной строкой"
#
# Скрипт соберёт и подпишет APK (задача tropaRelease), посчитает SHA-256, напишет update.json
# и выложит выпуск на GitHub. Приложения узнают о нём по ссылке
# https://github.com/$REPO/releases/latest/download/update.json
#
# Проверка без публикации: TROPA_DRY=1 TROPA_BASE_URL=http://127.0.0.1:8099 ./tools/release.sh "…"
# — файлы останутся в app/build/outputs/tropa, их можно раздать любым http-сервером.
set -euo pipefail

notes=${1:-}
repo=${TROPA_REPO:-nvsbunny/tropa}
cd "$(dirname "$0")/.."

code=$(sed -n 's/^tropa.versionCode=//p' gradle.properties)
name=$(sed -n 's/^tropa.versionName=//p' gradle.properties)
[ -n "${TROPA_VERSION_CODE:-}" ] && code=$TROPA_VERSION_CODE
[ -n "${TROPA_VERSION_NAME:-}" ] && name=$TROPA_VERSION_NAME
base=${TROPA_BASE_URL:-https://github.com/$repo/releases/download/v$name}

./gradlew tropaRelease --console=plain -q -Ptropa.versionCode="$code" -Ptropa.versionName="$name" ${TROPA_GRADLE_ARGS:-}

out=app/build/outputs/tropa
python -I - "$out" "$code" "$name" "$notes" "$base" <<'EOF'
import hashlib, json, os, sys
out, code, name, notes, base = sys.argv[1:]
# Имена для людей: телефону — «phone», эмулятору — «emulator». Тропа выбирает файл по ключу ABI в update.json.
public = {'arm64-v8a': f'Tropa-{name}-phone.apk', 'x86_64': f'Tropa-{name}-emulator-x86_64.apk'}
apks = {}
for f in sorted(os.listdir(out)):
    if not f.endswith('.apk') or not f.startswith(f'Tropa-{name}-'):
        continue
    abi = f.removeprefix(f'Tropa-{name}-').removesuffix('.apk')
    if abi not in public:
        continue
    os.replace(os.path.join(out, f), os.path.join(out, public[abi]))
    data = open(os.path.join(out, public[abi]), 'rb').read()
    apks[abi] = {'url': f'{base}/{public[abi]}', 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)}
json.dump({'versionCode': int(code), 'versionName': name, 'notes': notes, 'apks': apks},
          open(os.path.join(out, 'update.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('update.json:', name, '(' + code + '),', ', '.join(apks))
EOF

if [ -n "${TROPA_DRY:-}" ]; then
    echo "Без публикации: файлы в $out"
    exit 0
fi
gh release create "v$name" "$out"/*.apk "$out/update.json" --repo "$repo" --title "Тропа $name" --notes "${notes:-Тропа $name}"
echo "Выложено: https://github.com/$repo/releases/tag/v$name"
