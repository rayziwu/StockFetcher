package com.example.stockfetcher;

/**
 * 股票每日價格和技術指標的數據模型。
 * 從 MainActivity 中獨立出來，供所有類別存取。
 */
public class StockDayPrice {

    // 原始 K 線數據欄位
    private final String date;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final double volume;
    // RSI 指標 (通常取 14 日)
    public double rsi = Double.NaN;

    // DMI 指標
    public double dmiPDI = Double.NaN; // +DI
    public double dmiMDI = Double.NaN; // -DI
    public double dmiADX = Double.NaN; // ADX
    // 移動平均線欄位
    public double ma35 = Double.NaN;
    public double ma60 = Double.NaN;
    public double ma120 = Double.NaN;
    public double ma200 = Double.NaN;
    public double ma240 = Double.NaN;

    // MACD 技術指標欄位
    public double macdDIF = Double.NaN;       // 快線 DIF (Difference)
    public double macdDEA = Double.NaN;       // 慢線 DEA (Dematerialized Exchange Account)
    public double macdHistogram = Double.NaN; // 柱狀圖 (DIF - DEA)

    // KD 技術指標欄位
    public double kdK = Double.NaN;           // K 線 (快線)
    public double kdD = Double.NaN;           // D 線 (慢線)


    // 構造函數 (Constructor)
    public StockDayPrice(String date, double open, double high, double low, double close, double volume) {
        this.date = date;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    // Getter methods
    public String getDate() {
        return date;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public double getVolume() {
        return volume;
    }
}