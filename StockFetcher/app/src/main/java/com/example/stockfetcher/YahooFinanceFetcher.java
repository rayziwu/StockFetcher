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
                    calculateKD(result, 40, 3, 3);
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
    // 計算 MACD 指標的方法（改成與 MacdCalculator 相同）
    private void calculateMACD(List<StockDayPrice> prices) {
        if (prices == null || prices.isEmpty()) return;

        final int SHORT = 12;
        final int LONG  = 26;
        final int SIGNAL = 9;

        // 清空舊值（與 MacdCalculator 相同）
        for (StockDayPrice p : prices) {
            p.macdDIF = Double.NaN;
            p.macdDEA = Double.NaN;
            p.macdHistogram = Double.NaN;
        }

        int n = prices.size();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = prices.get(i).getClose();

        // ✅ ewm(adjust=false) 風格 EMA（seed=第一個 finite）
        double[] ema12 = calculateEMA(close, SHORT);
        double[] ema26 = calculateEMA(close, LONG);

        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(ema12[i]) && Double.isFinite(ema26[i])) {
                dif[i] = ema12[i] - ema26[i];
            } else {
                dif[i] = Double.NaN;
            }
        }

        double[] dea = calculateEMA(dif, SIGNAL);

        for (int i = 0; i < n; i++) {
            prices.get(i).macdDIF = dif[i];
            prices.get(i).macdDEA = dea[i];

            // Python: Hist = DIF - Signal（沒有 *2）
            if (Double.isFinite(dif[i]) && Double.isFinite(dea[i])) {
                prices.get(i).macdHistogram = dif[i] - dea[i];
            }
            // else 保持 NaN（因為一開始已清空）
        }
    }

    /**
     * 等價於 pandas: series.ewm(span=span, adjust=False).mean()
     * alpha = 2/(span+1)
     * ema[t] = alpha*x[t] + (1-alpha)*ema[t-1]
     *
     * seed：第一個 finite 值作為 ema 起點；之前維持 NaN。
     */
    private double[] calculateEMA(double[] x, int span) {
        int n = x.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        if (n == 0 || span <= 0) return out;

        double alpha = 2.0 / (span + 1.0);

        int first = -1;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(x[i])) { first = i; break; }
        }
        if (first < 0) return out;

        out[first] = x[first];
        for (int i = first + 1; i < n; i++) {
            double xi = x[i];
            if (!Double.isFinite(xi)) {
                out[i] = out[i - 1]; // 保守處理
            } else {
                out[i] = alpha * xi + (1.0 - alpha) * out[i - 1];
            }
        }
        return out;
    }

    // 計算 KD 指標的方法 (保持不變)
    // 計算 KD 指標的方法 — SMA(3)/SMA(3) 版本（與畫圖/篩選一致）
    private void calculateKD(List<StockDayPrice> prices, int N, int smoothK, int dPeriod) {
        if (prices == null) return;
        int n = prices.size();
        if (n < N) return;

        // 先把 kdK/kdD 清空，避免沿用舊值
        for (StockDayPrice p : prices) {
            p.kdK = Double.NaN;
            p.kdD = Double.NaN;
        }

        double[] rsv = new double[n];
        double[] k = new double[n];
        double[] d = new double[n];

        for (int i = 0; i < n; i++) {
            rsv[i] = Double.NaN;
            k[i] = Double.NaN;
            d[i] = Double.NaN;
        }

        // 1) RSV
        for (int i = N - 1; i < n; i++) {
            double ll = Double.POSITIVE_INFINITY;
            double hh = Double.NEGATIVE_INFINITY;

            for (int j = i - N + 1; j <= i; j++) {
                ll = Math.min(ll, prices.get(j).getLow());
                hh = Math.max(hh, prices.get(j).getHigh());
            }

            double denom = hh - ll;
            if (denom <= 0) {
                // 與你畫圖版一致：denom<=0 => NaN
                continue;
            }

            double close = prices.get(i).getClose();
            rsv[i] = 100.0 * (close - ll) / denom;
        }

        // 2) K = SMA(RSV, smoothK)
        smaStrict(rsv, smoothK, k);

        // 3) D = SMA(K, dPeriod)
        smaStrict(k, dPeriod, d);

        // 回寫到物件
        for (int i = 0; i < n; i++) {
            prices.get(i).kdK = k[i];
            prices.get(i).kdD = d[i];
        }
    }

    private static void smaStrict(double[] src, int window, double[] dst) {
        int n = src.length;
        for (int i = 0; i < n; i++) dst[i] = Double.NaN;
        if (window <= 0) return;

        for (int i = window - 1; i < n; i++) {
            double sum = 0.0;
            for (int j = i - window + 1; j <= i; j++) {
                double v = src[j];
                if (!Double.isFinite(v)) { // 只要窗口內有 NaN/Inf，這一格就 NaN（與你畫圖 smaStrict 對齊）
                    sum = Double.NaN;
                    break;
                }
                sum += v;
            }
            if (Double.isFinite(sum)) dst[i] = sum / window;
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