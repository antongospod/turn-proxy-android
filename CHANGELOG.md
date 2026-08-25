# Changelog

## [4.3.0](https://github.com/samosvalishe/turn-proxy-android/compare/v4.2.0...v4.3.0) (2026-08-25)


### Features

* bump aar ([082251b](https://github.com/samosvalishe/turn-proxy-android/commit/082251bd63154d2d213b40358457096ff3773e84))
* **proxy:** освобождать аллокации до потери сети ([d9af18a](https://github.com/samosvalishe/turn-proxy-android/commit/d9af18ab7a282915ae1e1d3c31b14cd4c71bbb75))
* **proxy:** переподключение вместо перезапуска ядра при смене сети ([e070d12](https://github.com/samosvalishe/turn-proxy-android/commit/e070d121e3351b82d3ceed6893f7bac2f733e856))
* **proxy:** режим проброса tcp с настраиваемым ARQ ([e7425a5](https://github.com/samosvalishe/turn-proxy-android/commit/e7425a5ab8df6731c2761684647ce1e26736f235))
* **server:** настройка пейсинга обфускации ([d16772d](https://github.com/samosvalishe/turn-proxy-android/commit/d16772da88e19df670974c3ee889c20871eccf9f))
* **settings:** тумблер сезонного декора ([bb0a40b](https://github.com/samosvalishe/turn-proxy-android/commit/bb0a40ba9b678fe5679a7bb0f4f3311cae28a4a3))
* **share:** опциональная ссылка на звонок в freeturn:// ([0f8dc7c](https://github.com/samosvalishe/turn-proxy-android/commit/0f8dc7cf1e521debd63aed54617693c386822654))
* **ui:** осенний декор кнопки-героя ([f430dea](https://github.com/samosvalishe/turn-proxy-android/commit/f430dea774889fc4a4e0c6a92c79c623760c3c07))


### Fixes

* **service:** запрос VPN-разрешения при старте из виджета и тайла ([29dc9a1](https://github.com/samosvalishe/turn-proxy-android/commit/29dc9a199979f8cb5a3206288dd24fe744574daa))
* **settings:** убрать моргание статуса обновления ([92c1afc](https://github.com/samosvalishe/turn-proxy-android/commit/92c1afcfb0489851ef925ae99e679de90b0bca19))
* **vpn:** не отзывать чужой VPN при открытии приложения ([a380d2c](https://github.com/samosvalishe/turn-proxy-android/commit/a380d2cfa4c1a170a232b24695925364683cac39))


### Performance

* **proxy:** снять старт сервиса с главного потока ([392e8be](https://github.com/samosvalishe/turn-proxy-android/commit/392e8bed69a8d3dae4791c69d5cb77a2e026c485))

## [4.2.0](https://github.com/samosvalishe/turn-proxy-android/compare/v4.1.0...v4.2.0) (2026-08-19)


### Features

* bump aar ([f0aa0bb](https://github.com/samosvalishe/turn-proxy-android/commit/f0aa0bbf27764da94695840b0658c1beb56487be))

## [4.1.0](https://github.com/samosvalishe/turn-proxy-android/compare/v4.0.0...v4.1.0) (2026-08-18)


### Features

* **logs:** файловый лог сессии с ротацией и отправкой ([0f9f9be](https://github.com/samosvalishe/turn-proxy-android/commit/0f9f9be46c58f87eb25f16e6954ee03c6180f0c7))
* **settings:** подключение при запуске и работа в фоне ([c85e3e0](https://github.com/samosvalishe/turn-proxy-android/commit/c85e3e08e82e88d0105eb44004286743c9a9cfb6))


### Fixes

* **proxy:** sticky-рестарт не поднимает сессию без намерения ([cccc711](https://github.com/samosvalishe/turn-proxy-android/commit/cccc711b11e4003da8145e80a395530ad6870e01))
* **proxy:** неудачный tun при смене сети не рвёт сессию ([36b123b](https://github.com/samosvalishe/turn-proxy-android/commit/36b123be46a762eceee2b11ec5aa147e134ab9c6))

## [4.0.0](https://github.com/samosvalishe/turn-proxy-android/compare/v3.6.0...v4.0.0) (2026-08-17)


### ⚠ BREAKING CHANGES

* **proxy:** убрать TCP-режим проброса и bond
* **proxy:** ядро gomobile в процессе приложения

### Features

* **proxy:** убрать TCP-режим проброса и bond ([215156e](https://github.com/samosvalishe/turn-proxy-android/commit/215156ea44d351f6fcde7d86d4b91259f6e06ed0))
* **proxy:** ядро gomobile в процессе приложения ([9c05bd9](https://github.com/samosvalishe/turn-proxy-android/commit/9c05bd9e76afa2276e2b5a6c4dad39d0f525616a))
* **settings:** пункт Always-on VPN в разделе "Работа в фоне" ([429affa](https://github.com/samosvalishe/turn-proxy-android/commit/429affa0a19f1280195857e2e4974b80ce603eed))


### Fixes

* bump aar ([be61dbc](https://github.com/samosvalishe/turn-proxy-android/commit/be61dbc69b66fc324aafc09e38135f088818535b))
* **proxy:** вернуть wake lock сессии, убрать always-on vpn ([0e0d3ee](https://github.com/samosvalishe/turn-proxy-android/commit/0e0d3ee952a0e3428cd928e1b32ed02e585475e3))
* пересоздание TUN-интерфейса при смене сети для обхода обрывов в Doze mode ([9bfd79a](https://github.com/samosvalishe/turn-proxy-android/commit/9bfd79a854de614af5dd73e9a860a8b59742a7a2))


### Performance

* **proxy:** экономия батареи - без wake lock, опрос метрик только при видимом окне ([82c1adf](https://github.com/samosvalishe/turn-proxy-android/commit/82c1adffb6239ca0f189f6904b5222e60b1c0195))

## [3.6.0](https://github.com/samosvalishe/turn-proxy-android/compare/v3.5.2...v3.6.0) (2026-08-14)


### Features

* update core ([31b6fc6](https://github.com/samosvalishe/turn-proxy-android/commit/31b6fc6fe17bdd7e3a542c8bf059ce4f6e62f913))

## [3.5.2](https://github.com/samosvalishe/turn-proxy-android/compare/v3.5.1...v3.5.2) (2026-08-04)


### Fixes

* **logs:** починен автоскролл к хвосту ([d52cceb](https://github.com/samosvalishe/turn-proxy-android/commit/d52cceb90c5a1fdbb1699b9ea9267b6f0a1d1ca5))
* **ui:** в шторке при WG - "Туннель активен" ([0cfaae2](https://github.com/samosvalishe/turn-proxy-android/commit/0cfaae2ac34d6bd91c1ab44357ddfb73d85b4008))
* **ui:** статистика и статус туннеля в WG-режиме ([009baae](https://github.com/samosvalishe/turn-proxy-android/commit/009baaeabc77d2e3b0ab275e71a2b661d3c1d492))

## [3.5.1](https://github.com/samosvalishe/turn-proxy-android/compare/v3.5.0...v3.5.1) (2026-07-30)


### Fixes

* **a11y:** описание QR-кода и роль кнопки у чипа split-tunnel ([2573d47](https://github.com/samosvalishe/turn-proxy-android/commit/2573d47c1c33193dd9fe302763f63e1a779673d9))
* **setup:** поля не затираются дефолтом до приезда DataStore ([50a03f5](https://github.com/samosvalishe/turn-proxy-android/commit/50a03f5ff49717aeeb8d25482c07717cc6e572a2))
* update бинарников ([2775435](https://github.com/samosvalishe/turn-proxy-android/commit/2775435472579a395626f55ba7ac9cf63c8d4392))
* гонки в колбэке камеры и в отложенном reconnect ([2607bd7](https://github.com/samosvalishe/turn-proxy-android/commit/2607bd7dc2e6bb91b5cc5ea67b4f5a912c5ecd83))


### Performance

* **ui:** меньше аллокаций и рекомпозиций на кадр ([f99e18c](https://github.com/samosvalishe/turn-proxy-android/commit/f99e18c024c91712a78edcdb8bff3d0d257299d4))


### Refactoring

* **logs:** уровень строки считается один раз в domain ([e78c335](https://github.com/samosvalishe/turn-proxy-android/commit/e78c335e2fee783780f046789339e4a688cb5e18))

## [3.5.0](https://github.com/samosvalishe/turn-proxy-android/compare/v3.4.2...v3.5.0) (2026-07-29)


### Features

* **client:** убран выбор браузерного профиля ([85e7b87](https://github.com/samosvalishe/turn-proxy-android/commit/85e7b87b9c5d3c1bc7232b1c013484d719ebd1ca))
* **logs:** маскировка аргументов ядра под приватным режимом ([8846e57](https://github.com/samosvalishe/turn-proxy-android/commit/8846e573cb05939599606bb7f595b64f7c93b556))
* **split:** убрана загрузка пресета РФ ([a30625f](https://github.com/samosvalishe/turn-proxy-android/commit/a30625f4a88daef57f2505f9655795613b99eef6))


### Fixes

* **backup:** восстановление заменяет профиль целиком ([93e1fc6](https://github.com/samosvalishe/turn-proxy-android/commit/93e1fc68bae14995d9ca84b7519dcb3bbb0f60d1))
* **captcha:** исправлена работоспособность автосолвера ([f0d0db3](https://github.com/samosvalishe/turn-proxy-android/commit/f0d0db3e5f1b2c0d6a5a0c0fcf07b6b00a8546df))
* **haptics:** вибра не глушится legacy-ключом системы ([e90c5f6](https://github.com/samosvalishe/turn-proxy-android/commit/e90c5f67709dd7d1576f6ac80dc637ea71def3fe))
* **proxy:** не ждать 5 минут при падении старта FGS ([bd48667](https://github.com/samosvalishe/turn-proxy-android/commit/bd486671ac1082975c6d52b19243dc565ffd69f1))
* **ssh:** StrictHostKeyChecking=yes для блокировки MITM ([ece9090](https://github.com/samosvalishe/turn-proxy-android/commit/ece90902f731a2db2efda676d33ee209cd36237e))
* **ui:** плавный переход состояний главной кнопки ([7708415](https://github.com/samosvalishe/turn-proxy-android/commit/77084151f649db1d02f9beb8a76f450b3a47529a))
* **wg:** MTU 1280 константой транспорта + MSS clamp на сервере ([f208597](https://github.com/samosvalishe/turn-proxy-android/commit/f208597cdea3c096e94a026ea3524eb5940d0650))
* временный блок socks 5 hotspot ([1460bfb](https://github.com/samosvalishe/turn-proxy-android/commit/1460bfb59f6f749e2dbb3be08b04f4d7a50e69f5))

## [3.4.2](https://github.com/samosvalishe/turn-proxy-android/compare/v3.4.1...v3.4.2) (2026-07-13)


### Fixes

* **captcha:** -platform mobile и WebView для ручной капчи ([e3ca640](https://github.com/samosvalishe/turn-proxy-android/commit/e3ca6407396f9c137a3ed9e357e63fec3cd6cd2c))

## [3.4.1](https://github.com/samosvalishe/turn-proxy-android/compare/v3.4.0...v3.4.1) (2026-07-12)


### Fixes

* **captcha:** адаптировать авторешение под SPA-капчу VK, обновление бинарника ([8c06c7e](https://github.com/samosvalishe/turn-proxy-android/commit/8c06c7ef99a530d8cccbea57e3e51ed7180357cb))
* формат телеграм поста ([bc9e459](https://github.com/samosvalishe/turn-proxy-android/commit/bc9e459b08cae003d6694c8e5d9eb39420c11cbf))

## [3.4.0](https://github.com/samosvalishe/turn-proxy-android/compare/v3.3.2...v3.4.0) (2026-07-08)


### Features

* add Russian preset and .srs rules to split tunneling ([9383401](https://github.com/samosvalishe/turn-proxy-android/commit/9383401188e3e65fc685c63f6ad3e66bb1c4b2d0))
* обновление бинарников ([28fed63](https://github.com/samosvalishe/turn-proxy-android/commit/28fed63359894d0c076fbf9856b779fc0092fbe4))


### Fixes

* **server:** диагностика скипов рестарта и гонка перезапуска прокси ([8e56023](https://github.com/samosvalishe/turn-proxy-android/commit/8e560234492d495191b20c09c657f0cf6efd0718))
* unit ([2e310b2](https://github.com/samosvalishe/turn-proxy-android/commit/2e310b27155a349a99dac8b90dd9932753f96395))
