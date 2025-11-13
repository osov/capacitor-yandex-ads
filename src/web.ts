import { WebPlugin } from '@capacitor/core';

import type {
  InitOptions,
  AdResult,
  LoadBannerOptions,
  LoadInterstitialOptions,
  LoadRewardedOptions,
  RewardedAdResult,
} from './definitions';

export class YandexAdsWeb extends WebPlugin {
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
}
