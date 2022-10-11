package com.osova.yandex.ads;

import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private static final String YANDEX_MOBILE_ADS_TAG = "YandexMobileAds";
    private  boolean isInit = false;
    private BannerAdView mBannerAdView;
    private LinearLayout bannerLayout;
    private PluginCall waitCall;
    InterstitialAd mInterstitialAd;
    RewardedAd mRewardedAd;

    @Override
    public void load() {
        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {

            bannerLayout = new LinearLayout(_this);
            bannerLayout.setOrientation(LinearLayout.HORIZONTAL);
            //bannerLayout.addView(bottomBanner);
            //bannerLayout.setBackgroundColor(0xfafa00);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity= Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM;
            _this.addContentView(bannerLayout, params);
        });
    }

    @PluginMethod
    public void init(PluginCall call) {
        JSObject ret = new JSObject();
        Log.d(YANDEX_MOBILE_ADS_TAG, "SDK star init");
        MobileAds.initialize(getActivity(), new InitializationListener() {
            @Override
            public void onInitializationCompleted() {
                Log.d(YANDEX_MOBILE_ADS_TAG, "SDK initialized");
                isInit = true;
                ret.put("result", true);
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void initInter(PluginCall call) {
        if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "initInter not inited");
            call.reject("not inited");
            return;
        }
        String id = call.getString("id");

        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {
            mInterstitialAd = new InterstitialAd(_this);
            mInterstitialAd.setAdUnitId(id);
            final AdRequest adRequest = new AdRequest.Builder().build();

            mInterstitialAd.setInterstitialAdEventListener(new InterstitialAdEventListener() {
                @Override
                public void onAdLoaded() {
                    JSObject ret = new JSObject();
                    ret.put("result", true);
                    call.resolve(ret);
                }

                @Override
                public void onAdFailedToLoad(AdRequestError adRequestError) {
                    JSObject ret = new JSObject();
                    ret.put("result", false);
                    ret.put("message", adRequestError.toString());
                    call.resolve(ret);
                }

                @Override
                public void onAdShown() {
                    if (waitCall != null){
                       // JSObject ret = new JSObject();
                       // ret.put("result", true);
                       // waitCall.resolve(ret);
                    }
                }

                @Override
                public void onAdDismissed() {
                    if (waitCall != null){
                        JSObject ret = new JSObject();
                        ret.put("result", false);
                        waitCall.resolve(ret);
                    }
                }

                @Override
                public void onAdClicked() {

                }

                @Override
                public void onLeftApplication() {
                }

                @Override
                public void onReturnedToApplication() {
                }

                @Override
                public void onImpression(@Nullable ImpressionData impressionData) {

                }
            });
            mInterstitialAd.loadAd(adRequest);
        });


    }

    @PluginMethod
    public void initReward(PluginCall call) {
        if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "initReward not inited");
            call.reject("not inited");
            return;
        }
        String id = call.getString("id");

        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {
            mRewardedAd = new RewardedAd(_this);
            mRewardedAd.setAdUnitId(id);
            final AdRequest adRequest = new AdRequest.Builder().build();

            mRewardedAd.setRewardedAdEventListener(new RewardedAdEventListener() {
                @Override
                public void onRewarded(final Reward reward) {
                    if (waitCall != null) {
                        JSObject ret = new JSObject();
                        ret.put("result", true);
                        waitCall.resolve(ret);
                    }
                }

                @Override
                public void onAdClicked() {

                }

                @Override
                public void onAdLoaded() {
                    JSObject ret = new JSObject();
                    ret.put("result", true);
                    call.resolve(ret);
                }

                @Override
                public void onAdFailedToLoad(final AdRequestError adRequestError) {
                    call.reject(adRequestError.toString());
                }

                @Override
                public void onAdShown() {
                }

                @Override
                public void onAdDismissed() {
                    if (waitCall != null){
                        JSObject ret = new JSObject();
                        ret.put("result", true);
                        waitCall.resolve(ret);
                    }
                }

                @Override
                public void onLeftApplication() {
                }

                @Override
                public void onReturnedToApplication() {
                }

                @Override
                public void onImpression(@Nullable ImpressionData impressionData) {

                }
            });

            // Загрузка объявления.
            mRewardedAd.loadAd(adRequest);
        });

    }

    @PluginMethod
    public void initBanner(PluginCall call) {
        if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "initBanner not inited");
            call.reject("not inited");
            return;
        }
        String id = call.getString("id");
        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {
            if (mBannerAdView != null)
                mBannerAdView.destroy();
            mBannerAdView = new BannerAdView(_this);
            mBannerAdView.setAdUnitId(id);
            mBannerAdView.setAdSize(AdSize.flexibleSize(320, 50));
            bannerLayout.addView(mBannerAdView);
            //bannerLayout.setPivotX(700);
            //bannerLayout.setPivotY(150);
            //bannerLayout.setScaleX(0.5f);
            //bannerLayout.setScaleY(0.5f);
            final AdRequest adRequest = new AdRequest.Builder().build();
            mBannerAdView.setBannerAdEventListener(new BannerAdEventListener() {
                @Override
                public void onAdLoaded() {
                    Log.i(YANDEX_MOBILE_ADS_TAG, "onAdLoaded banner:"+id);
                    mBannerAdView.setVisibility(View.INVISIBLE);
                    JSObject ret = new JSObject();
                    ret.put("result", true);
                    call.resolve(ret);
                }

                @Override
                public void onAdFailedToLoad(AdRequestError adRequestError) {
                    Log.e(YANDEX_MOBILE_ADS_TAG, "onAdFailedToLoad banner:"+adRequestError.toString());
                    call.reject(adRequestError.toString());
                }

                @Override
                public void onAdClicked() {

                }

                @Override
                public void onLeftApplication() {

                }

                @Override
                public void onReturnedToApplication() {

                }

                @Override
                public void onImpression(@Nullable ImpressionData impressionData) {

                }

            });

            // Загрузка объявления.
            mBannerAdView.loadAd(adRequest);
        });

    }

    @PluginMethod
    public void showAds(PluginCall call) {
        if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "showAds not inited");
            call.reject("not inited");
            return;
        }
        AppCompatActivity _this = getActivity();
        waitCall = call;
        mInterstitialAd.show();
    }

    @PluginMethod
    public void showReward (PluginCall call) {
        if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "showReward not inited");
            call.reject("not reward");
            return;
        }
        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {
        	waitCall = call;
        	mRewardedAd.show();
        });
    }


    @PluginMethod
    public void showBanner(PluginCall call) {
    	 if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "showBanner not inited sdk");
            call.reject("not banner");
            return;
        }
        if (mBannerAdView == null){
        	Log.e(YANDEX_MOBILE_ADS_TAG, "showBanner not inited");
            call.reject("not inited banner");
            return;
        }
        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {
            mBannerAdView.setVisibility(View.VISIBLE);
            call.resolve();
        });
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
    	if (!isInit){
            Log.e(YANDEX_MOBILE_ADS_TAG, "hideBanner not inited sdk");
            call.reject("not banner");
            return;
        }
        if (mBannerAdView == null){
        	Log.e(YANDEX_MOBILE_ADS_TAG, "hideBanner not inited");
            call.reject("not inited banner");
            return;
        }
        AppCompatActivity _this = getActivity();
        _this.runOnUiThread(() -> {
            mBannerAdView.setVisibility(View.INVISIBLE);
            call.resolve();
        });
    }
}
