package com.example.stockfetcher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * OhlcCleaners (合併版 + copy indicators)
 *
 * 功能對齊 Python _clean_ohlc_df(df, interval)：
 * 1) 空 -> 空
 * 2) 移除 Close 非有限值(NaN/Inf) 或 date 空值
 * 3) 依日期時間排序（舊->新）
 * 4) interval != "1h"：
 *    - date normalize 成 yyyy-MM-dd（等同 pandas normalize 後只保留日期）
 *    - 同日重複資料只保留最後一筆 (keep="last")
 *
 * 額外功能（只在 interval=="1mo" 或 "1wk" 啟用）：
 * 5) 丟掉未完成的最後一根（月/週進行中），並兼容：
 *    - 月K：用「下個月 1 號」標記本月K（尤其是當月未收完）
 *    - 週K：用「下週週一」標記本週K（尤其是本週未收完）
 *
 * 重要：因為 StockDayPrice.date 是 final，本清理會建立新的 StockDayPrice，
 * 但會「複製指標欄位」(maXX/macd/kd/rsi/dmi...)，避免清理後 MA 線消失。
 *
 * 建議：最穩的流程仍然是「先 clean 再重算指標」；但若你真的需要保留既有指標，
 * 這版會幫你拷貝過去。
 */
public final class OhlcCleaners {

    private static final DateTimeFormatter FMT_YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_YMD_HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_YMD_HMS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 台股用台北時區做「本週/本月」判斷較合理
    private static final ZoneId ZONE_TW = ZoneId.of("Asia/Taipei");

    private OhlcCleaners() {}

    /**
     * 清理 OHLC：
     * - 空 -> 空
     * - 移除 Close 非有限值(NaN/Inf) 或 date 空值
     * - 依日期時間排序（舊->新）
     * - interval != "1h"：date normalize 成 yyyy-MM-dd + 同日去重(保留最後一筆)
     * - interval == "1mo"/"1wk"：額外移除未完成的最後一根（月/週進行中）
     */
    public static List<StockDayPrice> cleanOhlc(List<StockDayPrice> src, String interval) {
        return cleanOhlc(src, interval, false);
    }
    public static List<StockDayPrice> cleanOhlc(List<StockDayPrice> src, String interval, boolean keepIncompleteLast) {
        if (src == null || src.isEmpty()) return Collections.emptyList();

        final String itv = normInterval(interval);

        ArrayList<StockDayPrice> a = new ArrayList<>(src.size());
        for (StockDayPrice p : src) {
            if (p == null) continue;
            String ds = safeTrim(p.getDate());
            if (ds.isEmpty()) continue;
            double close = p.getClose();
            if (!Double.isFinite(close)) continue;
            a.add(p);
        }
        if (a.isEmpty()) return Collections.emptyList();

        a.sort(Comparator.comparingLong(p -> parseEpochMillisSafe(p.getDate())));

        List<StockDayPrice> out;
        if (!"1h".equals(itv)) {
            LinkedHashMap<String, StockDayPrice> map = new LinkedHashMap<>();
            for (StockDayPrice p : a) {
                String ymd = normalizeToYmd(p.getDate());
                map.put(ymd, copyWithDateAndIndicators(p, ymd));
            }
            out = new ArrayList<>(map.values());
        } else {
            out = a;
        }

        // ✅ 只有在 keepIncompleteLast=false 時才 drop
        if (!keepIncompleteLast && ("1mo".equals(itv) || "1wk".equals(itv))) {
            out = dropIncompleteLastBarIfNeeded(out, itv);
        }

        return (out == null) ? Collections.emptyList() : out;
    }
    // -------------------------
    // helpers
    // -------------------------

    private static String normInterval(String interval) {
        return safeTrim(interval).toLowerCase(Locale.ROOT);
    }

    private static String safeTrim(String s) {
        return (s == null) ? "" : s.trim();
    }

    /** 把 "yyyy-MM-dd ..." 正規化成 "yyyy-MM-dd"（對齊 pandas normalize 後只保留日期） */
    private static String normalizeToYmd(String dateStr) {
        String s = safeTrim(dateStr);
        if (s.length() >= 10) return s.substring(0, 10);
        return s;
    }

    /**
     * 建立新物件（date 正規化），並把指標欄位一併複製過去，避免 clean 後 MA/MACD/KD 消失。
     */
    private static StockDayPrice copyWithDateAndIndicators(StockDayPrice p, String ymd) {
        StockDayPrice q = new StockDayPrice(
                ymd,
                p.getOpen(),
                p.getHigh(),
                p.getLow(),
                p.getClose(),
                p.getVolume()
        );

        // ----- indicators / derived fields -----
        q.rsi = p.rsi;

        q.dmiPDI = p.dmiPDI;
        q.dmiMDI = p.dmiMDI;
        q.dmiADX = p.dmiADX;

        q.ma35 = p.ma35;
        q.ma60 = p.ma60;
        q.ma120 = p.ma120;
        q.ma200 = p.ma200;
        q.ma240 = p.ma240;

        q.macdDIF = p.macdDIF;
        q.macdDEA = p.macdDEA;
        q.macdHistogram = p.macdHistogram;

        q.kdK = p.kdK;
        q.kdD = p.kdD;

        return q;
    }

    /**
     * 僅在 interval=1mo/1wk 時啟用：
     * - 1mo: 最後一根屬於本月(進行中) -> drop
     * - 1wk: 最後一根屬於本週(進行中) -> drop
     *
     * 兼容常見資料標記方式：
     * - 月K：用「下個月 1 號」標記本月K（尤其是當月未收完）
     * - 週K：用「下週週一」標記本週K（尤其是本週未收完）
     *
     * 注意：假設 list 已是舊->新且 date 已 normalize(yyyy-MM-dd)。
     */
    private static List<StockDayPrice> dropIncompleteLastBarIfNeeded(List<StockDayPrice> list, String itv) {
        if (list == null || list.size() < 2) return list;

        StockDayPrice lastBar = list.get(list.size() - 1);
        LocalDate lastDate = parseYmdSafe(lastBar.getDate());
        if (lastDate == null) return list;

        LocalDate now = LocalDate.now(ZONE_TW);

        boolean drop = false;

        if ("1mo".equals(itv)) {
            YearMonth nowYm = YearMonth.from(now);

            YearMonth ym0 = YearMonth.from(lastDate);
            YearMonth ym1 = YearMonth.from(lastDate.minusDays(1)); // 兼容「下月1號代表本月」

            // 若 lastDate 標到下一個月，但 lastDate-1day 落在本月，則以 lastDate-1day 的月份為準
            YearMonth barYm = (ym0.isAfter(nowYm) && ym1.equals(nowYm)) ? ym1 : ym0;

            drop = barYm.equals(nowYm);

        } else if ("1wk".equals(itv)) {
            WeekFields wf = WeekFields.ISO; // 週一為一週開始

            int nowKey = isoWeekKey(now, wf);
            int key0 = isoWeekKey(lastDate, wf);
            int key1 = isoWeekKey(lastDate.minusDays(1), wf); // 兼容「下週週一代表本週」

            // 若 lastDate 標到下一週，但 lastDate-1day 仍是本週，則以 lastDate-1day 的週為準
            int barKey = (key0 > nowKey && key1 == nowKey) ? key1 : key0;

            drop = (barKey == nowKey);
        }

        if (!drop) return list;
        return new ArrayList<>(list.subList(0, list.size() - 1));
    }

    private static int isoWeekKey(LocalDate d, WeekFields wf) {
        int wy = d.get(wf.weekBasedYear());
        int w = d.get(wf.weekOfWeekBasedYear());
        return wy * 100 + w; // 例如 202552
    }

    private static LocalDate parseYmdSafe(String dateStr) {
        if (dateStr == null) return null;
        String s = dateStr.trim();
        if (s.isEmpty()) return null;
        if (s.length() >= 10) s = s.substring(0, 10);
        try {
            return LocalDate.parse(s, FMT_YMD);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析為可排序時間戳；失敗就 fallback。
     * 你目前多數 date 是 yyyy-MM-dd（或帶時間），這裡做相容處理。
     */
    private static long parseEpochMillisSafe(String dateStr) {
        String s = safeTrim(dateStr);
        if (s.isEmpty()) return Long.MIN_VALUE;

        // yyyy-MM-dd HH:mm:ss
        try {
            LocalDateTime dt = LocalDateTime.parse(s, FMT_YMD_HMS);
            return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {}

        // yyyy-MM-dd HH:mm
        try {
            LocalDateTime dt = LocalDateTime.parse(s, FMT_YMD_HM);
            return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {}

        // yyyy-MM-dd
        try {
            LocalDate d = LocalDate.parse(normalizeToYmd(s), FMT_YMD);
            return d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {}

        // fallback（避免 crash）
        return s.hashCode();
    }
}