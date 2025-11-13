import { YandexAds, AdType, AdEventType, YandexAdEvent, BannerPosition } from '@osov/capacitor-yandex-ads';

// State management
const state = {
  isInitialized: false,
  bannerLoaded: false,
  interstitialLoaded: false,
  rewardedLoaded: false,
};

// DOM elements
const elements = {
  // Status
  sdkStatus: document.getElementById('sdk-status') as HTMLElement,
  bannerStatus: document.getElementById('banner-status') as HTMLElement,
  interstitialStatus: document.getElementById('interstitial-status') as HTMLElement,
  rewardedStatus: document.getElementById('rewarded-status') as HTMLElement,

  // Buttons
  btnInit: document.getElementById('btn-init') as HTMLButtonElement,
  btnLoadBanner: document.getElementById('btn-load-banner') as HTMLButtonElement,
  btnShowBanner: document.getElementById('btn-show-banner') as HTMLButtonElement,
  btnHideBanner: document.getElementById('btn-hide-banner') as HTMLButtonElement,
  btnDestroyBanner: document.getElementById('btn-destroy-banner') as HTMLButtonElement,
  btnLoadInterstitial: document.getElementById('btn-load-interstitial') as HTMLButtonElement,
  btnShowInterstitial: document.getElementById('btn-show-interstitial') as HTMLButtonElement,
  btnLoadRewarded: document.getElementById('btn-load-rewarded') as HTMLButtonElement,
  btnShowRewarded: document.getElementById('btn-show-rewarded') as HTMLButtonElement,
  btnClearLog: document.getElementById('btn-clear-log') as HTMLButtonElement,

  // Inputs
  bannerUnitId: document.getElementById('banner-unit-id') as HTMLInputElement,
  bannerPosition: document.getElementById('banner-position') as HTMLSelectElement,
  interstitialUnitId: document.getElementById('interstitial-unit-id') as HTMLInputElement,
  rewardedUnitId: document.getElementById('rewarded-unit-id') as HTMLInputElement,

  // Log
  logEntries: document.getElementById('log-entries') as HTMLElement,
};

// Logging
function log(message: string, type: 'info' | 'success' | 'error' | 'warning' = 'info') {
  const time = new Date().toLocaleTimeString('en-US', { hour12: false });
  const entry = document.createElement('div');
  entry.className = `log-entry ${type}`;
  entry.innerHTML = `<span class="log-time">[${time}]</span><span>${message}</span>`;
  elements.logEntries.appendChild(entry);
  elements.logEntries.scrollTop = elements.logEntries.scrollHeight;
}

// Status updates
function updateStatus(element: HTMLElement, text: string, type: 'success' | 'error' | 'warning' | 'default' = 'default') {
  element.textContent = text;
  element.className = 'status-value';
  if (type !== 'default') {
    element.classList.add(type);
  }
}

// Initialize SDK
async function initializeSDK() {
  try {
    log('Initializing Yandex Mobile Ads SDK...', 'info');
    elements.btnInit.disabled = true;

    const result = await YandexAds.init();

    if (result.success) {
      state.isInitialized = true;
      updateStatus(elements.sdkStatus, 'Initialized', 'success');
      log('✓ SDK initialized successfully', 'success');
      enableButtons();
    } else {
      updateStatus(elements.sdkStatus, 'Failed', 'error');
      log(`✗ SDK initialization failed: ${result.message}`, 'error');
      elements.btnInit.disabled = false;
    }
  } catch (error) {
    updateStatus(elements.sdkStatus, 'Error', 'error');
    log(`✗ SDK initialization error: ${error}`, 'error');
    elements.btnInit.disabled = false;
  }
}

// Load Banner
async function loadBanner() {
  try {
    const adUnitId = elements.bannerUnitId.value;
    const position = elements.bannerPosition.value as BannerPosition;

    log(`Loading banner ad (${position})...`, 'info');
    elements.btnLoadBanner.disabled = true;

    const result = await YandexAds.loadBanner({
      adUnitId,
      size: { width: 320 }, // Adaptive height
      position,
    });

    if (result.success) {
      state.bannerLoaded = true;
      updateStatus(elements.bannerStatus, 'Loaded', 'success');
      log('✓ Banner loaded', 'success');
    } else {
      updateStatus(elements.bannerStatus, 'Failed', 'error');
      log(`✗ Banner load failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Banner load error: ${error}`, 'error');
  } finally {
    elements.btnLoadBanner.disabled = false;
  }
}

// Show Banner
async function showBanner() {
  try {
    log('Showing banner...', 'info');
    const result = await YandexAds.showBanner();

    if (result.success) {
      updateStatus(elements.bannerStatus, 'Visible', 'success');
      log('✓ Banner shown', 'success');
    } else {
      log(`✗ Show banner failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Show banner error: ${error}`, 'error');
  }
}

// Hide Banner
async function hideBanner() {
  try {
    log('Hiding banner...', 'info');
    const result = await YandexAds.hideBanner();

    if (result.success) {
      updateStatus(elements.bannerStatus, 'Hidden', 'warning');
      log('✓ Banner hidden', 'success');
    } else {
      log(`✗ Hide banner failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Hide banner error: ${error}`, 'error');
  }
}

// Destroy Banner
async function destroyBanner() {
  try {
    log('Destroying banner...', 'info');
    const result = await YandexAds.destroyBanner();

    if (result.success) {
      state.bannerLoaded = false;
      updateStatus(elements.bannerStatus, 'Not Loaded', 'default');
      log('✓ Banner destroyed', 'success');
    } else {
      log(`✗ Destroy banner failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Destroy banner error: ${error}`, 'error');
  }
}

// Load Interstitial
async function loadInterstitial() {
  try {
    const adUnitId = elements.interstitialUnitId.value;

    log('Loading interstitial ad...', 'info');
    elements.btnLoadInterstitial.disabled = true;

    const result = await YandexAds.loadInterstitial({ adUnitId });

    if (result.success) {
      state.interstitialLoaded = true;
      updateStatus(elements.interstitialStatus, 'Loaded', 'success');
      log('✓ Interstitial loaded', 'success');
    } else {
      updateStatus(elements.interstitialStatus, 'Failed', 'error');
      log(`✗ Interstitial load failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Interstitial load error: ${error}`, 'error');
  } finally {
    elements.btnLoadInterstitial.disabled = false;
  }
}

// Show Interstitial
async function showInterstitial() {
  try {
    log('Showing interstitial...', 'info');
    const result = await YandexAds.showInterstitial();

    if (result.success) {
      log('✓ Interstitial shown', 'success');
    } else {
      log(`✗ Show interstitial failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Show interstitial error: ${error}`, 'error');
  }
}

// Load Rewarded
async function loadRewarded() {
  try {
    const adUnitId = elements.rewardedUnitId.value;

    log('Loading rewarded ad...', 'info');
    elements.btnLoadRewarded.disabled = true;

    const result = await YandexAds.loadRewarded({ adUnitId });

    if (result.success) {
      state.rewardedLoaded = true;
      updateStatus(elements.rewardedStatus, 'Loaded', 'success');
      log('✓ Rewarded ad loaded', 'success');
    } else {
      updateStatus(elements.rewardedStatus, 'Failed', 'error');
      log(`✗ Rewarded ad load failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Rewarded ad load error: ${error}`, 'error');
  } finally {
    elements.btnLoadRewarded.disabled = false;
  }
}

// Show Rewarded
async function showRewarded() {
  try {
    log('Showing rewarded ad...', 'info');
    const result = await YandexAds.showRewarded();

    if (result.success) {
      log('✓ Rewarded ad shown', 'success');

      if (result.rewarded && result.reward) {
        log(
          `🎁 User earned reward: ${result.reward.amount} ${result.reward.type}`,
          'success'
        );
      } else {
        log('User did not complete the ad', 'warning');
      }
    } else {
      log(`✗ Show rewarded ad failed: ${result.message}`, 'error');
    }
  } catch (error) {
    log(`✗ Show rewarded ad error: ${error}`, 'error');
  }
}

// Clear Log
function clearLog() {
  elements.logEntries.innerHTML = '';
  log('Log cleared', 'info');
}

// Enable buttons after initialization
function enableButtons() {
  elements.btnLoadBanner.disabled = false;
  elements.btnShowBanner.disabled = false;
  elements.btnHideBanner.disabled = false;
  elements.btnDestroyBanner.disabled = false;
  elements.btnLoadInterstitial.disabled = false;
  elements.btnShowInterstitial.disabled = false;
  elements.btnLoadRewarded.disabled = false;
  elements.btnShowRewarded.disabled = false;
}

// Ad Event Handler
function handleAdEvent(event: YandexAdEvent) {
  const eventName = `[${event.adType.toUpperCase()}] ${event.event}`;

  log(`📡 Event: ${eventName}`, 'info');

  // Update status based on event
  switch (event.adType) {
    case AdType.INIT:
      if (event.event === AdEventType.LOADED) {
        updateStatus(elements.sdkStatus, 'Initialized', 'success');
      }
      break;

    case AdType.BANNER:
      if (event.event === AdEventType.LOADED) {
        state.bannerLoaded = true;
        updateStatus(elements.bannerStatus, 'Loaded', 'success');
      } else if (event.event === AdEventType.FAILED_TO_LOAD) {
        state.bannerLoaded = false;
        updateStatus(elements.bannerStatus, 'Failed', 'error');
        if (event.error) {
          log(`   Error: ${event.error.message} (${event.error.code})`, 'error');
        }
      } else if (event.event === AdEventType.SHOWN) {
        updateStatus(elements.bannerStatus, 'Visible', 'success');
      } else if (event.event === AdEventType.DISMISSED) {
        updateStatus(elements.bannerStatus, 'Hidden', 'warning');
      } else if (event.event === AdEventType.CLICKED) {
        log('   User clicked on banner', 'info');
      } else if (event.event === AdEventType.IMPRESSION) {
        log('   Banner impression tracked', 'info');
      }
      break;

    case AdType.INTERSTITIAL:
      if (event.event === AdEventType.LOADED) {
        state.interstitialLoaded = true;
        updateStatus(elements.interstitialStatus, 'Loaded', 'success');
      } else if (event.event === AdEventType.FAILED_TO_LOAD) {
        state.interstitialLoaded = false;
        updateStatus(elements.interstitialStatus, 'Failed', 'error');
        if (event.error) {
          log(`   Error: ${event.error.message} (${event.error.code})`, 'error');
        }
      } else if (event.event === AdEventType.SHOWN) {
        updateStatus(elements.interstitialStatus, 'Showing', 'success');
      } else if (event.event === AdEventType.DISMISSED) {
        state.interstitialLoaded = false;
        updateStatus(elements.interstitialStatus, 'Not Loaded', 'default');
        log('   Interstitial dismissed. Consider preloading next ad.', 'info');
      } else if (event.event === AdEventType.CLICKED) {
        log('   User clicked on interstitial', 'info');
      } else if (event.event === AdEventType.IMPRESSION) {
        log('   Interstitial impression tracked', 'info');
      }
      break;

    case AdType.REWARDED:
      if (event.event === AdEventType.LOADED) {
        state.rewardedLoaded = true;
        updateStatus(elements.rewardedStatus, 'Loaded', 'success');
      } else if (event.event === AdEventType.FAILED_TO_LOAD) {
        state.rewardedLoaded = false;
        updateStatus(elements.rewardedStatus, 'Failed', 'error');
        if (event.error) {
          log(`   Error: ${event.error.message} (${event.error.code})`, 'error');
        }
      } else if (event.event === AdEventType.SHOWN) {
        updateStatus(elements.rewardedStatus, 'Showing', 'success');
      } else if (event.event === AdEventType.REWARDED) {
        if (event.reward) {
          log(
            `   🎁 Reward earned: ${event.reward.amount} ${event.reward.type}`,
            'success'
          );
        }
      } else if (event.event === AdEventType.DISMISSED) {
        state.rewardedLoaded = false;
        updateStatus(elements.rewardedStatus, 'Not Loaded', 'default');
        log('   Rewarded ad dismissed. Consider preloading next ad.', 'info');
      } else if (event.event === AdEventType.CLICKED) {
        log('   User clicked on rewarded ad', 'info');
      } else if (event.event === AdEventType.IMPRESSION) {
        log('   Rewarded ad impression tracked', 'info');
      }
      break;
  }
}

// Setup Event Listeners
function setupEventListeners() {
  // Initialize
  elements.btnInit.addEventListener('click', initializeSDK);

  // Banner
  elements.btnLoadBanner.addEventListener('click', loadBanner);
  elements.btnShowBanner.addEventListener('click', showBanner);
  elements.btnHideBanner.addEventListener('click', hideBanner);
  elements.btnDestroyBanner.addEventListener('click', destroyBanner);

  // Interstitial
  elements.btnLoadInterstitial.addEventListener('click', loadInterstitial);
  elements.btnShowInterstitial.addEventListener('click', showInterstitial);

  // Rewarded
  elements.btnLoadRewarded.addEventListener('click', loadRewarded);
  elements.btnShowRewarded.addEventListener('click', showRewarded);

  // Log
  elements.btnClearLog.addEventListener('click', clearLog);

  // Yandex Ads Events
  YandexAds.addListener('adEvent', handleAdEvent);

  log('Event listeners initialized', 'success');
}

// Initialize app
function init() {
  log('Demo app initialized. Platform: ' + navigator.platform, 'info');

  // Disable buttons until SDK is initialized
  elements.btnLoadBanner.disabled = true;
  elements.btnShowBanner.disabled = true;
  elements.btnHideBanner.disabled = true;
  elements.btnDestroyBanner.disabled = true;
  elements.btnLoadInterstitial.disabled = true;
  elements.btnShowInterstitial.disabled = true;
  elements.btnLoadRewarded.disabled = true;
  elements.btnShowRewarded.disabled = true;

  setupEventListeners();
}

// Start app when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
