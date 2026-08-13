package com.osova.yandex.ads;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.atomic.AtomicReference;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.yandex.mobile.ads.banner.BannerAdEventListener;
import com.yandex.mobile.ads.banner.BannerAdSize;
import com.yandex.mobile.ads.banner.BannerAdView;
import com.yandex.mobile.ads.common.AdError;
import com.yandex.mobile.ads.common.AdRequest;
import com.yandex.mobile.ads.common.AdRequestError;
import com.yandex.mobile.ads.common.ImpressionData;
import com.yandex.mobile.ads.common.YandexAds;
import com.yandex.mobile.ads.interstitial.InterstitialAd;
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener;
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener;
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader;
import com.yandex.mobile.ads.rewarded.Reward;
import com.yandex.mobile.ads.rewarded.RewardedAd;
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener;
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener;
import com.yandex.mobile.ads.rewarded.RewardedAdLoader;

/**
 * Yandex Mobile Ads SDK 8.x.
 *
 * По сравнению с SDK 5-7 изменилось почти всё, что трогает этот плагин:
 * точка входа MobileAds переименована в YandexAds, идентификатор блока
 * переехал в AdRequest (AdRequestConfiguration больше нет), слушатель
 * загрузки передаётся прямо в loadAd(), а BannerAdSize.stickySize/fixedSize
 * стали sticky/fixed и требуют контекст.
 */
@CapacitorPlugin(name = "YandexAds")
public class YandexAdsPlugin extends Plugin {
    private static final String TAG = "YandexAds";

    // Инициализация не должна вешать вызов навсегда, если ответа нет.
    private static final long INIT_TIMEOUT_MS = 10000;

    // Методы плагина Capacitor выполняет на своём потоке, а колбэки SDK
    // приходят на UI-поток, поэтому всё разделяемое состояние - volatile.
    private volatile boolean isInitialized = false;

    // Banner
    private volatile BannerAdView bannerAdView;
    private volatile LinearLayout bannerLayout;
    private volatile String bannerAdUnitId;

    // Отложенные вызовы - в AtomicReference: "прочитать и обнулить" должно быть
    // одной операцией, иначе поток моста и UI-поток могут ответить дважды.
    private final AtomicReference<PluginCall> pendingBannerLoadCall = new AtomicReference<>();

    // Interstitial
    private volatile InterstitialAdLoader interstitialLoader;
    private volatile InterstitialAd interstitialAd;
    private volatile String interstitialAdUnitId;
    private final AtomicReference<PluginCall> pendingInterstitialLoadCall = new AtomicReference<>();
    private final AtomicReference<PluginCall> pendingInterstitialShowCall = new AtomicReference<>();

    // Rewarded
    private volatile RewardedAdLoader rewardedLoader;
    private volatile RewardedAd rewardedAd;
    private volatile String rewardedAdUnitId;
    private final AtomicReference<PluginCall> pendingRewardedLoadCall = new AtomicReference<>();
    private final AtomicReference<PluginCall> pendingRewardedShowCall = new AtomicReference<>();
    private volatile Reward lastReward;

    @Override
    public void load() {
        AppCompatActivity activity = getActivity();
        activity.runOnUiThread(() -> {
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

    @Override
    protected void handleOnDestroy() {
        AppCompatActivity activity = getActivity();
        if (activity != null) activity.runOnUiThread(this::releaseAll);
        super.handleOnDestroy();
    }

    // MARK: - SDK

    @PluginMethod
    public void init(PluginCall call) {
        if (isInitialized) {
            resolveOk(call, "Already initialized");
            return;
        }

        AppCompatActivity activity = getActivity();
        Boolean userConsent = call.getBoolean("userConsent");
        Boolean ageRestrictedUser = call.getBoolean("ageRestrictedUser");
        Boolean locationTracking = call.getBoolean("locationTracking");
        Boolean enableLogging = call.getBoolean("enableLogging");

        activity.runOnUiThread(() -> {
            // Политики приватности выставляются до инициализации SDK.
            if (userConsent != null) YandexAds.setUserConsent(userConsent);
            if (ageRestrictedUser != null) YandexAds.setAgeRestricted(ageRestrictedUser);
            if (locationTracking != null) YandexAds.setLocationTracking(locationTracking);
            if (Boolean.TRUE.equals(enableLogging)) YandexAds.enableLogging(true);

            // Начиная с SDK 8 библиотека поднимается сама при старте приложения;
            // этот вызов лишь дожидается конца инициализации.
            final boolean[] isSettled = { false };

            YandexAds.initialize(activity, () -> {
                // Флаг ставим до проверки сторожа: SDK готов независимо от того,
                // успели ли мы уже ответить по таймауту. Иначе поздний колбэк
                // оставлял бы плагин "неинициализированным" навсегда.
                isInitialized = true;
                Log.d(TAG, "SDK initialized, version " + YandexAds.getLibraryVersion());

                if (isSettled[0]) return;
                isSettled[0] = true;

                notifyAdEvent("init", "loaded", null, null, null);
                resolveOk(call, null);
            });

            // Без сети колбэк может не прийти вовсе - не держим вызов вечно.
            bannerLayout.postDelayed(() -> {
                if (isSettled[0]) return;
                isSettled[0] = true;

                Log.w(TAG, "SDK init timed out");
                notifyAdEvent("init", "failed_to_load", null, errorObject(0, "Initialization timeout"), null);
                resolveFail(call, "Initialization timeout");
            }, INIT_TIMEOUT_MS);
        });
    }

    // MARK: - Banner

    @PluginMethod
    public void loadBanner(PluginCall call) {
        if (notInitialized(call)) return;

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
        // Предыдущую незавершённую загрузку закрываем, иначе её обещание висит.
        settleAndClearBannerLoad(false, "Superseded by a new loadBanner() call");
        final PluginCall loadCall = hold(call);
        pendingBannerLoadCall.set(loadCall);

        AppCompatActivity activity = getActivity();
        if (activity == null) {
            settleAndClearBannerLoad(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                destroyBannerView();

                BannerAdView view = new BannerAdView(activity);

                // Размер считает сам SDK, и ему нужен контекст: методов
                // stickySize/fixedSize без контекста в SDK 8 больше нет.
                BannerAdSize adSize = (height != null && height > 0)
                    ? BannerAdSize.fixed(activity, width, height)
                    : BannerAdSize.sticky(activity, resolveStickyWidthDp(activity, width));
                view.setAdSize(adSize);

                applyBannerPosition(activity, position);

                view.setBannerAdEventListener(new BannerAdEventListener() {
                    @Override
                    public void onAdLoaded() {
                        Log.d(TAG, "Banner loaded: " + adUnitId);
                        // Колбэк мог прийти после гибели activity - иначе утечка.
                        if (activity.isDestroyed()) {
                            destroyBannerView();
                            settleLoadCall(loadCall, false, "Activity destroyed");
                            return;
                        }
                        notifyAdEvent("banner", "loaded", adUnitId, null, null);
                        settleLoadCall(loadCall, true, null);
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull AdRequestError error) {
                        Log.e(TAG, "Banner failed to load: " + error.getDescription());
                        notifyAdEvent("banner", "failed_to_load", adUnitId, errorObject(error), null);
                        settleLoadCall(loadCall, false, error.getDescription());
                    }

                    @Override
                    public void onAdClicked() {
                        notifyAdEvent("banner", "clicked", adUnitId, null, null);
                    }

                    @Override
                    public void onImpression(@Nullable ImpressionData impressionData) {
                        notifyAdEvent("banner", "impression", adUnitId, null, null);
                    }
                });

                bannerAdView = view;
                bannerLayout.addView(view);
                view.setVisibility(View.INVISIBLE);

                // Идентификатор блока теперь часть запроса, а не свойство view.
                view.loadAd(new AdRequest.Builder(adUnitId).build());
            } catch (Exception e) {
                Log.e(TAG, "Error loading banner: " + e.getMessage());
                settleLoadCall(loadCall, false, e.getMessage());
            }
        });
    }

    @PluginMethod
    public void showBanner(PluginCall call) {
        if (notInitialized(call)) return;
        setBannerVisible(call, true);
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
        if (notInitialized(call)) return;
        setBannerVisible(call, false);
    }

    /**
     * Проверять bannerAdView здесь, на потоке моста, бесполезно: параллельный
     * destroyBanner()/loadBanner() успеет обнулить его до того, как выполнится
     * наш runnable. Поэтому берём ссылку уже на UI-потоке.
     */
    private void setBannerVisible(PluginCall call, boolean isVisible) {
        AppCompatActivity activity = getActivity();
        if (activity == null) {
            resolveFail(call, "Activity is gone");
            return;
        }

        activity.runOnUiThread(() -> {
            BannerAdView view = bannerAdView;
            if (view == null) {
                resolveFail(call, "Banner not loaded");
                return;
            }
            view.setVisibility(isVisible ? View.VISIBLE : View.INVISIBLE);
            notifyAdEvent("banner", isVisible ? "shown" : "dismissed", bannerAdUnitId, null, null);
            resolveOk(call, null);
        });
    }

    @PluginMethod
    public void destroyBanner(PluginCall call) {
        AppCompatActivity activity = getActivity();
        if (activity == null) {
            settleAndClearBannerLoad(false, "Banner destroyed");
            destroyBannerView();
            resolveOk(call, null);
            return;
        }
        activity.runOnUiThread(() -> {
            settleAndClearBannerLoad(false, "Banner destroyed");
            destroyBannerView();
            resolveOk(call, null);
        });
    }

    // MARK: - Interstitial

    @PluginMethod
    public void loadInterstitial(PluginCall call) {
        if (notInitialized(call)) return;

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        interstitialAdUnitId = adUnitId;
        // Загрузчик держит один запрос: новый вызов отменяет предыдущий, и его
        // слушатель уже не сработает - закрываем то обещание сами.
        settleAndClearInterstitialLoad(false, "Superseded by a new loadInterstitial() call");
        final PluginCall loadCall = hold(call);
        pendingInterstitialLoadCall.set(loadCall);

        AppCompatActivity activity = getActivity();
        if (activity == null) {
            settleAndClearInterstitialLoad(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                if (interstitialLoader == null) {
                    // Один загрузчик на всё время жизни плагина - так советует
                    // документация, это быстрее повторного создания.
                    interstitialLoader = new InterstitialAdLoader(activity);
                }

                destroyInterstitialAd();

                // В SDK 8 слушатель передаётся прямо в loadAd, поэтому вызов
                // JS-стороны захватывается замыканием и гонок между
                // параллельными загрузками нет.
                interstitialLoader.loadAd(
                    new AdRequest.Builder(adUnitId).build(),
                    new InterstitialAdLoadListener() {
                        @Override
                        public void onAdLoaded(@NonNull InterstitialAd ad) {
                            Log.d(TAG, "Interstitial loaded: " + adUnitId);
                            interstitialAd = ad;
                            notifyAdEvent("interstitial", "loaded", adUnitId, null, null);
                            settleLoadCall(loadCall, true, null);
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull AdRequestError error) {
                            Log.e(TAG, "Interstitial failed to load: " + error.getDescription());
                            interstitialAd = null;
                            notifyAdEvent("interstitial", "failed_to_load", adUnitId, errorObject(error), null);
                            settleLoadCall(loadCall, false, error.getDescription());
                        }
                    }
                );
            } catch (Exception e) {
                Log.e(TAG, "Error loading interstitial: " + e.getMessage());
                settleLoadCall(loadCall, false, e.getMessage());
            }
        });
    }

    @PluginMethod
    public void showInterstitial(PluginCall call) {
        if (notInitialized(call)) return;

        if (interstitialAd == null) {
            resolveFail(call, "Interstitial not loaded");
            return;
        }

        settleInterstitialShow(false, "Superseded by a new showInterstitial() call");
        final PluginCall showCall = hold(call);
        pendingInterstitialShowCall.set(showCall);

        AppCompatActivity activity = getActivity();
        if (activity == null) {
            settleInterstitialShow(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            InterstitialAd ad = interstitialAd;
            if (ad == null) {
                settleInterstitialShow(false, "Interstitial not loaded");
                return;
            }

            ad.setAdEventListener(new InterstitialAdEventListener() {
                @Override
                public void onAdShown() {
                    notifyAdEvent("interstitial", "shown", interstitialAdUnitId, null, null);
                    // Отвечаем по факту показа, а не по факту вызова show(),
                    // и строго своему вызову: поле могло уже смениться.
                    settleOwnCall(pendingInterstitialShowCall, showCall, true, null);
                }

                @Override
                public void onAdFailedToShow(@NonNull AdError adError) {
                    Log.e(TAG, "Interstitial failed to show: " + adError.getDescription());
                    notifyAdEvent("interstitial", "failed_to_show", interstitialAdUnitId,
                        errorObject(0, adError.getDescription()), null);
                    destroyInterstitialAd();
                    settleOwnCall(pendingInterstitialShowCall, showCall, false, adError.getDescription());
                }

                @Override
                public void onAdDismissed() {
                    notifyAdEvent("interstitial", "dismissed", interstitialAdUnitId, null, null);
                    // Показанный объект переиспользовать нельзя - освобождаем.
                    destroyInterstitialAd();
                }

                @Override
                public void onAdClicked() {
                    notifyAdEvent("interstitial", "clicked", interstitialAdUnitId, null, null);
                }

                @Override
                public void onAdImpression(@Nullable ImpressionData impressionData) {
                    notifyAdEvent("interstitial", "impression", interstitialAdUnitId, null, null);
                }
            });

            ad.show(activity);
        });
    }

    @PluginMethod
    public void isInterstitialLoaded(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("loaded", interstitialAd != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void destroyInterstitial(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            destroyInterstitialAd();
            resolveOk(call, null);
        });
    }

    // MARK: - Rewarded

    @PluginMethod
    public void loadRewarded(PluginCall call) {
        if (notInitialized(call)) return;

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        rewardedAdUnitId = adUnitId;
        settleAndClearRewardedLoad(false, "Superseded by a new loadRewarded() call");
        final PluginCall loadCall = hold(call);
        pendingRewardedLoadCall.set(loadCall);

        AppCompatActivity activity = getActivity();
        if (activity == null) {
            settleAndClearRewardedLoad(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                if (rewardedLoader == null) {
                    rewardedLoader = new RewardedAdLoader(activity);
                }

                // Ролик может идти прямо сейчас: снять с него слушателя значит
                // никогда не узнать о закрытии и подвесить обещание показа.
                // Предзагрузка следующего блока - штатный сценарий.
                if (pendingRewardedShowCall.get() == null) {
                    destroyRewardedAd();
                    lastReward = null;
                }

                rewardedLoader.loadAd(
                    new AdRequest.Builder(adUnitId).build(),
                    new RewardedAdLoadListener() {
                        @Override
                        public void onAdLoaded(@NonNull RewardedAd ad) {
                            Log.d(TAG, "Rewarded loaded: " + adUnitId);
                            rewardedAd = ad;
                            notifyAdEvent("rewarded", "loaded", adUnitId, null, null);
                            settleLoadCall(loadCall, true, null);
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull AdRequestError error) {
                            Log.e(TAG, "Rewarded failed to load: " + error.getDescription());
                            rewardedAd = null;
                            notifyAdEvent("rewarded", "failed_to_load", adUnitId, errorObject(error), null);
                            settleLoadCall(loadCall, false, error.getDescription());
                        }
                    }
                );
            } catch (Exception e) {
                Log.e(TAG, "Error loading rewarded: " + e.getMessage());
                settleLoadCall(loadCall, false, e.getMessage());
            }
        });
    }

    @PluginMethod
    public void showRewarded(PluginCall call) {
        if (notInitialized(call)) return;

        if (rewardedAd == null) {
            resolveFail(call, "Rewarded ad not loaded");
            return;
        }

        settleRewardedShow(false, null, "Superseded by a new showRewarded() call");
        // Награда прошлого показа не должна засчитаться этому.
        lastReward = null;
        final PluginCall showCall = hold(call);
        pendingRewardedShowCall.set(showCall);

        AppCompatActivity activity = getActivity();
        if (activity == null) {
            settleRewardedShow(false, null, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            RewardedAd ad = rewardedAd;
            if (ad == null) {
                settleRewardedShow(false, null, "Rewarded ad not loaded");
                return;
            }

            ad.setAdEventListener(new RewardedAdEventListener() {
                @Override
                public void onAdShown() {
                    notifyAdEvent("rewarded", "shown", rewardedAdUnitId, null, null);
                }

                @Override
                public void onAdFailedToShow(@NonNull AdError adError) {
                    Log.e(TAG, "Rewarded failed to show: " + adError.getDescription());
                    notifyAdEvent("rewarded", "failed_to_show", rewardedAdUnitId,
                        errorObject(0, adError.getDescription()), null);
                    destroyRewardedAd();
                    // Ролика не было - попытку сжигать нельзя.
                    settleRewardedShow(showCall, false, null, adError.getDescription());
                }

                @Override
                public void onRewarded(@NonNull Reward reward) {
                    Log.d(TAG, "Rewarded: " + reward.getAmount() + " " + reward.getType());
                    lastReward = reward;

                    JSObject rewardObj = new JSObject();
                    rewardObj.put("amount", reward.getAmount());
                    rewardObj.put("type", reward.getType());
                    notifyAdEvent("rewarded", "rewarded", rewardedAdUnitId, null, rewardObj);
                }

                @Override
                public void onAdDismissed() {
                    notifyAdEvent("rewarded", "dismissed", rewardedAdUnitId, null, null);
                    // Только к закрытию ролика ясно, досмотрел его игрок или нет.
                    settleRewardedShow(showCall, true, lastReward, null);
                    destroyRewardedAd();
                }

                @Override
                public void onAdClicked() {
                    notifyAdEvent("rewarded", "clicked", rewardedAdUnitId, null, null);
                }

                @Override
                public void onAdImpression(@Nullable ImpressionData impressionData) {
                    notifyAdEvent("rewarded", "impression", rewardedAdUnitId, null, null);
                }
            });

            ad.show(activity);
        });
    }

    @PluginMethod
    public void isRewardedLoaded(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("loaded", rewardedAd != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void destroyRewarded(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            destroyRewardedAd();
            resolveOk(call, null);
        });
    }

    // MARK: - Helpers

    /**
     * Ширина sticky-баннера задаётся в dp. Ноль или отрицательное значение
     * трактуем как "во всю ширину экрана".
     */
    private int resolveStickyWidthDp(@NonNull AppCompatActivity activity, int requestedWidth) {
        if (requestedWidth > 0) return requestedWidth;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return Math.round(metrics.widthPixels / metrics.density);
    }

    private void applyBannerPosition(@NonNull AppCompatActivity activity, @Nullable String position) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bannerLayout.getLayoutParams();
        if ("top".equalsIgnoreCase(position)) {
            params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            int statusBarHeight = 0;
            int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
            }
            params.topMargin = statusBarHeight;
        } else {
            params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            params.topMargin = 0;
        }
        bannerLayout.setLayoutParams(params);
    }

    /**
     * Снимает баннер с экрана. Ждущее обещание загрузки НЕ трогает: этот метод
     * вызывается и в начале новой загрузки, где закрывать только что
     * зарегистрированный вызов нельзя. Закрытием занимаются те, кто
     * действительно обрывает загрузку, - destroyBanner() и releaseAll().
     */
    private void destroyBannerView() {
        BannerAdView view = bannerAdView;
        if (view == null) return;
        bannerAdView = null;
        view.setBannerAdEventListener(null);
        view.destroy();
        LinearLayout layout = bannerLayout;
        if (layout != null) layout.removeAllViews();
    }

    // Документация требует снимать слушателя с показанного объявления, иначе
    // объект и его слушатель остаются в памяти.
    private void destroyInterstitialAd() {
        InterstitialAd ad = interstitialAd;
        if (ad == null) return;
        interstitialAd = null;
        ad.setAdEventListener(null);
    }

    private void destroyRewardedAd() {
        RewardedAd ad = rewardedAd;
        if (ad == null) return;
        rewardedAd = null;
        ad.setAdEventListener(null);
    }

    private void releaseAll() {
        destroyBannerView();
        destroyInterstitialAd();
        destroyRewardedAd();

        if (interstitialLoader != null) {
            interstitialLoader.cancelLoading();
            interstitialLoader = null;
        }
        if (rewardedLoader != null) {
            rewardedLoader.cancelLoading();
            rewardedLoader = null;
        }

        // Ни один колбэк больше не придёт: закрываем всё, что ждало ответа.
        settleAndClearBannerLoad(false, "Plugin destroyed");
        settleAndClearInterstitialLoad(false, "Plugin destroyed");
        settleAndClearRewardedLoad(false, "Plugin destroyed");
        settleInterstitialShow(false, "Plugin destroyed");
        settleRewardedShow(false, null, "Plugin destroyed");
    }

    // Отложенный вызов гасим строго один раз: getAndSet сразу снимает ссылку,
    // поэтому второй поток уже ничего не найдёт.
    private void settleAndClearBannerLoad(boolean success, @Nullable String message) {
        settle(pendingBannerLoadCall.getAndSet(null), success, message);
    }

    private void settleAndClearInterstitialLoad(boolean success, @Nullable String message) {
        settle(pendingInterstitialLoadCall.getAndSet(null), success, message);
    }

    private void settleAndClearRewardedLoad(boolean success, @Nullable String message) {
        settle(pendingRewardedLoadCall.getAndSet(null), success, message);
    }

    private void settleInterstitialShow(boolean success, @Nullable String message) {
        settle(pendingInterstitialShowCall.getAndSet(null), success, message);
    }

    /**
     * Отвечает конкретному вызову и снимает его из поля, только если поле всё
     * ещё указывает именно на него. Колбэк обязан отвечать захваченному вызову,
     * а не текущему полю: между запросом и ответом поле могло смениться.
     */
    private void settleOwnCall(AtomicReference<PluginCall> holder, @Nullable PluginCall call,
                               boolean success, @Nullable String message) {
        if (call == null) return;
        // Если поле уже указывает на другой вызов, чужое обещание не трогаем.
        if (!holder.compareAndSet(call, null) && holder.get() == call) return;
        settle(call, success, message);
    }

    private void settleLoadCall(@Nullable PluginCall call, boolean success, @Nullable String message) {
        if (call == null) return;
        pendingBannerLoadCall.compareAndSet(call, null);
        pendingInterstitialLoadCall.compareAndSet(call, null);
        pendingRewardedLoadCall.compareAndSet(call, null);
        settle(call, success, message);
    }

    /**
     * Отдаёт результат показа rewarded-ролика ровно один раз.
     *
     * shown=false означает "ролик не показали" - попытку сжигать нельзя;
     * shown=true с rewarded=false означает "показали, но игрок прервал".
     */
    private void settleRewardedShow(boolean shown, @Nullable Reward reward, @Nullable String message) {
        settleRewardedShow(null, shown, reward, message);
    }

    /**
     * expected != null - отвечаем только если поле всё ещё держит именно этот
     * вызов: колбэк прошлого показа не должен гасить обещание следующего.
     */
    private void settleRewardedShow(@Nullable PluginCall expected, boolean shown,
                                    @Nullable Reward reward, @Nullable String message) {
        PluginCall call;
        if (expected == null) {
            call = pendingRewardedShowCall.getAndSet(null);
        } else {
            call = pendingRewardedShowCall.compareAndSet(expected, null) ? expected : null;
        }
        if (call == null) return;

        JSObject ret = new JSObject();
        ret.put("success", shown);
        if (message != null) ret.put("message", message);

        if (shown) {
            ret.put("rewarded", reward != null);
            if (reward != null) {
                JSObject rewardObj = new JSObject();
                rewardObj.put("amount", reward.getAmount());
                rewardObj.put("type", reward.getType());
                ret.put("reward", rewardObj);
            }
        }

        release(call, ret);
    }

    /** Удерживает вызов до прихода нативного колбэка. */
    private PluginCall hold(PluginCall call) {
        call.setKeepAlive(true);
        return call;
    }

    private void settle(@Nullable PluginCall call, boolean success, @Nullable String message) {
        if (call == null) return;
        JSObject ret = new JSObject();
        ret.put("success", success);
        if (message != null) ret.put("message", message);
        release(call, ret);
    }

    /**
     * Снимаем удержание до ответа: тогда мост пришлёт save=false, JS-сторона
     * освободит колбэк, а сам вызов освободится автоматически.
     */
    private void release(@NonNull PluginCall call, @NonNull JSObject ret) {
        call.setKeepAlive(false);
        call.resolve(ret);
    }

    private void notifyAdEvent(String adType, String event, @Nullable String adUnitId,
                               @Nullable JSObject error, @Nullable JSObject reward) {
        JSObject eventData = new JSObject();
        eventData.put("adType", adType);
        eventData.put("event", event);

        if (adUnitId != null) eventData.put("adUnitId", adUnitId);
        if (error != null) eventData.put("error", error);
        if (reward != null) eventData.put("reward", reward);

        notifyListeners("adEvent", eventData);
    }

    private JSObject errorObject(@NonNull AdRequestError error) {
        return errorObject(error.getCode(), error.getDescription());
    }

    private JSObject errorObject(int code, @Nullable String message) {
        JSObject errorObj = new JSObject();
        errorObj.put("code", code);
        errorObj.put("message", message == null ? "" : message);
        return errorObj;
    }

    private boolean notInitialized(PluginCall call) {
        if (isInitialized) return false;
        resolveFail(call, "SDK not initialized. Call init() first.");
        return true;
    }

    private void resolveOk(PluginCall call, @Nullable String message) {
        JSObject ret = new JSObject();
        ret.put("success", true);
        if (message != null) ret.put("message", message);
        call.resolve(ret);
    }

    private void resolveFail(PluginCall call, @Nullable String message) {
        JSObject ret = new JSObject();
        ret.put("success", false);
        if (message != null) ret.put("message", message);
        call.resolve(ret);
    }

    private void rejectMissingParameter(PluginCall call, String paramName) {
        resolveFail(call, "Missing required parameter: " + paramName);
    }
}
