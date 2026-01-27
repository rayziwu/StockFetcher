package com.example.stockfetcher;

public enum ScreenerMode {
    LT20,      // KD40<20 last 40 + vol spike
    GT45,      // consecutive KD40>45 run 21~29 + vol spike
    MA60_3PCT, // close within ±3% of MA60 for last 40
    MACD_DIV_RECENT,   // ✅ 新增第4
    KD_GC_RECENT,      // ✅ 新第5項：最近N根XK內有KD黃金交叉
    MODE_1234,   // ✅ 新增：1234篩選
    CSV_LIST   // load list from CSV
}
