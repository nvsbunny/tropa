"""
Еженедельная проверка: вышли ли новые Xray, mihomo, Happ и INCY.

Запускается Планировщиком заданий Windows раз в неделю (tools/watch-install.ps1), работает
несколько секунд и завершается. Пишет сводку в ОБНОВЛЕНИЯ.md в корне проекта и показывает
уведомление Windows, если есть что-то новое.

Если вышел новый Xray — сам готовит версию Тропы (tools/update-xray.py): новая версия ядра
и контрольные суммы в сборке, номер версии +1, сборка, тесты и проверка конфигов настоящим Xray.
Выпускает человек: после проверки на телефоне — ./tools/release.sh "что нового".

Запуск вручную: python -I tools/watch.py
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE = os.path.join(ROOT, 'tools', 'watch-state.json')
REPORT = os.path.join(ROOT, 'ОБНОВЛЕНИЯ.md')

WATCHED = [
    # (что, репозиторий, фильтр тега, важность для Тропы)
    ('Xray-core', 'XTLS/Xray-core', r'^v\d', 'ядро Тропы — обновлять'),
    ('mihomo', 'MetaCubeX/mihomo', r'^v\d', 'ядро «для опытных»'),
    ('Happ (Android)', 'Happ-proxy/happ-android', r'.', 'конкурент — смотреть, что добавили'),
    ('INCY (Android)', 'INCY-DEV/incy-platforms', r'^android-', 'конкурент — смотреть, что добавили'),
]


def api(path):
    req = urllib.request.Request('https://api.github.com/' + path,
                                 headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'tropa-watch'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def latest(repo, pattern):
    for rel in api(f'repos/{repo}/releases?per_page=15'):
        if rel.get('draft') or rel.get('prerelease') or not re.search(pattern, rel['tag_name']):
            continue
        return rel
    return None


def pinned():
    """Версии, которые сейчас в сборке Тропы."""
    gradle = open(os.path.join(ROOT, 'app', 'build.gradle.kts'), encoding='utf-8').read()
    mihomo = open(os.path.join(ROOT, 'tools', 'build-mihomo.sh'), encoding='utf-8').read()
    return {
        'XTLS/Xray-core': re.search(r'val xrayVersion = "([^"]+)"', gradle).group(1),
        'MetaCubeX/mihomo': re.search(r'VERSION="([^"]+)"', mihomo).group(1),
    }


def notify(title, text):
    """Всплывающее уведомление Windows (через PowerShell, без сторонних модулей)."""
    ps = f'''
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null
$t = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
$x = $t.GetElementsByTagName("text")
$x.Item(0).AppendChild($t.CreateTextNode({json.dumps(title, ensure_ascii=False)})) > $null
$x.Item(1).AppendChild($t.CreateTextNode({json.dumps(text, ensure_ascii=False)})) > $null
$app = '{{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}}\\WindowsPowerShell\\v1.0\\powershell.exe'
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show([Windows.UI.Notifications.ToastNotification]::new($t))
'''
    subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', ps],
                   capture_output=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))


def main():
    state = json.load(open(STATE, encoding='utf-8')) if os.path.exists(STATE) else {}
    ours = pinned()
    lines, news, xray_new = [], [], None
    for name, repo, pattern, why in WATCHED:
        try:
            rel = latest(repo, pattern)
        except Exception as e:  # нет сети — попробуем в следующий раз
            lines.append(f'- **{name}**: не удалось проверить ({e})')
            continue
        if not rel:
            continue
        tag, date = rel['tag_name'], rel['published_at'][:10]
        seen = state.get(repo)
        mark = ''
        if repo in ours and tag != ours[repo]:
            mark = f' — **у Тропы {ours[repo]}, пора обновить**'
            if repo == 'XTLS/Xray-core':
                xray_new = tag
        if seen != tag:
            news.append(f'{name} {tag}')
        state[repo] = tag
        lines.append(f'- **{name}** {tag} от {date} ({why}){mark}')
        body = (rel.get('body') or '').strip()
        if body and repo.startswith(('Happ', 'INCY')):
            lines += ['  > ' + l for l in body.splitlines()[:12] if l.strip()]

    prepared = ''
    if xray_new and state.get('prepared_xray') != xray_new:
        r = subprocess.run([sys.executable, '-I', os.path.join(ROOT, 'tools', 'update-xray.py'), xray_new],
                           capture_output=True, text=True, encoding='utf-8', errors='replace')
        tail = '\n'.join(r.stdout.strip().splitlines()[-6:] + r.stderr.strip().splitlines()[-6:])
        if r.returncode == 0:
            state['prepared_xray'] = xray_new
            prepared = (f'\n## Готова новая версия Тропы с Xray {xray_new}\n'
                        'Собрана и проверена тестами. Поставьте на телефон, проверьте и выпустите:\n'
                        '`./tools/release.sh "Ядро Xray ' + xray_new + '"`\n\n```\n' + tail + '\n```\n')
        else:
            prepared = (f'\n## Не удалось подготовить Тропу с Xray {xray_new}\n'
                        'Изменения откатаны, сборка осталась прежней. Подробности:\n\n```\n' + tail + '\n```\n')

    now = datetime.now().strftime('%d.%m.%Y %H:%M')
    report = (f'# Новые версии — проверка {now}\n\n' + '\n'.join(lines) + '\n' + prepared +
              '\nПроверка идёт раз в неделю (Планировщик заданий, «Тропа — проверка обновлений»).\n')
    open(REPORT, 'w', encoding='utf-8').write(report)
    json.dump(state, open(STATE, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

    if prepared or news:
        title = 'Тропа: готово обновление ядра' if prepared and 'Готова' in prepared else 'Тропа: новые версии'
        notify(title, ', '.join(news) or 'см. ОБНОВЛЕНИЯ.md')
    print(report)


if __name__ == '__main__':
    main()
