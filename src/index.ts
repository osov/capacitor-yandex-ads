import { registerPlugin } from '@capacitor/core';

import type { YandexAdsPlugin } from './definitions';

const YandexAds = registerPlugin<YandexAdsPlugin>('YandexAds', {
  web: () => import('./web').then(m => new m.YandexAdsWeb()),
});

export * from './definitions';
export { YandexAds };
