package com.example.stockfetcher;

import androidx.annotation.Nullable;
public class ScreenerResult {
    public final String ticker;
    public final String name;
    public final String industry;

    public final double avgClose60;
    public final double latestClose;

    @Nullable public final Double lastK;
    @Nullable public final Double lastD;
    @Nullable public final Integer runDays;
    @Nullable public final Double ma60;
    @Nullable public final Double ma60DiffPct;
    @Nullable public final String crossDate; // yyyy-MM-dd

    // ✅ [ADD] 1234 fields
    @Nullable public final String  m1234LimitUpRule;   // "0"/"1"/">1"
    @Nullable public final Integer m1234LimitUp20d;
    @Nullable public final Integer m1234GapUp20d;
    @Nullable public final Integer m1234VolRunMax20d;
    @Nullable public final Integer m1234UpRunMax20d;

    public ScreenerResult(
            String ticker, String name, String industry,
            double avgClose60, double latestClose,
            @Nullable Double lastK, @Nullable Double lastD,
            @Nullable Integer runDays,
            @Nullable Double ma60, @Nullable Double ma60DiffPct,
            @Nullable String crossDate
    ) {
        this.ticker = ticker;
        this.name = name;
        this.industry = industry;
        this.avgClose60 = avgClose60;
        this.latestClose = latestClose;
        this.lastK = lastK;
        this.lastD = lastD;
        this.runDays = runDays;
        this.ma60 = ma60;
        this.ma60DiffPct = ma60DiffPct;
        this.crossDate = crossDate;

        // ✅ default null for 1234
        this.m1234LimitUpRule = null;
        this.m1234LimitUp20d = null;
        this.m1234GapUp20d = null;
        this.m1234VolRunMax20d = null;
        this.m1234UpRunMax20d = null;
    }

    // ✅ 工廠方法：1234 專用
    public static ScreenerResult for1234(
            String ticker, String name, String industry,
            double avgClose60, double latestClose,
            String rule, int limitUp20d, int gapUp20d, int volRunMax20d, int upRunMax20d
    ) {
        ScreenerResult r = new ScreenerResult(
                ticker, name, industry,
                avgClose60, latestClose,
                null, null, null,
                null, null, null
        );
        // 但欄位是 final，不能這樣賦值 => 所以 for1234 需要改成「走另一個 private constructor」
        // 我下面給你正確版本（final 欄位要在 constructor 內設定）
        return new ScreenerResult(
                ticker, name, industry,
                avgClose60, latestClose,
                rule, limitUp20d, gapUp20d, volRunMax20d, upRunMax20d
        );
    }

    // ✅ 1234 專用 constructor（不影響舊呼叫）
    private ScreenerResult(
            String ticker, String name, String industry,
            double avgClose60, double latestClose,
            String rule, int limitUp20d, int gapUp20d, int volRunMax20d, int upRunMax20d
    ) {
        this.ticker = ticker;
        this.name = name;
        this.industry = industry;
        this.avgClose60 = avgClose60;
        this.latestClose = latestClose;

        this.lastK = null;
        this.lastD = null;
        this.runDays = null;
        this.ma60 = null;
        this.ma60DiffPct = null;
        this.crossDate = null;

        this.m1234LimitUpRule = rule;
        this.m1234LimitUp20d = limitUp20d;
        this.m1234GapUp20d = gapUp20d;
        this.m1234VolRunMax20d = volRunMax20d;
        this.m1234UpRunMax20d = upRunMax20d;
    }
}