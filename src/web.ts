import { WebPlugin } from '@capacitor/core';

import type { YandexAdsPlugin } from './definitions';

export class YandexAdsWeb extends WebPlugin implements YandexAdsPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
