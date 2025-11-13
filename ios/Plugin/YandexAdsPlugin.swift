import Foundation
import Capacitor
import YandexMobileAds

@objc(YandexAdsPlugin)
public class YandexAdsPlugin: CAPPlugin {

    // SDK state
    private var isInitialized = false

    // Banner
    private var bannerAdView: YMAAdView?
    private var bannerAdUnitId: String?
    private var bannerPosition: String = "bottom"

    // Interstitial
    private var interstitialAd: YMAInterstitialAd?
    private var interstitialAdUnitId: String?
    private var isInterstitialLoaded = false

    // Rewarded
    private var rewardedAd: YMARewardedAd?
    private var rewardedAdUnitId: String?
    private var isRewardedLoaded = false
    private var lastReward: YMAReward?

    // MARK: - Plugin Methods

    /**
     * Initialize Yandex Mobile Ads SDK
     */
    @objc func `init`(_ call: CAPPluginCall) {
        if isInitialized {
            call.resolve([
                "success": true,
                "message": "Already initialized"
            ])
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            YMAMobileAds.initializeSDK()
            self.isInitialized = true

            // Send event
            self.notifyAdEvent(adType: "init", event: "loaded", adUnitId: nil, error: nil, reward: nil)

            call.resolve([
                "success": true
            ])
        }
    }

    /**
     * Load a banner ad
     */
    @objc func loadBanner(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let adUnitId = call.getString("adUnitId") else {
            rejectMissingParameter(call, paramName: "adUnitId")
            return
        }

        guard let sizeObj = call.getObject("size"),
              let width = sizeObj["width"] as? Int else {
            rejectMissingParameter(call, paramName: "size.width")
            return
        }

        let height = sizeObj["height"] as? Int
        let position = call.getString("position") ?? "bottom"

        bannerAdUnitId = adUnitId
        bannerPosition = position

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // Remove existing banner
            self.bannerAdView?.removeFromSuperview()
            self.bannerAdView = nil

            // Determine ad size
            let adSize: YMAAdSize
            if let height = height, height > 0 {
                adSize = YMAAdSize.flexibleSize(withContainerWidth: CGFloat(width), maxHeight: CGFloat(height))
            } else {
                // Adaptive sticky banner
                let viewWidth = self.bridge?.viewController?.view.bounds.width ?? UIScreen.main.bounds.width
                adSize = YMAAdSize.stickySize(withContainerWidth: viewWidth)
            }

            // Create banner
            self.bannerAdView = YMAAdView(adUnitID: adUnitId, adSize: adSize)
            guard let bannerAdView = self.bannerAdView else {
                call.reject("Failed to create banner")
                return
            }

            bannerAdView.delegate = self

            // Add to view (initially hidden)
            guard let webView = self.bridge?.webView,
                  let rootView = webView.superview else {
                call.reject("Failed to get root view")
                return
            }

            rootView.addSubview(bannerAdView)

            // Position banner
            bannerAdView.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                bannerAdView.centerXAnchor.constraint(equalTo: rootView.centerXAnchor)
            ])

            if position == "top" {
                if #available(iOS 11.0, *) {
                    bannerAdView.topAnchor.constraint(equalTo: rootView.safeAreaLayoutGuide.topAnchor).isActive = true
                } else {
                    bannerAdView.topAnchor.constraint(equalTo: rootView.topAnchor).isActive = true
                }
            } else {
                if #available(iOS 11.0, *) {
                    bannerAdView.bottomAnchor.constraint(equalTo: rootView.safeAreaLayoutGuide.bottomAnchor).isActive = true
                } else {
                    bannerAdView.bottomAnchor.constraint(equalTo: rootView.bottomAnchor).isActive = true
                }
            }

            bannerAdView.isHidden = true

            // Store call to resolve after load
            self.bridge?.saveCall(call)

            // Load ad
            let request = YMAAdRequest()
            bannerAdView.loadAd(with: request)
        }
    }

    /**
     * Show the loaded banner ad
     */
    @objc func showBanner(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let bannerAdView = bannerAdView else {
            call.resolve([
                "success": false,
                "message": "Banner not loaded"
            ])
            return
        }

        DispatchQueue.main.async { [weak self] in
            bannerAdView.isHidden = false
            self?.notifyAdEvent(adType: "banner", event: "shown", adUnitId: self?.bannerAdUnitId, error: nil, reward: nil)

            call.resolve([
                "success": true
            ])
        }
    }

    /**
     * Hide the banner ad
     */
    @objc func hideBanner(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let bannerAdView = bannerAdView else {
            call.resolve([
                "success": false,
                "message": "Banner not loaded"
            ])
            return
        }

        DispatchQueue.main.async { [weak self] in
            bannerAdView.isHidden = true
            self?.notifyAdEvent(adType: "banner", event: "dismissed", adUnitId: self?.bannerAdUnitId, error: nil, reward: nil)

            call.resolve([
                "success": true
            ])
        }
    }

    /**
     * Destroy the banner ad
     */
    @objc func destroyBanner(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.bannerAdView?.removeFromSuperview()
            self?.bannerAdView = nil
            self?.bannerAdUnitId = nil

            call.resolve([
                "success": true
            ])
        }
    }

    /**
     * Load an interstitial ad
     */
    @objc func loadInterstitial(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let adUnitId = call.getString("adUnitId") else {
            rejectMissingParameter(call, paramName: "adUnitId")
            return
        }

        interstitialAdUnitId = adUnitId
        isInterstitialLoaded = false

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            let configuration = YMAAdRequestConfiguration(adUnitID: adUnitId)
            let loader = YMAInterstitialAdLoader()
            loader.delegate = self

            // Store call to resolve after load
            self.bridge?.saveCall(call)

            loader.loadAd(with: configuration)
        }
    }

    /**
     * Show the loaded interstitial ad
     */
    @objc func showInterstitial(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let interstitialAd = interstitialAd, isInterstitialLoaded else {
            call.resolve([
                "success": false,
                "message": "Interstitial not loaded"
            ])
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let viewController = self?.bridge?.viewController else {
                call.reject("Failed to get view controller")
                return
            }

            interstitialAd.show(from: viewController)

            call.resolve([
                "success": true
            ])
        }
    }

    /**
     * Load a rewarded ad
     */
    @objc func loadRewarded(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let adUnitId = call.getString("adUnitId") else {
            rejectMissingParameter(call, paramName: "adUnitId")
            return
        }

        rewardedAdUnitId = adUnitId
        isRewardedLoaded = false
        lastReward = nil

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            let configuration = YMAAdRequestConfiguration(adUnitID: adUnitId)
            let loader = YMARewardedAdLoader()
            loader.delegate = self

            // Store call to resolve after load
            self.bridge?.saveCall(call)

            loader.loadAd(with: configuration)
        }
    }

    /**
     * Show the loaded rewarded ad
     */
    @objc func showRewarded(_ call: CAPPluginCall) {
        guard isInitialized else {
            rejectNotInitialized(call)
            return
        }

        guard let rewardedAd = rewardedAd, isRewardedLoaded else {
            call.resolve([
                "success": false,
                "message": "Rewarded ad not loaded"
            ])
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let viewController = self?.bridge?.viewController else {
                call.reject("Failed to get view controller")
                return
            }

            rewardedAd.show(from: viewController)

            // Delay to wait for reward callback
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                var result: [String: Any] = ["success": true]

                if let reward = self?.lastReward {
                    result["rewarded"] = true
                    result["reward"] = [
                        "amount": reward.amount,
                        "type": reward.type
                    ]
                } else {
                    result["rewarded"] = false
                }

                call.resolve(result)
            }
        }
    }

    // MARK: - Helper Methods

    private func notifyAdEvent(adType: String, event: String, adUnitId: String?, error: [String: Any]?, reward: [String: Any]?) {
        var eventData: [String: Any] = [
            "adType": adType,
            "event": event
        ]

        if let adUnitId = adUnitId {
            eventData["adUnitId"] = adUnitId
        }

        if let error = error {
            eventData["error"] = error
        }

        if let reward = reward {
            eventData["reward"] = reward
        }

        notifyListeners("adEvent", data: eventData)
    }

    private func createErrorObject(error: Error) -> [String: Any] {
        let nsError = error as NSError
        return [
            "code": nsError.code,
            "message": nsError.localizedDescription
        ]
    }

    private func rejectNotInitialized(_ call: CAPPluginCall) {
        call.resolve([
            "success": false,
            "message": "SDK not initialized. Call init() first."
        ])
    }

    private func rejectMissingParameter(_ call: CAPPluginCall, paramName: String) {
        call.resolve([
            "success": false,
            "message": "Missing required parameter: \(paramName)"
        ])
    }
}

// MARK: - YMAAdViewDelegate (Banner)
extension YandexAdsPlugin: YMAAdViewDelegate {
    public func adViewDidLoad(_ adView: YMAAdView) {
        print("Banner ad loaded: \(bannerAdUnitId ?? "")")
        notifyAdEvent(adType: "banner", event: "loaded", adUnitId: bannerAdUnitId, error: nil, reward: nil)

        if let call = bridge?.getSavedCall() {
            call.resolve([
                "success": true
            ])
            bridge?.releaseCall(call)
        }
    }

    public func adViewDidFailLoading(_ adView: YMAAdView, error: Error) {
        print("Banner ad failed to load: \(error.localizedDescription)")

        let errorObj = createErrorObject(error: error)
        notifyAdEvent(adType: "banner", event: "failed_to_load", adUnitId: bannerAdUnitId, error: errorObj, reward: nil)

        if let call = bridge?.getSavedCall() {
            call.resolve([
                "success": false,
                "message": error.localizedDescription
            ])
            bridge?.releaseCall(call)
        }
    }

    public func adViewDidClick(_ adView: YMAAdView) {
        print("Banner ad clicked")
        notifyAdEvent(adType: "banner", event: "clicked", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }

    public func adView(_ adView: YMAAdView, willPresentScreen viewController: UIViewController?) {
        print("Banner ad will present screen")
        notifyAdEvent(adType: "banner", event: "left_application", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }

    public func adView(_ adView: YMAAdView, didDismissScreen viewController: UIViewController?) {
        print("Banner ad did dismiss screen")
        notifyAdEvent(adType: "banner", event: "returned_to_application", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }

    public func adViewDidTrackImpression(_ adView: YMAAdView, with impressionData: YMAImpressionData?) {
        print("Banner ad impression")
        notifyAdEvent(adType: "banner", event: "impression", adUnitId: bannerAdUnitId, error: nil, reward: nil)
    }
}

// MARK: - YMAInterstitialAdLoaderDelegate
extension YandexAdsPlugin: YMAInterstitialAdLoaderDelegate {
    public func interstitialAdLoader(_ adLoader: YMAInterstitialAdLoader, didLoad interstitialAd: YMAInterstitialAd) {
        print("Interstitial ad loaded: \(interstitialAdUnitId ?? "")")

        self.interstitialAd = interstitialAd
        self.isInterstitialLoaded = true
        interstitialAd.delegate = self

        notifyAdEvent(adType: "interstitial", event: "loaded", adUnitId: interstitialAdUnitId, error: nil, reward: nil)

        if let call = bridge?.getSavedCall() {
            call.resolve([
                "success": true
            ])
            bridge?.releaseCall(call)
        }
    }

    public func interstitialAdLoader(_ adLoader: YMAInterstitialAdLoader, didFailToLoadWithError error: YMAAdRequestError) {
        print("Interstitial ad failed to load: \(error.error.localizedDescription)")

        isInterstitialLoaded = false
        let errorObj = createErrorObject(error: error.error)
        notifyAdEvent(adType: "interstitial", event: "failed_to_load", adUnitId: interstitialAdUnitId, error: errorObj, reward: nil)

        if let call = bridge?.getSavedCall() {
            call.resolve([
                "success": false,
                "message": error.error.localizedDescription
            ])
            bridge?.releaseCall(call)
        }
    }
}

// MARK: - YMAInterstitialAdDelegate
extension YandexAdsPlugin: YMAInterstitialAdDelegate {
    public func interstitialAdDidShow(_ interstitialAd: YMAInterstitialAd) {
        print("Interstitial ad shown")
        notifyAdEvent(adType: "interstitial", event: "shown", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAdDidDismiss(_ interstitialAd: YMAInterstitialAd) {
        print("Interstitial ad dismissed")
        isInterstitialLoaded = false
        notifyAdEvent(adType: "interstitial", event: "dismissed", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAdDidClick(_ interstitialAd: YMAInterstitialAd) {
        print("Interstitial ad clicked")
        notifyAdEvent(adType: "interstitial", event: "clicked", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAd(_ interstitialAd: YMAInterstitialAd, didTrackImpressionWith impressionData: YMAImpressionData?) {
        print("Interstitial ad impression")
        notifyAdEvent(adType: "interstitial", event: "impression", adUnitId: interstitialAdUnitId, error: nil, reward: nil)
    }

    public func interstitialAd(_ interstitialAd: YMAInterstitialAd, didFailToShowWithError error: Error) {
        print("Interstitial ad failed to show: \(error.localizedDescription)")

        let errorObj = createErrorObject(error: error)
        notifyAdEvent(adType: "interstitial", event: "failed_to_show", adUnitId: interstitialAdUnitId, error: errorObj, reward: nil)
    }
}

// MARK: - YMARewardedAdLoaderDelegate
extension YandexAdsPlugin: YMARewardedAdLoaderDelegate {
    public func rewardedAdLoader(_ adLoader: YMARewardedAdLoader, didLoad rewardedAd: YMARewardedAd) {
        print("Rewarded ad loaded: \(rewardedAdUnitId ?? "")")

        self.rewardedAd = rewardedAd
        self.isRewardedLoaded = true
        rewardedAd.delegate = self

        notifyAdEvent(adType: "rewarded", event: "loaded", adUnitId: rewardedAdUnitId, error: nil, reward: nil)

        if let call = bridge?.getSavedCall() {
            call.resolve([
                "success": true
            ])
            bridge?.releaseCall(call)
        }
    }

    public func rewardedAdLoader(_ adLoader: YMARewardedAdLoader, didFailToLoadWithError error: YMAAdRequestError) {
        print("Rewarded ad failed to load: \(error.error.localizedDescription)")

        isRewardedLoaded = false
        let errorObj = createErrorObject(error: error.error)
        notifyAdEvent(adType: "rewarded", event: "failed_to_load", adUnitId: rewardedAdUnitId, error: errorObj, reward: nil)

        if let call = bridge?.getSavedCall() {
            call.resolve([
                "success": false,
                "message": error.error.localizedDescription
            ])
            bridge?.releaseCall(call)
        }
    }
}

// MARK: - YMARewardedAdDelegate
extension YandexAdsPlugin: YMARewardedAdDelegate {
    public func rewardedAdDidShow(_ rewardedAd: YMARewardedAd) {
        print("Rewarded ad shown")
        notifyAdEvent(adType: "rewarded", event: "shown", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAdDidDismiss(_ rewardedAd: YMARewardedAd) {
        print("Rewarded ad dismissed")
        isRewardedLoaded = false
        notifyAdEvent(adType: "rewarded", event: "dismissed", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAdDidClick(_ rewardedAd: YMARewardedAd) {
        print("Rewarded ad clicked")
        notifyAdEvent(adType: "rewarded", event: "clicked", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAd(_ rewardedAd: YMARewardedAd, didReward reward: YMAReward) {
        print("Rewarded ad reward received: \(reward.amount) \(reward.type)")

        lastReward = reward
        let rewardObj: [String: Any] = [
            "amount": reward.amount,
            "type": reward.type
        ]

        notifyAdEvent(adType: "rewarded", event: "rewarded", adUnitId: rewardedAdUnitId, error: nil, reward: rewardObj)
    }

    public func rewardedAd(_ rewardedAd: YMARewardedAd, didTrackImpressionWith impressionData: YMAImpressionData?) {
        print("Rewarded ad impression")
        notifyAdEvent(adType: "rewarded", event: "impression", adUnitId: rewardedAdUnitId, error: nil, reward: nil)
    }

    public func rewardedAd(_ rewardedAd: YMARewardedAd, didFailToShowWithError error: Error) {
        print("Rewarded ad failed to show: \(error.localizedDescription)")

        let errorObj = createErrorObject(error: error)
        notifyAdEvent(adType: "rewarded", event: "failed_to_show", adUnitId: rewardedAdUnitId, error: errorObj, reward: nil)
    }
}
