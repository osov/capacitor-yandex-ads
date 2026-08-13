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
  s.swift_version = '5.9'
end
