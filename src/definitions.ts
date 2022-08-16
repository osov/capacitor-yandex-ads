export interface YandexAdsPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
