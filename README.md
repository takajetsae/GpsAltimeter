# GPS高度計（Android）

GPS と気圧センサーで高度を表示・記録するアプリです。外部ライブラリ不使用（Android標準APIのみ）。

## 機能
- **GPS高度（海抜）**：取得元を自動選択
  1. OSが提供する海抜高度（Android 14+ の一部端末）
  2. NMEA `GGA` 文の海抜高度（GNSSチップのジオイド補正値）
  3. Android 14+ 内蔵ジオイドモデルで楕円体高から変換
  4. いずれも無い場合は楕円体高を表示（注記あり）
- 衛星数（使用中／可視）、垂直精度、楕円体高の表示
- **気圧高度**：気圧センサー＋基準気圧(QNH)
  - 「GPSで校正」：現在のGPS海抜高度から基準気圧を逆算（垂直精度±20m超は拒否）
  - 「QNH入力」：METAR等の海面更正気圧を手入力／標準 1013.25 hPa に戻す
- 昇降率 [m/min]（直近10秒）、最高／最低高度
- 直近10分のグラフ（GPS＝青、Baro＝橙）
- CSV記録：`Download/Altimeter/altimeter_YYYYMMDD_HHMMSS.csv`（1秒周期）

対応：Android 10 以上。記録中は画面を点灯したままにします（画面OFF・バックグラウンドでは停止）。

## ビルド方法
### A. Android Studio
1. このフォルダを「Open」で開く（Gradle同期で SDK / AGP が自動取得されます）
2. 端末をUSB接続して ▶ Run、または Build → Build APK(s)
   - 出力：`app/build/outputs/apk/debug/app-debug.apk`

### B. GitHub Actions（PC に Android Studio 不要）
1. GitHub に新規リポジトリを作り、このフォルダを push
2. Actions タブ → 「Build APK」完了後、Artifacts の `GpsAltimeter-apk` をダウンロード
3. zip 内の `app-release.apk` をスマホに転送してインストール（「提供元不明のアプリ」を許可）

※ release もデバッグ鍵で署名しています（個人利用向け）。

## CSV列
time, latitude, longitude, gps_msl_m, ellipsoid_m, v_accuracy_m, msl_source, sats_used, sats_visible, pressure_hPa, qnh_hPa, baro_alt_m, vertical_speed_m_min
