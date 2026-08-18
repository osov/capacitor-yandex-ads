# Capacitor Yandex Ads

Capacitor plugin for integrating Yandex Mobile Ads SDK into your Ionic/Capacitor applications. Supports Banner, Interstitial, and Rewarded ads on Android and iOS.

## Features

- ✅ Banner Ads with customizable size and position
- ✅ Interstitial Ads
- ✅ Rewarded Ads
- ✅ Event-based callbacks for all ad lifecycle events
- ✅ Promise-based API for async/await support
- ✅ TypeScript support with full type definitions
- ✅ Android & iOS support
- ✅ Mediation out of the box (Google, Unity Ads, AppLovin, ironSource, Mintegral, BigoADS, Vungle, myTarget)

## Installation

```bash
npm install @osov/capacitor-yandex-ads
npx cap sync
```

## Configuration

### Android

The plugin uses Yandex Mobile Ads SDK **8.3.0** (`com.yandex.android:mobileads:8.3.0`)
and ships the mediation adapters (see [Mediation](#mediation) — that section lists the
two manifest entries and two Maven repositories the app must add).

Requirements: `compileSdk` 35+, `minSdk` 21+, Android Gradle Plugin 8.7+.
If the app also uses AppMetrica, its version must be **8.3.0+** — the ads SDK
depends on `io.appmetrica.analytics:analytics:[8.3.0,9.0.0)`.

### iOS

The plugin automatically adds the YandexMobileAds SDK dependency (`~> 8.3`) and the
mediation adapter pods via CocoaPods (see [Mediation](#mediation) — the app must add
`GADApplicationIdentifier` to `Info.plist`).

Requirements: Xcode 16.4+, deployment target iOS 13.0+.
The SDK pins the AppMetrica pods to `~> 6.5.0`, so any AppMetrica plugin in the
same app must stay inside that range.

### Mediation

The plugin depends on the Yandex mediation adapters, so the SDK can fill an ad
slot from other networks instead of Yandex demand only. This matters most for
banners: with a single demand source the same creative tends to win the auction
over and over, so a banner that reloads every 30 seconds still shows the same
picture.

Bundled adapters (versioned after the network SDK they wrap, last digit is the
adapter revision):

| Network | Android | iOS |
| --- | --- | --- |
| Google (AdMob) | `mobileads-google:25.2.0.2` | `GoogleYandexMobileAdsAdapters` |
| Unity Ads | `mobileads-unityads:4.17.0.3` | `UnityAdsYandexMobileAdsAdapters` |
| AppLovin | `mobileads-applovin:13.6.3.0` | `AppLovinYandexMobileAdsAdapters` |
| ironSource | `mobileads-ironsource:9.2.0.3` | `IronSourceYandexMobileAdsAdapters` |
| Mintegral | `mobileads-mintegral:17.0.41.3` | `MintegralYandexMobileAdsAdapters` |
| BigoADS | `mobileads-bigoads:5.7.0.3` | `BigoADSYandexMobileAdsAdapters` |
| Vungle | `mobileads-vungle:7.7.0.3` | `VungleYandexMobileAdsAdapters` |
| myTarget | `mobileads-mytarget:5.45.3.4` | `MyTargetYandexMobileAdsAdapters` |

Enabling a network on the Yandex side (which adapter actually gets a bid) is done
in the Partner Interface, not in code.

#### Android — required app-side setup

`ironSource` and `Mintegral` are not published to Maven Central. The plugin
declares their repositories for its own module, but Gradle resolves the app's
classpath with the **app's** repositories, so the root `android/build.gradle`
needs them too:

```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url "https://android-sdk.is.com/" }
        maven { url "https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea" }
    }
}
```

`AndroidManifest.xml` must carry the AdMob app id — the Google Mobile Ads SDK
throws on startup without it — and the AppLovin SDK key:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
<meta-data
    android:name="applovin.sdk.key"
    android:value="YOUR_APPLOVIN_SDK_KEY" />
```

To drop a network without touching the plugin:

```gradle
configurations.all {
    exclude group: 'com.yandex.ads.mediation', module: 'mobileads-mintegral'
}
```

#### iOS — required app-side setup

`Info.plist` needs the AdMob app id (same reason as on Android) and the
SKAdNetwork identifier list published by Yandex
(<https://yastatic.net/pcode-static/skadnetwork/skadids.json>):

```xml
<key>GADApplicationIdentifier</key>
<string>ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY</string>
```

The plugin does not request App Tracking Transparency; without it the SDK and
the adapters run in non-personalized mode.

## Usage

### Basic Setup

```typescript
import { YandexAds, AdType, AdEventType } from '@osov/capacitor-yandex-ads';

// Initialize the SDK
async function initializeAds() {
  try {
    const result = await YandexAds.init();
    console.log('Yandex Ads initialized:', result.success);
  } catch (error) {
    console.error('Failed to initialize:', error);
  }
}

// Set up event listener for all ad events
YandexAds.addListener('adEvent', (event) => {
  console.log(`Ad Event: ${event.adType} - ${event.event}`);

  if (event.event === AdEventType.LOADED) {
    console.log('Ad loaded successfully');
  } else if (event.event === AdEventType.FAILED_TO_LOAD) {
    console.error('Ad failed to load:', event.error);
  } else if (event.event === AdEventType.REWARDED) {
    console.log('User rewarded:', event.reward);
  }
});
```

### Banner Ads

```typescript
// Load a banner ad
async function loadBanner() {
  try {
    const result = await YandexAds.loadBanner({
      adUnitId: 'YOUR_BANNER_AD_UNIT_ID',
      // Optional. width+height = fixed size, width only = sticky of that
      // width, omitted = standard 320x50 banner.
      size: {
        width: 320,
        height: 50
      },
      // 'top-left' | 'top' | 'top-right' | 'bottom-left' | 'bottom' |
      // 'bottom-right' | 'center' (default: 'bottom')
      position: 'bottom'
    });

    if (result.success) {
      console.log('Banner loaded');
    }
  } catch (error) {
    console.error('Banner load error:', error);
  }
}

// Show the banner. The position can be changed on every show, even while
// the banner is visible.
async function showBanner() {
  const result = await YandexAds.showBanner({ position: 'bottom-left' });
  console.log('Banner shown:', result.success);
}

// Whether the banner content has loaded and can be shown
async function checkBanner() {
  const { loaded } = await YandexAds.isBannerLoaded();
  console.log('Banner loaded:', loaded);
}

// Hide the banner
async function hideBanner() {
  const result = await YandexAds.hideBanner();
  console.log('Banner hidden:', result.success);
}

// Destroy the banner (free resources)
async function destroyBanner() {
  const result = await YandexAds.destroyBanner();
  console.log('Banner destroyed:', result.success);
}
```

### Interstitial Ads

```typescript
// Load an interstitial ad
async function loadInterstitial() {
  try {
    const result = await YandexAds.loadInterstitial({
      adUnitId: 'YOUR_INTERSTITIAL_AD_UNIT_ID'
    });

    if (result.success) {
      console.log('Interstitial loaded');
    }
  } catch (error) {
    console.error('Interstitial load error:', error);
  }
}

// Show the interstitial
async function showInterstitial() {
  const result = await YandexAds.showInterstitial();
  if (result.success) {
    console.log('Interstitial shown');
  } else {
    console.log('Interstitial not ready:', result.message);
  }
}

// Listen for interstitial events
YandexAds.addListener('adEvent', (event) => {
  if (event.adType === AdType.INTERSTITIAL) {
    switch (event.event) {
      case AdEventType.LOADED:
        console.log('Interstitial ready to show');
        break;
      case AdEventType.DISMISSED:
        console.log('Interstitial dismissed');
        // Load next interstitial
        loadInterstitial();
        break;
      case AdEventType.CLICKED:
        console.log('Interstitial clicked');
        break;
    }
  }
});
```

### Rewarded Ads

```typescript
// Load a rewarded ad
async function loadRewarded() {
  try {
    const result = await YandexAds.loadRewarded({
      adUnitId: 'YOUR_REWARDED_AD_UNIT_ID'
    });

    if (result.success) {
      console.log('Rewarded ad loaded');
    }
  } catch (error) {
    console.error('Rewarded ad load error:', error);
  }
}

// Show the rewarded ad
async function showRewarded() {
  const result = await YandexAds.showRewarded();

  if (result.success && result.rewarded) {
    console.log('User earned reward:', result.reward);
    // Grant reward to user
    grantReward(result.reward.amount, result.reward.type);
  } else {
    console.log('User did not complete ad');
  }
}

// Listen for rewarded ad events
YandexAds.addListener('adEvent', (event) => {
  if (event.adType === AdType.REWARDED) {
    switch (event.event) {
      case AdEventType.LOADED:
        console.log('Rewarded ad ready');
        break;
      case AdEventType.REWARDED:
        console.log('User earned reward:', event.reward);
        break;
      case AdEventType.DISMISSED:
        console.log('Rewarded ad dismissed');
        // Load next rewarded ad
        loadRewarded();
        break;
    }
  }
});
```

### Complete Example

```typescript
import { YandexAds, AdType, AdEventType, YandexAdEvent } from '@osov/capacitor-yandex-ads';

export class AdManager {
  private isInitialized = false;
  private interstitialLoaded = false;
  private rewardedLoaded = false;

  async initialize() {
    if (this.isInitialized) return;

    // Initialize SDK
    const result = await YandexAds.init();
    if (!result.success) {
      throw new Error('Failed to initialize Yandex Ads');
    }

    this.isInitialized = true;

    // Set up event listener
    YandexAds.addListener('adEvent', this.handleAdEvent.bind(this));

    // Preload ads
    await this.loadInterstitial();
    await this.loadRewarded();
    await this.loadBanner();
  }

  private handleAdEvent(event: YandexAdEvent) {
    console.log(`[${event.adType}] ${event.event}`);

    switch (event.adType) {
      case AdType.INTERSTITIAL:
        this.handleInterstitialEvent(event);
        break;
      case AdType.REWARDED:
        this.handleRewardedEvent(event);
        break;
      case AdType.BANNER:
        this.handleBannerEvent(event);
        break;
    }
  }

  private handleInterstitialEvent(event: YandexAdEvent) {
    switch (event.event) {
      case AdEventType.LOADED:
        this.interstitialLoaded = true;
        break;
      case AdEventType.FAILED_TO_LOAD:
        this.interstitialLoaded = false;
        console.error('Interstitial failed:', event.error);
        break;
      case AdEventType.DISMISSED:
        this.interstitialLoaded = false;
        // Preload next ad
        setTimeout(() => this.loadInterstitial(), 1000);
        break;
    }
  }

  private handleRewardedEvent(event: YandexAdEvent) {
    switch (event.event) {
      case AdEventType.LOADED:
        this.rewardedLoaded = true;
        break;
      case AdEventType.FAILED_TO_LOAD:
        this.rewardedLoaded = false;
        console.error('Rewarded failed:', event.error);
        break;
      case AdEventType.REWARDED:
        console.log('Grant reward:', event.reward);
        // Grant reward to user
        break;
      case AdEventType.DISMISSED:
        this.rewardedLoaded = false;
        // Preload next ad
        setTimeout(() => this.loadRewarded(), 1000);
        break;
    }
  }

  private handleBannerEvent(event: YandexAdEvent) {
    switch (event.event) {
      case AdEventType.LOADED:
        // Auto-show banner when loaded
        YandexAds.showBanner();
        break;
      case AdEventType.FAILED_TO_LOAD:
        console.error('Banner failed:', event.error);
        break;
    }
  }

  async loadInterstitial() {
    await YandexAds.loadInterstitial({
      adUnitId: 'YOUR_INTERSTITIAL_ID'
    });
  }

  async showInterstitial() {
    if (!this.interstitialLoaded) {
      console.log('Interstitial not ready');
      return false;
    }

    const result = await YandexAds.showInterstitial();
    return result.success;
  }

  async loadRewarded() {
    await YandexAds.loadRewarded({
      adUnitId: 'YOUR_REWARDED_ID'
    });
  }

  async showRewarded() {
    if (!this.rewardedLoaded) {
      console.log('Rewarded ad not ready');
      return false;
    }

    const result = await YandexAds.showRewarded();
    return result.success && result.rewarded;
  }

  async loadBanner() {
    await YandexAds.loadBanner({
      adUnitId: 'YOUR_BANNER_ID',
      size: { width: 320 },  // Adaptive height
      position: 'bottom'
    });
  }

  cleanup() {
    YandexAds.removeAllListeners();
    YandexAds.destroyBanner();
  }
}
```

## API Reference

### Methods

#### `init(options?: InitOptions): Promise<AdResult>`

Initialize the Yandex Mobile Ads SDK. Must be called before any other operations.

**Parameters:**
- `options` (optional): Initialization options
  - `userConsent?: boolean` - User consent for personalized ads (GDPR)

**Returns:** `Promise<AdResult>`

---

#### `loadBanner(options: LoadBannerOptions): Promise<AdResult>`

Load a banner ad.

**Parameters:**
- `options.adUnitId: string` - Banner ad unit ID
- `options.size?: BannerSize` - Banner size configuration
  - `width: number` - Banner width in dp
  - `height?: number` - Banner height in dp
  - width + height = fixed size, width only = sticky of that width,
    omitted = standard 320x50 banner
- `options.position?: BannerPosition` - Banner position (default: 'bottom'):
  `'top-left' | 'top' | 'top-right' | 'bottom-left' | 'bottom' | 'bottom-right' | 'center'`

**Returns:** `Promise<AdResult>`

---

#### `showBanner(options?: ShowBannerOptions): Promise<AdResult>`

Show the loaded banner ad.

**Parameters:**
- `options.position?: BannerPosition` - Optional position override. Applied on
  every show, even while the banner is visible.

---

#### `hideBanner(): Promise<AdResult>`

Hide the banner ad without destroying it.

---

#### `isBannerLoaded(): Promise<AdLoadedResult>`

Whether the banner content has loaded and can be shown.

---

#### `destroyBanner(): Promise<AdResult>`

Destroy the banner ad and free resources.

---

#### `loadInterstitial(options: LoadInterstitialOptions): Promise<AdResult>`

Load an interstitial ad.

**Parameters:**
- `options.adUnitId: string` - Interstitial ad unit ID

**Returns:** `Promise<AdResult>`

---

#### `showInterstitial(): Promise<AdResult>`

Show the loaded interstitial ad.

---

#### `loadRewarded(options: LoadRewardedOptions): Promise<AdResult>`

Load a rewarded ad.

**Parameters:**
- `options.adUnitId: string` - Rewarded ad unit ID

**Returns:** `Promise<AdResult>`

---

#### `showRewarded(): Promise<RewardedAdResult>`

Show the loaded rewarded ad.

**Returns:** `Promise<RewardedAdResult>`
- `success: boolean` - Whether the ad was shown
- `rewarded?: boolean` - Whether the user earned the reward
- `reward?: RewardData` - Reward details if earned

---

### Events

#### `addListener('adEvent', callback: (event: YandexAdEvent) => void)`

Register a listener for all ad events.

**Event Types:**
- `AdType.INIT` - SDK initialization
- `AdType.BANNER` - Banner ad events
- `AdType.INTERSTITIAL` - Interstitial ad events
- `AdType.REWARDED` - Rewarded ad events

**Event Names:**
- `AdEventType.LOADED` - Ad loaded successfully
- `AdEventType.FAILED_TO_LOAD` - Ad failed to load
- `AdEventType.SHOWN` - Ad shown to user
- `AdEventType.FAILED_TO_SHOW` - Ad failed to show
- `AdEventType.DISMISSED` - Ad dismissed by user
- `AdEventType.CLICKED` - User clicked on ad
- `AdEventType.IMPRESSION` - Ad impression tracked
- `AdEventType.REWARDED` - User earned reward (rewarded ads only)
- `AdEventType.LEFT_APPLICATION` - User left app via ad
- `AdEventType.RETURNED_TO_APPLICATION` - User returned to app

---

## TypeScript Types

```typescript
enum AdType {
  INIT = 'init',
  BANNER = 'banner',
  INTERSTITIAL = 'interstitial',
  REWARDED = 'rewarded',
}

enum AdEventType {
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

interface AdResult {
  success: boolean;
  message?: string;
}

interface RewardedAdResult extends AdResult {
  rewarded?: boolean;
  reward?: RewardData;
}

interface RewardData {
  type: string;
  amount: number;
}
```

## Migration from Previous Version

If you're upgrading from the old API, here are the key changes:

### Old API → New API

```typescript
// OLD
await YandexAds.initInter({ id: 'xxx' });
await YandexAds.showAds();

// NEW
await YandexAds.init();
await YandexAds.loadInterstitial({ adUnitId: 'xxx' });
await YandexAds.showInterstitial();

// OLD
await YandexAds.initBanner({ id: 'xxx' });
await YandexAds.loadBanner();
await YandexAds.showBanner();

// NEW
await YandexAds.init();
await YandexAds.loadBanner({
  adUnitId: 'xxx',
  size: { width: 320, height: 50 },
  position: 'bottom'
});
await YandexAds.showBanner();

// OLD
await YandexAds.initReward({ id: 'xxx' });
const result = await YandexAds.showReward();
if (result.result) { /* rewarded */ }

// NEW
await YandexAds.init();
await YandexAds.loadRewarded({ adUnitId: 'xxx' });
const result = await YandexAds.showRewarded();
if (result.rewarded) { /* grant reward */ }
```

### Event Callbacks

The new version supports event-based callbacks for detailed tracking:

```typescript
YandexAds.addListener('adEvent', (event) => {
  // Unified callback for all ad events
  console.log(`${event.adType}: ${event.event}`);

  // Handle specific events
  if (event.event === AdEventType.REWARDED) {
    grantReward(event.reward);
  }
});
```

## Testing

Use Yandex's demo ad unit IDs for testing:

- **Banner:** `demo-banner-yandex`
- **Interstitial:** `demo-interstitial-yandex`
- **Rewarded:** `demo-rewarded-yandex`

## License

MIT

## Author

osov

## Repository

https://github.com/osov/capacitor-yandex-ads
