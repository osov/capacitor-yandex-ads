package com.osova.yandex.ads;

import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Методы плагина Capacitor выполняет на своём потоке, поэтому отложенные
    // задачи ставим через явный Handler главного потока: View.postDelayed при
    // неудачном чтении состояния привязки молча не срабатывает.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Инициализация не должна вешать вызов навсегда, если ответа нет.
    private static final long INIT_TIMEOUT_MS = 10000;
    // Показ тоже: если SDK потеряет колбэк, обещание показа висело бы вечно, а
    // вместе с ним - кнопка награды в игре.
    private static final long SHOW_TIMEOUT_MS = 5 * 60 * 1000;
    // Загрузка тоже: если SDK не позовёт ни onAdLoaded, ни onAdFailedToLoad,
    // обещание висело бы вечно, а вместе с ним - ожидающий его код игры.
    private static final long LOAD_TIMEOUT_MS = 60 * 1000;

    // Методы плагина Capacitor выполняет на своём потоке, а колбэки SDK
    // приходят на UI-поток, поэтому всё разделяемое состояние - volatile.
    private volatile boolean isInitialized = false;
    // Мост дренирует свою очередь после handleOnDestroy, поэтому метод плагина
    // может выполниться уже после уборки и снова взвести пятиминутный сторож -
    // некому будет ни снять его, ни закрыть удержанный вызов.
    private volatile boolean isPluginDestroyed = false;
    // Вызов init() тоже отложенный: без этого его обещание не закрывал бы никто
    // при уничтожении плагина, а сторож снимается вместе с остальными задачами.
    private final AtomicReference<PluginCall> pendingInitCall = new AtomicReference<>();
    // Событие об успешной инициализации шлём один раз за процесс.
    private final AtomicBoolean isInitEventSent = new AtomicBoolean(false);

    // Banner
    private volatile BannerAdView bannerAdView;
    private volatile LinearLayout bannerLayout;
    private volatile String bannerAdUnitId;
    // Вью появляется в начале загрузки, а показывать баннер можно только после
    // onAdLoaded - для isBannerLoaded() нужен отдельный флаг.
    private volatile boolean isBannerAdLoaded = false;

    // Отложенные вызовы - в AtomicReference: "прочитать и обнулить" должно быть
    // одной операцией, иначе поток моста и UI-поток могут ответить дважды.
    private final AtomicReference<PluginCall> pendingBannerLoadCall = new AtomicReference<>();

    // Interstitial
    private volatile InterstitialAdLoader interstitialLoader;
    private volatile InterstitialAd interstitialAd;
    // Показываемое сейчас объявление держим отдельно от предзагруженного: иначе
    // предзагрузка во время показа снимала бы слушателя с того, что на экране,
    // и события о его закрытии в JS уже не приходили бы.
    private volatile InterstitialAd showingInterstitialAd;
    private volatile String interstitialAdUnitId;
    private final AtomicReference<PluginCall> pendingInterstitialLoadCall = new AtomicReference<>();
    private final AtomicReference<PluginCall> pendingInterstitialShowCall = new AtomicReference<>();

    // Rewarded
    private volatile RewardedAdLoader rewardedLoader;
    private volatile RewardedAd rewardedAd;
    private volatile RewardedAd showingRewardedAd;
    private volatile String rewardedAdUnitId;
    private final AtomicReference<PluginCall> pendingRewardedLoadCall = new AtomicReference<>();
    private final AtomicReference<PluginCall> pendingRewardedShowCall = new AtomicReference<>();

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
        // Сторожа показа живут до пяти минут и держат ссылку на плагин, а через
        // него - на activity. Без снятия задач она пережила бы своё уничтожение.
        isPluginDestroyed = true;
        runOnUi(() -> {
            releaseAll();
            // Снимаем задачи уже после уборки: сторожа держат ссылку на плагин,
            // а через него на activity. Раньше нельзя - при отсутствующей
            // activity сама уборка идёт через этот же обработчик.
            mainHandler.removeCallbacksAndMessages(null);
        });
        super.handleOnDestroy();
    }

    // MARK: - SDK

    @PluginMethod
    public void init(PluginCall call) {
        if (isGone(call)) return;
        if (isInitialized) {
            resolveOk(call, "Already initialized");
            return;
        }

        Boolean userConsent = call.getBoolean("userConsent");
        Boolean ageRestrictedUser = call.getBoolean("ageRestrictedUser");
        Boolean locationTracking = call.getBoolean("locationTracking");
        Boolean enableLogging = call.getBoolean("enableLogging");

        final PluginCall initCall = hold(call);
        settle(pendingInitCall.getAndSet(initCall), false, "Superseded by a new init() call");

        runOnUi(() -> {
            AppCompatActivity activity = getActivity();
            if (isActivityGone()) {
                settleOwnInit(initCall, false, "Activity is gone");
                return;
            }

            // Сторож ставим до обращения к SDK: если оно бросит исключение,
            // вызов иначе остался бы незакрытым навсегда.
            armInitWatchdog(initCall);

            try {
                // Политики приватности выставляются до инициализации SDK.
                if (userConsent != null) YandexAds.setUserConsent(userConsent);
                if (ageRestrictedUser != null) YandexAds.setAgeRestricted(ageRestrictedUser);
                if (locationTracking != null) YandexAds.setLocationTracking(locationTracking);
                if (Boolean.TRUE.equals(enableLogging)) YandexAds.enableLogging(true);

                // Начиная с SDK 8 библиотека поднимается сама при старте
                // приложения; этот вызов лишь дожидается конца инициализации.
                YandexAds.initialize(activity, () -> {
                    // Флаг ставим до проверки владения: SDK готов независимо от
                    // того, успели ли мы уже ответить по таймауту. Иначе поздний
                    // колбэк оставлял бы плагин "неинициализированным" навсегда.
                    isInitialized = true;
                    Log.d(TAG, "SDK initialized, version " + YandexAds.getLibraryVersion());

                    // Событие - ровно одно за процесс: слушателей SDK может быть
                    // несколько (два параллельных init() регистрируют каждый
                    // свой, и снять чужой нечем), а игра по этому событию
                    // запускает предзагрузку.
                    if (isInitEventSent.compareAndSet(false, true)) {
                        notifyAdEvent("init", "loaded", null, null, null);
                    }
                    if (!pendingInitCall.compareAndSet(initCall, null)) return;
                    settle(initCall, true, null);
                });
            } catch (Exception e) {
                // Исключение с главного потока Capacitor не ловит - оно роняет
                // процесс. Отвечаем отказом, как и на любой другой сбой.
                Log.e(TAG, "Error initializing SDK: " + e.getMessage());
                settleOwnInit(initCall, false, e.getMessage());
            }
        });
    }

    // MARK: - Banner

    @PluginMethod
    public void loadBanner(PluginCall call) {
        if (isGone(call)) return;
        if (notInitialized(call)) return;

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        // Размер необязателен, как у loadBanner в Defold-расширении: без него
        // грузится стандартный баннер 320x50.
        JSObject sizeObj = call.getObject("size");
        Integer widthValue = sizeObj != null ? sizeObj.getInteger("width") : null;
        final int width = widthValue != null ? widthValue : 0;
        Integer height = sizeObj != null ? sizeObj.getInteger("height") : null;
        String position = call.getString("position", "bottom");

        bannerAdUnitId = adUnitId;
        // Предыдущую незавершённую загрузку закрываем, иначе её обещание висит.
        final PluginCall loadCall = hold(call);
        settle(pendingBannerLoadCall.getAndSet(loadCall), false, "Superseded by a new loadBanner() call");
        armLoadWatchdog(pendingBannerLoadCall, loadCall, "banner");

        AppCompatActivity activity = getActivity();
        if (isActivityGone()) {
            settleAndClearBannerLoad(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            // Загрузку могли закрыть, пока раннабл ждал очереди UI-потока
            // (destroyBanner, releaseAll или следующий loadBanner). Тогда вешать
            // новую вью нельзя: убрать её потом будет некому.
            if (pendingBannerLoadCall.get() != loadCall) return;
            if (isActivityGone()) {
                settleLoadCall(loadCall, false, "Activity is gone");
                return;
            }
            try {
                destroyBannerView();

                BannerAdView view = new BannerAdView(activity);

                // Семантика размеров - как у Defold-расширения: width и height
                // задают фиксированный размер, только width - sticky этой
                // ширины, без размера - стандартный баннер 320x50 (в SDK 8
                // методы стали sticky/fixed и требуют контекст).
                BannerAdSize adSize;
                if (width > 0 && height != null && height > 0)
                    adSize = BannerAdSize.fixed(activity, width, height);
                else if (width > 0)
                    adSize = BannerAdSize.sticky(activity, width);
                else
                    adSize = BannerAdSize.fixed(activity, 320, 50);
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
                        isBannerAdLoaded = true;
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
        if (isGone(call)) return;
        if (notInitialized(call)) return;
        setBannerVisible(call, true, call.getString("position"));
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
        if (isGone(call)) return;
        if (notInitialized(call)) return;
        setBannerVisible(call, false, null);
    }

    @PluginMethod
    public void isBannerLoaded(PluginCall call) {
        if (isGone(call)) return;
        JSObject ret = new JSObject();
        ret.put("loaded", isBannerAdLoaded);
        call.resolve(ret);
    }

    /**
     * Проверять bannerAdView здесь, на потоке моста, бесполезно: параллельный
     * destroyBanner()/loadBanner() успеет обнулить его до того, как выполнится
     * наш runnable. Поэтому берём ссылку уже на UI-потоке.
     */
    private void setBannerVisible(PluginCall call, boolean isVisible, @Nullable String position) {
        AppCompatActivity activity = getActivity();
        if (isActivityGone()) {
            resolveFail(call, "Activity is gone");
            return;
        }

        activity.runOnUiThread(() -> {
            BannerAdView view = bannerAdView;
            if (view == null) {
                resolveFail(call, "Banner not loaded");
                return;
            }
            // Позицию можно менять прямо при показе, в том числе у видимого
            // баннера, - как show_banner(pos) в Defold-расширении.
            if (isVisible && position != null) applyBannerPosition(activity, position);
            view.setVisibility(isVisible ? View.VISIBLE : View.INVISIBLE);
            notifyAdEvent("banner", isVisible ? "shown" : "dismissed", bannerAdUnitId, null, null);
            resolveOk(call, null);
        });
    }

    @PluginMethod
    public void destroyBanner(PluginCall call) {
        if (isGone(call)) return;
        runOnUi(() -> {
            settleAndClearBannerLoad(false, "Banner destroyed");
            destroyBannerView();
            resolveOk(call, null);
        });
    }

    // MARK: - Interstitial

    @PluginMethod
    public void loadInterstitial(PluginCall call) {
        if (isGone(call)) return;
        if (notInitialized(call)) return;

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        interstitialAdUnitId = adUnitId;
        // Загрузчик держит один запрос: новый вызов отменяет предыдущий, и его
        // слушатель уже не сработает - закрываем то обещание сами.
        final PluginCall loadCall = hold(call);
        settle(pendingInterstitialLoadCall.getAndSet(loadCall), false, "Superseded by a new loadInterstitial() call");
        armLoadWatchdog(pendingInterstitialLoadCall, loadCall, "interstitial");

        AppCompatActivity activity = getActivity();
        if (isActivityGone()) {
            settleAndClearInterstitialLoad(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                if (interstitialLoader == null) {
                    // Один загрузчик на всё время жизни плагина - так советует
                    // документация, это быстрее повторного создания.
                    interstitialLoader = new InterstitialAdLoader(activity);
                } else {
                    // Предыдущий запрос отменяем явно: иначе его поздний
                    // onAdLoaded перезаписал бы объявление этой загрузки, и
                    // показан был бы не тот креатив.
                    interstitialLoader.cancelLoading();
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
                            // Эта загрузка могла быть вытеснена следующей:
                            // cancelLoading() не отзывает уже поставленный в
                            // очередь колбэк, и объявление подменило бы текущее.
                            if (pendingInterstitialLoadCall.get() != loadCall) return;
                            interstitialAd = ad;
                            notifyAdEvent("interstitial", "loaded", adUnitId, null, null);
                            settleLoadCall(loadCall, true, null);
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull AdRequestError error) {
                            Log.e(TAG, "Interstitial failed to load: " + error.getDescription());
                            if (pendingInterstitialLoadCall.get() != loadCall) return;
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
        if (isGone(call)) return;
        if (notInitialized(call)) return;

        if (interstitialAd == null) {
            resolveFail(call, "Interstitial not loaded");
            return;
        }

        final PluginCall showCall = hold(call);
        settle(pendingInterstitialShowCall.getAndSet(showCall), false, "Superseded by a new showInterstitial() call");

        AppCompatActivity activity = getActivity();
        if (isActivityGone()) {
            settleInterstitialShow(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            // Вызов могли закрыть, пока показ ждал очереди UI-потока
            // (destroyInterstitial или новый showInterstitial). Тогда показывать
            // нельзя: реклама появилась бы поверх игры, а JS уже считает показ
            // несостоявшимся и события о закрытии не ждёт.
            if (pendingInterstitialShowCall.get() != showCall) return;

            InterstitialAd ad = interstitialAd;
            if (ad == null) {
                settleOwnCall(pendingInterstitialShowCall, showCall, false, "Interstitial not loaded");
                return;
            }
            // С этого момента объявление показывается, а поле предзагрузки
            // свободно: следующее можно грузить, не трогая то, что на экране.
            interstitialAd = null;
            showingInterstitialAd = ad;
            final String shownAdUnitId = interstitialAdUnitId;
            armInterstitialShowWatchdog(showCall, ad);

            ad.setAdEventListener(new InterstitialAdEventListener() {
                @Override
                public void onAdShown() {
                    notifyAdEvent("interstitial", "shown", shownAdUnitId, null, null);
                    // Отвечаем по факту показа, а не по факту вызова show(),
                    // и строго своему вызову: поле могло уже смениться.
                    settleOwnCall(pendingInterstitialShowCall, showCall, true, null);
                }

                @Override
                public void onAdFailedToShow(@NonNull AdError adError) {
                    Log.e(TAG, "Interstitial failed to show: " + adError.getDescription());
                    notifyAdEvent("interstitial", "failed_to_show", shownAdUnitId,
                        errorObject(0, adError.getDescription()), null);
                    releaseShowingInterstitial(ad);
                    settleOwnCall(pendingInterstitialShowCall, showCall, false, adError.getDescription());
                }

                @Override
                public void onAdDismissed() {
                    notifyAdEvent("interstitial", "dismissed", shownAdUnitId, null, null);
                    // Показанный объект переиспользовать нельзя - освобождаем.
                    releaseShowingInterstitial(ad);
                }

                @Override
                public void onAdClicked() {
                    notifyAdEvent("interstitial", "clicked", shownAdUnitId, null, null);
                }

                @Override
                public void onAdImpression(@Nullable ImpressionData impressionData) {
                    notifyAdEvent("interstitial", "impression", shownAdUnitId, null, null);
                }
            });

            try {
                ad.show(activity);
            } catch (Exception e) {
                Log.e(TAG, "Error showing interstitial: " + e.getMessage());
                releaseShowingInterstitial(ad);
                settleOwnCall(pendingInterstitialShowCall, showCall, false, e.getMessage());
            }
        });
    }

    @PluginMethod
    public void isInterstitialLoaded(PluginCall call) {
        if (isGone(call)) return;
        // Без поднятого SDK объявления нет, но ответ обязан быть той же формы:
        // в типе плагина поле loaded объявлено обязательным.
        JSObject ret = new JSObject();
        ret.put("loaded", interstitialAd != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void destroyInterstitial(PluginCall call) {
        if (isGone(call)) return;
        // Слушателя сейчас снимут - ждущий показ иначе висел бы до сторожа.
        settleInterstitialShow(false, "Interstitial destroyed");
        settleAndClearInterstitialLoad(false, "Interstitial destroyed");
        runOnUi(() -> {
            if (interstitialLoader != null) interstitialLoader.cancelLoading();
            destroyInterstitialAd();
            releaseShowingInterstitial(showingInterstitialAd);
            resolveOk(call, null);
        });
    }

    // MARK: - Rewarded

    @PluginMethod
    public void loadRewarded(PluginCall call) {
        if (isGone(call)) return;
        if (notInitialized(call)) return;

        String adUnitId = call.getString("adUnitId");
        if (adUnitId == null || adUnitId.isEmpty()) {
            rejectMissingParameter(call, "adUnitId");
            return;
        }

        rewardedAdUnitId = adUnitId;
        final PluginCall loadCall = hold(call);
        settle(pendingRewardedLoadCall.getAndSet(loadCall), false, "Superseded by a new loadRewarded() call");
        armLoadWatchdog(pendingRewardedLoadCall, loadCall, "rewarded");

        AppCompatActivity activity = getActivity();
        if (isActivityGone()) {
            settleAndClearRewardedLoad(false, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                if (rewardedLoader == null) {
                    rewardedLoader = new RewardedAdLoader(activity);
                } else {
                    rewardedLoader.cancelLoading();
                }

                destroyRewardedAd();

                rewardedLoader.loadAd(
                    new AdRequest.Builder(adUnitId).build(),
                    new RewardedAdLoadListener() {
                        @Override
                        public void onAdLoaded(@NonNull RewardedAd ad) {
                            Log.d(TAG, "Rewarded loaded: " + adUnitId);
                            if (pendingRewardedLoadCall.get() != loadCall) return;
                            rewardedAd = ad;
                            notifyAdEvent("rewarded", "loaded", adUnitId, null, null);
                            settleLoadCall(loadCall, true, null);
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull AdRequestError error) {
                            Log.e(TAG, "Rewarded failed to load: " + error.getDescription());
                            if (pendingRewardedLoadCall.get() != loadCall) return;
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
        if (isGone(call)) return;
        if (notInitialized(call)) return;

        if (rewardedAd == null) {
            resolveFail(call, "Rewarded ad not loaded");
            return;
        }

        final PluginCall showCall = hold(call);
        settle(pendingRewardedShowCall.getAndSet(showCall), false, "Superseded by a new showRewarded() call");

        AppCompatActivity activity = getActivity();
        if (isActivityGone()) {
            settleRewardedShow(false, null, "Activity is gone");
            return;
        }
        activity.runOnUiThread(() -> {
            if (pendingRewardedShowCall.get() != showCall) return;

            RewardedAd ad = rewardedAd;
            if (ad == null) {
                settleRewardedShow(showCall, false, null, "Rewarded ad not loaded");
                return;
            }
            rewardedAd = null;
            showingRewardedAd = ad;

            // Награда принадлежит этому показу. В общем поле её мог бы
            // перезаписать запоздалый onRewarded брошенного ролика - и игрок
            // получил бы награду за непросмотренный.
            final Reward[] reward = { null };
            final String shownAdUnitId = rewardedAdUnitId;
            armRewardedShowWatchdog(showCall, ad, reward);

            ad.setAdEventListener(new RewardedAdEventListener() {
                @Override
                public void onAdShown() {
                    notifyAdEvent("rewarded", "shown", shownAdUnitId, null, null);
                }

                @Override
                public void onAdFailedToShow(@NonNull AdError adError) {
                    Log.e(TAG, "Rewarded failed to show: " + adError.getDescription());
                    notifyAdEvent("rewarded", "failed_to_show", shownAdUnitId,
                        errorObject(0, adError.getDescription()), null);
                    releaseShowingRewarded(ad);
                    // Ролика не было - попытку сжигать нельзя.
                    settleRewardedShow(showCall, false, null, adError.getDescription());
                }

                @Override
                public void onRewarded(@NonNull Reward rewardValue) {
                    Log.d(TAG, "Rewarded: " + rewardValue.getAmount() + " " + rewardValue.getType());
                    reward[0] = rewardValue;

                    JSObject rewardObj = new JSObject();
                    rewardObj.put("amount", rewardValue.getAmount());
                    rewardObj.put("type", rewardValue.getType());
                    notifyAdEvent("rewarded", "rewarded", shownAdUnitId, null, rewardObj);
                }

                @Override
                public void onAdDismissed() {
                    notifyAdEvent("rewarded", "dismissed", shownAdUnitId, null, null);
                    // Только к закрытию ролика ясно, досмотрел его игрок или нет.
                    settleRewardedShow(showCall, true, reward[0], null);
                    releaseShowingRewarded(ad);
                }

                @Override
                public void onAdClicked() {
                    notifyAdEvent("rewarded", "clicked", shownAdUnitId, null, null);
                }

                @Override
                public void onAdImpression(@Nullable ImpressionData impressionData) {
                    notifyAdEvent("rewarded", "impression", shownAdUnitId, null, null);
                }
            });

            try {
                ad.show(activity);
            } catch (Exception e) {
                Log.e(TAG, "Error showing rewarded: " + e.getMessage());
                releaseShowingRewarded(ad);
                settleRewardedShow(showCall, false, null, e.getMessage());
            }
        });
    }

    @PluginMethod
    public void isRewardedLoaded(PluginCall call) {
        if (isGone(call)) return;
        JSObject ret = new JSObject();
        ret.put("loaded", rewardedAd != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void destroyRewarded(PluginCall call) {
        if (isGone(call)) return;
        settleRewardedShow(false, null, "Rewarded ad destroyed");
        settleAndClearRewardedLoad(false, "Rewarded ad destroyed");
        runOnUi(() -> {
            if (rewardedLoader != null) rewardedLoader.cancelLoading();
            destroyRewardedAd();
            releaseShowingRewarded(showingRewardedAd);
            resolveOk(call, null);
        });
    }

    // MARK: - Helpers

    /**
     * Позиции - как getGravity() в Defold-расширении: три горизонтали сверху
     * и снизу плюс центр экрана. "top"/"bottom" - центральные варианты своих
     * рядов; "top-center"/"bottom-center" принимаем как их синонимы.
     */
    private void applyBannerPosition(@NonNull AppCompatActivity activity, @Nullable String position) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bannerLayout.getLayoutParams();
        String pos = position == null ? "bottom" : position.toLowerCase(Locale.ROOT);
        int gravity;
        switch (pos) {
            case "top-left":
                gravity = Gravity.TOP | Gravity.LEFT;
                break;
            case "top":
            case "top-center":
                gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                break;
            case "top-right":
                gravity = Gravity.TOP | Gravity.RIGHT;
                break;
            case "bottom-left":
                gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case "bottom-right":
                gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            case "center":
                gravity = Gravity.CENTER;
                break;
            default:
                gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                break;
        }
        params.gravity = gravity;

        // Верхний ряд опускаем под статусбар, иначе баннер лёг бы под вырез.
        int topMargin = 0;
        if ((gravity & Gravity.TOP) == Gravity.TOP) {
            int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) topMargin = activity.getResources().getDimensionPixelSize(resourceId);
        }
        params.topMargin = topMargin;
        bannerLayout.setLayoutParams(params);
    }

    /**
     * Снимает баннер с экрана. Ждущее обещание загрузки НЕ трогает: этот метод
     * вызывается и в начале новой загрузки, где закрывать только что
     * зарегистрированный вызов нельзя. Закрытием занимаются те, кто
     * действительно обрывает загрузку, - destroyBanner() и releaseAll().
     */
    private void destroyBannerView() {
        isBannerAdLoaded = false;
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

    /**
     * Выполняет действие на UI-потоке.
     *
     * Методы плагина Capacitor зовёт на своём потоке, а работа с View оттуда
     * даёт CalledFromWrongThreadException; исключение же, вылетевшее из метода
     * плагина, Capacitor перебрасывает как RuntimeException и роняет процесс.
     *
     * Ветка с mainHandler - страховка на случай, если Bridge однажды начнёт
     * отдавать null: сегодня getActivity() возвращает одно и то же final-поле и
     * null не бывает (состояние activity проверяется через isActivityGone).
     */
    private void runOnUi(Runnable action) {
        AppCompatActivity activity = getActivity();
        if (activity != null) activity.runOnUiThread(action);
        else mainHandler.post(action);
    }

    /**
     * Activity непригодна для показа рекламы.
     *
     * Проверять getActivity() на null бесполезно: Bridge отдаёт одно и то же
     * final-поле и после onDestroy, поэтому ссылка всегда есть, а activity за
     * ней может быть уже уничтожена. Показ на такой activity даёт
     * BadTokenException, а прикрепление баннера - вью, которую никто не увидит.
     */
    private boolean isActivityGone() {
        AppCompatActivity activity = getActivity();
        return activity == null || activity.isDestroyed() || activity.isFinishing();
    }

    /** Плагин уже разобран - принимать новую работу нельзя. */
    private boolean isGone(PluginCall call) {
        if (!isPluginDestroyed) return false;
        resolveFail(call, "Plugin destroyed");
        return true;
    }

    /**
     * Освобождает показанное объявление. Поле обнуляем только если на экране всё
     * ещё оно: запоздалый колбэк брошенного объявления не должен трогать
     * следующее, которое к этому моменту уже показывается.
     */
    private void releaseShowingInterstitial(@Nullable InterstitialAd ad) {
        if (ad == null) return;
        ad.setAdEventListener(null);
        if (showingInterstitialAd == ad) showingInterstitialAd = null;
    }

    private void releaseShowingRewarded(@Nullable RewardedAd ad) {
        if (ad == null) return;
        ad.setAdEventListener(null);
        if (showingRewardedAd == ad) showingRewardedAd = null;
    }

    /** Закрывает вызов init(), если он всё ещё наш. */
    private void settleOwnInit(PluginCall call, boolean success, @Nullable String message) {
        if (!pendingInitCall.compareAndSet(call, null)) return;
        settle(call, success, message);
    }

    private void releaseAll() {
        destroyBannerView();
        destroyInterstitialAd();
        destroyRewardedAd();
        releaseShowingInterstitial(showingInterstitialAd);
        releaseShowingRewarded(showingRewardedAd);

        if (interstitialLoader != null) {
            interstitialLoader.cancelLoading();
            interstitialLoader = null;
        }
        if (rewardedLoader != null) {
            rewardedLoader.cancelLoading();
            rewardedLoader = null;
        }

        // Ни один колбэк больше не придёт: закрываем всё, что ждало ответа.
        settle(pendingInitCall.getAndSet(null), false, "Plugin destroyed");
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
        // CAS не прошёл - поле уже указывает на другой вызов, значит наш
        // либо погашен, либо вытеснен. Чужое обещание не трогаем.
        if (!holder.compareAndSet(call, null)) return;
        settle(call, success, message);
    }

    private void settleLoadCall(@Nullable PluginCall call, boolean success, @Nullable String message) {
        if (call == null) return;
        // Отвечаем только если поле всё ещё держит именно этот вызов: иначе его
        // уже погасили как вытесненный, и второй ответ был бы лишним.
        boolean isOwner = pendingBannerLoadCall.compareAndSet(call, null)
            || pendingInterstitialLoadCall.compareAndSet(call, null)
            || pendingRewardedLoadCall.compareAndSet(call, null);
        if (!isOwner) return;
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

    /**
     * Через SHOW_TIMEOUT_MS закрывает обещание показа, если колбэк так и не
     * пришёл. Отвечает только своему вызову: к этому моменту мог начаться
     * следующий показ.
     */
    /** Через LOAD_TIMEOUT_MS закрывает обещание загрузки, если колбэка не было. */
    private void armLoadWatchdog(AtomicReference<PluginCall> holder, PluginCall call, String label) {
        // Плагин мог быть разобран, пока метод шёл по очереди моста: тогда
        // задачи уже сняты, и эта пережила бы уборку, удерживая activity.
        if (isPluginDestroyed) {
            settleOwnCall(holder, call, false, "Plugin destroyed");
            return;
        }
        mainHandler.postDelayed(() -> {
            if (holder.get() != call) return;
            Log.w(TAG, label + ": не дождались колбэка загрузки");
            settleOwnCall(holder, call, false, "Load timeout");
        }, LOAD_TIMEOUT_MS);
    }

    /** Без сети колбэк инициализации может не прийти вовсе - не держим вызов вечно. */
    private void armInitWatchdog(PluginCall call) {
        mainHandler.postDelayed(() -> {
            if (!pendingInitCall.compareAndSet(call, null)) return;
            Log.w(TAG, "SDK init timed out");
            notifyAdEvent("init", "failed_to_load", null, errorObject(0, "Initialization timeout"), null);
            settle(call, false, "Initialization timeout");
        }, INIT_TIMEOUT_MS);
    }

    private void armInterstitialShowWatchdog(PluginCall call, InterstitialAd ad) {
        mainHandler.postDelayed(() -> {
            // Объявление освобождаем в любом случае: обещание показа interstitial
            // закрывается уже в onAdShown, и проверка "вызов ещё мой" здесь
            // всегда была бы ложной - объявление со слушателем утекало бы.
            releaseShowingInterstitial(ad);
            if (pendingInterstitialShowCall.get() != call) return;
            Log.w(TAG, "interstitial: не дождались колбэка показа");
            settleOwnCall(pendingInterstitialShowCall, call, false, "Show timeout");
        }, SHOW_TIMEOUT_MS);
    }

    private void armRewardedShowWatchdog(PluginCall call, RewardedAd ad, Reward[] reward) {
        mainHandler.postDelayed(() -> {
            releaseShowingRewarded(ad);
            if (pendingRewardedShowCall.get() != call) return;
            Log.w(TAG, "rewarded: не дождались колбэка показа");
            // Награда могла прийти до того, как ролик потерялся: тогда показ
            // состоялся, и отвечать отказом значило бы отобрать заработанное.
            // Без награды success=false - ролика по сути не было, попытку
            // сжигать нельзя.
            settleRewardedShow(call, reward[0] != null, reward[0], "Show timeout");
        }, SHOW_TIMEOUT_MS);
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
