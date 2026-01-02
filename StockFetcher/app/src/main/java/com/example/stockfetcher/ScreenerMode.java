package com.example.stockfetcher;

public enum ScreenerMode {
    LT20,      // KD40<20 last 40 + vol spike
    GT45,      // consecutive KD40>45 run 21~29 + vol spike
    MA60_3PCT, // close within ±3% of MA60 for last 40
    MACD_DIV_RECENT,   // ✅ 新增第4
    KD9_MO_GC, // monthly KD9 golden cross
    KD9_WK_GC, // weekly KD9 golden cross
    CSV_LIST   // load list from CSV
}
