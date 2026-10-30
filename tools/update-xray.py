"""
Подготовить Тропу с новым Xray: python -I tools/update-xray.py v26.11.5

1. Берёт контрольные суммы сборок для Android из файлов .dgst этого выпуска Xray.
2. Ставит новую версию в app/build.gradle.kts, поднимает версию Тропы (versionCode +1, x.y.z → x.y.z+1).
3. Собирает подписанный релиз и прогоняет тесты.
4. Проверяет конфиги из тестов настоящим Xray той же версии (xray run -test, сборка для Windows).
Если что-то не прошло — возвращает файлы как были и завершается с ошибкой. Ничего не публикует.
"""
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(ROOT, 'app', 'build.gradle.kts')
PROPS = os.path.join(ROOT, 'gradle.properties')
SETTINGS = os.path.join(ROOT, 'app', 'src', 'main', 'kotlin', 'app', 'tropa', 'ui', 'SettingsScreen.kt')
FILES = [GRADLE, PROPS, SETTINGS]
ZIPS = {'arm64-v8a': 'Xray-android-arm64-v8a.zip', 'x86_64': 'Xray-android-amd64.zip'}


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'tropa-update'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def sha_from_dgst(tag, name):
    text = get(f'https://github.com/XTLS/Xray-core/releases/download/{tag}/{name}.dgst').decode()
    return re.search(r'SHA2-256=\s*([0-9a-f]{64})', text).group(1)


def run(cmd, **kw):
    print('$', ' '.join(cmd))
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)
    out = (r.stdout + r.stderr).strip().splitlines()
    print('\n'.join(l for l in out if not l.startswith('WARNING'))[-3000:])
    if r.returncode != 0:
        raise RuntimeError('команда не прошла: ' + ' '.join(cmd))


def xray_check(tag):
    """Конфиги, которые пишут тесты в app/build/core-check, проверяем xray.exe той же версии."""
    name = 'Xray-windows-64.zip'
    data = get(f'https://github.com/XTLS/Xray-core/releases/download/{tag}/{name}')
    if hashlib.sha256(data).hexdigest() != sha_from_dgst(tag, name):
        raise RuntimeError('Xray для Windows скачался с ошибкой')
    work = tempfile.mkdtemp(prefix='tropa-xray-')
    try:
        zipfile.ZipFile(io.BytesIO(data)).extractall(work)
        assets = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'xray')
        for f in ('geosite.dat', 'geoip.dat'):
            shutil.copy(os.path.join(assets, f), work)
        checked = os.path.join(ROOT, 'app', 'build', 'core-check')
        configs = sorted(f for f in os.listdir(checked) if f.startswith('config-') and f.endswith('.json'))
        if not configs:
            raise RuntimeError('тесты не оставили конфигов для проверки')
        for c in configs:
            shutil.copy(os.path.join(checked, c), os.path.join(work, 'c.json'))
            r = subprocess.run([os.path.join(work, 'xray.exe'), 'run', '-test', '-c', 'c.json'], cwd=work,
                               capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120,
                               env={**os.environ, 'XRAY_LOCATION_ASSET': work})
            ok = 'Configuration OK' in r.stdout + r.stderr
            print(f'{c}: {"Configuration OK" if ok else (r.stdout + r.stderr).strip()[-300:]}')
            if not ok:
                raise RuntimeError(f'Xray {tag} не принимает {c}')
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(tag):
    backup = {f: open(f, encoding='utf-8').read() for f in FILES}
    try:
        gradle = backup[GRADLE]
        old = re.search(r'val xrayVersion = "([^"]+)"', gradle).group(1)
        if old == tag:
            print(f'Xray уже {tag}')
            return
        gradle = gradle.replace(f'val xrayVersion = "{old}"', f'val xrayVersion = "{tag}"')
        for abi, name in ZIPS.items():
            new_sha = sha_from_dgst(tag, name)
            gradle = re.sub(rf'("{re.escape(abi)}" to \("{re.escape(name)}" to ")[0-9a-f]{{64}}', rf'\g<1>{new_sha}', gradle)
        open(GRADLE, 'w', encoding='utf-8', newline='\n').write(gradle)

        props = backup[PROPS]
        code = int(re.search(r'^tropa\.versionCode=(\d+)$', props, re.M).group(1)) + 1
        name = re.search(r'^tropa\.versionName=(.+)$', props, re.M).group(1).strip()
        parts = name.split('.')
        parts[-1] = str(int(parts[-1]) + 1)
        new_name = '.'.join(parts)
        props = re.sub(r'^tropa\.versionCode=\d+$', f'tropa.versionCode={code}', props, flags=re.M)
        props = re.sub(r'^tropa\.versionName=.+$', f'tropa.versionName={new_name}', props, flags=re.M)
        open(PROPS, 'w', encoding='utf-8', newline='\n').write(props)

        settings = backup[SETTINGS].replace(f'Ядро Xray {old.lstrip("v")}', f'Ядро Xray {tag.lstrip("v")}')
        open(SETTINGS, 'w', encoding='utf-8', newline='\n').write(settings)

        gradlew = os.path.join(ROOT, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
        shutil.rmtree(os.path.join(ROOT, 'app', 'build', 'core-check'), ignore_errors=True)
        run([gradlew, 'testXrayDebugUnitTest', 'tropaRelease', '--console=plain', '-q'])
        xray_check(tag)
        print(f'Готово: Тропа {new_name} (versionCode {code}) с Xray {tag}. APK — app/build/outputs/tropa/')
    except Exception as e:
        for f, text in backup.items():
            open(f, 'w', encoding='utf-8', newline='\n').write(text)
        print('Откатили изменения:', e)
        sys.exit(1)


if __name__ == '__main__':
    main(sys.argv[1])
