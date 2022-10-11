import { WebPlugin } from '@capacitor/core';

import type { YandexAdsPlugin } from './definitions';

export class YandexAdsWeb extends WebPlugin implements YandexAdsPlugin {
  async init(): Promise<any> { }

  async initInter(options: { id: string }): Promise<any> { }
  async initReward(options: { id: string }): Promise<any> { }
  async initBanner(options: { id: string }): Promise<any> { }


  async loadBanner(): Promise<any> { }
  async showBanner(): Promise<any> { }
  async hideBanner(): Promise<any> { }
  async showAds(): Promise<any> { }
  async showReward(): Promise<any> { }
}
