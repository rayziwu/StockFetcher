package com.example.stockfetcher;

// 註解版本號3.02版 + intraday 1d/1h merge

import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class YahooFinanceFetcher {

    private static final String TAG = "YahooFinanceFetcher";

    // Yahoo chart
    private static final String YAHOO_BASE_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?symbol=%s&interval=%s&period1=%d&period2=%d";

    // TWSE MIS
    private static final String MIS_REFERER = "https://mis.twse.com.tw/stock/fibest.jsp?lang=zh_tw";
    private static final String MIS_URL_FMT = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=%s";
    private static final ZoneId ZONE_TW = ZoneId.of("Asia/Taipei");

    private static final DateTimeFormatter FMT_YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_YMD_HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ---- intraday caches (avoid flicker) ----
    private static final class DailySnap {
        final String dateYmd;   // yyyy-MM-dd (TW)
        final double o, h, l, c;
        final double vShares;   // shares
        DailySnap(String dateYmd, double o, double h, double l, double c, double vShares) {
            this.dateYmd = dateYmd;
            this.o = o; this.h = h; this.l = l; this.c = c;
            this.vShares = vShares;
        }
    }
    private static final class HourSnap {
        final String hourKey;   // yyyy-MM-dd HH:mm (minute=00)
        final double o, h, l, c;
        final double vShares;   // shares
        HourSnap(String hourKey, double o, double h, double l, double c, double vShares) {
            this.hourKey = hourKey;
            this.o = o; this.h = h; this.l = l; this.c = c;
            this.vShares = vShares;
        }
    }

    private final Map<String, DailySnap> intradayDailyCache = new ConcurrentHashMap<>();
    private final Map<String, HourSnap> intradayHourCache  = new ConcurrentHashMap<>();

    // ---- OkHttp with simple in-memory cookie jar (MIS requires cookies more often than not) ----
    private final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(new InMemoryCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // 參數類別（保留：雖目前未使用，但不影響編譯）
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

    @SuppressWarnings("unused")
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

    // 異步數據獲取接口
    public interface DataFetchListener {
        void onDataFetched(List<StockDayPrice> data, String fetchedSymbol);
        void onError(String errorMessage);
    }

    // -------------------------
    // public APIs
    // -------------------------

    /** 舊版：預設不套用盤中聚合（給篩選/比較等使用） */
    public void fetchStockDataAsync(String symbol, String interval, long startTimeLimit, DataFetchListener listener) {
        fetchStockDataAsync(symbol, interval, startTimeLimit, false, listener);
    }

    /** 新版：可選是否套用台股盤中聚合（主股票用 true，比較/篩選用 false） */
    public void fetchStockDataAsync(String symbol, String interval, long startTimeLimit,
                                    boolean applyTwIntraday, DataFetchListener listener) {
        executor.submit(() -> {
            String processedSymbol = (symbol == null) ? "" : symbol.toUpperCase(Locale.US).trim();
            if (processedSymbol.isEmpty()) {
                if (listener != null) listener.onError("Empty symbol");
                return;
            }

            long period2 = System.currentTimeMillis() / 1000L; // now seconds
            long period1;

            if (startTimeLimit > 0L) {
                period1 = startTimeLimit;
                Log.d(TAG, "使用 MainActivity 傳入的自訂限制 period1: " + period1 + " (Interval: " + interval + ")");
            } else {
                long rangeInSeconds;
                if ("1h".equals(interval)) rangeInSeconds = TimeUnit.DAYS.toSeconds(60);
                else rangeInSeconds = TimeUnit.DAYS.toSeconds(5L * 365L);

                period1 = Math.max(0L, period2 - rangeInSeconds);
                Log.d(TAG, "使用下載器預設範圍 period1: " + period1 + " (Interval: " + interval + ")");
            }

            String url = String.format(Locale.US, YAHOO_BASE_URL, processedSymbol, processedSymbol, interval, period1, period2);

            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                if (response.body() == null) throw new IOException("Empty body");

                String json = response.body().string();

                // ✅ parse with timezone (meta.exchangeTimezoneName) + interval-based date format
                List<StockDayPrice> result = parseYahooData(json, interval, processedSymbol);
                if (result.isEmpty()) throw new IOException("No data found for symbol " + processedSymbol);

                // ✅ sort old->new
                Collections.sort(result, Comparator.comparing(StockDayPrice::getDate));

                // ✅ apply intraday merge BEFORE indicators
                if (applyTwIntraday && isTwSymbol(processedSymbol)) {
                    if ("1d".equals(interval)) {
                        result = applyTwseMisDailyBar(result, processedSymbol);
                    } else if ("1h".equals(interval)) {
                        result = applyYahoo1mAggregateHourBar(result, processedSymbol);
                    }
                    Collections.sort(result, Comparator.comparing(StockDayPrice::getDate));
                }

                // indicators
                calculateMovingAverages(result, interval);
                calculateMACD(result);

                if ("1h".equals(interval) || "1d".equals(interval)) calculateKD(result, 40, 3, 3);
                else calculateKD(result, 9, 3, 3);

                calculateRSI(result, 14);
                calculateDMI(result, 14);

                if (listener != null) listener.onDataFetched(result, processedSymbol);

            } catch (IOException e) {
                handleError(processedSymbol + " 請求失敗", e, listener);
            } catch (Exception e) {
                handleError(processedSymbol + " 數據解析失敗: " + e.getMessage(), e, listener);
            }
        });
    }

    // Blocking: keep old signature (no intraday by default)
    public List<StockDayPrice> fetchStockDataBlocking(String symbol, String interval, long startTime) throws Exception {
        return fetchStockDataBlocking(symbol, interval, startTime, false);
    }

    // Blocking overload: allow intraday
    public List<StockDayPrice> fetchStockDataBlocking(String symbol, String interval, long startTime,
                                                      boolean applyTwIntraday) throws Exception {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<List<StockDayPrice>> ref =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Collections.emptyList());
        final java.util.concurrent.atomic.AtomicReference<String> err =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        fetchStockDataAsync(symbol, interval, startTime, applyTwIntraday, new DataFetchListener() {
            @Override public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                ref.set(data);
                latch.countDown();
            }
            @Override public void onError(String errorMessage) {
                err.set(errorMessage);
                latch.countDown();
            }
        });

        boolean ok = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!ok) throw new java.io.IOException("fetch timeout: " + symbol);
        if (err.get() != null) throw new java.io.IOException(err.get());
        return ref.get();
    }

    // -------------------------
    // parsing
    // -------------------------

    private List<StockDayPrice> parseYahooData(String json, String interval, String symbolUpper) {
        List<StockDayPrice> prices = new ArrayList<>();
        Gson gson = new Gson();

        try {
            YahooApiResponse response = gson.fromJson(json, YahooApiResponse.class);
            if (response == null || response.chart == null || response.chart.result == null || response.chart.result.isEmpty()) {
                return prices;
            }

            YahooApiResponse.Result r0 = response.chart.result.get(0);
            long[] timestamps = r0.timestamp;

            YahooApiResponse.Quote quote = (r0.indicators != null && r0.indicators.quote != null && !r0.indicators.quote.isEmpty())
                    ? r0.indicators.quote.get(0)
                    : null;

            if (timestamps == null || quote == null || quote.close == null) return prices;
            if (quote.open == null || quote.high == null || quote.low == null || quote.volume == null) return prices;
            if (timestamps.length != quote.close.length) return prices;

            ZoneId zone = resolveExchangeZone(r0, symbolUpper);
            boolean intraday = "1h".equals(interval) || "1m".equals(interval);

            for (int i = 0; i < timestamps.length; i++) {
                if (quote.open[i] == null || quote.high[i] == null || quote.low[i] == null || quote.close[i] == null || quote.volume[i] == null) continue;

                Instant ins = Instant.ofEpochSecond(timestamps[i]);

                String dateStr;
                if (intraday) {
                    LocalDateTime ldt = ins.atZone(zone).toLocalDateTime();
                    dateStr = ldt.format(FMT_YMD_HM); // ✅ yyyy-MM-dd HH:mm
                } else {
                    LocalDate ld = ins.atZone(zone).toLocalDate();
                    dateStr = ld.format(FMT_YMD);     // ✅ yyyy-MM-dd
                }

                prices.add(new StockDayPrice(
                        dateStr,
                        quote.open[i].doubleValue(),
                        quote.high[i].doubleValue(),
                        quote.low[i].doubleValue(),
                        quote.close[i].doubleValue(),
                        quote.volume[i].doubleValue()
                ));
            }

        } catch (Exception e) {
            Log.e(TAG, "Parsing error: " + e.getMessage(), e);
        }

        return prices;
    }

    private ZoneId resolveExchangeZone(YahooApiResponse.Result r0, String symbolUpper) {
        try {
            String tz = (r0 != null && r0.meta != null) ? r0.meta.exchangeTimezoneName : null;
            if (tz != null && !tz.trim().isEmpty()) return ZoneId.of(tz.trim());
        } catch (Exception ignored) {}

        if (isTwSymbol(symbolUpper)) return ZONE_TW;
        return ZoneId.of("UTC");
    }

    private static boolean isTwSymbol(String symbolUpper) {
        if (symbolUpper == null) return false;
        String s = symbolUpper.trim().toUpperCase(Locale.US);
        return s.endsWith(".TW") || s.endsWith(".TWO");
    }

    // -------------------------
    // intraday 1d: TWSE MIS
    // -------------------------

    private List<StockDayPrice> applyTwseMisDailyBar(List<StockDayPrice> in, String symbolUpper) {
        if (in == null || in.isEmpty()) return in;

        String exCh = twseExCh(symbolUpper);
        if (exCh == null) return in;

        String key = symbolUpper.trim().toUpperCase(Locale.US);
        String todayYmd = LocalDate.now(ZONE_TW).format(FMT_YMD);

        // warm referer (cookie)
        try (Response r = client.newCall(new Request.Builder()
                .url(MIS_REFERER)
                .header("User-Agent", ua())
                .header("Referer", MIS_REFERER)
                .build()).execute()) {
            // ignore body
        } catch (Exception ignored) {}

        MisResponse data = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Response r = client.newCall(new Request.Builder()
                    .url(String.format(Locale.US, MIS_URL_FMT, exCh))
                    .header("User-Agent", ua())
                    .header("Referer", MIS_REFERER)
                    .build()).execute()) {

                if (r.isSuccessful() && r.body() != null) {
                    String js = r.body().string();
                    data = new Gson().fromJson(js, MisResponse.class);
                    if (data != null && data.msgArray != null && !data.msgArray.isEmpty()) break;
                }
            } catch (Exception ignored) {}
        }

        if (data == null || data.msgArray == null || data.msgArray.isEmpty()) {
            // MIS fail -> fallback cache ONLY if yahoo missing today
            if (!containsKeyDate(in, todayYmd)) {
                DailySnap snap = intradayDailyCache.get(key);
                if (snap != null && todayYmd.equals(snap.dateYmd)) {
                    return upsertDaily(in, todayYmd, snap.o, snap.h, snap.l, snap.c, snap.vShares);
                }
            }
            return in;
        }

        MisRow row = data.msgArray.get(0);
        double o = toDouble(row.o);
        double h = toDouble(row.h);
        double l = toDouble(row.l);
        double c = toDouble(row.z);     // last price
        double vLots = toDouble(row.v); // ✅ MIS v is lots(張)

        if (!Double.isFinite(c)) {
            if (!containsKeyDate(in, todayYmd)) {
                DailySnap snap = intradayDailyCache.get(key);
                if (snap != null && todayYmd.equals(snap.dateYmd)) {
                    return upsertDaily(in, todayYmd, snap.o, snap.h, snap.l, snap.c, snap.vShares);
                }
            }
            return in;
        }

        if (!isMisTlongToday(row.tlong)) {
            if (!containsKeyDate(in, todayYmd)) {
                DailySnap snap = intradayDailyCache.get(key);
                if (snap != null && todayYmd.equals(snap.dateYmd)) {
                    return upsertDaily(in, todayYmd, snap.o, snap.h, snap.l, snap.c, snap.vShares);
                }
            }
            return in;
        }

        if (!Double.isFinite(o)) o = c;
        if (!Double.isFinite(h)) h = Math.max(o, c);
        if (!Double.isFinite(l)) l = Math.min(o, c);

        // MIS v is lots -> shares
        double vShares = Double.isFinite(vLots) ? (vLots * 1000.0) : Double.NaN;

        intradayDailyCache.put(key, new DailySnap(todayYmd, o, h, l, c, vShares));
        return upsertDaily(in, todayYmd, o, h, l, c, vShares);
    }

    private static boolean isMisTlongToday(String tlong) {
        try {
            if (tlong == null || tlong.trim().isEmpty()) return false;
            long ms = Long.parseLong(tlong.trim());
            LocalDate d = Instant.ofEpochMilli(ms).atZone(ZONE_TW).toLocalDate();
            LocalDate today = LocalDate.now(ZONE_TW);
            return today.equals(d);
        } catch (Exception e) {
            return false;
        }
    }

    private static String twseExCh(String symbolUpper) {
        String s = symbolUpper.trim().toUpperCase(Locale.US);
        if (s.endsWith(".TW")) {
            String code = s.substring(0, s.length() - 3);
            if (code.isEmpty()) return null;
            return "tse_" + code + ".tw";
        }
        if (s.endsWith(".TWO")) {
            String code = s.substring(0, s.length() - 4);
            if (code.isEmpty()) return null;
            return "otc_" + code + ".tw";
        }
        return null;
    }

    private static boolean containsKeyDate(List<StockDayPrice> list, String ymd) {
        for (StockDayPrice p : list) {
            if (p != null && ymd.equals(p.getDate())) return true;
        }
        return false;
    }

    private static List<StockDayPrice> upsertDaily(List<StockDayPrice> in, String ymd,
                                                   double o, double h, double l, double c, double vShares) {
        ArrayList<StockDayPrice> out = new ArrayList<>(in.size() + 1);
        boolean replaced = false;

        for (StockDayPrice p : in) {
            if (p == null) continue;
            if (!replaced && ymd.equals(p.getDate())) {
                out.add(new StockDayPrice(ymd, o, h, l, c, Double.isFinite(vShares) ? vShares : p.getVolume()));
                replaced = true;
            } else {
                out.add(p);
            }
        }
        if (!replaced) {
            out.add(new StockDayPrice(ymd, o, h, l, c, Double.isFinite(vShares) ? vShares : Double.NaN));
        }
        return out;
    }

    // -------------------------
    // intraday 1h: aggregate from Yahoo 1m
    // -------------------------

    private List<StockDayPrice> applyYahoo1mAggregateHourBar(List<StockDayPrice> in, String symbolUpper) {
        if (in == null || in.isEmpty()) return in;

        String key = symbolUpper.trim().toUpperCase(Locale.US);

        long nowSec = System.currentTimeMillis() / 1000L;
        long p1 = nowSec - 2L * 86400L;
        long p2 = nowSec;

        String url = String.format(Locale.US, YAHOO_BASE_URL, key, key, "1m", p1, p2);

        List<StockDayPrice> mins;
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("1m fetch failed");
            String json = response.body().string();
            mins = parseYahooData(json, "1m", key);
        } catch (Exception e) {
            HourSnap snap = intradayHourCache.get(key);
            if (snap != null) return upsertHour(in, snap.hourKey, snap.o, snap.h, snap.l, snap.c, snap.vShares);
            return in;
        }

        if (mins == null || mins.isEmpty()) {
            HourSnap snap = intradayHourCache.get(key);
            if (snap != null) return upsertHour(in, snap.hourKey, snap.o, snap.h, snap.l, snap.c, snap.vShares);
            return in;
        }

        String last = mins.get(mins.size() - 1).getDate();
        LocalDateTime lastTs;
        try {
            lastTs = LocalDateTime.parse(last, FMT_YMD_HM);
        } catch (Exception e) {
            HourSnap snap = intradayHourCache.get(key);
            if (snap != null) return upsertHour(in, snap.hourKey, snap.o, snap.h, snap.l, snap.c, snap.vShares);
            return in;
        }

        LocalDateTime hourStart = lastTs.withMinute(0).withSecond(0).withNano(0);
        String hourKey = hourStart.format(FMT_YMD_HM); // minute=00
        String hourPrefix = hourKey.substring(0, 13);  // "yyyy-MM-dd HH"

        boolean any = false;
        double o = Double.NaN, h = Double.NaN, l = Double.NaN, c = Double.NaN;
        double vSum = 0.0;

        for (StockDayPrice p : mins) {
            if (p == null) continue;
            String ds = p.getDate();
            if (ds == null || ds.length() < 13) continue;
            if (!ds.startsWith(hourPrefix)) continue;

            if (!any) {
                o = p.getOpen();
                h = p.getHigh();
                l = p.getLow();
                any = true;
            } else {
                h = Math.max(h, p.getHigh());
                l = Math.min(l, p.getLow());
            }
            c = p.getClose();
            vSum += p.getVolume(); // Yahoo 1m volume is shares
        }

        if (!any || !Double.isFinite(c)) {
            HourSnap snap = intradayHourCache.get(key);
            if (snap != null) return upsertHour(in, snap.hourKey, snap.o, snap.h, snap.l, snap.c, snap.vShares);
            return in;
        }

        if (!Double.isFinite(o)) o = c;
        if (!Double.isFinite(h)) h = Math.max(o, c);
        if (!Double.isFinite(l)) l = Math.min(o, c);

        intradayHourCache.put(key, new HourSnap(hourKey, o, h, l, c, vSum));
        return upsertHour(in, hourKey, o, h, l, c, vSum);
    }

    private static List<StockDayPrice> upsertHour(List<StockDayPrice> in, String hourKey,
                                                  double o, double h, double l, double c, double vShares) {
        ArrayList<StockDayPrice> out = new ArrayList<>(in.size() + 1);
        boolean replaced = false;

        for (StockDayPrice p : in) {
            if (p == null) continue;
            if (!replaced && hourKey.equals(p.getDate())) {
                out.add(new StockDayPrice(hourKey, o, h, l, c, Double.isFinite(vShares) ? vShares : p.getVolume()));
                replaced = true;
            } else {
                out.add(p);
            }
        }
        if (!replaced) {
            out.add(new StockDayPrice(hourKey, o, h, l, c, Double.isFinite(vShares) ? vShares : Double.NaN));
        }
        return out;
    }

    // -------------------------
    // utils
    // -------------------------

    private static String ua() {
        return "Mozilla/5.0 (Linux; Android 13; StockFetcher) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    }

    private static double toDouble(String x) {
        try {
            if (x == null) return Double.NaN;
            String s = x.trim();
            if (s.isEmpty() || "-".equals(s) || "—".equals(s)) return Double.NaN;
            return Double.parseDouble(s.replace(",", ""));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private void handleError(String baseMessage, Throwable t, DataFetchListener listener) {
        String errorMessage = baseMessage + ": " + (t.getMessage() != null ? t.getMessage() : "未知錯誤");
        Log.e(TAG, errorMessage, t);
        if (listener != null) listener.onError(errorMessage);
    }

    // -------------------------
    // CookieJar (in-memory)
    // -------------------------

    private static final class InMemoryCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> store = new ConcurrentHashMap<>();

        @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            if (url == null || cookies == null) return;
            store.put(url.host(), cookies);
        }

        @Override public List<Cookie> loadForRequest(HttpUrl url) {
            if (url == null) return java.util.Collections.emptyList();
            List<Cookie> cookies = store.get(url.host());
            return (cookies != null) ? cookies : java.util.Collections.emptyList();
        }
    }

    // -------------------------
    // JSON models
    // -------------------------

    public static class YahooApiResponse {
        public Chart chart;
        public static class Chart { public List<Result> result; }
        public static class Result {
            public long[] timestamp;
            public Meta meta;
            public Indicators indicators;
        }
        public static class Meta {
            public String exchangeTimezoneName; // e.g. "Asia/Taipei"
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

    private static final class MisResponse {
        List<MisRow> msgArray;
    }
    private static final class MisRow {
        String o, h, l, z, v, tlong;
    }

    // -------------------------
    // indicators (完整補齊：避免你遇到 missing method / scope 錯誤)
    // -------------------------

    private void calculateDMI(List<StockDayPrice> prices, int period) {
        if (prices == null || prices.size() <= period + 1) return;

        int n = prices.size();

        for (StockDayPrice p : prices) {
            p.dmiPDI = Double.NaN;
            p.dmiMDI = Double.NaN;
            p.dmiADX = Double.NaN;
        }

        double[] tr = new double[n];
        double[] dmPlus = new double[n];
        double[] dmMinus = new double[n];

        // 1) TR, +DM, -DM
        for (int i = 1; i < n; i++) {
            double upMove = prices.get(i).getHigh() - prices.get(i - 1).getHigh();
            double downMove = prices.get(i - 1).getLow() - prices.get(i).getLow();

            dmPlus[i] = (upMove > downMove && upMove > 0) ? upMove : 0.0;
            dmMinus[i] = (downMove > upMove && downMove > 0) ? downMove : 0.0;

            double h_l = prices.get(i).getHigh() - prices.get(i).getLow();
            double h_pc = Math.abs(prices.get(i).getHigh() - prices.get(i - 1).getClose());
            double l_pc = Math.abs(prices.get(i).getLow() - prices.get(i - 1).getClose());
            tr[i] = Math.max(h_l, Math.max(h_pc, l_pc));
        }

        // 2) Wilder smoothing init
        double smTR = 0.0, smP = 0.0, smM = 0.0;
        for (int i = 1; i <= period; i++) {
            smTR += tr[i];
            smP += dmPlus[i];
            smM += dmMinus[i];
        }

        double[] dx = new double[n];
        for (int i = 0; i < n; i++) dx[i] = Double.NaN;

        for (int i = period; i < n; i++) {
            if (i > period) {
                smTR = smTR - (smTR / period) + tr[i];
                smP  = smP  - (smP  / period) + dmPlus[i];
                smM  = smM  - (smM  / period) + dmMinus[i];
            }

            if (smTR <= 0) continue;

            double pdi = (smP / smTR) * 100.0;
            double mdi = (smM / smTR) * 100.0;
            prices.get(i).dmiPDI = pdi;
            prices.get(i).dmiMDI = mdi;

            double denom = pdi + mdi;
            if (denom == 0) continue;

            dx[i] = (Math.abs(pdi - mdi) / denom) * 100.0;
        }

        // 3) ADX
        int adxStart = period * 2;
        if (adxStart >= n) return;

        double sumDx = 0.0;
        int cnt = 0;
        for (int i = period; i < adxStart; i++) {
            if (!Double.isNaN(dx[i])) { sumDx += dx[i]; cnt++; }
        }
        if (cnt < period) return;

        double adx = sumDx / period;
        prices.get(adxStart - 1).dmiADX = adx;

        for (int i = adxStart; i < n; i++) {
            if (Double.isNaN(dx[i])) continue;
            adx = (adx * (period - 1) + dx[i]) / period;
            prices.get(i).dmiADX = adx;
        }
    }

    private void calculateRSI(List<StockDayPrice> prices, int period) {
        if (prices == null || prices.size() <= period) return;

        for (StockDayPrice p : prices) p.rsi = Double.NaN;

        double avgGain = 0.0;
        double avgLoss = 0.0;

        for (int i = 1; i <= period; i++) {
            double diff = prices.get(i).getClose() - prices.get(i - 1).getClose();
            if (diff >= 0) avgGain += diff;
            else avgLoss -= diff;
        }
        avgGain /= period;
        avgLoss /= period;

        prices.get(period).rsi = (avgLoss == 0)
                ? 100.0
                : 100.0 - (100.0 / (1.0 + (avgGain / avgLoss)));

        for (int i = period + 1; i < prices.size(); i++) {
            double diff = prices.get(i).getClose() - prices.get(i - 1).getClose();
            double gain = (diff > 0) ? diff : 0.0;
            double loss = (diff < 0) ? -diff : 0.0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            if (avgLoss == 0) {
                prices.get(i).rsi = 100.0;
            } else {
                double rs = avgGain / avgLoss;
                prices.get(i).rsi = 100.0 - (100.0 / (1.0 + rs));
            }
        }
    }

    private void calculateMovingAverages(List<StockDayPrice> prices, String interval) {
        if (prices == null || prices.isEmpty()) return;

        for (StockDayPrice p : prices) {
            p.ma35 = Double.NaN;
            p.ma60 = Double.NaN;
            p.ma120 = Double.NaN;
            p.ma200 = Double.NaN;
            p.ma240 = Double.NaN;
        }

        final int dataSize = prices.size();

        final int[] maPeriods;
        switch (interval) {
            case "1h":
            case "1wk":
                maPeriods = new int[]{35, 200};
                break;
            case "1mo":
                maPeriods = new int[]{60, 120};
                break;
            case "1d":
            default:
                maPeriods = new int[]{60, 240};
                break;
        }

        final double[] close = new double[dataSize];
        for (int i = 0; i < dataSize; i++) close[i] = prices.get(i).getClose();

        for (int period : maPeriods) {
            if (period <= 0) continue;

            double sum = 0.0;

            for (int i = 0; i < dataSize; i++) {
                sum += close[i];

                if (i >= period) sum -= close[i - period];

                if (i >= period - 1) {
                    double ma = sum / period;

                    if (period == 35) prices.get(i).ma35 = ma;
                    else if (period == 60) prices.get(i).ma60 = ma;
                    else if (period == 120) prices.get(i).ma120 = ma;
                    else if (period == 200) prices.get(i).ma200 = ma;
                    else if (period == 240) prices.get(i).ma240 = ma;
                }
            }
        }
    }

    private void calculateMACD(List<StockDayPrice> prices) {
        if (prices == null || prices.isEmpty()) return;

        final int SHORT = 12;
        final int LONG  = 26;
        final int SIGNAL = 9;

        for (StockDayPrice p : prices) {
            p.macdDIF = Double.NaN;
            p.macdDEA = Double.NaN;
            p.macdHistogram = Double.NaN;
        }

        int n = prices.size();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = prices.get(i).getClose();

        double[] ema12 = calculateEMA(close, SHORT);
        double[] ema26 = calculateEMA(close, LONG);

        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(ema12[i]) && Double.isFinite(ema26[i])) dif[i] = ema12[i] - ema26[i];
            else dif[i] = Double.NaN;
        }

        double[] dea = calculateEMA(dif, SIGNAL);

        for (int i = 0; i < n; i++) {
            prices.get(i).macdDIF = dif[i];
            prices.get(i).macdDEA = dea[i];
            if (Double.isFinite(dif[i]) && Double.isFinite(dea[i])) prices.get(i).macdHistogram = dif[i] - dea[i];
        }
    }

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
            if (!Double.isFinite(xi)) out[i] = out[i - 1];
            else out[i] = alpha * xi + (1.0 - alpha) * out[i - 1];
        }
        return out;
    }

    private void calculateKD(List<StockDayPrice> prices, int N, int smoothK, int dPeriod) {
        if (prices == null) return;
        int n = prices.size();
        if (n < N) return;

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

        // RSV
        for (int i = N - 1; i < n; i++) {
            double ll = Double.POSITIVE_INFINITY;
            double hh = Double.NEGATIVE_INFINITY;

            for (int j = i - N + 1; j <= i; j++) {
                ll = Math.min(ll, prices.get(j).getLow());
                hh = Math.max(hh, prices.get(j).getHigh());
            }

            double denom = hh - ll;
            if (denom <= 0) continue;

            double close = prices.get(i).getClose();
            rsv[i] = 100.0 * (close - ll) / denom;
        }

        smaStrict(rsv, smoothK, k);
        smaStrict(k, dPeriod, d);

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
                if (!Double.isFinite(v)) { sum = Double.NaN; break; }
                sum += v;
            }
            if (Double.isFinite(sum)) dst[i] = sum / window;
        }
    }
}