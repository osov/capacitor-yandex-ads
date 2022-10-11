export interface YandexAdsPlugin {

  initInter(options: { id: string  }): Promise<{}>;
  initReward(options: { id: string  }): Promise<{}>;
  initBanner(options: { id: string  }): Promise<{}>;

	loadBanner(): Promise<any> ;

	showBanner(): Promise<any> ;

	hideBanner(): Promise<any> ;

	showAds(): Promise<any> ;

	showReward(): Promise<{result:boolean}>;
}
