import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Ad types supported by the plugin
 */
export enum AdType {
  INIT = 'init',
  BANNER = 'banner',
  INTERSTITIAL = 'interstitial',
  REWARDED = 'rewarded',
}

/**
 * Ad event types
 */
export enum AdEventType {
  LOADED = 'loaded',
  FAILED_TO_LOAD = 'failed_to_load',
  SHOWN = 'shown',
  FAILED_TO_SHOW = 'failed_to_show',
  DISMISSED = 'dismissed',
  CLICKED = 'clicked',
  IMPRESSION = 'impression',
  REWARDED = 'rewarded',
  LEFT_APPLICATION = 'left_application',
  RETURNED_TO_APPLICATION = 'returned_to_application',
}

/**
 * Banner positions
 */
export enum BannerPosition {
  TOP = 'top',
  BOTTOM = 'bottom',
}

/**
 * Banner sizes
 */
export interface BannerSize {
  width: number;
  height?: number; // Optional for adaptive banners
}

/**
 * Reward data for rewarded ads
 */
export interface RewardData {
  type: string;
  amount: number;
}

/**
 * Base ad event
 */
export interface AdEvent {
  adType: AdType;
  event: AdEventType;
  adUnitId?: string;
}

/**
 * Ad loaded event
 */
export interface AdLoadedEvent extends AdEvent {
  event: AdEventType.LOADED;
}

/**
 * Ad failed to load event
 */
export interface AdFailedToLoadEvent extends AdEvent {
  event: AdEventType.FAILED_TO_LOAD;
  error: {
    code: number;
    message: string;
  };
}

/**
 * Ad shown event
 */
export interface AdShownEvent extends AdEvent {
  event: AdEventType.SHOWN;
}

/**
 * Ad failed to show event
 */
export interface AdFailedToShowEvent extends AdEvent {
  event: AdEventType.FAILED_TO_SHOW;
  error: {
    code: number;
    message: string;
  };
}

/**
 * Ad dismissed event
 */
export interface AdDismissedEvent extends AdEvent {
  event: AdEventType.DISMISSED;
}

/**
 * Ad clicked event
 */
export interface AdClickedEvent extends AdEvent {
  event: AdEventType.CLICKED;
}

/**
 * Ad impression event
 */
export interface AdImpressionEvent extends AdEvent {
  event: AdEventType.IMPRESSION;
}

/**
 * Rewarded ad reward event
 */
export interface AdRewardedEvent extends AdEvent {
  event: AdEventType.REWARDED;
  reward: RewardData;
}

/**
 * Union type for all ad events
 */
export type YandexAdEvent =
  | AdLoadedEvent
  | AdFailedToLoadEvent
  | AdShownEvent
  | AdFailedToShowEvent
  | AdDismissedEvent
  | AdClickedEvent
  | AdImpressionEvent
  | AdRewardedEvent;

/**
 * Init options
 */
export interface InitOptions {
  /** Optional user consent for personalized ads (GDPR) */
  userConsent?: boolean;
}

/**
 * Options for loading banner ads
 */
export interface LoadBannerOptions {
  adUnitId: string;
  size: BannerSize;
  position?: BannerPosition;
}

/**
 * Options for loading interstitial ads
 */
export interface LoadInterstitialOptions {
  adUnitId: string;
}

/**
 * Options for loading rewarded ads
 */
export interface LoadRewardedOptions {
  adUnitId: string;
}

/**
 * Generic result interface
 */
export interface AdResult {
  success: boolean;
  message?: string;
}

/**
 * Rewarded ad result
 */
export interface RewardedAdResult extends AdResult {
  rewarded?: boolean;
  reward?: RewardData;
}

/**
 * Main plugin interface
 */
export interface YandexAdsPlugin {
  /**
   * Initialize Yandex Mobile Ads SDK
   * Must be called before any other ad operations
   * @param options - Initialization options
   * @returns Promise that resolves when SDK is initialized
   */
  init(options?: InitOptions): Promise<AdResult>;

  /**
   * Load a banner ad
   * @param options - Banner configuration
   * @returns Promise that resolves when banner is loaded
   */
  loadBanner(options: LoadBannerOptions): Promise<AdResult>;

  /**
   * Show a loaded banner ad
   * @returns Promise that resolves when banner is shown
   */
  showBanner(): Promise<AdResult>;

  /**
   * Hide the banner ad
   * @returns Promise that resolves when banner is hidden
   */
  hideBanner(): Promise<AdResult>;

  /**
   * Destroy the banner ad and free resources
   * @returns Promise that resolves when banner is destroyed
   */
  destroyBanner(): Promise<AdResult>;

  /**
   * Load an interstitial ad
   * @param options - Interstitial configuration
   * @returns Promise that resolves when interstitial is loaded
   */
  loadInterstitial(options: LoadInterstitialOptions): Promise<AdResult>;

  /**
   * Show a loaded interstitial ad
   * @returns Promise that resolves when interstitial is shown
   */
  showInterstitial(): Promise<AdResult>;

  /**
   * Load a rewarded ad
   * @param options - Rewarded ad configuration
   * @returns Promise that resolves when rewarded ad is loaded
   */
  loadRewarded(options: LoadRewardedOptions): Promise<AdResult>;

  /**
   * Show a loaded rewarded ad
   * @returns Promise that resolves when rewarded ad is shown
   */
  showRewarded(): Promise<RewardedAdResult>;

  /**
   * Add a listener for ad events
   * This provides detailed callbacks for all ad lifecycle events
   * @param eventName - Always 'adEvent'
   * @param listenerFunc - Callback function that receives ad events
   * @returns Promise with listener handle for cleanup
   */
  addListener(
    eventName: 'adEvent',
    listenerFunc: (event: YandexAdEvent) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  /**
   * Remove all listeners
   */
  removeAllListeners(): Promise<void>;
}
