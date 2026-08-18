require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  # Имя обязано совпадать с тем, что генерирует Capacitor из имени пакета
  # (capacitor-yandex-ads -> CapacitorYandexAds), иначе pod install не найдёт
  # спецификацию. Заодно снимает затенение типа YandexAds из SDK.
  s.name = 'CapacitorYandexAds'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => s.version.to_s }
  s.source_files = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target  = '13.0'
  s.dependency 'Capacitor'
  # SDK 8 требует Xcode 16.4+. Он же тянет AppMetricaCore ~> 6.5.0, поэтому
  # плагин аналитики не должен требовать более новую мажорную версию AppMetrica.
  s.dependency 'YandexMobileAds', '~> 8.3'
  # Медиация. Без адаптеров у SDK остаётся один источник спроса - собственный
  # RTB Яндекса, и на одном рекламном блоке аукцион раз за разом выигрывает тот
  # же креатив. Набор сетей - как в Podfile Defold-расширения игры.
  #
  # Версии не закрепляем: адаптер нумеруется по версии SDK своей сети, а
  # совместимость с YandexMobileAds каждый из них объявляет сам - CocoaPods
  # подберёт набор под резолвнутую версию SDK.
  #
  # Приложению с Google-адаптером обязателен GADApplicationIdentifier в
  # Info.plist: без него Google Mobile Ads SDK падает на старте.
  s.dependency 'GoogleYandexMobileAdsAdapters'
  s.dependency 'UnityAdsYandexMobileAdsAdapters'
  s.dependency 'AppLovinYandexMobileAdsAdapters'
  s.dependency 'IronSourceYandexMobileAdsAdapters'
  s.dependency 'MintegralYandexMobileAdsAdapters'
  s.dependency 'MyTargetYandexMobileAdsAdapters'
  s.dependency 'VungleYandexMobileAdsAdapters'
  s.dependency 'BigoADSYandexMobileAdsAdapters'
  s.swift_version = '5.9'
end
