# Тропа

Android-клиент для VPN-подписок: VLESS (Reality, Vision, XHTTP, WebSocket, gRPC), VMess, Trojan, Shadowsocks,
Hysteria2; через второе ядро mihomo — ещё TUIC. Понимает подписки, которые провайдеры выдают для Happ и INCY
(Xray JSON и профили маршрутизации), Clash/mihomo, v2rayNG (base64) и отдельные ссылки.

Готовые сборки — в [Releases](https://github.com/nvsbunny/tropa/releases/latest), оттуда же Тропа обновляется сама:
- `Tropa-<версия>-phone.apk` — Тропа для телефона, ядро Xray;
- `Tropa-<версия>-phone-mihomo.apk` — то же плюс второе ядро mihomo «для опытных»;
- `…-emulator-x86_64…` — то же для эмуляторов Android на компьютере.

Обе сборки — один пакет и один ключ: одну можно поставить поверх другой, настройки сохранятся.
Каждая обновляется своей же сборкой.
Лицензия — [GPL-3.0](LICENSE).

## Что внутри
- **Ядро** — официальный [Xray-core](https://github.com/XTLS/Xray-core) отдельным процессом. Конфиги провайдера
  в формате Xray JSON уходят ядру как есть, вместе с цепочками (dialerProxy). В сборке «mihomo» — ещё
  [mihomo](https://github.com/MetaCubeX/mihomo) на выбор (Настройки → Для опытных).
- **Подключение без root** — `VpnService` + TUN-вход Xray (дескриптор передаётся ядру как fd 3). Приложение само
  исключено из VPN, поэтому трафик ядра к серверам не зацикливается.
- **Root-режим** (Magisk, KernelSU) — без VPN: Xray от root с входом TPROXY, правила iptables в таблице mangle,
  `ip rule` по метке. Подробности и грабли Android — в комментарии к [`RootProxy.kt`](app/src/main/kotlin/app/tropa/core/RootProxy.kt).
- **Выбор сервера** — балансировщик Xray `leastLoad` с «ценой» сервера: очередь провайдера, результаты проверки
  качества (открываются ли Google, YouTube, Telegram, WhatsApp, Claude через сервер), серверы для обхода белых
  списков (LTE) — только на мобильном интернете и с учётом оператора.
- **Маршруты** — всё через VPN / российские сайты напрямую / только заблокированное / как у провайдера; приложения
  и свои сайты мимо VPN или через него. Списки блокировок ([itdoginfo/allow-domains](https://github.com/itdoginfo/allow-domains))
  и geo-файлы ([XTLS/Xray-rules-dat](https://github.com/XTLS/Xray-rules-dat)) обновляются сами раз в три дня по Wi-Fi.
- **Android TV** — экран под пульт; подписки передаются с телефона по QR-коду прямо по домашней сети,
  зашифрованно (AES-GCM с одноразовым ключом из QR), без сторонних серверов — [`Pairing.kt`](app/src/main/kotlin/app/tropa/core/Pairing.kt).
- **Автообновление** — из GitHub Releases: `update.json`, сверка SHA-256 и ключа подписи, установка через
  `PackageInstaller` — [`Updater.kt`](app/src/main/kotlin/app/tropa/core/Updater.kt).

## Приватность — что можно проверить в коде
- Никаких трекеров и аналитики: в зависимостях ([`app/build.gradle.kts`](app/build.gradle.kts)) только Jetpack Compose,
  Glance, ZXing core и SnakeYAML. Ни Google Play services, ни Firebase.
- Нет открытых локальных прокси: у ядра только вход TUN (или TPROXY на 127.0.0.1 в root-режиме) и страница
  статистики на 127.0.0.1. Другие приложения не могут подключиться к ядру и узнать адрес сервера.
- В сеть приложение ходит к вашим подпискам, к серверам (через ядро), к GitHub за обновлениями и списками.

## Сборка
Нужны JDK 17+ и Android SDK. Ядро Xray, geo-файлы и списки блокировок скачиваются при сборке (задача `fetchCore`,
версии и SHA-256 закреплены в [`app/build.gradle.kts`](app/build.gradle.kts)).

```
./gradlew testXrayDebugUnitTest   # тесты: разбор подписок, конфиги ядер, выбор сервера, передача на ТВ
./gradlew assembleXrayDebug       # Тропа на Xray: app/build/outputs/apk/xray/debug/
./gradlew assembleMihomoDebug     # с ядром mihomo (сначала tools/build-mihomo.sh): …/apk/mihomo/debug/
```

Ядро для сборки «mihomo» собирает [`tools/build-mihomo.sh`](tools/build-mihomo.sh) с правкой
[`tools/mihomo-android-nonroot.patch`](tools/mihomo-android-nonroot.patch): без неё Android-сборка mihomo без root
не поднимает TUN. Собрав ядро сами, можно сверить SHA-256 с `lib/arm64-v8a/libmihomo.so` из официального APK.

Официальные выпуски подписываются ключом автора (`./gradlew tropaRelease`, ключа в репозитории нет) и выкладываются
скриптом [`tools/release.sh`](tools/release.sh). Своя сборка подписана другим ключом: поверх официальной
Android её не поставит, и обновления официальной она не получит.

## Сторонние компоненты
| Компонент | Лицензия |
|---|---|
| Xray-core | MPL-2.0 |
| mihomo (с правкой из `tools/`) | GPL-3.0 |
| Шрифт Onest | SIL OFL 1.1 ([`ONEST-OFL.txt`](ONEST-OFL.txt)) |
| Jetpack Compose, Glance, ZXing core, SnakeYAML | Apache-2.0 |

```
Тропа — клиент для VPN-подписок
Copyright (C) 2026 nvsbunny

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. This program is distributed in the hope that it
will be useful, but WITHOUT ANY WARRANTY; see the GNU General Public License for more details.
```
