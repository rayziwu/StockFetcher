package com.example.stockfetcher;

public class TickerInfo {
    public final String ticker;
    public final String name;
    public final String industry;

    public TickerInfo(String ticker, String name, String industry) {
        this.ticker = ticker;
        this.name = name;
        this.industry = industry;
    }
}