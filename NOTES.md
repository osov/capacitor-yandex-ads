# Что было не так и чего не хватает

Разбор по состоянию на август 2026, после интеграции плагина в игру «Морской бой».
Верхняя часть — уже исправлено в этом репозитории, нижняя — то, что осталось.

## Переход на Yandex Mobile Ads SDK 8 (v0.2.0)

Было: Android `com.yandex.android:mobileads:5.4.0`, iOS `YandexMobileAds ~> 6.0`.
Стало: Android **8.3.0**, iOS **~> 8.3**. Обе платформы переписаны — в SDK 8
переименована точка входа и изменился способ загрузки объявлений.

Требования SDK 8: Android — `compileSdk 35+`, `minSdk 21+`, AGP 8.7+;
iOS — Xcode 16.4+, deployment target 13.0.

### Что изменилось в API (Android)

| SDK 5–7 | SDK 8 |
| --- | --- |
| `MobileAds.initialize/setUserConsent/enableLogging` | `YandexAds.` то же самое |
| `MobileAds.setAgeRestrictedUser(b)` | `YandexAds.setAgeRestricted(b)` |
| `AdRequestConfiguration.Builder(unitId)` | класса нет: id блока переехал в `AdRequest.Builder(unitId)` |
| `loader.setAdLoadListener(l); loader.loadAd(cfg)` | `loader.loadAd(AdRequest, listener)` — слушатель на каждую загрузку |
| `BannerAdView.setAdUnitId(id)` | id блока в `AdRequest`, свойства у view больше нет |
| `BannerAdSize.stickySize/fixedSize/inlineSize` | `BannerAdSize.sticky/fixed/inline`, все требуют `Context` |
| `BannerAdEventListener.onLeftApplication/onReturnedToApplication` | колбэков больше нет |
| `new InterstitialAd(activity)` | класс стал интерфейсом, создаётся только загрузчиком |

Важно: **документация Яндекса отстаёт от артефакта.** Страницы «Android SDK 8»
всё ещё описывают `AdRequestConfiguration` и `setAdLoadListener`, которых в
8.3.0 уже нет. Сверялись напрямую с классами из `mobileads-8.3.0.aar`.

### Что изменилось в API (iOS)

Префикс `YMA` убран, API стал Swift-нативным:

| SDK 6–7 | SDK 8 |
| --- | --- |
| `YMAMobileAds.initializeSDK()` | `YandexAds.initializeSDK(completionHandler:)` |
| `YMAAdRequestConfiguration(adUnitID:)` | `AdRequest(adUnitID:)` |
| делегат загрузчика `YMAInterstitialAdLoaderDelegate` | completion-хендлер `loadAd(with:) { Result<InterstitialAd, Error> }` |
| `YMAInterstitialAdDelegate` | `InterstitialAdDelegate`, метод `interstitialAd(_:didFailToShow:)` |
| `YMAAdView` + `YMABannerAdSize` | `BannerAdView(adSize:)` + `BannerAdSize.sticky(containerWidth:)` |
| `id<YMAReward>` | `Reward` |

Переход на completion-хендлеры попутно снял старую проблему с `getSavedCall()`:
вызов JS-стороны теперь захватывается замыканием загрузки, и параллельные
загрузки не могут разрешить чужой промис.

### Совместимость с AppMetrica

- **Android:** Yandex Ads 8.x требует AppMetrica **8.3.0+** (в пределах одной
  мажорной версии). В `capacitor-app-metrica` поднято до 8.5.0.
- **iOS:** `YandexMobileAds 8.3.0` тянет `AppMetricaCore ~> 6.5.0`, а зонтичный
  под `AppMetricaAnalytics 6.6.0` прибивает `AppMetricaCore = 6.6.0` — это
  конфликт, `pod install` упал бы. Поэтому плагин аналитики теперь зависит от
  `AppMetricaCore ~> 6.5` напрямую (Swift-код и так импортирует только его).

### Новое в API плагина

`isInterstitialLoaded()`, `isRewardedLoaded()`, `destroyInterstitial()`,
`destroyRewarded()`; в `InitOptions` добавлены `ageRestrictedUser`,
`locationTracking`, `enableLogging`. Из `AdEventType` убраны
`left_application` и `returned_to_application` — SDK 8 их больше не шлёт.

`showInterstitial()` теперь отвечает по факту показа (из `onAdShown` /
`onAdFailedToShow`), а не сразу после вызова `show()`.

### Проверка

Android собран и скомпилирован против 8.3.0 в реальном приложении
(`assembleGplayDebug`, чистая сборка). **iOS не компилировался** — нужен macOS.

## Исправлено ранее

### 1. `showRewarded()` возвращал неверный результат (Android + iOS) — критично

Метод запускал ролик и резолвился **через 100 мс** по таймеру, проверяя
`lastReward`. Награда же приходит в `onRewarded`/`didReward` в конце просмотра,
то есть через десятки секунд. На практике вызов почти всегда возвращал
`rewarded: false`, даже когда игрок честно досмотрел ролик.

Хуже того, результат нельзя было отличить от «игрок закрыл ролик досрочно»: и то
и другое приходило как `success: true, rewarded: false`.

Теперь вызов удерживается (`keepAlive`) и отвечает из `onAdDismissed` /
`rewardedAdDidDismiss` — к этому моменту исход известен точно. Контракт:

| Ответ | Что произошло | Что делать вызывающему |
| --- | --- | --- |
| `success: false` | ролик не показали | попытку **не** сжигать |
| `success: true, rewarded: false` | показали, игрок прервал | попытка сгорает, награды нет |
| `success: true, rewarded: true` | досмотрел | выдать награду |

Дополнительно: `lastReward` сбрасывается в начале каждого показа (иначе награда
от прошлого ролика могла засчитаться следующему), а повторный вызов
`showRewarded()` корректно закрывает предыдущее висящее обещание.

### 2. iOS: промисы загрузки разрешались чужими результатами — критично

Загрузка сохраняла вызов через `bridge?.saveCall(call)`, а делегаты доставали
его через `bridge?.getSavedCall()` — **без идентификатора**. Этот метод отдаёт
произвольный сохранённый вызов, поэтому при одновременной загрузке (типичный
сценарий: на старте приложение греет interstitial и rewarded сразу) промис
`loadInterstitial()` мог получить результат баннера, а один из вызовов —
не разрешиться никогда.

Теперь идентификатор каждого отложенного вызова хранится в своём поле
(`interstitialLoadCallId` и т.д.), а ответ идёт адресно через
`bridge?.savedCall(withID:)`.

### 3. iOS: загрузчики уничтожались до ответа делегата — критично

`YMAInterstitialAdLoader` / `YMARewardedAdLoader` создавались как локальные
переменные внутри блока. Делегат в них — слабая ссылка, самих загрузчиков никто
не держал, поэтому ARC освобождал их сразу после выхода из блока, и колбэк
загрузки мог не прийти вовсе (гонка, зависит от таймингов сети). Теперь
загрузчики хранятся полями плагина.

### 4. `InitOptions.userConsent` был объявлен, но не работал

В `definitions.ts` описан флаг согласия на персонализацию (GDPR), но ни Android,
ни iOS его не читали — параметр молча игнорировался. Теперь оба вызывают
`MobileAds.setUserConsent` / `YMAMobileAds.setUserConsent` до инициализации SDK.

### 5. Метаданные пакета не давали установить плагин из git

- `name` был `yandex-ads`, а репозиторий называется `capacitor-yandex-ads` —
  после `npm i github:osov/capacitor-yandex-ads` пакет вставал под именем
  `yandex-ads`, и документированный импорт не работал.
- `repository.url` указывал на несуществующий `github.com/osova/yandex-ads`.
- В `files` заявлен `dist/`, но он в `.gitignore` и собирался только скриптом
  `prepublishOnly`, который при установке из git **не запускается**. В итоге
  пакет приезжал без JS вообще: `import { YandexAds } from '...'` падал, и
  приходилось объявлять плагин вручную через `registerPlugin('YandexAds')`.

Добавлен скрипт `prepare` — npm выполняет его при установке из git-ссылки,
поэтому `dist/` теперь собирается автоматически.

## Чего не хватает

### Обязательное для публикации в App Store

- **Нет запроса ATT.** Yandex Mobile Ads на iOS использует IDFA; без
  `ATTrackingManager.requestTrackingAuthorization` до `initializeSDK()` реклама
  работает в неперсонализированном режиме, а ревью Apple требует, чтобы запрос
  показывался. Сейчас приложение обязано делать это само, и в плагине нет метода
  вроде `requestTrackingAuthorization()`.
- **Нет списка SKAdNetwork.** В `README` не сказано, что в `Info.plist`
  приложения нужен блок `SKAdNetworkItems` со списком идентификаторов Яндекса —
  без него атрибуция установок не работает.

### Пробелы в API

- **Нет `removeAllListeners()` в нативной части** (в TS-интерфейсе объявлен —
  базовый `Plugin` его закрывает, но это стоит проверить тестом).
- **Медиация не настраивается.** В Defold-версии игры использовалась медиация
  (`is_mediation: true`); плагин никак не позволяет ей управлять.
- **`notInitialized()` блокирует все методы** до успешного `init()`. В SDK 8
  библиотека поднимается сама при старте процесса, так что осечка `init()` без
  нужды выключает рекламу. Стоит либо снять проверку, либо не считать её
  фатальной.

### Прочее

- **В `src/` лежат скомпилированные `.js` рядом с `.ts`** (`definitions.js`,
  `index.js`, `web.js`) — артефакты сборки в исходниках, их стоит удалить и
  добавить в `.gitignore`.
- **Нет тестов.** `ios/PluginTests/YandexAdsTests.swift` — заготовка из шаблона.
- **`YandexAdsWeb` не объявляет `implements YandexAdsPlugin`.** Сегодня все
  методы на месте, но следующий метод в `definitions.ts` молча не появится на
  web. В `capacitor-app-metrica` объявление есть — стоит выровнять.
- **`BannerSize` не документирует единицы.** Android получает dp
  (`BannerAdSize.fixed(context, …)`), iOS — points; значения совпадают, но
  вызывающий, передавший пиксели устройства, получит баннер не того размера.
- **Комментарий в `android/build.gradle` про AGP 8.7+** расходится с
  `buildscript`, где закреплён AGP 8.1.4. Метаданные AAR ничего не требуют, так
  что это неточность комментария, а не поломка.
