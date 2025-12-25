package com.example.stockfetcher;

// 註解版本號3.02版

import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class YahooFinanceFetcher {

    private static final String TAG = "YahooFinanceFetcher";

    // *** 修正: 更改為使用 period1 和 period2 參數的 URL 模板 ***
    private static final String YAHOO_BASE_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?symbol=%s&interval=%s&period1=%d&period2=%d";


    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // 參數類別 (保持不變)
    private static class MovingAverageParams {
        final int period1;
        final int period2;
        final String maxRange;

        MovingAverageParams(int p1, int p2, String range) {
            this.period1 = p1;
            this.period2 = p2;
            this.maxRange = range;
        }
    }

    // MA 參數計算 (保持不變)
    private static MovingAverageParams getParamsForInterval(String interval) {
        switch (interval) {
            case "1h":
                return new MovingAverageParams(35, 200, "1mo");
            case "1wk":
                return new MovingAverageParams(35, 200, "5y");
            case "1mo":
                return new MovingAverageParams(60, 120, "max");
            case "1d":
            default:
                return new MovingAverageParams(60, 240, "5y");
        }
    }

    // 異步數據獲取接口 (保持不變)
    public interface DataFetchListener {
        void onDataFetched(List<StockDayPrice> data, String fetchedSymbol);
        void onError(String errorMessage);
    }

    // *** 修正: 更新方法簽名以接受 long startTimeLimit 參數 (修復編譯錯誤) ***
    /**
     * 異步獲取股票歷史數據。
     *
     * @param symbol 股票代碼 (如 2330.TW)
     * @param interval 時間間隔 (如 1d, 1wk, 1mo)
     * @param startTimeLimit 傳入的起始時間戳 (秒)，用於 1mo 週期時的 15 年限制。
     * @param listener 數據回調介面
     */
public void fetchStockDataAsync(String symbol, String interval, long startTimeLimit, DataFetchListener listener) {
    executor.submit(() -> {
        String processedSymbol = symbol.toUpperCase(Locale.US);

        // -----------------------------------------------------------------
        // *** 修正邏輯：計算 period1 和 period2 (取代 range 參數) ***
        // -----------------------------------------------------------------
        long period2 = System.currentTimeMillis() / 1000; // 結束時間: 當前時間 (秒)
        long period1; // 開始時間

        // 修正 V2.28: 只要 MainActivity 傳入了有效的起始時間限制 (startTimeLimit > 0L)，
        // 就應優先使用它，這適用於所有自訂限制 (1mo, 1wk, 1d, 1h)。
        if (startTimeLimit > 0L) {
            period1 = startTimeLimit;
            Log.d(TAG, "使用 MainActivity 傳入的自訂限制 period1: " + period1 + " (Interval: " + interval + ")");
        } else {
            // 如果 MainActivity 傳入 0L (表示無限制，例如：5min, 1min 或其他未設定的間隔)，
            // 則設定一個合理的預設範圍作為 fallback，避免下載量過大。
            long rangeInSeconds;

            if ("1h".equals(interval)) {
                // 時線: 預設 60 天
                rangeInSeconds = TimeUnit.DAYS.toSeconds(60);
            } else {
                // 日線/週線/其他: 預設 5 年
                rangeInSeconds = TimeUnit.DAYS.toSeconds(5 * 365);
            }

            // 確保 period1 不為負
            period1 = Math.max(0L, period2 - rangeInSeconds);
            Log.d(TAG, "使用下載器預設範圍 period1: " + period1 + " (Interval: " + interval + ")");
        }
            // -----------------------------------------------------------------

            // *** 修正: 使用新的 YAHOO_BASE_URL 模板 (period1/period2) ***
            // 參數順序: symbol, symbol, interval, period1, period2
            String url = String.format(YAHOO_BASE_URL, processedSymbol, processedSymbol, interval, period1, period2);

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }

                String json = response.body().string();
                List<StockDayPrice> result = parseYahooData(json);

                if (result.isEmpty()) {
                    throw new IOException("No data found for symbol " + processedSymbol);
                }

                // 排序 (確保數據從舊到新)
                Collections.sort(result, Comparator.comparing(StockDayPrice::getDate));

                // 2. 計算移動平均線 (MA)
                calculateMovingAverages(result, interval);

                // 3. 計算 MACD 指標 (MACD)
                calculateMACD(result);

                // 4. 計算 KD 指標 (KD)
                if (interval.equals("1h") || interval.equals("1d")) {
                    // 週期 40: 採用 (40, 14, 3)
                    calculateKD(result, 40, 14, 3);
                } else {
                    // 週期 9: 採用標準的 (9, 3, 3)
                    calculateKD(result, 9, 3, 3);
                }
               // 5. 計算 RSI
                calculateRSI(result, 14);

               // 6. 計算 DMI
                calculateDMI(result, 14);


                if (listener != null) {
                    listener.onDataFetched(result, processedSymbol);
                }
            } catch (IOException e) {
                handleError(processedSymbol + " 請求失敗", e, listener);
            } catch (Exception e) {
                handleError(processedSymbol + " 數據解析失敗: " + e.getMessage(), e, listener);
            }

        });
    }


    // Yahoo Finance 數據解析方法 (保持不變)
    private List<StockDayPrice> parseYahooData(String json) {
        List<StockDayPrice> prices = new ArrayList<>();
        Gson gson = new Gson();

        try {
            // *** 使用新的巢狀類別引用 ***
            YahooApiResponse response = gson.fromJson(json, YahooApiResponse.class);
            if (response != null && response.chart != null && response.chart.result != null && !response.chart.result.isEmpty()) {
                YahooApiResponse.Result result = response.chart.result.get(0);
                long[] timestamps = result.timestamp;
                YahooApiResponse.Quote quote = result.indicators.quote.get(0);

                if (timestamps != null && quote.close != null && timestamps.length == quote.close.length) {
                    for (int i = 0; i < timestamps.length; i++) {
                        // 檢查是否有空值（null）
                        if (quote.open[i] != null && quote.high[i] != null && quote.low[i] != null && quote.close[i] != null && quote.volume[i] != null) {

                            // 轉換時間戳為日期字符串
                            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(timestamps[i] * 1000));

                            prices.add(new StockDayPrice(
                                    date,
                                    quote.open[i].doubleValue(),
                                    quote.high[i].doubleValue(),
                                    quote.low[i].doubleValue(),
                                    quote.close[i].doubleValue(),
                                    quote.volume[i].longValue()
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parsing error: " + e.getMessage(), e);
        }

        return prices;
    }

    private void calculateDMI(List<StockDayPrice> prices, int period) {
        if (prices.size() <= period) return;

        int size = prices.size();
        double[] tr = new double[size];
        double[] dmPlus = new double[size];
        double[] dmMinus = new double[size];

        // 1. 計算 TR, +DM, -DM
        for (int i = 1; i < size; i++) {
            double highDiff = prices.get(i).getHigh() - prices.get(i - 1).getHigh();
            double lowDiff = prices.get(i - 1).getLow() - prices.get(i).getLow();

            dmPlus[i] = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            dmMinus[i] = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;

            double h_l = prices.get(i).getHigh() - prices.get(i).getLow();
            double h_pc = Math.abs(prices.get(i).getHigh() - prices.get(i - 1).getClose());
            double l_pc = Math.abs(prices.get(i).getLow() - prices.get(i - 1).getClose());
            tr[i] = Math.max(h_l, Math.max(h_pc, l_pc));
        }

        // 2. 平滑計算 TRn, DMn
        double smoothTR = 0, smoothPlus = 0, smoothMinus = 0;
        for (int i = 1; i <= period; i++) {
            smoothTR += tr[i];
            smoothPlus += dmPlus[i];
            smoothMinus += dmMinus[i];
        }

        for (int i = period; i < size; i++) {
            if (i > period) {
                smoothTR = smoothTR - (smoothTR / period) + tr[i];
                smoothPlus = smoothPlus - (smoothPlus / period) + dmPlus[i];
                smoothMinus = smoothMinus - (smoothMinus / period) + dmMinus[i];
            }

            if (smoothTR != 0) {
                prices.get(i).dmiPDI = (smoothPlus / smoothTR) * 100;
                prices.get(i).dmiMDI = (smoothMinus / smoothTR) * 100;
            }

            // 3. 計算 DX 與 ADX
            double pdi = prices.get(i).dmiPDI;
            double mdi = prices.get(i).dmiMDI;
            double dx = (pdi + mdi == 0) ? 0 : (Math.abs(pdi - mdi) / (pdi + mdi)) * 100;

            // 簡單化處理：此處可再對 dx 進行一次 period 平均得到 ADX
            if (i == period) prices.get(i).dmiADX = dx;
            else prices.get(i).dmiADX = (prices.get(i - 1).dmiADX * (period - 1) + dx) / period;
        }
    }
    private void calculateRSI(List<StockDayPrice> prices, int period) {
        if (prices.size() <= period) return;

        double avgGain = 0;
        double avgLoss = 0;

        // 1. 計算第一個平均漲跌 (SMA 方式初始化)
        for (int i = 1; i <= period; i++) {
            double diff = prices.get(i).getClose() - prices.get(i - 1).getClose();
            if (diff >= 0) avgGain += diff;
            else avgLoss -= diff;
        }
        avgGain /= period;
        avgLoss /= period;

        // 2. 使用平滑移動平均計算後續 RSI
        for (int i = period; i < prices.size(); i++) {
            double diff = prices.get(i).getClose() - prices.get(i - 1).getClose();
            double gain = diff >= 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            if (avgLoss == 0) {
                prices.get(i).rsi = 100;
            } else {
                double rs = avgGain / avgLoss;
                prices.get(i).rsi = 100 - (100 / (1 + rs));
            }
        }
    }


    // 計算移動平均線 (MA) 方法 (保持不變)
    private void calculateMovingAverages(List<StockDayPrice> prices, String interval) {
        int dataSize = prices.size();

        // 根據時間間隔設定 MA 週期
        int[] maPeriods;
        switch (interval) {
            case "1h":
            case "1wk":
                maPeriods = new int[]{5, 10, 20, 35, 200};
                break;
            case "1mo":
                maPeriods = new int[]{5, 10, 20, 60, 120};
                break;
            case "1d":
            default:
                maPeriods = new int[]{5, 10, 20, 60, 240}; // 5, 10, 20日, 季線(60), 年線(240)
                break;
        }

        for (int period : maPeriods) {
            double sum = 0;
            for (int i = 0; i < dataSize; i++) {
                sum += prices.get(i).getClose();

                if (i >= period - 1) {
                    // 計算平均值
                    double ma = sum / period;

                    // 將結果存入對應的 StockDayPrice 欄位
                    if (period == 35) {
                        prices.get(i).ma35 = ma;
                    } else if (period == 60) {
                        prices.get(i).ma60 = ma;
                    } else if (period == 120) {
                        prices.get(i).ma120 = ma;
                    } else if (period == 200) {
                        prices.get(i).ma200 = ma;
                    } else if (period == 240) {
                        prices.get(i).ma240 = ma;
                    }

                    // 減去最舊的數值以準備下一個週期的計算
                    sum -= prices.get(i - period + 1).getClose();
                }
            }
        }
    }

    // 計算 MACD 指標的方法 (保持不變)
    private void calculateMACD(List<StockDayPrice> prices) {
        int dataSize = prices.size();

        // 1. 計算 EMA (指數移動平均線)
        // 常用參數：12 (快線), 26 (慢線), 9 (DEA)
        int fastPeriod = 12;
        int slowPeriod = 26;
        int deaPeriod = 9;

        // 輔助陣列，用於儲存 Close Price
        double[] closes = prices.stream().mapToDouble(StockDayPrice::getClose).toArray();

        // 1a. 計算 12 日 EMA
        double[] ema12s = calculateEMA(closes, fastPeriod);

        // 1b. 計算 26 日 EMA
        double[] ema26s = calculateEMA(closes, slowPeriod);

        // 2. 計算 DIF (Difference) 和 DEA (MACD Line)
        double[] difs = new double[dataSize];
        for (int i = 0; i < dataSize; i++) {
            if (!Double.isNaN(ema12s[i]) && !Double.isNaN(ema26s[i])) {
                difs[i] = ema12s[i] - ema26s[i];
                prices.get(i).macdDIF = difs[i];
            } else {
                difs[i] = Double.NaN;
                prices.get(i).macdDIF = Double.NaN;
            }
        }

        // 3. 計算 DEA (DIF 的 9 日 EMA)
        double[] deas = calculateEMA(difs, deaPeriod);

        // 4. 計算 Histogram (MACD 柱狀圖)
        for (int i = 0; i < dataSize; i++) {
            // 確保 DIF 和 DEA 都有值
            if (!Double.isNaN(difs[i]) && !Double.isNaN(deas[i])) {
                prices.get(i).macdDEA = deas[i];
                prices.get(i).macdHistogram = prices.get(i).macdDIF - prices.get(i).macdDEA;
            } else {
                prices.get(i).macdDEA = Double.NaN;
                prices.get(i).macdHistogram = Double.NaN;
            }
        }
    }

    // EMA 計算輔助方法 (保持不變)
    private double[] calculateEMA(double[] data, int period) {
        int dataSize = data.length;
        double[] emas = new double[dataSize];
        double smoothingConstant = 2.0 / (period + 1.0);
        double sum = 0;
        int initialCount = 0;

        // 1. 計算第一個 EMA (通常使用 SMA 作為初始值)
        for (int i = 0; i < dataSize; i++) {
            if (Double.isNaN(data[i])) {
                emas[i] = Double.NaN;
                continue;
            }

            sum += data[i];
            initialCount++;

            if (initialCount == period) {
                emas[i] = sum / period;
                break;
            } else {
                emas[i] = Double.NaN;
            }
        }

        // 2. 計算後續的 EMA
        for (int i = period; i < dataSize; i++) {
            if (!Double.isNaN(emas[i-1])) {
                // EMA(t) = (Price(t) * smoothingConstant) + (EMA(t-1) * (1 - smoothingConstant))
                emas[i] = (data[i] * smoothingConstant) + (emas[i - 1] * (1.0 - smoothingConstant));
            } else {
                // 如果前一個 EMA 為 NaN，則嘗試計算 SMA
                if (i - period + 1 >= 0) {
                    double initialSum = 0;
                    int count = 0;
                    for(int j = i - period + 1; j <= i; j++) {
                        if (!Double.isNaN(data[j])) {
                            initialSum += data[j];
                            count++;
                        }
                    }
                    if (count == period) {
                        emas[i] = initialSum / period;
                    } else {
                        emas[i] = Double.NaN;
                    }
                } else {
                    emas[i] = Double.NaN;
                }
            }
        }

        return emas;
    }

    // 計算 KD 指標的方法 (保持不變)
    private void calculateKD(List<StockDayPrice> prices, int n, int kPeriod, int dPeriod) {
        int dataSize = prices.size();

        double[] rsvs = new double[dataSize];
        double[] ks = new double[dataSize];
        double[] ds = new double[dataSize];

        // 初始化
        for (int i = 0; i < dataSize; i++) {
            rsvs[i] = Double.NaN;
            ks[i] = Double.NaN;
            ds[i] = Double.NaN;
        }

        // 1. 計算 RSV (Raw Stochastic Value)
        for (int i = n - 1; i < dataSize; i++) {
            double highestHigh = 0;
            double lowestLow = Double.MAX_VALUE;

            // 找出最近 N 天的最高價和最低價
            for (int j = i - n + 1; j <= i; j++) {
                if (prices.get(j).getHigh() > highestHigh) {
                    highestHigh = prices.get(j).getHigh();
                }
                if (prices.get(j).getLow() < lowestLow) {
                    lowestLow = prices.get(j).getLow();
                }
            }

            double closePrice = prices.get(i).getClose();

            if (highestHigh != lowestLow) {
                // RSV = (當日收盤價 - N日內最低價) / (N日內最高價 - N日內最低價) * 100
                rsvs[i] = ((closePrice - lowestLow) / (highestHigh - lowestLow)) * 100;
            } else {
                // 如果最高價等於最低價，則 RSV 為 100 (極端情況)
                rsvs[i] = 100;
            }
        }

        // 2. 平滑 K 值 (K-Line)
        double kAlpha = 1.0 / kPeriod;

        // K 值的初始值 (通常是第一個有效的 RSV)
        for(int i = n - 1; i < dataSize; i++) {
            if (!Double.isNaN(rsvs[i])) {
                ks[i] = rsvs[i];
                prices.get(i).kdK = ks[i];
                break;
            }
        }

        // 計算後續的 K 值: K(t) = K(t-1) + alpha * (RSV(t) - K(t-1))
        for (int i = n; i < dataSize; i++) {
            if (!Double.isNaN(rsvs[i]) && !Double.isNaN(ks[i-1])) {
                ks[i] = ks[i-1] + kAlpha * (rsvs[i] - ks[i-1]);
            } else if (!Double.isNaN(rsvs[i])) {
                ks[i] = rsvs[i]; // 如果前一個 K 是 NaN，則從 RSV 開始
            }
            prices.get(i).kdK = ks[i];
        }

        // 3. 平滑 D 值 (D-Line)
        double dAlpha = 1.0 / dPeriod;

        // D 值的初始值 (通常是第一個有效的 K)
        for(int i = n - 1; i < dataSize; i++) {
            if (!Double.isNaN(ks[i])) {
                ds[i] = ks[i];
                prices.get(i).kdD = ds[i];
                break;
            }
        }

        // 計算後續的 D 值: D(t) = D(t-1) + alpha * (K(t) - D(t-1))
        for (int i = n; i < dataSize; i++) {
            if (!Double.isNaN(ks[i]) && !Double.isNaN(ds[i-1])) {
                ds[i] = ds[i-1] + dAlpha * (ks[i] - ds[i-1]);
            } else if (!Double.isNaN(ks[i])) {
                ds[i] = ks[i]; // 如果前一個 D 是 NaN，則從 K 開始
            }
            prices.get(i).kdD = ds[i];
        }
    }


    private void handleError(String baseMessage, Throwable t, DataFetchListener listener) {
        String errorMessage = baseMessage + ": " + (t.getMessage() != null ? t.getMessage() : "未知錯誤");
        Log.e(TAG, errorMessage, t);
        if (listener != null) {
            listener.onError(errorMessage);
        }
    }

    // -------------------------------------------------------------------
    // 巢狀類別定義 (保持不變)
    // -------------------------------------------------------------------
    public static class YahooApiResponse {
        public Chart chart;
        public static class Chart { public List<Result> result; }
        public static class Result {
            public long[] timestamp;
            public Indicators indicators;
        }
        public static class Indicators { public List<Quote> quote; }
        public static class Quote {
            public Float[] open;
            public Float[] high;
            public Float[] low;
            public Float[] close;
            public Long[] volume;
        }
    }
    // YahooFinanceFetcher.java 內新增
    public List<StockDayPrice> fetchStockDataBlocking(String symbol, String interval, long startTime) throws Exception {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<List<StockDayPrice>> ref =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Collections.emptyList());
        final java.util.concurrent.atomic.AtomicReference<String> err =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        fetchStockDataAsync(symbol, interval, startTime, new DataFetchListener() {
            @Override public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                ref.set(data);
                latch.countDown();
            }
            @Override public void onError(String errorMessage) {
                err.set(errorMessage);
                latch.countDown();
            }
        });

        // 避免永遠卡住：你可自行調整等待秒數
        boolean ok = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!ok) throw new java.io.IOException("fetch timeout: " + symbol);
        if (err.get() != null) throw new java.io.IOException(err.get());
        return ref.get();
    }

}