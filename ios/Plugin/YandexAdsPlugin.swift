import Foundation
import Capacitor
import YandexMobileAds

/**
 * Yandex Mobile Ads SDK 8.x (iOS).
 *
 * По сравнению с SDK 6-7 изменилось почти всё, что трогает этот плагин:
 * префикс YMA у классов исчез, точка входа YMAMobileAds стала YandexAds,
 * загрузчики отвечают через completion-хендлер с Result вместо делегата,
 * а идентификатор блока переехал в AdRequest (YMAAdRequestConfiguration нет).
 */
@objc(YandexAdsPlugin)
public class YandexAdsPlugin: CAPPlugin {

    private var isInitialized = false

    // Banner
    private var bannerAdView: BannerAdView?
    private var bannerAdUnitId: String?
    private var bannerPosition: String = "bottom"
    private var bannerLoadCallId: String?

    // Interstitial
    private let interstitialLoader = InterstitialAdLoader()
    private var interstitialAd: InterstitialAd?
    private var interstitialAdUnitId: String?
    private var interstitialShowCallId: String?

    // Rewarded
    private let rewardedLoader = RewardedAdLoader()
    private var rewardedAd: RewardedAd?
    private var rewardedAdUnitId: String?
    private var rewardedShowCallId: String?
    private var lastReward: Reward?

    // MARK: - SDK

    @objc func `init`(_ call: CAPPluginCall) {
        if isInitialized {
            call.resolve(["success": true, "message": "Already initialized"])
            return
        }

        let userConsent = call.getBool("userConsent")
        let ageRestrictedUser = call.getBool("ageRestrictedUser")
        let locationTracking = call.getBool("locationTracking")
        let enableLogging = call.getBool("enableLogging") ?? false

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // Политики приватности выставляются до инициализации SDK.
            if let userConsent = userConsent { YandexAds.setUserConsent(userConsent) }
            if let ageRestrictedUser = ageRestrictedUser { YandexAds.setAgeRestricted(ageRestrictedUser) }
            if let locationTracking = locationTracking { YandexAds.setLocationTracking(locationTracking) }
            // На iOS у enableLogging нет аргумента, в отличие от Android.
            if enableLogging { YandexAds.enableLogging() }

            // Без сети колбэк может не прийти вовсе - не держим вызов вечно.
            var isSettled = false

            YandexAds.initializeSDK { [weak self] in
                guard let self = self else { return }
                // Флаг ставим до проверки сторожа: SDK готов независимо от того,
                // успели ли мы ответить по таймауту.
                self.isInitialized = true
                if isSettled { return }
                isSettled = true

                self.notifyAdEvent(adType: "init", event: "loaded", adUnitId: nil, error: nil, reward: nil)
                call.resolve(["success": true])
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + Self.initTimeout) {
                if isSettled { return }
                isSettled = true
                self.resolveFail(call, "Initialization timeout")
            }
        }
    }

    private static let initTimeout: TimeInterval = 10

    // MARK: - Banner

    @objc func loadBanner(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        guard let adUnitId = call.getString("adUnitId"), !adUnitId.isEmpty else {
            resolveFail(call, "Missing required parameter: adUnitId")
            return
        }
        guard let sizeObj = call.getObject("size"), let width = sizeObj["width"] as? Int else {
            resolveFail(call, "Missing required parameter: size.width")
            return
        }

        let height = sizeObj["height"] as? Int
        bannerAdUnitId = adUnitId
        bannerPosition = call.getString("position") ?? "bottom"

        // Вызов удерживаем до загрузки и делаем это ДО loadAd: делегат может
        // сработать синхронно, и тогда отвечать было бы уже некому.
        settle(&bannerLoadCallId, with: ["success": false, "message": "Superseded by a new loadBanner() call"])
        bannerLoadCallId = hold(call)

        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard let rootView = self.bridge?.viewController?.view else {
                self.resolveFail(call, "Failed to get view controller")
                return
            }

            self.destroyBannerView()

            let containerWidth = width > 0 ? CGFloat(width) : rootView.bounds.width
            let size: BannerAdSize = (height != nil && height! > 0)
                ? BannerAdSize.inline(width: containerWidth, maxHeight: CGFloat(height!))
                : BannerAdSize.sticky(containerWidth: containerWidth)

            let view = BannerAdView(adSize: size)
            view.delegate = self
            view.translatesAutoresizingMaskIntoConstraints = false
            view.isHidden = true
            self.bannerAdView = view

            // Показ и позиционирование - в showBanner: до загрузки вью пустая.
            self.attachBanner(view, to: rootView)

            // Идентификатор блока теперь часть запроса, а не свойство view.
            view.loadAd(with: AdRequest(adUnitID: adUnitId))
        }
    }

    @objc func showBanner(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let view = self.bannerAdView else {
                self?.resolveFail(call, "Banner not loaded")
                return
            }
            view.isHidden = false
            self.notifyAdEvent(adType: "banner", event: "shown", adUnitId: self.bannerAdUnitId, error: nil, reward: nil)
            call.resolve(["success": true])
        }
    }

    @objc func hideBanner(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let view = self.bannerAdView else {
                self?.resolveFail(call, "Banner not loaded")
                return
            }
            view.isHidden = true
            self.notifyAdEvent(adType: "banner", event: "dismissed", adUnitId: self.bannerAdUnitId, error: nil, reward: nil)
            call.resolve(["success": true])
        }
    }

    @objc func destroyBanner(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.destroyBannerView()
            call.resolve(["success": true])
        }
    }

    // MARK: - Interstitial

    @objc func loadInterstitial(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        guard let adUnitId = call.getString("adUnitId"), !adUnitId.isEmpty else {
            resolveFail(call, "Missing required parameter: adUnitId")
            return
        }

        interstitialAdUnitId = adUnitId

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.interstitialAd = nil

            // Загрузчик отвечает в замыкание, поэтому вызов JS-стороны
            // захватывается напрямую и гонок между загрузками нет.
            self.interstitialLoader.loadAd(with: AdRequest(adUnitID: adUnitId)) { [weak self] result in
                guard let self = self else { return }
                switch result {
                case .success(let ad):
                    ad.delegate = self
                    self.interstitialAd = ad
                    self.notifyAdEvent(adType: "interstitial", event: "loaded", adUnitId: adUnitId, error: nil, reward: nil)
                    call.resolve(["success": true])
                case .failure(let error):
                    self.interstitialAd = nil
                    self.notifyAdEvent(adType: "interstitial", event: "failed_to_load", adUnitId: adUnitId,
                                       error: self.errorObject(error), reward: nil)
                    call.resolve(["success": false, "message": error.localizedDescription])
                }
            }
        }
    }

    @objc func showInterstitial(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        guard let ad = interstitialAd else {
            resolveFail(call, "Interstitial not loaded")
            return
        }

        settleInterstitialShow(shown: false, message: "Superseded by a new showInterstitial() call")
        interstitialShowCallId = hold(call)

        // @MainActor: InterstitialAd изолирован главным актором, простое
        // DispatchQueue.main.async этого компилятору не доказывает.
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard let viewController = self.bridge?.viewController else {
                self.settleInterstitialShow(shown: false, message: "Failed to get view controller")
                return
            }
            // Ответ придёт из interstitialAdDidShow / didFailToShow.
            ad.show(from: viewController)
        }
    }

    @objc func isInterstitialLoaded(_ call: CAPPluginCall) {
        call.resolve(["loaded": interstitialAd != nil])
    }

    @objc func destroyInterstitial(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.interstitialAd = nil
            // Делегата больше не будет - закрываем ждущее обещание показа.
            self.settleInterstitialShow(shown: false, message: "Interstitial destroyed")
            call.resolve(["success": true])
        }
    }

    // MARK: - Rewarded

    @objc func loadRewarded(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        guard let adUnitId = call.getString("adUnitId"), !adUnitId.isEmpty else {
            resolveFail(call, "Missing required parameter: adUnitId")
            return
        }

        rewardedAdUnitId = adUnitId

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.rewardedAd = nil
            self.lastReward = nil

            self.rewardedLoader.loadAd(with: AdRequest(adUnitID: adUnitId)) { [weak self] result in
                guard let self = self else { return }
                switch result {
                case .success(let ad):
                    ad.delegate = self
                    self.rewardedAd = ad
                    self.notifyAdEvent(adType: "rewarded", event: "loaded", adUnitId: adUnitId, error: nil, reward: nil)
                    call.resolve(["success": true])
                case .failure(let error):
                    self.rewardedAd = nil
                    self.notifyAdEvent(adType: "rewarded", event: "failed_to_load", adUnitId: adUnitId,
                                       error: self.errorObject(error), reward: nil)
                    call.resolve(["success": false, "message": error.localizedDescription])
                }
            }
        }
    }

    @objc func showRewarded(_ call: CAPPluginCall) {
        guard checkInitialized(call) else { return }

        guard let ad = rewardedAd else {
            resolveFail(call, "Rewarded ad not loaded")
            return
        }

        settleRewardedShow(shown: false, reward: nil, message: "Superseded by a new showRewarded() call")
        // Награда прошлого показа не должна засчитаться этому.
        lastReward = nil
        rewardedShowCallId = hold(call)

        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard let viewController = self.bridge?.viewController else {
                self.settleRewardedShow(shown: false, reward: nil, message: "Failed to get view controller")
                return
            }
            // Ответ придёт из rewardedAdDidDismiss: только к закрытию ролика
            // известно, досмотрел его игрок или прервал.
            ad.show(from: viewController)
        }
    }

    @objc func isRewardedLoaded(_ call: CAPPluginCall) {
        call.resolve(["loaded": rewardedAd != nil])
    }

    @objc func destroyRewarded(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.rewardedAd = nil
            self.settleRewardedShow(shown: false, reward: nil, message: "Rewarded ad destroyed")
            call.resolve(["success": true])
        }
    }

    // MARK: - Helpers

    private func attachBanner(_ view: BannerAdView, to rootView: UIView) {
        rootView.addSubview(view)
        let guide = rootView.safeAreaLayoutGuide
        var constraints: [NSLayoutConstraint] = [view.centerXAnchor.constraint(equalTo: guide.centerXAnchor)]
        if bannerPosition.lowercased() == "top" {
            constraints.append(view.topAnchor.constraint(equalTo: guide.topAnchor))
        } else {
            constraints.append(view.bottomAnchor.constraint(equalTo: guide.bottomAnchor))
        }
        NSLayoutConstraint.activate(constraints)
    }

    private func destroyBannerView() {
        // Делегата больше не будет - ждущее обещание загрузки закрываем здесь.
        settle(&bannerLoadCallId, with: ["success": false, "message": "Banner destroyed"])
        bannerAdView?.delegate = nil
        bannerAdView?.removeFromSuperview()
        bannerAdView = nil
    }

    /// Запоминает вызов до прихода нативного колбэка.
    private func hold(_ call: CAPPluginCall) -> String? {
        call.keepAlive = true
        bridge?.saveCall(call)
        return call.callbackId
    }

    /// Отвечает на ранее отложенный вызов ровно один раз и освобождает его.
    private func settle(_ callId: inout String?, with data: [String: Any]) {
        guard let id = callId, let call = bridge?.savedCall(withID: id) else {
            callId = nil
            return
        }
        callId = nil
        // keepAlive снимаем до ответа, иначе JS-сторона не освободит колбэк.
        call.keepAlive = false
        call.resolve(data)
        bridge?.releaseCall(call)
    }

    private func settleInterstitialShow(shown: Bool, message: String?) {
        var result: [String: Any] = ["success": shown]
        if let message = message { result["message"] = message }
        settle(&interstitialShowCallId, with: result)
    }

    /// Отдаёт результат показа rewarded-ролика ровно один раз.
    ///
    /// shown=false означает "ролик не показали" - попытку сжигать нельзя;
    /// shown=true с rewarded=false означает "показали, но игрок прервал".
    private func settleRewardedShow(shown: Bool, reward: Reward?, message: String?) {
        var result: [String: Any] = ["success": shown]
        if let message = message { result["message"] = message }
        if shown {
            result["rewarded"] = reward != nil
            if let reward = reward {
                result["reward"] = ["amount": reward.amount, "type": reward.type]
            }
        }
        settle(&rewardedShowCallId, with: result)
    }

    private func notifyAdEvent(adType: String, event: String, adUnitId: String?,
                               error: [String: Any]?, reward: [String: Any]?) {
        var eventData: [String: Any] = ["adType": adType, "event": event]
        if let adUnitId = adUnitId { eventData["adUnitId"] = adUnitId }
        if let error = error { eventData["error"] = error }
        if let reward = reward { eventData["reward"] = reward }
        notifyListeners("adEvent", data: eventData)
    }

    private func errorObject(_ error: Error) -> [String: Any] {
        return ["code": (error as NSError).code, "message": error.localizedDescription]
    }

    private func checkInitialized(_ call: CAPPluginCall) -> Bool {
        if isInitialized { return true }
        resolveFail(call, "SDK not initialized. Call init() first.")
        return false
    }

    private func resolveFail(_ call: CAPPluginCall, _ message: String) {
        call.resolve(["success": false, "message": message])
    }
}

// MARK: - BannerAdViewDelegate

extension YandexAdsPlugin: BannerAdViewDelegate {
    public func bannerAdViewDidLoad(_ bannerAdView: BannerAdView) {
        notifyAdEvent(adType: "banner", event: "loaded", adUnitId: bannerAdUnitId, error: nil, reward: nil)
        settle(&bannerLoadCallId, with: ["success": true])
    }

    public func bannerAdViewDidFailLoading(_ bannerAdView: BannerAdView, error: Error) {
        notifyAdEvent(adType: "banner", event: "failed_to_load", adUnitId: bannerAdUnitId,
                      error: errorObject(error), reward: nil)
        settle(&bannerLoadCallId, with: ["success": false, "message": error.localizedDescription])
    }

    public func bannerAdViewDidClick(_ bannerAdView: BannerAdView) {
        notifyAdEvent(adType: "banner", event: "clicked", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }

    public func bannerAdView(_ bannerAdView: BannerAdView, didTrackImpression impressionData: ImpressionData?) {
        notifyAdEvent(adType: "banner", event: "impression", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }
}

// MARK: - InterstitialAdDelegate

extension YandexAdsPlugin: InterstitialAdDelegate {
    public func interstitialAdDidShow(_ interstitialAd: InterstitialAd) {
        notifyAdEvent(adType: "interstitial", event: "shown", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
        // Отвечаем по факту показа, а не по факту вызова show().
        settleInterstitialShow(shown: true, message: nil)
    }

    public func interstitialAd(_ interstitialAd: InterstitialAd, didFailToShow error: Error) {
        self.interstitialAd = nil
        notifyAdEvent(adType: "interstitial", event: "failed_to_show", adUnitId: interstitialAdUnitId,
                      error: errorObject(error), reward: nil)
        settleInterstitialShow(shown: false, message: error.localizedDescription)
    }

    public func interstitialAdDidDismiss(_ interstitialAd: InterstitialAd) {
        // Показанный объект переиспользовать нельзя - освобождаем.
        self.interstitialAd = nil
        notifyAdEvent(adType: "interstitial", event: "dismissed", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAdDidClick(_ interstitialAd: InterstitialAd) {
        notifyAdEvent(adType: "interstitial", event: "clicked", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAd(_ interstitialAd: InterstitialAd, didTrackImpression impressionData: ImpressionData?) {
        notifyAdEvent(adType: "interstitial", event: "impression", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }
}

// MARK: - RewardedAdDelegate

extension YandexAdsPlugin: RewardedAdDelegate {
    public func rewardedAdDidShow(_ rewardedAd: RewardedAd) {
        notifyAdEvent(adType: "rewarded", event: "shown", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAd(_ rewardedAd: RewardedAd, didFailToShow error: Error) {
        self.rewardedAd = nil
        notifyAdEvent(adType: "rewarded", event: "failed_to_show", adUnitId: rewardedAdUnitId,
                      error: errorObject(error), reward: nil)
        // Ролика не было - попытку сжигать нельзя.
        settleRewardedShow(shown: false, reward: nil, message: error.localizedDescription)
    }

    public func rewardedAd(_ rewardedAd: RewardedAd, didReward reward: Reward) {
        lastReward = reward
        notifyAdEvent(adType: "rewarded", event: "rewarded", adUnitId: rewardedAdUnitId, error: nil,
                      reward: ["amount": reward.amount, "type": reward.type])
    }

    public func rewardedAdDidDismiss(_ rewardedAd: RewardedAd) {
        notifyAdEvent(adType: "rewarded", event: "dismissed", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
        // Только к закрытию ролика ясно, досмотрел его игрок или нет.
        settleRewardedShow(shown: true, reward: lastReward, message: nil)
        self.rewardedAd = nil
    }

    public func rewardedAdDidClick(_ rewardedAd: RewardedAd) {
        notifyAdEvent(adType: "rewarded", event: "clicked", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAd(_ rewardedAd: RewardedAd, didTrackImpression impressionData: ImpressionData?) {
        notifyAdEvent(adType: "rewarded", event: "impression", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }
}
