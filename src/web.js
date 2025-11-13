import { WebPlugin } from '@capacitor/core';
export class YandexAdsWeb extends WebPlugin {
    async init(_options) {
        console.warn('YandexAds: init() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async loadBanner(_options) {
        console.warn('YandexAds: loadBanner() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async showBanner() {
        console.warn('YandexAds: showBanner() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async hideBanner() {
        console.warn('YandexAds: hideBanner() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async destroyBanner() {
        console.warn('YandexAds: destroyBanner() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async loadInterstitial(_options) {
        console.warn('YandexAds: loadInterstitial() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async showInterstitial() {
        console.warn('YandexAds: showInterstitial() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async loadRewarded(_options) {
        console.warn('YandexAds: loadRewarded() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
    async showRewarded() {
        console.warn('YandexAds: showRewarded() is not available on web platform');
        return { success: false, message: 'Not available on web' };
    }
}
