package com.example.stockfetcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

public final class ScreenerEngine {

    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    public interface CancelToken {
        boolean isCancelled();
    }

    public static final class ModeConfig {
        public final String interval; // "1d","1wk","1mo"
        public final int kPeriod;
        public final double thr;
        public final boolean wantLt;
        public final int persistDays; // for LT20
        public final boolean needVolSpike;
        public final int runMin, runMax; // for GT45 (inclusive); if <=0 means unused
        public final boolean needMaBand;
        public final int maWindow;
        public final double band;
        public final boolean needGoldenCross;

        private ModeConfig(String interval, int kPeriod, double thr, boolean wantLt,
                           int persistDays, boolean needVolSpike,
                           int runMin, int runMax,
                           boolean needMaBand, int maWindow, double band,
                           boolean needGoldenCross) {
            this.interval = interval;
            this.kPeriod = kPeriod;
            this.thr = thr;
            this.wantLt = wantLt;
            this.persistDays = persistDays;
            this.needVolSpike = needVolSpike;
            this.runMin = runMin;
            this.runMax = runMax;
            this.needMaBand = needMaBand;
            this.maWindow = maWindow;
            this.band = band;
            this.needGoldenCross = needGoldenCross;
        }

        public static ModeConfig forMode(ScreenerMode mode) {
            switch (mode) {
                case LT20:
                    return new ModeConfig("1d", 40, 20.0, true, 40, true,
                            0, 0, false, 0, 0.0, false);
                case GT45:
                    return new ModeConfig("1d", 40, 45.0, false, 0, true,
                            21, 29, false, 0, 0.0, false);
                case MA60_3PCT:
                    return new ModeConfig("1d", 0, 0.0, true, 0, false,
                            0, 0, true, 60, 0.03, false);
                case KD9_MO_GC:
                    return new ModeConfig("1mo", 9, 0.0, true, 0, false,
                            0, 0, false, 0, 0.0, true);
                case KD9_WK_GC:
                    return new ModeConfig("1wk", 9, 0.0, true, 0, false,
                            0, 0, false, 0, 0.0, true);
                default:
                    throw new IllegalArgumentException("Unsupported mode: " + mode);
            }
        }
    }

    // 對齊 Python：日線約 1100 天、週線 10 年、月線 20 年
    public static long getScreenerStartTimeSeconds(String interval) {
        Calendar cal = Calendar.getInstance();
        switch (interval) {
            case "1d":  cal.add(Calendar.DAY_OF_YEAR, -1100); break;
            case "1wk": cal.add(Calendar.YEAR, -10); break;
            case "1mo": cal.add(Calendar.YEAR, -20); break;
            default:    cal.add(Calendar.DAY_OF_YEAR, -1100); break;
        }
        return cal.getTimeInMillis() / 1000L;
    }

    public static List<ScreenerResult> run(
            List<TickerInfo> tickers,
            ScreenerMode mode,
            YahooFinanceFetcher fetcher,
            ProgressListener progress,
            CancelToken cancel
    ) throws Exception {

        ModeConfig cfg = ModeConfig.forMode(mode);
        int total = tickers.size();
        int done = 0;

        List<ScreenerResult> out = new ArrayList<>();
        long startTime = getScreenerStartTimeSeconds(cfg.interval);

        for (TickerInfo info : tickers) {
            if (cancel != null && cancel.isCancelled()) break;

            done++;
            if (progress != null && (done == 1 || done % 3 == 0 || done == total)) {
                progress.onProgress(done, total);
            }

            List<StockDayPrice> data = fetcher.fetchStockDataBlocking(info.ticker, cfg.interval, startTime);
            data = OhlcCleaners.cleanOhlc(data, cfg.interval);
            if (data == null || data.isEmpty()) continue;

            // MA60 band
            if (cfg.needMaBand) {
                ScreenerResult r = evalMaBand(info, data, cfg.maWindow, cfg.band);
                if (r != null) out.add(r);
                continue;
            }

            // KD golden cross
            if (cfg.needGoldenCross) {
                ScreenerResult r = evalGoldenCross(info, data, cfg.kPeriod);
                if (r != null) out.add(r);
                continue;
            }

            // KD + volume spike modes
            ScreenerResult r;
            if (mode == ScreenerMode.GT45) {
                r = evalGt45(info, data, cfg.kPeriod, cfg.thr, cfg.runMin, cfg.runMax, cfg.needVolSpike);
            } else { // LT20
                r = evalLt20(info, data, cfg.kPeriod, cfg.thr, cfg.persistDays, cfg.wantLt, cfg.needVolSpike);
            }
            if (r != null) out.add(r);
        }

        out.sort(Comparator.comparingDouble((ScreenerResult r) -> r.avgClose60).reversed());
        return out;
    }

    // ---------- helpers (對齊 Python 的 rolling KD) ----------

    private static class KD {
        final double[] k;
        final double[] d;
        KD(double[] k, double[] d) { this.k = k; this.d = d; }
    }

    private static KD stochKD_SMA(List<StockDayPrice> list, int kPeriod, int smoothK, int dPeriod) {
        int n = list.size();
        double[] kRaw = new double[n];
        double[] k = new double[n];
        double[] d = new double[n];

        for (int i = 0; i < n; i++) { kRaw[i] = Double.NaN; k[i] = Double.NaN; d[i] = Double.NaN; }

        for (int i = kPeriod - 1; i < n; i++) {
            double ll = Double.POSITIVE_INFINITY;
            double hh = Double.NEGATIVE_INFINITY;
            for (int j = i - kPeriod + 1; j <= i; j++) {
                ll = Math.min(ll, list.get(j).getLow());
                hh = Math.max(hh, list.get(j).getHigh());
            }
            double denom = hh - ll;
            if (denom <= 0) continue;
            double close = list.get(i).getClose();
            kRaw[i] = 100.0 * (close - ll) / denom;
        }

        sma(kRaw, smoothK, k);
        sma(k, dPeriod, d);
        return new KD(k, d);
    }

    private static void sma(double[] src, int window, double[] dst) {
        int n = src.length;
        for (int i = 0; i < n; i++) dst[i] = Double.NaN;
        if (window <= 0) return;

        for (int i = 0; i < n; i++) {
            if (i < window - 1) continue;
            double sum = 0.0;
            int cnt = 0;
            for (int j = i - window + 1; j <= i; j++) {
                double v = src[j];
                if (Double.isNaN(v)) { cnt = 0; break; }
                sum += v;
                cnt++;
            }
            if (cnt == window) dst[i] = sum / window;
        }
    }

    private static boolean[] volumeSpike(List<StockDayPrice> list, int maWindow, double mult) {
        int n = list.size();
        double[] vol = new double[n];
        for (int i = 0; i < n; i++) vol[i] = list.get(i).getVolume();

        double[] ma = new double[n];
        sma(vol, maWindow, ma);

        boolean[] spike = new boolean[n];
        for (int i = 0; i < n; i++) {
            spike[i] = !Double.isNaN(ma[i]) && ma[i] > 0 && vol[i] > ma[i] * mult;
        }
        return spike;
    }

    private static double avgCloseLastN(List<StockDayPrice> list, int n) {
        int size = list.size();
        int m = Math.min(n, size);
        double sum = 0.0;
        for (int i = size - m; i < size; i++) sum += list.get(i).getClose();
        return sum / Math.max(1, m);
    }

    // ---------- evaluators (對齊 Python 規則) ----------

    private static ScreenerResult evalMaBand(TickerInfo info, List<StockDayPrice> list, int maWindow, double band) {
        int needLen = maWindow + 40 + 10;
        if (list.size() < needLen) return null;

        double[] close = new double[list.size()];
        for (int i = 0; i < list.size(); i++) close[i] = list.get(i).getClose();

        double[] ma = new double[list.size()];
        sma(close, maWindow, ma);

        int n = list.size();
        for (int i = n - 40; i < n; i++) {
            if (Double.isNaN(ma[i]) || ma[i] == 0.0) return null;
            double diffPct = Math.abs(close[i] - ma[i]) / ma[i];
            if (diffPct > band) return null;
        }

        double latestClose = close[n - 1];
        double latestMa = ma[n - 1];
        double latestDiffPct = (latestMa != 0.0) ? Math.abs(latestClose - latestMa) / latestMa : Double.NaN;

        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), latestClose,
                null, null, null,
                latestMa, latestDiffPct * 100.0,
                null
        );
    }

    private static ScreenerResult evalGoldenCross(TickerInfo info, List<StockDayPrice> list, int kPeriod) {
        //if (list.size() < Math.max(30, kPeriod + 10)) return null;
        //if (list.size() < (kPeriod + 3 + 3)) return null; // 只做很低的保底也行
        list.sort(java.util.Comparator.comparing(StockDayPrice::getDate));
        // 你的日期是 yyyy-MM-dd，字串排序可行（等同時間排序）

        KD kd = stochKD_SMA(list, kPeriod, 3, 3);

        int n = list.size();
        int last = -1, prev = -1;
        for (int i = n - 1; i >= 0; i--) {
            if (!Double.isNaN(kd.k[i]) && !Double.isNaN(kd.d[i])) { last = i; break; }
        }
        for (int i = last - 1; i >= 0; i--) {
            if (!Double.isNaN(kd.k[i]) && !Double.isNaN(kd.d[i])) { prev = i; break; }
        }
        if (last < 0 || prev < 0) return null;

        boolean isGc = kd.k[prev] <= kd.d[prev] && kd.k[last] > kd.d[last];
        if (!isGc) return null;

        String crossDate = list.get(last).getDate(); // 你的資料目前是 yyyy-MM-dd

        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                kd.k[last], kd.d[last], null,
                null, null,
                crossDate
        );
    }

    private static ScreenerResult evalLt20(
            TickerInfo info, List<StockDayPrice> list,
            int kPeriod, double thr, int persistDays, boolean wantLt, boolean needVolSpike
    ) {
        if (list.size() < Math.max(60, kPeriod + 40 + 10)) return null;

        KD kd = stochKD_SMA(list, kPeriod, 3, 3);
        boolean[] spike = needVolSpike ? volumeSpike(list, 20, 1.5) : null;

        // 找出最後 persistDays 個有效 K（Python 用 dropna 後 tail）
        List<Integer> validIdx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) if (!Double.isNaN(kd.k[i])) validIdx.add(i);
        if (validIdx.size() < persistDays) return null;

        int startPos = validIdx.size() - persistDays;
        boolean anySpike = false;
        double lastK = kd.k[validIdx.get(validIdx.size() - 1)];

        for (int p = startPos; p < validIdx.size(); p++) {
            int i = validIdx.get(p);
            boolean ok = wantLt ? (kd.k[i] < thr) : (kd.k[i] > thr);
            if (!ok) return null;
            if (needVolSpike && spike != null && spike[i]) anySpike = true;
        }
        if (needVolSpike && !anySpike) return null;

        int n = list.size();
        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                lastK, null, null,
                null, null, null
        );
    }

    private static ScreenerResult evalGt45(
            TickerInfo info, List<StockDayPrice> list,
            int kPeriod, double thr, int runMin, int runMax, boolean needVolSpike
    ) {
        if (list.size() < Math.max(60, kPeriod + 40 + 10)) return null;

        KD kd = stochKD_SMA(list, kPeriod, 3, 3);
        boolean[] spike = needVolSpike ? volumeSpike(list, 20, 1.5) : null;

        // Python：對 k_valid > thr 做「從尾端連續 True 計數」
        int n = list.size();
        int run = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (Double.isNaN(kd.k[i])) continue; // 跳過 NaN 直到遇到有效值後再算連續（簡化做法）
            if (kd.k[i] > thr) run++;
            else break;
        }

        if (run < runMin || run > runMax) return null;

        boolean anySpike = false;
        if (needVolSpike && spike != null) {
            for (int i = n - run; i < n; i++) if (spike[i]) { anySpike = true; break; }
            if (!anySpike) return null;
        }

        double lastK = Double.NaN;
        for (int i = n - 1; i >= 0; i--) { if (!Double.isNaN(kd.k[i])) { lastK = kd.k[i]; break; } }

        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                lastK, null, run,
                null, null, null
        );
    }
}