import { registerPlugin } from '@capacitor/core';
const YandexAds = registerPlugin('YandexAds', {
    web: () => import('./web').then(m => new m.YandexAdsWeb()),
});
export * from './definitions';
export { YandexAds };
