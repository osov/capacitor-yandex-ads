import { WebPlugin } from '@capacitor/core';

import type {
  YandexAdsPlugin,
  InitOptions,
  AdLoadedResult,
  AdResult,
  LoadBannerOptions,
  LoadInterstitialOptions,
  LoadRewardedOptions,
  RewardedAdResult,
} from './definitions';

// implements обязателен: без него расхождение заглушки с интерфейсом плагина
// компилятор не поймает, и web-сборка молча теряла бы методы.
export class YandexAdsWeb extends WebPlugin implements YandexAdsPlugin {
  async init(_options?: InitOptions): Promise<AdResult> {
    console.warn('YandexAds: init() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async loadBanner(_options: LoadBannerOptions): Promise<AdResult> {
    console.warn('YandexAds: loadBanner() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async showBanner(): Promise<AdResult> {
    console.warn('YandexAds: showBanner() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async hideBanner(): Promise<AdResult> {
    console.warn('YandexAds: hideBanner() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async destroyBanner(): Promise<AdResult> {
    console.warn('YandexAds: destroyBanner() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async loadInterstitial(_options: LoadInterstitialOptions): Promise<AdResult> {
    console.warn(
      'YandexAds: loadInterstitial() is not available on web platform',
    );
    return { success: false, message: 'Not available on web' };
  }

  async showInterstitial(): Promise<AdResult> {
    console.warn(
      'YandexAds: showInterstitial() is not available on web platform',
    );
    return { success: false, message: 'Not available on web' };
  }

  async loadRewarded(_options: LoadRewardedOptions): Promise<AdResult> {
    console.warn('YandexAds: loadRewarded() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async showRewarded(): Promise<RewardedAdResult> {
    console.warn('YandexAds: showRewarded() is not available on web platform');
    return { success: false, message: 'Not available on web' };
  }

  async isInterstitialLoaded(): Promise<AdLoadedResult> {
    return { loaded: false };
  }

  async isRewardedLoaded(): Promise<AdLoadedResult> {
    return { loaded: false };
  }

  async destroyInterstitial(): Promise<AdResult> {
    return { success: false, message: 'Not available on web' };
  }

  async destroyRewarded(): Promise<AdResult> {
    return { success: false, message: 'Not available on web' };
  }
}
