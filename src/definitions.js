/**
 * Ad types supported by the plugin
 */
export var AdType;
(function (AdType) {
    AdType["INIT"] = "init";
    AdType["BANNER"] = "banner";
    AdType["INTERSTITIAL"] = "interstitial";
    AdType["REWARDED"] = "rewarded";
})(AdType || (AdType = {}));
/**
 * Ad event types
 */
export var AdEventType;
(function (AdEventType) {
    AdEventType["LOADED"] = "loaded";
    AdEventType["FAILED_TO_LOAD"] = "failed_to_load";
    AdEventType["SHOWN"] = "shown";
    AdEventType["FAILED_TO_SHOW"] = "failed_to_show";
    AdEventType["DISMISSED"] = "dismissed";
    AdEventType["CLICKED"] = "clicked";
    AdEventType["IMPRESSION"] = "impression";
    AdEventType["REWARDED"] = "rewarded";
    AdEventType["LEFT_APPLICATION"] = "left_application";
    AdEventType["RETURNED_TO_APPLICATION"] = "returned_to_application";
})(AdEventType || (AdEventType = {}));
/**
 * Banner positions
 */
export var BannerPosition;
(function (BannerPosition) {
    BannerPosition["TOP"] = "top";
    BannerPosition["BOTTOM"] = "bottom";
})(BannerPosition || (BannerPosition = {}));
