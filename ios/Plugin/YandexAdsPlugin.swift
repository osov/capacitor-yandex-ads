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
 *
 * Про потоки. Capacitor исполняет методы плагина на своей фоновой очереди, а
 * делегаты SDK и сторожевые таймеры приходят на главный поток. Поэтому всё
 * изменяемое состояние трогаем только на главном потоке: каждый метод сразу
 * уходит в DispatchQueue.main.async и уже там читает поля. Android-двойник
 * решает ту же задачу через volatile и AtomicReference.
 *
 * Замыкания, внутри которых вызывается API SDK или UIKit, помечены @MainActor:
 * эти типы изолированы главным актором, и одного DispatchQueue.main компилятору
 * недостаточно. По той же причине загрузчики создаются лениво уже на главном
 * потоке, а не в инициализаторе свойств - тот выполняется неизолированно.
 */
@objc(YandexAdsPlugin)
public class YandexAdsPlugin: CAPPlugin {

    private var isInitialized = false
    // Инициализация уже идёт: второй init() должен ждать, а не поднимать SDK
    // повторно. Размер очереди для этого не годится - она пустеет по таймауту.
    private var isInitializing = false
    // Вызовы init(), ждущие ответа SDK. Именно список, а не одно поле: два
    // параллельных init() затирали бы друг друга, и первый не закрылся бы никогда.
    private var pendingInitCalls: [CAPPluginCall] = []

    // Banner
    private var bannerAdView: BannerAdView?
    private var bannerAdUnitId: String?
    private var bannerPosition: String = "bottom"
    private var bannerLoadCallId: String?

    // Interstitial
    private var interstitialLoader: InterstitialAdLoader?
    // Предзагруженное объявление и показываемое сейчас - это разные поля.
    // Иначе предзагрузка во время показа затиралась бы закрытием показанного.
    private var interstitialAd: InterstitialAd?
    private var showingInterstitialAd: InterstitialAd?
    private var interstitialAdUnitId: String?
    private var interstitialLoadCallId: String?
    private var interstitialShowCallId: String?

    // Rewarded
    private var rewardedLoader: RewardedAdLoader?
    private var rewardedAd: RewardedAd?
    private var showingRewardedAd: RewardedAd?
    private var rewardedAdUnitId: String?
    private var rewardedLoadCallId: String?
    private var rewardedShowCallId: String?
    private var lastReward: Reward?

    // MARK: - SDK

    @objc func `init`(_ call: CAPPluginCall) {
        let userConsent = call.getBool("userConsent")
        let ageRestrictedUser = call.getBool("ageRestrictedUser")
        let locationTracking = call.getBool("locationTracking")
        let enableLogging = call.getBool("enableLogging") ?? false

        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }

            if self.isInitialized {
                call.resolve(["success": true, "message": "Already initialized"])
                return
            }

            // Политики приватности выставляются до инициализации SDK.
            if let userConsent = userConsent { YandexAds.setUserConsent(userConsent) }
            if let ageRestrictedUser = ageRestrictedUser { YandexAds.setAgeRestricted(ageRestrictedUser) }
            if let locationTracking = locationTracking { YandexAds.setLocationTracking(locationTracking) }
            // На iOS у enableLogging нет аргумента, в отличие от Android.
            if enableLogging { YandexAds.enableLogging() }

            self.pendingInitCalls.append(call)

            // Без сети колбэк может не прийти вовсе - не держим вызов вечно.
            // Сторож закрывает только свой вызов, остальные продолжают ждать.
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.initTimeout) { [weak self] in
                guard let self = self else { return }
                guard let index = self.pendingInitCalls.firstIndex(where: { $0 === call }) else { return }
                self.pendingInitCalls.remove(at: index)
                // Событие с тем же составом, что на Android: поле error в
                // AdFailedToLoadEvent объявлено обязательным.
                self.notifyAdEvent(adType: "init", event: "failed_to_load", adUnitId: nil,
                                   error: self.errorObject(code: 0, message: "Initialization timeout"), reward: nil)
                self.resolveFail(call, "Initialization timeout")
            }

            // SDK поднимаем один раз, остальные вызовы просто ждут ответа.
            guard !self.isInitializing else { return }
            self.isInitializing = true

            // Колбэк помечен @Sendable: ничего не захватываем и сразу
            // возвращаемся на главный поток. Захват self даже слабый сделал бы
            // замыкание несендабельным.
            YandexAds.initializeSDK {
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    // Флаг ставим независимо от того, ждёт ли ещё кто-то ответа:
                    // SDK готов, даже если все вызовы уже закрылись по таймауту.
                    self.isInitialized = true
                    self.isInitializing = false
                    let waiting = self.pendingInitCalls
                    self.pendingInitCalls.removeAll()
                    guard !waiting.isEmpty else { return }
                    self.notifyAdEvent(adType: "init", event: "loaded", adUnitId: nil, error: nil, reward: nil)
                    for pending in waiting { pending.resolve(["success": true]) }
                }
            }
        }
    }

    private static let initTimeout: TimeInterval = 10

    // MARK: - Banner

    @objc func loadBanner(_ call: CAPPluginCall) {
        guard let adUnitId = call.getString("adUnitId"), !adUnitId.isEmpty else {
            resolveFail(call, "Missing required parameter: adUnitId")
            return
        }
        guard let sizeObj = call.getObject("size") else {
            resolveFail(call, "Missing required parameter: size")
            return
        }
        // Через мост число приезжает как NSNumber: приведение к Int сорвалось бы
        // на нецелом значении, тогда как Android его округляет. Берём NSNumber.
        guard let width = (sizeObj["width"] as? NSNumber)?.intValue else {
            resolveFail(call, "Missing required parameter: size.width")
            return
        }

        let height = (sizeObj["height"] as? NSNumber)?.intValue
        let position = call.getString("position") ?? "bottom"

        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }

            self.bannerAdUnitId = adUnitId
            self.bannerPosition = position

            // Прошлую загрузку закрываем до регистрации новой.
            self.settle(&self.bannerLoadCallId,
                        with: ["success": false, "message": "Superseded by a new loadBanner() call"])
            guard let heldId = self.hold(call) else {
                self.resolveFail(call, "Bridge is gone")
                return
            }
            self.bannerLoadCallId = heldId

            guard let rootView = self.bridge?.viewController?.view else {
                // Вызов уже удержан - отвечаем через settle, иначе он останется
                // в saved calls и разрешится второй раз.
                self.settle(&self.bannerLoadCallId,
                            with: ["success": false, "message": "Failed to get view controller"])
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
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }
            guard let view = self.bannerAdView else {
                self.resolveFail(call, "Banner not loaded")
                return
            }
            view.isHidden = false
            self.notifyAdEvent(adType: "banner", event: "shown", adUnitId: self.bannerAdUnitId, error: nil, reward: nil)
            call.resolve(["success": true])
        }
    }

    @objc func hideBanner(_ call: CAPPluginCall) {
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }
            guard let view = self.bannerAdView else {
                self.resolveFail(call, "Banner not loaded")
                return
            }
            view.isHidden = true
            self.notifyAdEvent(adType: "banner", event: "dismissed", adUnitId: self.bannerAdUnitId, error: nil, reward: nil)
            call.resolve(["success": true])
        }
    }

    @objc func destroyBanner(_ call: CAPPluginCall) {
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            // Здесь загрузка действительно обрывается - закрываем её обещание.
            self.settle(&self.bannerLoadCallId, with: ["success": false, "message": "Banner destroyed"])
            self.destroyBannerView()
            call.resolve(["success": true])
        }
    }

    // MARK: - Interstitial

    @objc func loadInterstitial(_ call: CAPPluginCall) {
        guard let adUnitId = call.getString("adUnitId"), !adUnitId.isEmpty else {
            resolveFail(call, "Missing required parameter: adUnitId")
            return
        }

        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }

            self.interstitialAdUnitId = adUnitId
            // Показываемое сейчас объявление живёт в отдельном поле, поэтому
            // предзагрузка во время показа безопасна: типовой сценарий - грузить
            // следующее по событию shown.
            self.interstitialAd = nil

            // Загрузчик держит один запрос, и колбэк вытесненного может не
            // прийти вовсе - закрываем его обещание сами, как на Android.
            self.settle(&self.interstitialLoadCallId,
                        with: ["success": false, "message": "Superseded by a new loadInterstitial() call"])
            guard let heldId = self.hold(call) else {
                self.resolveFail(call, "Bridge is gone")
                return
            }
            self.interstitialLoadCallId = heldId

            if self.interstitialLoader == nil { self.interstitialLoader = InterstitialAdLoader() }
            self.interstitialLoader?.loadAd(with: AdRequest(adUnitID: adUnitId)) { [weak self] result in
                DispatchQueue.main.async { @MainActor in
                    guard let self = self else { return }
                    switch result {
                    case .success(let ad):
                        ad.delegate = self
                        self.interstitialAd = ad
                        self.notifyAdEvent(adType: "interstitial", event: "loaded", adUnitId: adUnitId, error: nil, reward: nil)
                        self.settle(&self.interstitialLoadCallId, with: ["success": true])
                    case .failure(let error):
                        self.interstitialAd = nil
                        self.notifyAdEvent(adType: "interstitial", event: "failed_to_load", adUnitId: adUnitId,
                                           error: self.errorObject(error), reward: nil)
                        self.settle(&self.interstitialLoadCallId,
                                    with: ["success": false, "message": error.localizedDescription])
                    }
                }
            }
        }
    }

    @objc func showInterstitial(_ call: CAPPluginCall) {
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }

            // Проверка по объявлению на экране, а не по ждущему вызову: вызов
            // закрывается уже в didShow, когда реклама ещё видна.
            guard self.showingInterstitialAd == nil else {
                self.resolveFail(call, "Interstitial is being shown")
                return
            }
            guard let ad = self.interstitialAd else {
                self.resolveFail(call, "Interstitial not loaded")
                return
            }
            guard let viewController = self.bridge?.viewController else {
                self.resolveFail(call, "Failed to get view controller")
                return
            }
            guard let heldId = self.hold(call) else {
                self.resolveFail(call, "Bridge is gone")
                return
            }

            self.interstitialAd = nil
            self.showingInterstitialAd = ad
            self.interstitialShowCallId = heldId
            self.armInterstitialWatchdog(ad)

            // Ответ придёт из interstitialAdDidShow / didFailToShow.
            ad.show(from: viewController)
        }
    }

    @objc func isInterstitialLoaded(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            call.resolve(["loaded": self?.interstitialAd != nil])
        }
    }

    @objc func destroyInterstitial(_ call: CAPPluginCall) {
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            self.interstitialAd = nil
            self.showingInterstitialAd = nil
            // Делегата больше не будет - закрываем ждущее обещание показа.
            self.settleInterstitialShow(shown: false, message: "Interstitial destroyed")
            call.resolve(["success": true])
        }
    }

    // MARK: - Rewarded

    @objc func loadRewarded(_ call: CAPPluginCall) {
        guard let adUnitId = call.getString("adUnitId"), !adUnitId.isEmpty else {
            resolveFail(call, "Missing required parameter: adUnitId")
            return
        }

        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }

            self.rewardedAdUnitId = adUnitId
            self.rewardedAd = nil
            // lastReward относится к показываемому ролику и здесь не трогается:
            // сброс стёр бы уже выданную награду, если грузить во время показа.

            self.settle(&self.rewardedLoadCallId,
                        with: ["success": false, "message": "Superseded by a new loadRewarded() call"])
            guard let heldId = self.hold(call) else {
                self.resolveFail(call, "Bridge is gone")
                return
            }
            self.rewardedLoadCallId = heldId

            if self.rewardedLoader == nil { self.rewardedLoader = RewardedAdLoader() }
            self.rewardedLoader?.loadAd(with: AdRequest(adUnitID: adUnitId)) { [weak self] result in
                DispatchQueue.main.async { @MainActor in
                    guard let self = self else { return }
                    switch result {
                    case .success(let ad):
                        ad.delegate = self
                        self.rewardedAd = ad
                        self.notifyAdEvent(adType: "rewarded", event: "loaded", adUnitId: adUnitId, error: nil, reward: nil)
                        self.settle(&self.rewardedLoadCallId, with: ["success": true])
                    case .failure(let error):
                        self.rewardedAd = nil
                        self.notifyAdEvent(adType: "rewarded", event: "failed_to_load", adUnitId: adUnitId,
                                           error: self.errorObject(error), reward: nil)
                        self.settle(&self.rewardedLoadCallId,
                                    with: ["success": false, "message": error.localizedDescription])
                    }
                }
            }
        }
    }

    @objc func showRewarded(_ call: CAPPluginCall) {
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            guard self.checkInitialized(call) else { return }

            guard self.showingRewardedAd == nil else {
                self.resolveFail(call, "Rewarded ad is being shown")
                return
            }
            guard let ad = self.rewardedAd else {
                self.resolveFail(call, "Rewarded ad not loaded")
                return
            }
            guard let viewController = self.bridge?.viewController else {
                self.resolveFail(call, "Failed to get view controller")
                return
            }
            guard let heldId = self.hold(call) else {
                self.resolveFail(call, "Bridge is gone")
                return
            }

            // Награда прошлого показа не должна засчитаться этому.
            self.lastReward = nil
            self.rewardedAd = nil
            self.showingRewardedAd = ad
            self.rewardedShowCallId = heldId
            self.armRewardedWatchdog(ad)

            // Ответ придёт из rewardedAdDidDismiss: только к закрытию ролика
            // известно, досмотрел его игрок или прервал.
            ad.show(from: viewController)
        }
    }

    @objc func isRewardedLoaded(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            call.resolve(["loaded": self?.rewardedAd != nil])
        }
    }

    @objc func destroyRewarded(_ call: CAPPluginCall) {
        DispatchQueue.main.async { @MainActor [weak self] in
            guard let self = self else { return }
            self.rewardedAd = nil
            self.showingRewardedAd = nil
            self.lastReward = nil
            self.settleRewardedShow(shown: false, reward: nil, message: "Rewarded ad destroyed")
            call.resolve(["success": true])
        }
    }

    // MARK: - Helpers

    @MainActor
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

    /// Снимает баннер с экрана. Ждущее обещание загрузки НЕ трогает: метод
    /// вызывается и в начале новой загрузки, где закрывать только что
    /// зарегистрированный вызов нельзя.
    @MainActor
    private func destroyBannerView() {
        bannerAdView?.delegate = nil
        bannerAdView?.removeFromSuperview()
        bannerAdView = nil
    }

    /// Запоминает вызов до прихода нативного колбэка.
    ///
    /// Возвращает nil, если моста уже нет: тогда вызов нигде не сохранён, и
    /// записывать его идентификатор нельзя - ответить по нему потом не выйдет,
    /// а обещание на JS-стороне повисло бы навсегда.
    private func hold(_ call: CAPPluginCall) -> String? {
        guard let bridge = bridge else { return nil }
        call.keepAlive = true
        bridge.saveCall(call)
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

    /// Сторож показа привязан к объявлению, а не к ждущему вызову: обещание
    /// показа interstitial закрывается уже в didShow, и проверка «вызов ещё мой»
    /// в этот момент всегда ложна. Без освобождения объявления поле
    /// showingInterstitialAd осталось бы занятым, а показ - заблокированным
    /// до конца сессии.
    private func armInterstitialWatchdog(_ ad: InterstitialAd) {
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.showTimeout) { [weak self] in
            guard let self = self else { return }
            guard self.showingInterstitialAd === ad else { return }
            self.showingInterstitialAd = nil
            self.settleInterstitialShow(shown: false, message: "Show timeout")
        }
    }

    private func armRewardedWatchdog(_ ad: RewardedAd) {
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.showTimeout) { [weak self] in
            guard let self = self else { return }
            guard self.showingRewardedAd === ad else { return }
            self.showingRewardedAd = nil
            // Награда могла прийти до того, как ролик потерялся, - тогда показ
            // состоялся и попытку надо засчитать.
            let reward = self.lastReward
            self.lastReward = nil
            self.settleRewardedShow(shown: reward != nil, reward: reward, message: "Show timeout")
        }
    }

    private static let showTimeout: TimeInterval = 5 * 60

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
                let rewardData: [String: Any] = ["amount": reward.amount, "type": reward.type]
                result["reward"] = rewardData
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
        return errorObject(code: (error as NSError).code, message: error.localizedDescription)
    }

    private func errorObject(code: Int, message: String) -> [String: Any] {
        return ["code": code, "message": message]
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
        // Колбэк мог прийти от вью, которую уже сменила следующая загрузка.
        guard bannerAdView === self.bannerAdView else { return }
        notifyAdEvent(adType: "banner", event: "loaded", adUnitId: bannerAdUnitId, error: nil, reward: nil)
        settle(&bannerLoadCallId, with: ["success": true])
    }

    public func bannerAdViewDidFailLoading(_ bannerAdView: BannerAdView, error: Error) {
        guard bannerAdView === self.bannerAdView else { return }
        notifyAdEvent(adType: "banner", event: "failed_to_load", adUnitId: bannerAdUnitId,
                      error: errorObject(error), reward: nil)
        settle(&bannerLoadCallId, with: ["success": false, "message": error.localizedDescription])
    }

    public func bannerAdViewDidClick(_ bannerAdView: BannerAdView) {
        guard bannerAdView === self.bannerAdView else { return }
        notifyAdEvent(adType: "banner", event: "clicked", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }

    public func bannerAdView(_ bannerAdView: BannerAdView, didTrackImpression impressionData: ImpressionData?) {
        guard bannerAdView === self.bannerAdView else { return }
        notifyAdEvent(adType: "banner", event: "impression", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }
}

// MARK: - InterstitialAdDelegate

/**
 * Все делегаты сверяют объявление с показываемым сейчас. Без этого брошенное
 * объявление (destroyInterstitial во время показа, сторож по таймауту) закрывало
 * бы своим запоздалым колбэком обещание уже следующего показа.
 */
extension YandexAdsPlugin: InterstitialAdDelegate {
    public func interstitialAdDidShow(_ interstitialAd: InterstitialAd) {
        guard interstitialAd === showingInterstitialAd else { return }
        notifyAdEvent(adType: "interstitial", event: "shown", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
        // Отвечаем по факту показа, а не по факту вызова show().
        settleInterstitialShow(shown: true, message: nil)
    }

    public func interstitialAd(_ interstitialAd: InterstitialAd, didFailToShow error: Error) {
        guard interstitialAd === showingInterstitialAd else { return }
        showingInterstitialAd = nil
        notifyAdEvent(adType: "interstitial", event: "failed_to_show", adUnitId: interstitialAdUnitId,
                      error: errorObject(error), reward: nil)
        settleInterstitialShow(shown: false, message: error.localizedDescription)
    }

    public func interstitialAdDidDismiss(_ interstitialAd: InterstitialAd) {
        guard interstitialAd === showingInterstitialAd else { return }
        // Показанный объект переиспользовать нельзя - освобождаем. Предзагрузку
        // в interstitialAd при этом не трогаем: это уже следующее объявление.
        showingInterstitialAd = nil
        notifyAdEvent(adType: "interstitial", event: "dismissed", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAdDidClick(_ interstitialAd: InterstitialAd) {
        guard interstitialAd === showingInterstitialAd else { return }
        notifyAdEvent(adType: "interstitial", event: "clicked", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAd(_ interstitialAd: InterstitialAd, didTrackImpression impressionData: ImpressionData?) {
        guard interstitialAd === showingInterstitialAd else { return }
        notifyAdEvent(adType: "interstitial", event: "impression", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }
}

// MARK: - RewardedAdDelegate

extension YandexAdsPlugin: RewardedAdDelegate {
    public func rewardedAdDidShow(_ rewardedAd: RewardedAd) {
        guard rewardedAd === showingRewardedAd else { return }
        notifyAdEvent(adType: "rewarded", event: "shown", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAd(_ rewardedAd: RewardedAd, didFailToShow error: Error) {
        guard rewardedAd === showingRewardedAd else { return }
        showingRewardedAd = nil
        notifyAdEvent(adType: "rewarded", event: "failed_to_show", adUnitId: rewardedAdUnitId,
                      error: errorObject(error), reward: nil)
        // Ролика не было - попытку сжигать нельзя.
        settleRewardedShow(shown: false, reward: nil, message: error.localizedDescription)
    }

    public func rewardedAd(_ rewardedAd: RewardedAd, didReward reward: Reward) {
        // Награда может прийти и после закрытия ролика: поле уже обнулено, но
        // обещание ещё ждёт - сверяемся с ним, иначе награда потерялась бы.
        guard rewardedAd === showingRewardedAd || rewardedShowCallId != nil else { return }
        lastReward = reward
        let rewardData: [String: Any] = ["amount": reward.amount, "type": reward.type]
        notifyAdEvent(adType: "rewarded", event: "rewarded", adUnitId: rewardedAdUnitId, error: nil,
                      reward: rewardData)
    }

    public func rewardedAdDidDismiss(_ rewardedAd: RewardedAd) {
        guard rewardedAd === showingRewardedAd else { return }
        showingRewardedAd = nil
        notifyAdEvent(adType: "rewarded", event: "dismissed", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
        // Только к закрытию ролика ясно, досмотрел его игрок или нет.
        settleRewardedShow(shown: true, reward: lastReward, message: nil)
        lastReward = nil
    }

    public func rewardedAdDidClick(_ rewardedAd: RewardedAd) {
        guard rewardedAd === showingRewardedAd else { return }
        notifyAdEvent(adType: "rewarded", event: "clicked", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAd(_ rewardedAd: RewardedAd, didTrackImpression impressionData: ImpressionData?) {
        guard rewardedAd === showingRewardedAd else { return }
        notifyAdEvent(adType: "rewarded", event: "impression", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }
}
