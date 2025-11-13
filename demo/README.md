# Yandex Ads Plugin Demo App

This is a demo application for testing the `capacitor-yandex-ads` plugin on Android and iOS devices.

## Features

- ✅ Initialize Yandex Mobile Ads SDK
- ✅ Test Banner Ads (with position control)
- ✅ Test Interstitial Ads
- ✅ Test Rewarded Ads
- ✅ Real-time event logging
- ✅ Live status indicators
- ✅ Demo ad unit IDs pre-configured

## Setup

### 1. Install Dependencies

```bash
cd demo
npm install
```

### 2. Build the App

```bash
npm run build
```

### 3. Add Platforms

#### Android

```bash
npx cap add android
npx cap sync
```

#### iOS

```bash
npx cap add ios
npx cap sync
cd ios/App
pod install
cd ../..
```

## Running the Demo

### Android

```bash
npx cap open android
```

Then run the app from Android Studio.

### iOS

```bash
npx cap open ios
```

Then run the app from Xcode.

### Development (Browser)

For quick UI testing (ads won't work, but you can test the interface):

```bash
npm run dev
```

Open http://localhost:3000 in your browser.

## Testing Instructions

### 1. Initialize SDK

- Click **"Initialize SDK"** button
- Wait for success confirmation in the log
- Status should change to "Initialized"

### 2. Test Banner Ads

1. Select position (Top or Bottom)
2. Click **"Load Banner"**
3. Wait for "Loaded" status
4. Click **"Show Banner"** to display it
5. Click **"Hide Banner"** to hide (keeps it loaded)
6. Click **"Destroy"** to completely remove it

### 3. Test Interstitial Ads

1. Click **"Load"** under Interstitial section
2. Wait for "Loaded" status
3. Click **"Show"** to display full-screen ad
4. After dismissing, reload before showing again

### 4. Test Rewarded Ads

1. Click **"Load"** under Rewarded section
2. Wait for "Loaded" status
3. Click **"Show"** to display rewarded ad
4. Complete the ad to receive reward
5. Check log for reward details

## Demo Ad Unit IDs

These are Yandex's official demo ad units for testing:

- **Banner:** `demo-banner-yandex`
- **Interstitial:** `demo-interstitial-yandex`
- **Rewarded:** `demo-rewarded-yandex`

## Event Log

The event log shows:
- 📡 All ad lifecycle events (loaded, shown, dismissed, etc.)
- ✓ Success messages in green
- ✗ Error messages in red
- ⚠️ Warning messages in yellow
- 🎁 Reward notifications

## Troubleshooting

### Ads Not Loading

1. **Check initialization:** Make sure SDK is initialized first
2. **Check ad unit IDs:** Use demo IDs for testing
3. **Check internet:** Ads require network connection
4. **Check logs:** Look for error messages in the event log

### Android Build Issues

1. Make sure Android SDK is properly installed
2. Check `android/build.gradle` has correct settings
3. Run `npx cap sync android`

### iOS Build Issues

1. Make sure CocoaPods is installed: `sudo gem install cocoapods`
2. Run `pod install` in `ios/App` directory
3. Check that YandexMobileAds pod is installed
4. Run `npx cap sync ios`

### Web Platform

Ads do not work on web platform. The plugin returns mock responses with error messages. Use Android or iOS for actual ad testing.

## Plugin Source

The demo app uses the local plugin from the parent directory (`../`). Any changes to the plugin source will be reflected after:

```bash
npm run build  # in parent directory
npx cap sync   # in demo directory
```

## File Structure

```
demo/
├── src/
│   └── app.ts           # Main app logic
├── www/
│   └── index.html       # UI and styles
├── capacitor.config.ts  # Capacitor configuration
├── package.json         # Dependencies
├── tsconfig.json        # TypeScript config
├── vite.config.ts       # Build config
└── README.md           # This file
```

## Development Workflow

1. Make changes to plugin source in `../src/`
2. Build plugin: `cd .. && npm run build`
3. Sync changes: `cd demo && npx cap sync`
4. Test on device

## Support

For issues with the plugin, visit:
https://github.com/osov/capacitor-yandex-ads/issues

## License

MIT
