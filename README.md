<p align="center">
  <img src="v2plus.png" alt="V2plus">
</p>

<p align="center">
  Android-клиент на базе <a href="https://github.com/2dust/v2rayNG">v2rayNG</a> с ядром Xray.
</p>

<p align="center">
  <a href="https://developer.android.com/studio/releases/platforms"><img src="https://img.shields.io/badge/Android-24%2B-a57fff?style=flat-square&labelColor=18181c" alt="Android 24+"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-a57fff?style=flat-square&labelColor=18181c" alt="GPL-3.0"></a>
  <img src="https://img.shields.io/badge/version-1.3.0-a57fff?style=flat-square&labelColor=18181c" alt="1.3.0">
  <a href="https://t.me/GoodbyeWLALT"><img src="https://img.shields.io/badge/Telegram-канал-26A5E4?style=flat-square&labelColor=18181c&logo=telegram&logoColor=white" alt="Telegram"></a>
</p>

## Возможности

| | |
|:---|:---|
| Протоколы | VLESS, VMess, Trojan, Shadowsocks, Hysteria и транспорты Xray |
| Оформление | Пресеты, цвета, шрифт, скругления |
| Языки | English, русский, беларуская, українська |
| Сборки | Play Store, F-Droid, RuStore |

## Сборка

JDK 17+ и Android SDK 36.

1. Скачайте [`libv2ray.aar`](app/libs/README.md) в `app/libs/`.
2. Для release скопируйте `keystore.properties.example` → `keystore.properties`.
3. Соберите нужный flavor:

```bash
./gradlew assemblePlaystoreRelease
./gradlew assembleFdroidRelease
./gradlew assembleRustoreRelease
```

На Windows: `.\gradlew.bat assemblePlaystoreRelease`. Debug: `assemblePlaystoreDebug`.

APK появится в `app/build/outputs/apk/<flavor>/release/`.

## GeoIP и Geosite

`geoip.dat` и `geosite.dat` лежат в `Android/data/com.v2plus.app/files/assets` (путь может отличаться). Можно подставить наборы вроде [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat).

## Лицензия

[GPL-3.0](LICENSE) · [NOTICE](NOTICE)

Основано на [v2rayNG](https://github.com/2dust/v2rayNG). Ядро — [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) / [Xray-core](https://github.com/XTLS/Xray-core). TUN — [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

---

<p align="center">
  <sub>English · Android VPN/proxy client based on v2rayNG. Put <code>libv2ray.aar</code> in <code>app/libs/</code>, then <code>./gradlew assemblePlaystoreRelease</code>.</sub>
</p>
