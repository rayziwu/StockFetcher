package com.example.stockfetcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

public final class ScreenerEngine {

    private static final String TAG = "ScreenerEngine";

    // [ADD] parameter overrides from UI/service
    public static final class Overrides {
        public Integer ltThr;      // KD40 < thr
        public Integer ltDays;     // last N days

        public Integer gtThr;      // KD40 > thr
        public Integer gtMin;      // runMin
        public Integer gtMax;      // runMax

        public Integer maBandPct;  // ±% (e.g. 3 means 3%)
        public Integer maDays;     // last N days

        public String maTf;

        public Integer maWindow;

        // ✅ [ADD] MACD divergence recent
        public Integer macdDivBars;   // recent N bars, default 2
        public String  macdDivTf;     // timeframe: "HOUR/DAY/WEEK/MONTH" (or "時/日/周/月", or "1h/1d/1wk/1mo")
        public String  macdDivSide;   // "BOTTOM/TOP" (or "底/頂")
        public Integer kdGcBars;  // 最近N根，預設2
        public String  kdGcTf;    // 時/日/周/月（或 Hour/Day/Week/Month）
        public String m1234LimitUpRule; // "0"/"1"/">1"

    }

    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    public interface CancelToken {
        boolean isCancelled();
    }

    public static final class ModeConfig {
        public final String interval; // "1h","1d","1wk","1mo"
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
                    return new ModeConfig("1d", 40, 20.0, true, 20, true,
                            0, 0, false, 0, 0.0, false);

                case GT45:
                    return new ModeConfig("1d", 40, 45.0, false, 0, true,
                            20, 30, false, 0, 0.0, false);

                case MA60_3PCT:
                    return new ModeConfig("1d", 0, 0.0, true, 20, false,
                            0, 0, true, 60, 0.03, false);

                case MACD_DIV_RECENT:
                    // 預設 1h（實際會依 ov.macdDivTf 覆寫）
                    return new ModeConfig("1h", 0, 0.0, true, 0, false,
                            0, 0, false, 0, 0.0, false);

                case KD_GC_RECENT:
                    // ✅ 新第5項：KD 黃金交叉（近N根XK）
                    // 預設 1h（實際會依 ov.kdGcTf 覆寫）
                    // 不需要 kPeriod / needGoldenCross（我們用已算好的 kdK/kdD 判斷）
                    return new ModeConfig("1h", 0, 0.0, true, 0, false,
                            0, 0, false, 0, 0.0, false);

                case MODE_1234:
                    return new ModeConfig("1d", 0, 0.0, true, 0, false,
                            0, 0, false, 0, 0.0, false);
                default:
                    throw new IllegalArgumentException("Unsupported mode: " + mode);
            }
        }
    }

    // 對齊 Python：日線約 1100 天、週線 10 年、月線 20 年
    // ✅ [ADD] 1h：抓近 ~400 天（你可自行調整）
    public static long getScreenerStartTimeSeconds(String interval) {
        Calendar cal = Calendar.getInstance();
        switch (interval) {
            case "1h":  cal.add(Calendar.DAY_OF_YEAR, -400); break;
            case "1d":  cal.add(Calendar.DAY_OF_YEAR, -1100); break;
            case "1wk": cal.add(Calendar.YEAR, -10); break;
            case "1mo": cal.add(Calendar.YEAR, -20); break;
            default:    cal.add(Calendar.DAY_OF_YEAR, -1100); break;
        }
        return cal.getTimeInMillis() / 1000L;
    }

    // [ADD] new overload with overrides
    public static List<ScreenerResult> run(
            List<TickerInfo> tickers,
            ScreenerMode mode,
            YahooFinanceFetcher fetcher,
            ProgressListener progress,
            CancelToken cancel,
            Overrides ov
    ) throws Exception {

        ModeConfig cfg = ModeConfig.forMode(mode);

        String interval = cfg.interval;

        if (mode == ScreenerMode.MACD_DIV_RECENT) {
            interval = resolveMacdDivInterval(ov);
        } else if (mode == ScreenerMode.KD_GC_RECENT) {
            interval = resolveKdGcInterval(ov);
        } else if (mode == ScreenerMode.MA60_3PCT) {
            interval = resolveMaBandInterval(ov);   // ✅ 新增
        }

        long startTime = getScreenerStartTimeSeconds(interval);

        int total = tickers.size();
        int done = 0;

        List<ScreenerResult> out = new ArrayList<>();



        // resolve overrides (with sane clamps)
        final double ltThr = (ov != null && ov.ltThr != null) ? clampD(ov.ltThr, 0, 100) : cfg.thr;
        final int ltDays = (ov != null && ov.ltDays != null) ? clampI(ov.ltDays, 1, 400) : cfg.persistDays;

        final double gtThr = (ov != null && ov.gtThr != null) ? clampD(ov.gtThr, 0, 100) : cfg.thr;
        final int gtMin = (ov != null && ov.gtMin != null) ? clampI(ov.gtMin, 1, 400) : cfg.runMin;
        final int gtMax = (ov != null && ov.gtMax != null) ? clampI(ov.gtMax, 1, 400) : cfg.runMax;

        final double maBand = (ov != null && ov.maBandPct != null)
                ? (clampD(ov.maBandPct, 0, 99) / 100.0)
                : cfg.band;
        final int maDays = (ov != null && ov.maDays != null) ? clampI(ov.maDays, 1, 400) : cfg.persistDays;

        final int maWindow = (ov != null && ov.maWindow != null)
                ? clampI(ov.maWindow, 1, 400)
                : cfg.maWindow; // fallback（你 cfg 原本是 60 或其它）
        final int kdGcBars = (ov != null && ov.kdGcBars != null) ? clampI(ov.kdGcBars, 1, 99) : 2;

        final int gtMin2 = Math.min(gtMin, gtMax);
        final int gtMax2 = Math.max(gtMin, gtMax);

        // ✅ MACD divergence params
        final int macdBars = (ov != null && ov.macdDivBars != null) ? clampI(ov.macdDivBars, 1, 50) : 2;
        final MacdDivergenceUtil.Side macdSide = resolveMacdDivSide(ov);

        for (TickerInfo info : tickers) {
            if (cancel != null && cancel.isCancelled()) break;

            done++;
            if (progress != null && (done == 1 || done % 3 == 0 || done == total)) {
                progress.onProgress(done, total);
            }

            // ✅ 用 interval（MACD divergence 可能是 1h/1d/1wk/1mo）
            List<StockDayPrice> data;
            try {
                data = fetcher.fetchStockDataBlocking(info.ticker, interval, startTime);
            } catch (Exception e) {
                // 單一 ticker 失敗：跳過，繼續篩選，不中止整批
                // （YahooFinanceFetcher 那邊已經有印錯誤 log，這裡可不再印）
                continue;
            }
            if (data == null || data.isEmpty()) continue;

            // ✅ 第4項：MACD 背離（DIF/Hist 任一成立）
            if (mode == ScreenerMode.MACD_DIV_RECENT) {
                ScreenerResult r = evalMacdDivRecent(info, data, macdBars, macdSide);
                if (r != null) out.add(r);
                continue;
            }
            if (mode == ScreenerMode.KD_GC_RECENT) {
                ScreenerResult r = evalKdGoldenCrossRecent(info, data, kdGcBars);
                if (r != null) out.add(r);
                continue;
            }

            if (mode == ScreenerMode.MODE_1234) {
                String rule = (ov != null && ov.m1234LimitUpRule != null) ? ov.m1234LimitUpRule : "1";
                ScreenerResult r = eval1234(info, data, rule);
                if (r != null) out.add(r);
                continue;
            }
            // MA60 band
            if (cfg.needMaBand) {
                ScreenerResult r = evalMaBand(info, data, maWindow, maBand, maDays);
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
                r = evalGt45(info, data, cfg.kPeriod, gtThr, gtMin2, gtMax2, cfg.needVolSpike);
            } else { // LT20
                r = evalLt20(info, data, cfg.kPeriod, ltThr, ltDays, cfg.wantLt, cfg.needVolSpike);
            }
            if (r != null) out.add(r);
                    }

        out.sort(Comparator.comparingDouble((ScreenerResult r) -> r.avgClose60).reversed());
        return out;
    }
    private static ScreenerResult eval1234(TickerInfo info, List<StockDayPrice> list, String limitUpRule) {
        if (list == null || list.size() < 60) return null;

        list.sort(java.util.Comparator.comparing(StockDayPrice::getDate));

        final int lookN = 20;
        final double limitUpThr = 0.095;

        int n = list.size();
        int winStart = Math.max(0, n - lookN);

        // pull arrays
        double[] close = new double[n];
        double[] high  = new double[n];
        double[] low   = new double[n];
        double[] vol   = new double[n];
        for (int i = 0; i < n; i++) {
            StockDayPrice p = list.get(i);
            close[i] = p.getClose();
            high[i]  = p.getHigh();
            low[i]   = p.getLow();
            vol[i]   = p.getVolume();
        }

        // 1) limit up cnt in last 20
        int limitUpCnt = 0;
        for (int i = winStart; i < n; i++) {
            if (i <= 0) continue;
            double pct = (close[i] / close[i - 1]) - 1.0;
            if (Double.isFinite(pct) && pct >= limitUpThr) limitUpCnt++;
        }

        // 2) gap up cnt (low > prev high)
        int gapUpCnt = 0;
        for (int i = winStart; i < n; i++) {
            if (i <= 0) continue;
            if (Double.isFinite(low[i]) && Double.isFinite(high[i - 1]) && low[i] > high[i - 1]) gapUpCnt++;
        }

        // 3) vol > vol_ma20 consecutive max
        double[] prefix = new double[n + 1];
        prefix[0] = 0.0;
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + vol[i];

        boolean[] volAbove = new boolean[n - winStart];
        for (int k = 0; k < volAbove.length; k++) {
            int i = winStart + k;
            if (i >= 19) {
                double ma20 = (prefix[i + 1] - prefix[i + 1 - 20]) / 20.0;
                volAbove[k] = Double.isFinite(ma20) && ma20 > 0 && vol[i] > ma20;
            } else {
                volAbove[k] = false;
            }
        }
        int volRunMax = maxConsecutiveTrue(volAbove);

        // 4) close up consecutive max (first in window forced false)
        boolean[] up = new boolean[n - winStart];
        for (int k = 0; k < up.length; k++) {
            int i = winStart + k;
            if (k == 0 || i <= 0) up[k] = false;
            else up[k] = Double.isFinite(close[i]) && Double.isFinite(close[i - 1]) && close[i] > close[i - 1];
        }
        int upRunMax = maxConsecutiveTrue(up);

        // rule c1
        String rule = (limitUpRule == null) ? "1" : limitUpRule.trim();
        boolean c1;
        if ("0".equals(rule)) c1 = (limitUpCnt == 0);
        else if (">1".equals(rule)) c1 = (limitUpCnt >= 2);
        else c1 = (limitUpCnt == 1);

        boolean c2 = (gapUpCnt >= 1);
        boolean c3 = (volRunMax >= 3);
        boolean c4 = (upRunMax >= 4);

        if (!(c1 && c2 && c3 && c4)) return null;

        return ScreenerResult.for1234(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                rule, limitUpCnt, gapUpCnt, volRunMax, upRunMax
        );
    }

    private static int maxConsecutiveTrue(boolean[] arr) {
        int best = 0, cur = 0;
        if (arr == null) return 0;
        for (boolean b : arr) {
            if (b) { cur++; if (cur > best) best = cur; }
            else cur = 0;
        }
        return best;
    }
    private static String resolveMaBandInterval(Overrides ov) {
        if (ov == null || ov.maTf == null) return "1d";
        String tf = ov.maTf.trim();

        if ("時".equals(tf)) return "1h";
        if ("日".equals(tf)) return "1d";
        if ("周".equals(tf)) return "1wk";
        if ("月".equals(tf)) return "1mo";

        String up = tf.toUpperCase(java.util.Locale.ROOT);
        if ("HOUR".equals(up))  return "1h";
        if ("DAY".equals(up))   return "1d";
        if ("WEEK".equals(up))  return "1wk";
        if ("MONTH".equals(up)) return "1mo";

        if ("1h".equals(tf) || "1d".equals(tf) || "1wk".equals(tf) || "1mo".equals(tf)) return tf;
        return "1d";
    }
    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static ScreenerResult evalKdGoldenCrossRecent(
            TickerInfo info,
            List<StockDayPrice> list,
            int lastBars
    ) {
        String tk = (info == null || info.ticker == null) ? "(null)" : info.ticker;

        if (list == null || list.size() < 3) {
            //android.util.Log.d(TAG, "KD_GC skip " + tk + " : list too short=" + (list == null ? -1 : list.size()));
            return null;
        }

        // 保險：確保時間序
        list.sort(java.util.Comparator.comparing(StockDayPrice::getDate));

        //android.util.Log.d(TAG, "KD_GC check " + tk
        //        + " lastBars=" + lastBars
        //        + " size=" + list.size()
        //        + " first=" + list.get(0).getDate()
        //       + " last=" + list.get(list.size() - 1).getDate());

        // 只用已算好的 kdK/kdD
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (isFinite(list.get(i).kdK) && isFinite(list.get(i).kdD)) valid.add(i);
        }
        if (valid.size() < 2) {
            //android.util.Log.d(TAG, "KD_GC skip " + tk + " : valid KD < 2, valid=" + valid.size());
            return null;
        }

        int need = Math.max(2, lastBars); // 至少兩根才判斷交叉
        int start = Math.max(1, valid.size() - need);

        //android.util.Log.d(TAG, "KD_GC scan " + tk
        //        + " validCount=" + valid.size()
        //        + " need=" + need
        //        + " scanValidPFrom=" + (valid.size() - 1) + " downTo=" + start);

        int crossIdx = -1;

        // 從尾端往回找「最近一次」交叉發生在 lastBars 內
        for (int p = valid.size() - 1; p >= start; p--) {
            int i = valid.get(p);
            int j = valid.get(p - 1);

            double kPrev = list.get(j).kdK;
            double dPrev = list.get(j).kdD;
            double kCur  = list.get(i).kdK;
            double dCur  = list.get(i).kdD;

            boolean gc = (kPrev <= dPrev) && (kCur > dCur);
            boolean dBelow20 = (dCur < 20.0);
            boolean kabove20 = (kCur > 20.0);

            if (kabove20 && dBelow20 ) {
                crossIdx = i;

                //android.util.Log.d(TAG, "KD_GC HIT " + tk
                //        + " crossDate=" + list.get(crossIdx).getDate()
                //        + " kPrev=" + kPrev + " dPrev=" + dPrev
                //        + " kCur=" + kCur + " dCur=" + dCur
                //        + " (kCur<25)");
                break;
            }
        }

        if (crossIdx < 0) {
            //android.util.Log.d(TAG, "K" +
            //        "" +
            //        "D_GC miss " + tk + " : no cross (kCur<25) within lastBars=" + lastBars);
            return null;
        }

        int n = list.size();
        String crossDate = list.get(crossIdx).getDate();

        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                list.get(crossIdx).kdK, list.get(crossIdx).kdD, null,
                null, null,
                crossDate
        );
    }
    private static String resolveKdGcInterval(Overrides ov) {
        String tf = (ov == null) ? null : ov.kdGcTf;
        if (tf == null) return "1h";
        tf = tf.trim();

        switch (tf) {
            // 中文
            case "時": return "1h";
            case "日": return "1d";
            case "周": return "1wk";
            case "月": return "1mo";

            // 英文長字
            case "Hour": case "HOUR": case "hour": return "1h";
            case "Day":  case "DAY":  case "day":  return "1d";
            case "Week": case "WEEK": case "week": return "1wk";
            case "Month":case "MONTH":case "month":return "1mo";

            // 也允許直接傳 interval
            case "1h": case "1d": case "1wk": case "1mo":
                return tf;

            default:
                return "1h";
        }
    }
    private static int clampI(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static double clampD(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // ✅ 解析 MACD divergence timeframe -> fetch interval
    private static String resolveMacdDivInterval(Overrides ov) {
        String tf = (ov == null) ? null : ov.macdDivTf;
        if (tf == null) return "1h";
        tf = tf.trim();

        // 支援：HOUR/DAY/WEEK/MONTH、1h/1d/1wk/1mo、以及 時/日/周/月
        switch (tf) {
            case "HOUR":
            case "Hour":
            case "hour":
            case "1h":
            case "時":
                return "1h";

            case "DAY":
            case "Day":
            case "day":
            case "1d":
            case "日":
                return "1d";

            case "WEEK":
            case "Week":
            case "week":
            case "1wk":
            case "周":
                return "1wk";

            case "MONTH":
            case "Month":
            case "month":
            case "1mo":
            case "月":
                return "1mo";

            default:
                return "1h";
        }
    }

    // ✅ 解析 MACD divergence side
    private static MacdDivergenceUtil.Side resolveMacdDivSide(Overrides ov) {
        String s = (ov == null) ? null : ov.macdDivSide;
        if (s == null) return MacdDivergenceUtil.Side.BOTTOM;
        s = s.trim();

        switch (s) {
            case "TOP":
            case "Top":
            case "top":
            case "頂":
                return MacdDivergenceUtil.Side.TOP;

            case "BOTTOM":
            case "Bottom":
            case "bottom":
            case "底":
            default:
                return MacdDivergenceUtil.Side.BOTTOM;
        }
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

    private static ScreenerResult evalMacdDivRecent(
            TickerInfo info,
            List<StockDayPrice> list,
            int lastBars,
            MacdDivergenceUtil.Side side
    ) {
        // 背離偵測會回看到前 35 根 + local high/low 的鄰近，資料太短意義不大
        if (list == null || list.size() < 60) return null;

        // 保險：按日期排序（你資料 yyyy-MM-dd，字串排序可行；若 1h 資料日期格式不同也至少保持穩定）
        list.sort(java.util.Comparator.comparing(StockDayPrice::getDate));

        //MacdCalculator.calculateMACD(list);

        MacdDivergenceUtil.Result div;
        if (lastBars <= 2) {
            // 對齊 Python：div_n<=2 用 past-only（可對最後一根/兩根判定）
            div = MacdDivergenceUtil.computePastOnly(list);
        } else {
            // div_n>2 用分型確認版（較穩）
            div = MacdDivergenceUtil.compute(list);
        }

        boolean ok = div.hasInLastBars(list.size(), lastBars, side);
        if (!ok) return null;

        int n = list.size();
        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                null, null, null,
                null, null, null
        );
    }

    // [CHANGE] add tailDays
    // ✅ 不再重算 MA：直接使用 fetcher 已填好的 prices.get(i).maXX
    private static ScreenerResult evalMaBand(
            TickerInfo info,
            List<StockDayPrice> list,
            int maWindow,
            double band,
            int tailDays
    ) {
        int days = Math.max(1, tailDays);
        if (list == null || list.isEmpty()) return null;

        // 保底：至少要能看最後 days 根，且 MA 欄位要有值
        // 不強制用 maWindow+days+10 這種長度推算（因為 MA 是否可用由欄位是否為 finite 決定）
        int n = list.size();
        if (n < days) return null;

        // 檢查最後 days 根是否都在 band 內（|close-ma|/ma <= band）
        for (int i = n - days; i < n; i++) {
            double close = list.get(i).getClose();
            double ma = getMaField(list.get(i), maWindow);

            if (!Double.isFinite(ma) || ma == 0.0) return null;

            double diffPct = Math.abs(close - ma) / ma;
            if (diffPct > band) return null;
        }

        double latestClose = list.get(n - 1).getClose();
        double latestMa = getMaField(list.get(n - 1), maWindow);
        double latestDiffPct = (Double.isFinite(latestMa) && latestMa != 0.0)
                ? Math.abs(latestClose - latestMa) / latestMa
                : Double.NaN;

        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), latestClose,
                null, null, null,
                latestMa, latestDiffPct * 100.0,
                null
        );
    }
    private static double getMaField(StockDayPrice p, int window) {
        if (p == null) return Double.NaN;
        switch (window) {
            case 35:  return p.ma35;
            case 60:  return p.ma60;
            case 120: return p.ma120;
            case 200: return p.ma200;
            case 240: return p.ma240;
            default:  return Double.NaN;
        }
    }
    private static ScreenerResult evalGoldenCross(TickerInfo info, List<StockDayPrice> list, int kPeriod) {
        if (list == null || list.isEmpty()) return null;

        // 保險：你的日期是 yyyy-MM-dd（或 1h 你目前仍是 yyyy-MM-dd），字串排序至少穩定
        list.sort(java.util.Comparator.comparing(StockDayPrice::getDate));

        int n = list.size();
        int last = -1, prev = -1;

        for (int i = n - 1; i >= 0; i--) {
            if (isFinite(list.get(i).kdK) && isFinite(list.get(i).kdD)) { last = i; break; }
        }
        for (int i = last - 1; i >= 0; i--) {
            if (isFinite(list.get(i).kdK) && isFinite(list.get(i).kdD)) { prev = i; break; }
        }
        if (last < 0 || prev < 0) return null;

        double kPrev = list.get(prev).kdK;
        double dPrev = list.get(prev).kdD;
        double kLast = list.get(last).kdK;
        double dLast = list.get(last).kdD;

        boolean isGc = kPrev <= dPrev && kLast > dLast;
        if (!isGc) return null;

        String crossDate = list.get(last).getDate();

        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                kLast, dLast, null,
                null, null,
                crossDate
        );
    }

    private static ScreenerResult evalLt20(
            TickerInfo info, List<StockDayPrice> list,
            int kPeriod, double thr, int persistDays, boolean wantLt, boolean needVolSpike
    ) {
        if (list == null || list.isEmpty()) return null;

        // 原本你用 kPeriod 做長度保底，這裡保留（也可更寬鬆）
        if (list.size() < Math.max(60, kPeriod + 40 + 10)) return null;

        boolean[] spike = needVolSpike ? volumeSpike(list, 20, 1.5) : null;

        // 用 fetcher 算好的 kdK：找出有效 K index（等價於 dropna 後 tail）
        List<Integer> validIdx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (isFinite(list.get(i).kdK)) validIdx.add(i);
        }
        if (validIdx.size() < persistDays) return null;

        int startPos = validIdx.size() - persistDays;

        boolean anySpike = false;
        double lastK = list.get(validIdx.get(validIdx.size() - 1)).kdK;

        for (int p = startPos; p < validIdx.size(); p++) {
            int i = validIdx.get(p);
            double k = list.get(i).kdK;

            boolean ok = wantLt ? (k < thr) : (k > thr);
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
        if (list == null || list.isEmpty()) return null;
        if (list.size() < Math.max(60, kPeriod + 40 + 10)) return null;

        boolean[] spike = needVolSpike ? volumeSpike(list, 20, 1.5) : null;

        // dropna(k) 後，從尾端數連續 > thr
        List<Integer> validIdx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (isFinite(list.get(i).kdK)) validIdx.add(i);
        }
        if (validIdx.isEmpty()) return null;

        int run = 0;
        for (int p = validIdx.size() - 1; p >= 0; p--) {
            int idx = validIdx.get(p);
            double k = list.get(idx).kdK;
            if (k > thr) run++;
            else break;
        }

        if (run < runMin || run > runMax) return null;

        if (needVolSpike && spike != null) {
            boolean anySpike = false;
            // 檢查最後 run 根「有效K對應的 bar」是否有量放大
            for (int p = validIdx.size() - run; p < validIdx.size(); p++) {
                int idx = validIdx.get(p);
                if (spike[idx]) { anySpike = true; break; }
            }
            if (!anySpike) return null;
        }

        double lastK = list.get(validIdx.get(validIdx.size() - 1)).kdK;

        int n = list.size();
        return new ScreenerResult(
                info.ticker, info.name, info.industry,
                avgCloseLastN(list, 60), list.get(n - 1).getClose(),
                lastK, null, run,
                null, null, null
        );
    }
}