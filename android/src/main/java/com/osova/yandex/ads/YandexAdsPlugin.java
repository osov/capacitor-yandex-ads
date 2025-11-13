package com.osova.yandex.ads;

import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.yandex.mobile.ads.banner.AdSize;
import com.yandex.mobile.ads.banner.BannerAdEventListener;
import com.yandex.mobile.ads.banner.BannerAdView;
import com.yandex.mobile.ads.common.AdRequest;
import com.yandex.mobile.ads.common.AdRequestError;
import com.yandex.mobile.ads.common.ImpressionData;
import com.yandex.mobile.ads.common.InitializationListener;
import com.yandex.mobile.ads.common.MobileAds;
import com.yandex.mobile.ads.interstitial.InterstitialAd;
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener;
import com.yandex.mobile.ads.rewarded.Reward;
import com.yandex.mobile.ads.rewarded.RewardedAd;
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener;

@CapacitorPlugin(name = "YandexAds")
public class YandexAdsPlugin extends Plugin {
    private static final String TAG = "YandexAds";

    // SDK state
    private boolean isInitialized = false;

    // Banner
    private BannerAdView bannerAdView;
    private LinearLayout bannerLayout;
    private String bannerAdUnitId;

    // Interstitial
    private InterstitialAd interstitialAd;
    private String interstitialAdUnitId;
    private boolean isInterstitialLoaded = false;

    // Rewarded
    private RewardedAd rewardedAd;
    private String rewardedAdUnitId;
    private boolean isRewardedLoaded = false;
    private Reward lastReward;

    @Override
    public void load() {
        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            // Create banner container layout
            bannerLayout = new LinearLayout(activity);
            bannerLayout.setOrientation(LinearLayout.HORIZONTAL);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;

            activity.addContentView(bannerLayout, params);
        });
    }

    /**
     * Initialize Yandex Mobile Ads SDK
     */
    @PluginMethod
    public void init(PluginCall call) {
        if (isInitialized) {
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("message", "Already initialized");
            call.resolve(ret);
            return;
        }

        Log.d(TAG, "Initializing Yandex Mobile Ads SDK");

        MobileAds.initialize(getActivity(), new InitializationListener() {
            @Override
            public void onInitializationCompleted() {
                Log.d(TAG, "SDK initialized successfully");
                isInitialized = true;

                // Send event
                notifyAdEvent("init", "loaded", null, null, null);

                // Resolve promise
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            }
        });
    }

    /**
     * Load a banner ad
     */
    @PluginMethod
    public void loadBanner(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        JSObject sizeObj = call.getObject("size");
        if (sizeObj == null) {
            rejectMissingParameter(call, "size");
            return;
        }

        Integer width = sizeObj.getInteger("width");
        if (width == null) {
            rejectMissingParameter(call, "size.width");
            return;
        }

        Integer height = sizeObj.getInteger("height");
        String position = call.getString("position", "bottom");

        bannerAdUnitId = adUnitId;

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            try {
                // Destroy existing banner if any
                if (bannerAdView != null) {
                    bannerAdView.destroy();
                    bannerLayout.removeAllViews();
                }

                // Create new banner
                bannerAdView = new BannerAdView(activity);
                bannerAdView.setAdUnitId(adUnitId);

                // Set adaptive size
                AdSize adSize;
                if (height != null && height > 0) {
                    adSize = AdSize.flexibleSize(width, height);
                } else {
                    // Adaptive banner - height calculated automatically
                    adSize = AdSize.stickySize(width);
                }
                bannerAdView.setAdSize(adSize);

                // Update banner position
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bannerLayout.getLayoutParams();
                if ("top".equalsIgnoreCase(position)) {
                    params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    // Добавляем отступ под строку состояния
                    int statusBarHeight = 0;
                    int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                    if (resourceId > 0) {
                        statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
                    }
                    params.topMargin = statusBarHeight;
                } else {

                    params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;

                    params.topMargin = 0;
                bannerLayout.setLayoutParams(params);

                // Add to layout (initially hidden)
                bannerLayout.addView(bannerAdView);
                bannerAdView.setVisibility(View.INVISIBLE);

                // Set event listener
                bannerAdView.setBannerAdEventListener(new BannerAdEventListener() {
                    @Override
                    public void onAdLoaded() {
                        Log.d(TAG, "Banner ad loaded: " + adUnitId);
                        notifyAdEvent("banner", "loaded", adUnitId, null, null);

                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        call.resolve(ret);
                    }

                    @Override
                    public void onAdFailedToLoad(AdRequestError error) {
                        Log.e(TAG, "Banner ad failed to load: " + error.getDescription());

                        JSObject errorObj = createErrorObject(error);
                        notifyAdEvent("banner", "failed_to_load", adUnitId, errorObj, null);

                        JSObject ret = new JSObject();
                        ret.put("success", false);
                        ret.put("message", error.getDescription());
                        call.resolve(ret);
                    }

                    @Override
                    public void onAdClicked() {
                        Log.d(TAG, "Banner ad clicked");
                        notifyAdEvent("banner", "clicked", adUnitId, null, null);
                    }

                    @Override
                    public void onLeftApplication() {
                        Log.d(TAG, "Banner ad left application");
                        notifyAdEvent("banner", "left_application", adUnitId, null, null);
                    }

                    @Override
                    public void onReturnedToApplication() {
                        Log.d(TAG, "Banner ad returned to application");
                        notifyAdEvent("banner", "returned_to_application", adUnitId, null, null);
                    }

                    @Override
                    public void onImpression(@Nullable ImpressionData impressionData) {
                        Log.d(TAG, "Banner ad impression");
                        notifyAdEvent("banner", "impression", adUnitId, null, null);
                    }
                });

                // Load ad
                AdRequest adRequest = new AdRequest.Builder().build();
                bannerAdView.loadAd(adRequest);

            } catch (Exception e) {
                Log.e(TAG, "Error loading banner: " + e.getMessage());
                call.reject("Error loading banner: " + e.getMessage());
            }
        });
    }

    /**
     * Show the loaded banner ad
     */
    @PluginMethod
    public void showBanner(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        if (bannerAdView == null) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("message", "Banner not loaded");
            call.resolve(ret);
            return;
        }

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            bannerAdView.setVisibility(View.VISIBLE);
            notifyAdEvent("banner", "shown", bannerAdUnitId, null, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        });
    }

    /**
     * Hide the banner ad
     */
    @PluginMethod
    public void hideBanner(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        if (bannerAdView == null) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("message", "Banner not loaded");
            call.resolve(ret);
            return;
        }

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            bannerAdView.setVisibility(View.INVISIBLE);
            notifyAdEvent("banner", "dismissed", bannerAdUnitId, null, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        });
    }

    /**
     * Destroy the banner ad
     */
    @PluginMethod
    public void destroyBanner(PluginCall call) {
        if (bannerAdView != null) {
            AppCompatActivity activity = getActivity();
            activity.runOnUiThread(() -> {
                bannerAdView.destroy();
                bannerLayout.removeAllViews();
                bannerAdView = null;
                bannerAdUnitId = null;

                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            });
        } else {
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("message", "No banner to destroy");
            call.resolve(ret);
        }
    }

    /**
     * Load an interstitial ad
     */
    @PluginMethod
    public void loadInterstitial(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        interstitialAdUnitId = adUnitId;
        isInterstitialLoaded = false;

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            try {
                interstitialAd = new InterstitialAd(activity);
                interstitialAd.setAdUnitId(adUnitId);

                interstitialAd.setInterstitialAdEventListener(new InterstitialAdEventListener() {
                    @Override
                    public void onAdLoaded() {
                        Log.d(TAG, "Interstitial ad loaded: " + adUnitId);
                        isInterstitialLoaded = true;
                        notifyAdEvent("interstitial", "loaded", adUnitId, null, null);

                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        call.resolve(ret);
                    }

                    @Override
                    public void onAdFailedToLoad(AdRequestError error) {
                        Log.e(TAG, "Interstitial ad failed to load: " + error.getDescription());
                        isInterstitialLoaded = false;

                        JSObject errorObj = createErrorObject(error);
                        notifyAdEvent("interstitial", "failed_to_load", adUnitId, errorObj, null);

                        JSObject ret = new JSObject();
                        ret.put("success", false);
                        ret.put("message", error.getDescription());
                        call.resolve(ret);
                    }

                    @Override
                    public void onAdShown() {
                        Log.d(TAG, "Interstitial ad shown");
                        notifyAdEvent("interstitial", "shown", adUnitId, null, null);
                    }

                    @Override
                    public void onAdDismissed() {
                        Log.d(TAG, "Interstitial ad dismissed");
                        isInterstitialLoaded = false;
                        notifyAdEvent("interstitial", "dismissed", adUnitId, null, null);
                    }

                    @Override
                    public void onAdClicked() {
                        Log.d(TAG, "Interstitial ad clicked");
                        notifyAdEvent("interstitial", "clicked", adUnitId, null, null);
                    }

                    @Override
                    public void onLeftApplication() {
                        Log.d(TAG, "Interstitial ad left application");
                        notifyAdEvent("interstitial", "left_application", adUnitId, null, null);
                    }

                    @Override
                    public void onReturnedToApplication() {
                        Log.d(TAG, "Interstitial ad returned to application");
                        notifyAdEvent("interstitial", "returned_to_application", adUnitId, null, null);
                    }

                    @Override
                    public void onImpression(@Nullable ImpressionData impressionData) {
                        Log.d(TAG, "Interstitial ad impression");
                        notifyAdEvent("interstitial", "impression", adUnitId, null, null);
                    }
                });

                AdRequest adRequest = new AdRequest.Builder().build();
                interstitialAd.loadAd(adRequest);

            } catch (Exception e) {
                Log.e(TAG, "Error loading interstitial: " + e.getMessage());
                call.reject("Error loading interstitial: " + e.getMessage());
            }
        });
    }

    /**
     * Show the loaded interstitial ad
     */
    @PluginMethod
    public void showInterstitial(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        if (interstitialAd == null || !isInterstitialLoaded) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("message", "Interstitial not loaded");
            call.resolve(ret);
            return;
        }

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            try {
                interstitialAd.show();
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "Error showing interstitial: " + e.getMessage());
                JSObject ret = new JSObject();
                ret.put("success", false);
                ret.put("message", e.getMessage());
                call.resolve(ret);
            }
        });
    }

    /**
     * Load a rewarded ad
     */
    @PluginMethod
    public void loadRewarded(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        rewardedAdUnitId = adUnitId;
        isRewardedLoaded = false;
        lastReward = null;

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            try {
                rewardedAd = new RewardedAd(activity);
                rewardedAd.setAdUnitId(adUnitId);

                rewardedAd.setRewardedAdEventListener(new RewardedAdEventListener() {
                    @Override
                    public void onAdLoaded() {
                        Log.d(TAG, "Rewarded ad loaded: " + adUnitId);
                        isRewardedLoaded = true;
                        notifyAdEvent("rewarded", "loaded", adUnitId, null, null);

                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        call.resolve(ret);
                    }

                    @Override
                    public void onAdFailedToLoad(AdRequestError error) {
                        Log.e(TAG, "Rewarded ad failed to load: " + error.getDescription());
                        isRewardedLoaded = false;

                        JSObject errorObj = createErrorObject(error);
                        notifyAdEvent("rewarded", "failed_to_load", adUnitId, errorObj, null);

                        JSObject ret = new JSObject();
                        ret.put("success", false);
                        ret.put("message", error.getDescription());
                        call.resolve(ret);
                    }

                    @Override
                    public void onRewarded(Reward reward) {
                        Log.d(TAG, "Rewarded ad reward received: " + reward.getAmount() + " " + reward.getType());
                        lastReward = reward;

                        JSObject rewardObj = new JSObject();
                        rewardObj.put("amount", reward.getAmount());
                        rewardObj.put("type", reward.getType());

                        notifyAdEvent("rewarded", "rewarded", adUnitId, null, rewardObj);
                    }

                    @Override
                    public void onAdShown() {
                        Log.d(TAG, "Rewarded ad shown");
                        notifyAdEvent("rewarded", "shown", adUnitId, null, null);
                    }

                    @Override
                    public void onAdDismissed() {
                        Log.d(TAG, "Rewarded ad dismissed");
                        isRewardedLoaded = false;
                        notifyAdEvent("rewarded", "dismissed", adUnitId, null, null);
                    }

                    @Override
                    public void onAdClicked() {
                        Log.d(TAG, "Rewarded ad clicked");
                        notifyAdEvent("rewarded", "clicked", adUnitId, null, null);
                    }

                    @Override
                    public void onLeftApplication() {
                        Log.d(TAG, "Rewarded ad left application");
                        notifyAdEvent("rewarded", "left_application", adUnitId, null, null);
                    }

                    @Override
                    public void onReturnedToApplication() {
                        Log.d(TAG, "Rewarded ad returned to application");
                        notifyAdEvent("rewarded", "returned_to_application", adUnitId, null, null);
                    }

                    @Override
                    public void onImpression(@Nullable ImpressionData impressionData) {
                        Log.d(TAG, "Rewarded ad impression");
                        notifyAdEvent("rewarded", "impression", adUnitId, null, null);
                    }
                });

                AdRequest adRequest = new AdRequest.Builder().build();
                rewardedAd.loadAd(adRequest);

            } catch (Exception e) {
                Log.e(TAG, "Error loading rewarded ad: " + e.getMessage());
                call.reject("Error loading rewarded ad: " + e.getMessage());
            }
        });
    }

    /**
     * Show the loaded rewarded ad
     */
    @PluginMethod
    public void showRewarded(PluginCall call) {
        if (!isInitialized) {
            rejectNotInitialized(call);
            return;
        }

        if (rewardedAd == null || !isRewardedLoaded) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("message", "Rewarded ad not loaded");
            call.resolve(ret);
            return;
        }

        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            try {
                rewardedAd.show();

                // Wait a moment for reward callback
                new android.os.Handler().postDelayed(() -> {
                    JSObject ret = new JSObject();
                    ret.put("success", true);

                    if (lastReward != null) {
                        ret.put("rewarded", true);
                        JSObject rewardObj = new JSObject();
                        rewardObj.put("amount", lastReward.getAmount());
                        rewardObj.put("type", lastReward.getType());
                        ret.put("reward", rewardObj);
                    } else {
                        ret.put("rewarded", false);
                    }

                    call.resolve(ret);
                }, 100);

            } catch (Exception e) {
                Log.e(TAG, "Error showing rewarded ad: " + e.getMessage());
                JSObject ret = new JSObject();
                ret.put("success", false);
                ret.put("message", e.getMessage());
                call.resolve(ret);
            }
        });
    }

    // Helper methods

    private void notifyAdEvent(String adType, String event, String adUnitId, JSObject error, JSObject reward) {
        JSObject eventData = new JSObject();
        eventData.put("adType", adType);
        eventData.put("event", event);

        if (adUnitId != null) {
            eventData.put("adUnitId", adUnitId);
        }

        if (error != null) {
            eventData.put("error", error);
        }

        if (reward != null) {
            eventData.put("reward", reward);
        }

        notifyListeners("adEvent", eventData);
    }

    private JSObject createErrorObject(AdRequestError error) {
        JSObject errorObj = new JSObject();
        errorObj.put("code", error.getCode());
        errorObj.put("message", error.getDescription());
        return errorObj;
    }

    private void rejectNotInitialized(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("success", false);
        ret.put("message", "SDK not initialized. Call init() first.");
        call.resolve(ret);
    }

    private void rejectMissingParameter(PluginCall call, String paramName) {
        JSObject ret = new JSObject();
        ret.put("success", false);
        ret.put("message", "Missing required parameter: " + paramName);
        call.resolve(ret);
    }
}
