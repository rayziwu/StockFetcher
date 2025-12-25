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
    }
}