// *** 註解版本號：3.10版
package com.example.stockfetcher;

import android.content.pm.PackageManager;
import android.view.GestureDetector;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.InputFilter;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.github.mikephil.charting.charts.CombinedChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.CandleData;
import com.github.mikephil.charting.data.CandleDataSet;
import com.github.mikephil.charting.data.CandleEntry;
import com.github.mikephil.charting.data.CombinedData;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.interfaces.datasets.ICandleDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.interfaces.datasets.IScatterDataSet;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity implements OnChartGestureListener {

    // MainActivity fields 新增
    private final java.util.HashSet<String> selectedIndustries = new java.util.HashSet<>();
    private static final String PREF_SCREEN = "screen_prefs";
    private int pMacdDivBars = 2;
    private String pMacdDivTf = "時";
    private String pMacdDivSide = "底";

    private static final String PREF_INDUSTRIES = "selected_industries";
    private Button screenerButton;
    // 篩選完成後是否已自動存檔（避免重複寫檔）
    private boolean screenerAutoExported = false;
    // 只要成功載入一次，就不再重複掃描股票代碼.csv
    private boolean tickerMetaLoadedOnce = false;
    private boolean screenerReceiverRegistered = false;
    private volatile boolean isScreening = false;
    private java.util.concurrent.ExecutorService screenerExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> screenerFuture;
    private final java.util.concurrent.atomic.AtomicBoolean screenerCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final List<ScreenerResult> screenerResults = new ArrayList<>();
    private int activeLtThr = 20, activeLtDays = 20;
    private int activeGtThr = 45, activeGtMin = 20, activeGtMax = 30;
    private int activeMaBandPct = 3, activeMaDays = 20;
    private int activeMacdDivBars = 2;
    private String activeMacdDivTf = "時";
    private String activeMacdDivSide = "底";

    private int screenerIndex = 0;
    private boolean screenerSessionClosed = true;
    private boolean allowSaveOnSwipeUp = true;
    private ScreenerMode screenerMode = ScreenerMode.LT20;
    private GestureDetector navGestureDetector;
    // 匯出 CSV 用
    private String pendingExportCsv = null;
    private enum IndicatorMode { MACD, RSI, DMI }

    private static final String TAG = "StockFetcherApp";

    // 預設的圖表邊界偏移量，用於確保 mainChart 和 vpChart 的內容繪圖區域垂直對齊
    private static final float CHART_OFFSET_TOP = 20f;
    private static final float CHART_OFFSET_BOTTOM = 20f;
    private static final float CHART_OFFSET_LEFT = 0f;
    private static final float CHART_OFFSET_RIGHT = 0f;

    private static final String GAP_LABEL_UP = "GAP_LABEL_UP";
    private static final String GAP_LABEL_DOWN = "GAP_LABEL_DOWN";
    private static final float GAP_LABEL_SHOW_SCALE = 1.02f;

    private YahooFinanceFetcher yahooFinanceFetcher;

    private Button intervalSwitchButton;
    private EditText stockIdEditText, startDateEditText, comparisonStockIdEditText;

    private boolean isSwitchingInterval = false;
    private boolean suppressNextStockIdFocusFetch = false;

    private String currentStockId = "2330.TW";
    private final String[] VIEW_MODES = {"K", "VP", "ALL"};
    private int currentViewModeIndex = 2; // 預設 ALL
    private List<StockDayPrice> lastDisplayedListForMain = null;
    private GestureDetector mainTapDetector;

    private final String[] INTERVALS = {"1d", "1wk", "1mo", "1h"};
    private int currentIntervalIndex = 0;
    private String currentInterval = INTERVALS[currentIntervalIndex];
    private String[] displayText;

    private IndicatorMode indicatorMode = IndicatorMode.MACD;
    private int currentKDNPeriod = 9;
    // [ADD] 放在 MainActivity 成員變數區
    private int pLtThr = 20;
    private int pLtDays = 20;

    private int pGtThr = 45;
    private int pGtMin = 20;
    private int pGtMax = 30;

    private int pMaBandPct = 3;
    private int pMaDays = 20;

    private String pMaTf = "日";   // Row3 預設週期
    private int pMaPick = 1;       // 0=MA1, 1=MA2（預設 MA2）

    private String activeMaTf = "日";
    private int activeMaPick = 1;

    private TextView indicatorModeLabel;
    private List<StockDayPrice> lastDisplayedListForIndicator = null;
    enum KkdViewMode { KD, VOL, COMP }
    private KkdViewMode kkdViewMode = KkdViewMode.KD;
    private boolean pendingRestoreCandleWidth = false;
    private float savedMainScaleX = 1f;
    private float savedMainXRange = -1f;
    // k_kdChart 切換需要用到的「目前顯示資料」
    @androidx.annotation.Nullable
    private List<StockDayPrice> lastDisplayedListForKkd;
    private GestureDetector kkdSwipeDetector;

    @androidx.annotation.Nullable
    private String pendingTickerCsvPathForScreening = null;

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
    // 三個圖表區域
    private CombinedChart mainChart, k_kdChart, indicatorChart;
    private VolumeProfileView vpView;

    private double bucketSizeGlobal = 0.0;
    private List<StockDayPrice> fullPriceList = new ArrayList<>();
    private List<StockDayPrice> comparisonPriceList = new ArrayList<>();
    private String displayedComparisonSymbol = "^TWII";

    // 分價量表
    private final Map<Integer, Double> volumeProfileData = new HashMap<>();
    private double minPriceGlobal = 0.0;
    private double priceRangeGlobal = 0.0;
    private final int NUM_PRICE_BUCKETS = 76;
    private int pKdGcBars = 2;
    private String pKdGcTf = "時";

    private int activeKdGcBars = 2;
    private String activeKdGcTf = "時";

    private CoordinateMarkerView coordinateMarker;
    // 從股票清單 cache 讀到的 meta（ticker -> info）
    private final Map<String, TickerInfo> tickerMetaMap = new HashMap<>();
    private static final String TICKERS_CACHE_FILE = "股票代碼.csv";
    private static boolean pendingInitAfterLocaleChange = false;
    private boolean notifPermInFlight = false;

    private void setScreenerEnabled(boolean enabled) {
        screenerButton.setEnabled(enabled);
    }
    private androidx.appcompat.widget.AppCompatTextView tvTopOutsideMainChart;

    // 目前輪播所使用的檔案（CSV picker 載入、或 screener 匯出後確定的檔）
    @androidx.annotation.Nullable
    private java.io.File activeCarouselFile;
    private static final String FAVORITES_FILE = "最愛.csv";
    private java.io.File activeFavoritesFile; // 目前正在使用的最愛清單檔

    private androidx.appcompat.widget.AppCompatTextView btnFavorite;

    private androidx.appcompat.widget.AppCompatTextView tvCarouselPos;
    private boolean carouselActive = false;

    // key: Ticker（例 1101.TW），value: (ticker,name,industry)
    private final java.util.Map<String, FavoriteInfo> favoriteMap = new java.util.LinkedHashMap<>();
    private KkdVolumeMarkerView kkdVolumeMarker;
    private static class FavoriteInfo {
        final String ticker;
        final String name;
        final String industry;

        FavoriteInfo(String ticker, String name, String industry) {
            this.ticker = ticker;
            this.name = name;
            this.industry = industry;
        }
    }

    private String getCurrentMainTickerKey() {
        if (currentStockId == null) return "";
        return currentStockId.trim().toUpperCase(java.util.Locale.US);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (applyLanguagePolicyBySystemLocaleAndReturnIfChanged()) {
            return;
        }

        setContentView(R.layout.activity_main);
        bindViews();
        initUi();
        loadSelectedIndustriesFromPrefs();
        initCharts();

        // ✅ 第一次畫圖前先讀最愛.csv（沒有檔案就略過）
        initActiveFavoritesFileIfNeeded();
        loadFavoritesIfExists();
        setupFavoriteButton();
        updateTopOutsideMainChartUi();

        boolean shouldInitialFetch = (savedInstanceState == null) || pendingInitAfterLocaleChange;
        pendingInitAfterLocaleChange = false;

        if (shouldInitialFetch) {
            ensureTickerMetaThen(() -> fetchStockDataWithFallback(
                    currentStockId,
                    currentInterval,
                    getStartTimeLimit(currentInterval, isSwitchingInterval)
            ));
        } else {
            // 非首次進入就照你原本要不要 preload
            preloadTickerMetaIfCsvExists();
        }

        // Android 13+：未允許通知前不讓使用者篩選
        if (Build.VERSION.SDK_INT >= 33) {
            boolean granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;

            setScreenerEnabled(granted);

            if (!granted && !notifPermInFlight) {
                notifPermInFlight = true;
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        } else {
            setScreenerEnabled(true);
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            notifPermInFlight = false;
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            setScreenerEnabled(granted);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!screenerReceiverRegistered) {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(ScreenerForegroundService.ACTION_PROGRESS);
            f.addAction(ScreenerForegroundService.ACTION_DONE);
            f.addAction(ScreenerForegroundService.ACTION_FAIL);

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenerReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenerReceiver, f);
            }
            screenerReceiverRegistered = true;
        }
    }
    @Override
    protected void onStop() {
        if (screenerReceiverRegistered) {
            try { unregisterReceiver(screenerReceiver); } catch (Exception ignored) {}
            screenerReceiverRegistered = false;
        }
        super.onStop();
    }
    @Override
    protected void onResume() {
        super.onResume();

        // 如果目前正在等通知權限回覆，避免這時候又切語言造成重建/流程混亂
        if (notifPermInFlight) return;

        if (applyLanguagePolicyBySystemLocaleAndReturnIfChanged()) {
            return;
        }
    }
    private void startCarouselFromFavoritesIfNeeded() {
        if (carouselActive) return;

        if (favoriteMap == null || favoriteMap.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_favorites_empty), Toast.LENGTH_SHORT).show();
            return;
        }

        // ✅ 把最愛檔案當作輪播檔案
        activeCarouselFile = activeFavoritesFile;

        // 用 favoriteMap 建立 screenerResults（含 name/industry，顯示輪播清單用）
        screenerResults.clear();
        for (FavoriteInfo fi : favoriteMap.values()) {
            if (fi == null) continue;
            String tk = (fi.ticker == null) ? "" : fi.ticker.trim().toUpperCase(java.util.Locale.US);
            if (tk.isEmpty()) continue;

            ScreenerResult r = new ScreenerResult(
                    tk,
                    fi.name == null ? "" : fi.name,
                    fi.industry == null ? "" : fi.industry,
                    Double.NaN, Double.NaN,
                    null, null, null, null, null, null
            );
            screenerResults.add(r);
        }

        if (screenerResults.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_favorites_empty), Toast.LENGTH_SHORT).show();
            return;
        }

        // 起始 index：若 currentStockId 在最愛清單內，就從它開始
        int found = -1;
        String cur = (currentStockId == null) ? "" : currentStockId.trim().toUpperCase(java.util.Locale.US);
        for (int i = 0; i < screenerResults.size(); i++) {
            if (screenerResults.get(i) != null
                    && screenerResults.get(i).ticker != null
                    && cur.equalsIgnoreCase(screenerResults.get(i).ticker.trim())) {
                found = i;
                break;
            }
        }
        screenerIndex = (found >= 0) ? found : 0;

        screenerSessionClosed = true;   // 這是最愛輪播，不是篩選 session
        allowSaveOnSwipeUp = false;

        carouselActive = true;
        updateTopOutsideMainChartUi();
    }
    private void initActiveFavoritesFileIfNeeded() {
        if (activeFavoritesFile != null) return;

        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) return;

        activeFavoritesFile = new java.io.File(dir, FAVORITES_FILE); // FAVORITES_FILE = "最愛.csv"
    }

    private void loadFavoritesIfExists() {
        initActiveFavoritesFileIfNeeded();
        if (activeFavoritesFile == null || !activeFavoritesFile.exists()) return;

        loadFavoritesFromFile(activeFavoritesFile);
    }
    private void loadFavoritesFromFile(java.io.File f) {
        favoriteMap.clear();

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8))) {

            String headerOrFirst = br.readLine();
            if (headerOrFirst == null) return;
            if (headerOrFirst.startsWith("\uFEFF")) headerOrFirst = headerOrFirst.substring(1);

            String[] h = splitCsvLine(headerOrFirst);
            int iTicker = -1, iName = -1, iInd = -1;
            boolean hasHeader = false;

            for (int i = 0; i < h.length; i++) {
                String col = h[i].trim().toLowerCase(java.util.Locale.US);
                if (col.equals("ticker")) { iTicker = i; hasHeader = true; }
                else if (col.equals("name")) { iName = i; hasHeader = true; }
                else if (col.equals("industry") || col.equals("category")) { iInd = i; hasHeader = true; }
            }

            // 如果第一行不是 header，就把第一行當資料處理
            if (!hasHeader) {
                processFavoriteCsvLine(headerOrFirst, 0, -1, -1);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                processFavoriteCsvLine(line, iTicker >= 0 ? iTicker : 0, iName, iInd);
            }

        } catch (Exception e) {
            android.util.Log.w(TAG, "loadFavoritesFromFile failed: " + e.getMessage(), e);
        }
    }

    private void processFavoriteCsvLine(String line, int iTicker, int iName, int iInd) {
        String[] cols = splitCsvLine(line);

        String tk = (iTicker >= 0 && iTicker < cols.length) ? cols[iTicker].trim()
                : (cols.length > 0 ? cols[0].trim() : "");
        if (tk.isEmpty() || tk.equalsIgnoreCase("ticker")) return;

        String ticker = tk.toUpperCase(java.util.Locale.US);

        String name = (iName >= 0 && iName < cols.length) ? cols[iName].trim() : "";
        String ind  = (iInd >= 0 && iInd < cols.length) ? cols[iInd].trim() : "";

        // 若檔案只有 ticker，嘗試用 tickerMetaMap 補 name/industry
        if ((name.isEmpty() || ind.isEmpty()) && tickerMetaMap != null) {
            TickerInfo info = tickerMetaMap.get(ticker);
            if (info == null) {
                String base = ticker.replace(".TW", "").replace(".TWO", "");
                info = tickerMetaMap.get(base);
            }
            if (info != null) {
                if (name.isEmpty()) name = info.name;
                if (ind.isEmpty())  ind  = info.industry; // 你已確認欄位叫 industry
            }
        }

        favoriteMap.put(ticker, new FavoriteInfo(ticker, name, ind));
    }
    private void loadSelectedIndustriesFromPrefs() {
        try {
            java.util.Set<String> s = getSharedPreferences(PREF_SCREEN, MODE_PRIVATE)
                    .getStringSet(PREF_INDUSTRIES, null);
            selectedIndustries.clear();
            if (s != null) selectedIndustries.addAll(s);
        } catch (Exception ignored) {}
    }

    private void saveSelectedIndustriesToPrefs() {
        getSharedPreferences(PREF_SCREEN, MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_INDUSTRIES, new java.util.HashSet<>(selectedIndustries))
                .apply();
    }
    private boolean preloadTickerMetaIfCsvExists() {
        // 已經載入過就直接視為 ready
        if (tickerMetaLoadedOnce) return true;

        java.io.File internal = new java.io.File(getFilesDir(), TICKERS_CACHE_FILE);

        // 兼容：如果你有改成存 externalFilesDir，也一起找（不影響原本 internal）
        java.io.File external = null;
        try {
            java.io.File extDir = getExternalFilesDir(null);
            if (extDir != null) external = new java.io.File(extDir, TICKERS_CACHE_FILE);
        } catch (Exception ignored) {
        }

        java.io.File f = internal.exists()
                ? internal
                : (external != null && external.exists() ? external : null);

        if (f == null) return false;

        try {
            loadTickerMetaFromCsv(f);   // 你原本的方法（void）維持不變
            tickerMetaLoadedOnce = true;
            return true;
        } catch (Exception e) {
            // 不要把 tickerMetaLoadedOnce 設成 true，讓之後還有機會重新載入/重爬
            android.util.Log.w("MainActivity",
                    "Failed to preload ticker meta from: " + f.getAbsolutePath(), e);
            return false;
        }
    }
    private void ensureTickerMetaThen(Runnable next) {
        boolean ready = preloadTickerMetaIfCsvExists(); // 先用本機 CSV 快取秒開

        if (ready) {
            if (next != null) next.run();
            return;
        }

        // 沒有代碼才真的去 scrape，並顯示進度 dialog
        showTickerScrapeDialogThen(() -> {
            // scrape 完通常會落地成 CSV/快取；再 preload 一次保證 repo 已載入
            preloadTickerMetaIfCsvExists();
            if (next != null) next.run();
        });
    }
    private String tryNormalizeAsTicker(String input) {
        if (input == null) return null;
        String s = input.trim().toUpperCase(java.util.Locale.US);

        // 1101.TW / 1101.TWO
        if (s.matches("^\\d{4,6}\\.(TW|TWO)$")) return s;

        // 1101（維持你原本行為：不強制補 .TW）
        if (s.matches("^\\d{4,6}$")) return s;

        return null;
    }

    /**
     * 允許輸入「代碼或名稱」：
     * - 像代碼 => 直接回代碼
     * - 否則當名稱 => 查 tickerNameToTicker
     * - 查不到 => 回原字串（維持原流程）
     */
    private String resolveForMainFetch(String rawInput) {
        if (rawInput == null) return "";

        String input = rawInput.trim();
        if (input.isEmpty()) return "";

        // 1) 像代碼：直接走原本流程
        String asTicker = tryNormalizeAsTicker(input);
        if (asTicker != null) return asTicker;

        // 2) 像名稱：先確保至少 preload 過本機 CSV（不會跳 dialog）
        preloadTickerMetaIfCsvExists();

        String mapped = tickerNameToTicker.get(normalizeTickerName(input));

        // 3) 查不到：維持原本流程
        return (mapped != null && !mapped.isEmpty()) ? mapped : input;
    }
    // Name -> Ticker（例如：台泥 -> 1101.TW）
    private final java.util.Map<String, String> tickerNameToTicker = new java.util.HashMap<>();

    private String normalizeTickerName(String s) {
        if (s == null) return "";
        return s.replace('\u3000', ' ').trim().replaceAll("\\s+", " ");
    }
    private void loadTickerMetaFromCsv(java.io.File file) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {

            String header = br.readLine();
            if (header == null) return;
            if (header.startsWith("\uFEFF")) header = header.substring(1); // BOM

            String[] h = splitCsvLine(header);

            int iTicker = -1, iName = -1, iInd = -1;
            for (int i = 0; i < h.length; i++) {
                String col = h[i].trim().toLowerCase(java.util.Locale.US);
                if (col.equals("ticker")) iTicker = i;
                else if (col.equals("name")) iName = i;
                else if (col.equals("industry") || col.equals("category")) iInd = i;
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] cols = splitCsvLine(line);

                String tk = (iTicker >= 0 && iTicker < cols.length) ? cols[iTicker].trim()
                        : (cols.length > 0 ? cols[0].trim() : "");

                if (tk.isEmpty() || tk.equalsIgnoreCase("ticker")) continue;

                String tku = tk.trim().toUpperCase(java.util.Locale.US);

                String name = (iName >= 0 && iName < cols.length) ? cols[iName].trim() : "";
                String ind  = (iInd >= 0 && iInd < cols.length) ? cols[iInd].trim() : "";

                // 你原本的：存 full ticker
                tickerMetaMap.put(tku, new TickerInfo(tku, name, ind));

                // 新增：建立 Name -> Ticker（給 EditText 輸入名稱用）
                if (!name.isEmpty()) {
                    String key = normalizeTickerName(name);
                    String existing = tickerNameToTicker.get(key);

                    if (existing == null) {
                        tickerNameToTicker.put(key, tku);
                    } else {
                        // 若同名同時存在 .TW / .TWO，偏好 .TW（可依你需求調整）
                        boolean existingIsTWO = existing.endsWith(".TWO");
                        boolean newIsTW = tku.endsWith(".TW");
                        if (existingIsTWO && newIsTW) {
                            tickerNameToTicker.put(key, tku);
                        }
                    }
                }

                // 你原本的：兼容 base ticker（2330.TW / 2330.TWO 也存 2330）
                if (tku.matches("^\\d{4}\\.(TW|TWO)$")) {
                    String base = tku.substring(0, 4);
                    if (!tickerMetaMap.containsKey(base)) {
                        tickerMetaMap.put(base, new TickerInfo(base, name, ind));
                    }
                }
            }

        } catch (Exception e) {
            android.util.Log.w(TAG, "loadTickerMetaFromCsv failed: " + e.getMessage(), e);
        }
    }

    private final java.util.concurrent.ExecutorService tickerExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void showTickerScrapeDialogThen(Runnable onOkContinue) {
        // Scroll + TextView
        final android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTextSize(14f);
        int dp12 = Math.round(12f * getResources().getDisplayMetrics().density);
        tv.setPadding(dp12, dp12, dp12, dp12);

        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);

        final StringBuilder sb = new StringBuilder();

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ticker_scrape_title)
                .setView(sv)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null) // show 後再接管
                .create();

        dlg.show();

        final android.widget.Button okBtn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        okBtn.setEnabled(false);

        java.util.function.Consumer<String> appendLine = (line) -> runOnUiThread(() -> {
            sb.append(line).append('\n');
            tv.setText(sb.toString());
            sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
        });

        tickerExec.execute(() -> {
            // 真的爬取
            TwTickerRepository.loadOrScrape(getApplicationContext(), appendLine::accept);

            runOnUiThread(() -> {
                okBtn.setEnabled(true);
                okBtn.setOnClickListener(v -> {
                    dlg.dismiss();
                    if (onOkContinue != null) onOkContinue.run();
                });
            });
        });
    }

    private void setupFavoriteButton() {
        if (btnFavorite == null) {
            android.util.Log.e(TAG, "setupFavoriteButton: btnFavorite is null");
            return;
        }

        // 保險：確保可點
        btnFavorite.setClickable(true);
        btnFavorite.setFocusable(true);
        btnFavorite.setTextColor(android.graphics.Color.RED);

        // 如果你的 btnFavorite 仍是 overlay（會被 vpView 蓋住），保留這兩行；
        // 若 btnFavorite 已移到 top bar（不在 chart overlay），留著也不影響
        btnFavorite.bringToFront();
        androidx.core.view.ViewCompat.setElevation(btnFavorite, 100f);

        // ✅ 短按：維持原本加入/移除（寫入 activeFavoritesFile）
        btnFavorite.setOnClickListener(v -> {
            String t = getCurrentMainTickerKey();
            android.util.Log.d(TAG, "♥/♡ clicked, currentStockId=" + currentStockId + ", key=" + t);

            toggleFavoriteForCurrentMain();
            updateFavoriteButtonState();
            updateTopOutsideMainChartUi();
        });

        // ✅ 長按：跳出「最愛檔案清單 + '+'」並依選擇加入後存檔、切換 activeFavoritesFile
        btnFavorite.setOnLongClickListener(v -> {
            showFavoriteFilePickerThenAddCurrent();
            return true; // ⚠️ 一定要 true，避免長按後又觸發短按 click
        });

        updateFavoriteButtonState();
        updateTopOutsideMainChartUi();
    }
    private void updateFavoriteButtonState() {
        if (btnFavorite == null) return;

        String key = getCurrentMainTickerKey();
        boolean isFav = !key.isEmpty() && favoriteMap.containsKey(key);

        //android.util.Log.d(TAG, "updateFavoriteButtonState key=" + key + " isFav=" + isFav);

        btnFavorite.setText(isFav ? "♥" : "♡");
        btnFavorite.setTextColor(android.graphics.Color.RED);
    }
    private void toggleFavoriteForCurrentMain() {
        String ticker = getCurrentMainTickerKey();
        if (ticker.isEmpty()) return;

        if (favoriteMap.containsKey(ticker)) {
            // 已是最愛：移除
            favoriteMap.remove(ticker);
            saveFavoritesToFile();
            return;
        }

        // 不是最愛：加入
        String name = "";
        String industry = "";

        // 你的 TickerInfo 欄位名稱以你原本為準：new TickerInfo(tku, name, ind)
        TickerInfo info = tickerMetaMap.get(ticker);

        // 保守相容：如果 currentStockId 是 1101 而 meta 是 1101.TW（或反過來）
        if (info == null) {
            String base = ticker.replace(".TW", "").replace(".TWO", "");
            info = tickerMetaMap.get(base);
        }

        if (info != null) {
            name = info.name;
            industry = info.industry;
        }

        favoriteMap.put(ticker, new FavoriteInfo(ticker, name, industry));
        saveFavoritesToFile();
    }
    private void saveFavoritesToFile() {
        initActiveFavoritesFileIfNeeded();
        if (activeFavoritesFile == null) {
            android.util.Log.w(TAG, "saveFavoritesToFile: activeFavoritesFile is null");
            return;
        }

        writeFavoritesCsvToFile(activeFavoritesFile);
    }

    private void writeFavoritesCsvToFile(java.io.File f) {
        android.util.Log.d(TAG, "Saving favorites to: " + f.getAbsolutePath()
                + " count=" + favoriteMap.size());

        try (java.io.BufferedWriter bw = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f, false),
                        java.nio.charset.StandardCharsets.UTF_8))) {

            bw.write("Ticker,Name,Industry\n");
            for (FavoriteInfo it : favoriteMap.values()) {
                bw.write(csvEscape(it.ticker));
                bw.write(",");
                bw.write(csvEscape(it.name));
                bw.write(",");
                bw.write(csvEscape(it.industry));
                bw.write("\n");
            }
            bw.flush();

        } catch (Exception e) {
            android.util.Log.e(TAG, "writeFavoritesCsvToFile failed: " + f.getAbsolutePath(), e);
            return;
        }

        android.util.Log.d(TAG, "Saved OK: exists=" + f.exists() + " size=" + f.length());
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String t = s.replace("\"", "\"\"");
        return needQuote ? "\"" + t + "\"" : t;
    }
    private void bindViews() {
        intervalSwitchButton = findViewById(R.id.intervalSwitchButton);
        indicatorModeLabel = findViewById(R.id.indicatorModeLabel);
// indicatorModeButton 相關 findViewById / setOnClickListener 全部刪掉

        startDateEditText = findViewById(R.id.startDateEditText);
        comparisonStockIdEditText = findViewById(R.id.comparisonStockIdEditText);
        stockIdEditText = findViewById(R.id.stockIdEditText);
        btnFavorite = findViewById(R.id.btnFavorite);

        mainChart = findViewById(R.id.mainChart);
        k_kdChart = findViewById(R.id.k_kdChart);
        indicatorChart = findViewById(R.id.indicatorChart);
        vpView = findViewById(R.id.vpView);
        screenerButton = findViewById(R.id.screenerButton);
        btnFavorite = findViewById(R.id.btnFavorite);
        tvTopOutsideMainChart = findViewById(R.id.tvTopOutsideMainChart);
        if (comparisonStockIdEditText != null) comparisonStockIdEditText.setVisibility(View.GONE);
        tvTopOutsideMainChart.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tvTopOutsideMainChart.setHighlightColor(android.graphics.Color.TRANSPARENT); // 點擊不出現底色
    }

    private void updateTopOutsideMainChartUi() {
        if (tvTopOutsideMainChart == null) return;

        String favName = (activeFavoritesFile != null)
                ? stripCsvExt(activeFavoritesFile.getName())
                : stripCsvExt(FAVORITES_FILE);

        int favCount = (favoriteMap != null) ? favoriteMap.size() : 0;

        boolean hasCarousel = carouselActive && screenerResults != null && !screenerResults.isEmpty();

        String prefix = getString(R.string.label_carousel_prefix); // 輪播: / Carousel:
        String carouselName = (activeCarouselFile != null) ? stripCsvExt(activeCarouselFile.getName()) : "";

        String pos = "";
        if (hasCarousel) {
            int total = screenerResults.size();
            int idx1 = Math.max(0, Math.min(screenerIndex, total - 1)) + 1;
            pos = idx1 + "/" + total;
        }

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();

        // ===== 最愛區塊：最愛檔名(數量) 可點 =====
        int favStart = sb.length();
        sb.append(favName).append("(").append(String.valueOf(favCount)).append(")");
        int favEnd = sb.length();

        sb.setSpan(new android.text.style.ClickableSpan() {
            @Override public void onClick(android.view.View widget) {
                showFavoritesStockListDialog();
            }
            @Override public void updateDrawState(android.text.TextPaint ds) {
                super.updateDrawState(ds);
                ds.setUnderlineText(false);
                ds.setColor(android.graphics.Color.WHITE);
            }
        }, favStart, favEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // ===== 輪播區塊：<檔名> 序號/總數 可點（不含「輪播:」）=====
        if (hasCarousel) {
            sb.append("  ");
            sb.append(prefix); // prefix 不可點

            int carClickStart = sb.length(); // 可點從 prefix 後開始

            if (!carouselName.isEmpty()) sb.append(carouselName);
            if (!pos.isEmpty()) sb.append("  ").append(pos);

            int carClickEnd = sb.length();

            if (carClickEnd > carClickStart) {
                sb.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(android.view.View widget) {
                        showCarouselStockListDialog();
                    }
                    @Override public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setUnderlineText(false);
                        ds.setColor(android.graphics.Color.WHITE);
                    }
                }, carClickStart, carClickEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        tvTopOutsideMainChart.setText(sb);
        tvTopOutsideMainChart.setVisibility(android.view.View.VISIBLE);
    }
    private void showCarouselStockListDialog() {
        // 優先：輪播結果清單（有 name 就直接用）
        if (screenerResults != null && !screenerResults.isEmpty()) {
            CharSequence[] items = new CharSequence[screenerResults.size()];
            for (int i = 0; i < screenerResults.size(); i++) {
                ScreenerResult r = screenerResults.get(i);
                String tk = (r == null || r.ticker == null) ? "" : r.ticker.trim().toUpperCase(java.util.Locale.US);
                String name = (r == null) ? "" : r.name;
                String ind  = (r == null) ? "" : r.industry;
                items[i] = buildTickerDisplayText(tk, name, ind);
            }

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_pick_carousel_ticker_title)
                    .setItems(items, (d, which) -> {
                        // 這裡 which 就是 screenerResults 的 index，直接跳
                        showFilteredAt(which, true);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        // fallback：用 activeCarouselFile 讀 CSV 第一欄 ticker，名稱用 tickerMetaMap 補
        if (activeCarouselFile != null && activeCarouselFile.exists()) {
            java.util.List<String> tickers = readFirstColumnTickersFromCsv(activeCarouselFile);
            if (tickers != null && !tickers.isEmpty()) {
                showCarouselTickerPickerFromTickers(tickers);
                return;
            }
        }

        Toast.makeText(this, getString(R.string.toast_carousel_list_empty), Toast.LENGTH_SHORT).show();
    }

    private void showCarouselTickerPickerFromTickers(java.util.List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return;

        CharSequence[] items = new CharSequence[tickers.size()];
        for (int i = 0; i < tickers.size(); i++) {
            String tk = (tickers.get(i) == null) ? "" : tickers.get(i).trim().toUpperCase(java.util.Locale.US);
            String[] ni = lookupTickerNameIndustry(tk);
            items[i] = buildTickerDisplayText(tk, ni[0], ni[1]);
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_pick_carousel_ticker_title)
                .setItems(items, (d, which) -> {
                    String tk = tickers.get(which).trim().toUpperCase(java.util.Locale.US);

                    // 這種情況沒有 screenerResults index 可跳，就直接當主股票畫
                    carouselActive = false;
                    updateTopOutsideMainChartUi();

                    suppressNextStockIdFocusFetch = true;
                    stockIdEditText.setText(tk);

                    rememberMainCandlePixelWidthForNextRedraw();
                    currentStockId = tk;
                    fetchStockDataWithFallback(tk, currentInterval,
                            getStartTimeLimit(currentInterval, isSwitchingInterval));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
     private void showFavoritesStockListDialog() {
        if (favoriteMap == null || favoriteMap.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_favorites_empty), Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.ArrayList<FavoriteInfo> list = new java.util.ArrayList<>(favoriteMap.values());
        list.sort((a, b) -> a.ticker.compareToIgnoreCase(b.ticker));

        CharSequence[] items = new CharSequence[list.size()];
        for (int i = 0; i < list.size(); i++) {
            FavoriteInfo fi = list.get(i);
            items[i] = buildTickerDisplayText(fi.ticker, fi.name, fi.industry);
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_pick_favorite_ticker_title)
                .setItems(items, (d, which) -> {
                    FavoriteInfo fi = list.get(which);
                    String tk = fi.ticker.trim().toUpperCase(java.util.Locale.US);

                    // ✅ 如果目前在輪播中：
                    // 1) 若此股票也在輪播清單，直接跳到該 index（輪播序號也會更新，且從此繼續）
                    if (carouselActive && screenerResults != null && !screenerResults.isEmpty()) {
                        int found = -1;
                        for (int i = 0; i < screenerResults.size(); i++) {
                            ScreenerResult r = screenerResults.get(i);
                            if (r != null && r.ticker != null
                                    && tk.equalsIgnoreCase(r.ticker.trim())) {
                                found = i;
                                break;
                            }
                        }
                        if (found >= 0) {
                            showFilteredAt(found, true);
                            return;
                        }

                        // 2) 若不在輪播清單：暫時看這檔，但不退出輪播（screenerIndex 不變）
                        //    下次左右滑仍會照原輪播清單繼續
                        suppressNextStockIdFocusFetch = true;
                        stockIdEditText.setText(tk);

                        rememberMainCandlePixelWidthForNextRedraw();
                        currentStockId = tk;
                        fetchStockDataWithFallback(tk, currentInterval,
                                getStartTimeLimit(currentInterval, isSwitchingInterval));

                        // 保持輪播狀態（不要設 carouselActive=false）
                        updateTopOutsideMainChartUi();
                        return;
                    }

                    // ✅ 非輪播狀態：維持原本行為（選最愛就退出輪播）
                    carouselActive = false;
                    updateTopOutsideMainChartUi();

                    suppressNextStockIdFocusFetch = true;
                    stockIdEditText.setText(tk);

                    rememberMainCandlePixelWidthForNextRedraw();
                    currentStockId = tk;
                    fetchStockDataWithFallback(tk, currentInterval,
                            getStartTimeLimit(currentInterval, isSwitchingInterval));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    private String buildTickerDisplayText(String ticker, String name, String industry) {
        String tk = (ticker == null) ? "" : ticker.trim().toUpperCase(java.util.Locale.US);
        String nm = (name == null) ? "" : name.trim();
        String ind = (industry == null) ? "" : industry.trim();

        StringBuilder sb = new StringBuilder();
        sb.append(tk);

        if (!nm.isEmpty()) sb.append(" ").append(nm);
        if (!ind.isEmpty()) sb.append(" ").append(ind);

        return sb.toString();
    }

    private String[] lookupTickerNameIndustry(String ticker) {
        String tk = (ticker == null) ? "" : ticker.trim().toUpperCase(java.util.Locale.US);
        if (tk.isEmpty() || tickerMetaMap == null) return new String[]{"", ""};

        TickerInfo info = tickerMetaMap.get(tk);
        if (info == null) {
            String base = tk.replace(".TW", "").replace(".TWO", "");
            info = tickerMetaMap.get(base);
        }
        if (info == null) return new String[]{"", ""};

        String name = (info.name == null) ? "" : info.name.trim();
        String ind  = (info.industry == null) ? "" : info.industry.trim();
        return new String[]{name, ind};
    }
   private String stripCsvExt(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.toLowerCase(java.util.Locale.US).endsWith(".csv")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }
   private void updateCarouselPosUi() {
       updateTopOutsideMainChartUi();
   }
    private void initUi() {
        yahooFinanceFetcher = new YahooFinanceFetcher();
        vpView.bindMainChart(mainChart);

        displayText = getResources().getStringArray(R.array.interval_display_text);
        intervalSwitchButton.setText(displayText[currentIntervalIndex]);

        // 日期輸入框：不彈鍵盤，點擊出日曆
        startDateEditText.setFocusable(false);
        startDateEditText.setFocusableInTouchMode(false);
        startDateEditText.setOnClickListener(v -> showDatePickerDialog());

        // 對比代號輸入框
        comparisonStockIdEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
        comparisonStockIdEditText.setSelectAllOnFocus(true);
        comparisonStockIdEditText.setText(displayedComparisonSymbol);
        comparisonStockIdEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            String newCompSymbol = v.getText().toString().trim().toUpperCase(Locale.US);
            if (!newCompSymbol.isEmpty() && !newCompSymbol.equals(displayedComparisonSymbol)) {
                displayedComparisonSymbol = newCompSymbol;
                fetchComparisonDataOnly(currentStockId, fullPriceList, currentInterval, displayedComparisonSymbol);
            }
            hideKeyboard(v);
            comparisonStockIdEditText.clearFocus();
            return true;
        });

        // 股票代碼輸入框
        stockIdEditText.setText(currentStockId);
        stockIdEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                stockIdEditText.getText().clear();
                return;
            }

            if (suppressNextStockIdFocusFetch) {
                suppressNextStockIdFocusFetch = false;
                hideKeyboard(v);
                return;
            }

            String stockIdInput = stockIdEditText.getText().toString().trim();

            if (!stockIdInput.isEmpty()) {
                String stockId = resolveForMainFetch(stockIdInput);

                if (!stockId.isEmpty() && !stockId.equalsIgnoreCase(currentStockId)) {
                    fetchStockDataWithFallback(
                            stockId,
                            currentInterval,
                            getStartTimeLimit(currentInterval, isSwitchingInterval)
                    );
                }
            } else {
                stockIdEditText.setText(currentStockId);
            }
            hideKeyboard(v);
        });

        stockIdEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;

            String stockIdInput = v.getText().toString().trim();
            if (!stockIdInput.isEmpty()) {
                String stockId = resolveForMainFetch(stockIdInput);
                if (!stockId.isEmpty() && !stockId.equalsIgnoreCase(currentStockId)) {
                    carouselActive = false;
                    updateCarouselPosUi();
                    rememberMainCandlePixelWidthForNextRedraw();
                    fetchStockDataWithFallback(
                            stockId,
                            currentInterval,
                            getStartTimeLimit(currentInterval, isSwitchingInterval)
                    );
                }
            }

            suppressNextStockIdFocusFetch = true;
            hideKeyboard(v);
            v.clearFocus();
            return true;
        });

        // 週期切換
        intervalSwitchButton.setOnClickListener(v -> {
            isSwitchingInterval = true;
            currentIntervalIndex = (currentIntervalIndex + 1) % INTERVALS.length;
            currentInterval = INTERVALS[currentIntervalIndex];
            intervalSwitchButton.setText(displayText[currentIntervalIndex]);

            updateDateInputByInterval(currentInterval);

            if (mainChart != null && mainChart.getData() != null) {
                savedMainScaleX = mainChart.getViewPortHandler().getScaleX();
                XAxis x = mainChart.getXAxis();
                savedMainXRange = x.getAxisMaximum() - x.getAxisMinimum();
                pendingRestoreCandleWidth = true;
            }

            comparisonPriceList.clear();
            executeCustomDataFetch();

            isSwitchingInterval = false;
        });
        updateDateInputByInterval(currentInterval);
        // Screener button
        if (screenerButton != null) {
            screenerButton.setText(getString(R.string.screener_btn));
            screenerButton.setOnClickListener(v -> {
                if (isScreening) {
                    // 點擊時提示（或你也可改成直接取消）
                    Toast.makeText(this, getString(R.string.toast_screener_running), Toast.LENGTH_SHORT).show();
                } else {
                    showScreenerModeDialog();
                }
            });
        }
        // 股票代碼輸入格、起始日期輸入格：字體縮小 30%
        if (stockIdEditText != null) {
            float px = stockIdEditText.getTextSize(); // px
            stockIdEditText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * 0.7f);
        }

        if (startDateEditText != null) {
            float px = startDateEditText.getTextSize(); // px
            startDateEditText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * 0.7f);
        }
    }
    private void setControlsEnabled(boolean enabled) {
        if (intervalSwitchButton != null) intervalSwitchButton.setEnabled(enabled);
        if (stockIdEditText != null) stockIdEditText.setEnabled(enabled);
        if (startDateEditText != null) startDateEditText.setEnabled(enabled);
        if (comparisonStockIdEditText != null) comparisonStockIdEditText.setEnabled(enabled);

        // 篩選按鈕保持可用（用於顯示進度/提示）fviewModeButton.getLayoutParams().height
        if (screenerButton != null) screenerButton.setEnabled(true);
    }
    private String getActiveParamsText(ScreenerMode mode) {
        switch (mode) {
            case LT20:
                return getString(R.string.screener_params_lt20, activeLtThr, activeLtDays);
            case GT45:
                return getString(R.string.screener_params_gt45, activeGtThr, activeGtMin, activeGtMax);
            case MA60_3PCT:
                return getString(R.string.screener_params_ma60, activeMaBandPct, activeMaDays);
            case MACD_DIV_RECENT:
                return getString(R.string.screener_params_macd_div_recent,
                        activeMacdDivBars,
                        activeMacdDivTf,
                        activeMacdDivSide);
            case KD_GC_RECENT:
                return getString(R.string.screener_params_kd_gc_recent,
                        activeKdGcBars,
                        activeKdGcTf);
            default:
                return ""; // 其他模式不顯示參數
        }
    }
    private void applyIntervalForScreener(ScreenerMode mode) {
        if (mode == null) return;

        final String target;
        switch (mode) {
            case MACD_DIV_RECENT:
                target = mapTfToInterval(activeMacdDivTf);
                break;

            case KD_GC_RECENT:
                target = mapTfToInterval(activeKdGcTf);
                break;

            case LT20:
            case GT45:
            case MA60_3PCT:
                target = mapTfToInterval(activeMaTf);
                break;
            default:
                target = "1d";
                break;
        }

        if (target.equals(currentInterval)) return;

        currentInterval = target;
        // 同步 index 與按鈕文字
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i].equals(target)) {
                currentIntervalIndex = i;
                break;
            }
        }
        if (intervalSwitchButton != null && displayText != null && currentIntervalIndex >= 0
                && currentIntervalIndex < displayText.length) {
            intervalSwitchButton.setText(displayText[currentIntervalIndex]);
        }
        updateDateInputByInterval(currentInterval);
        Log.d(TAG, "applyIntervalForScreener target=" + target
                + " currentInterval=" + currentInterval
                + " idx=" + currentIntervalIndex
                + " idxInterval=" + INTERVALS[currentIntervalIndex]);
    }

    private String mapTfToInterval(String tf) {
        if (tf == null) return "1d";
        tf = tf.trim();

        // 中文
        if ("時".equals(tf)) return "1h";
        if ("日".equals(tf)) return "1d";
        if ("周".equals(tf)) return "1wk";
        if ("月".equals(tf)) return "1mo";

        // 英文長字
        String up = tf.toUpperCase(java.util.Locale.ROOT);
        if ("HOUR".equals(up)) return "1h";
        if ("DAY".equals(up)) return "1d";
        if ("WEEK".equals(up)) return "1wk";
        if ("MONTH".equals(up)) return "1mo";

        // 直接傳 interval 也接受
        if ("1h".equals(tf) || "1d".equals(tf) || "1wk".equals(tf) || "1mo".equals(tf)) return tf;

        return "1d";
    }
    private void openCsvListPicker() {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, getString(R.string.error_csv_dir_unavailable), Toast.LENGTH_LONG).show();
            return;
        }

        java.io.File[] files = dir.listFiles((d, name) ->
                name != null && name.toLowerCase(java.util.Locale.US).endsWith(".csv"));

        if (files == null || files.length == 0) {
            Toast.makeText(this, getString(R.string.error_csv_not_found, dir.getAbsolutePath()), Toast.LENGTH_LONG).show();
            return;
        }

        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        // 用 ListView 才能做長按
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDividerHeight(1);

        java.util.ArrayList<java.io.File> fileList = new java.util.ArrayList<>();
        for (java.io.File f : files) fileList.add(f);

        android.widget.ArrayAdapter<java.io.File> adapter = new android.widget.ArrayAdapter<java.io.File>(
                this, android.R.layout.simple_list_item_1, fileList) {
            @Override public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View v = super.getView(position, convertView, parent);
                android.widget.TextView tv = (android.widget.TextView) v.findViewById(android.R.id.text1);
                java.io.File f = getItem(position);
                tv.setText(f != null ? f.getName() : "");
                return v;
            }
        };

        lv.setAdapter(adapter);

        final androidx.appcompat.app.AlertDialog pickerDlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.screener_pick_csv_title)
                .setView(lv)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // ✅ 點擊：維持原本「讀取檔案及後續功能」
        lv.setOnItemClickListener((parent, view, which, id) -> {
            java.io.File f = fileList.get(which);

        // ✅ 如果檔名前兩個字是「最愛」，這份清單就當作目前最愛清單檔
        if (isFavoritesCsvFileName(f.getName())) {
            setActiveFavoritesFileAndReload(f);
        }

        List<String> tickers = readFirstColumnTickersFromCsv(f);
        if (tickers.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_csv_no_tickers, f.getName()), Toast.LENGTH_LONG).show();
            return;
        }

        activeCarouselFile = f;
        // 轉成 screenerResults（假設 ScreenerResult 有 public field: ticker）
        screenerResults.clear();
        for (String t : tickers) {
            String tk = (t == null) ? "" : t.trim().toUpperCase(java.util.Locale.US);
            if (tk.isEmpty()) continue;
            String[] ni = lookupTickerNameIndustry(tk);
            ScreenerResult r = new ScreenerResult(
                    tk,
                    ni[0],  // name
                    ni[1],  // industry
                    Double.NaN, Double.NaN, null, null, null, null, null, null
            );
                screenerResults.add(r);
        }

        screenerIndex = 0;
        screenerSessionClosed = true;      // CSV list 模式不是「篩選 session」
        allowSaveOnSwipeUp = false;

        new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_csv_loaded_title)
                    .setMessage(getString(R.string.dialog_csv_loaded_msg, f.getName(), screenerResults.size()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();

        pickerDlg.dismiss();
        showFilteredAt(0, true);
        });

        // ✅ 長按：跳出「複製 / 刪除」選單
        lv.setOnItemLongClickListener((parent, view, which, id) -> {
            java.io.File f = fileList.get(which);
            showCsvLongPressMenu(pickerDlg, f);
            return true; // 吃掉 long press，避免誤觸 click
        });

        pickerDlg.show();
    }

    private boolean isFavoritesCsvFileName(String fileName) {
        if (fileName == null) return false;
        // 前兩個字是「最愛」
        return fileName.startsWith("最愛");
    }

    private void setActiveFavoritesFileAndReload(java.io.File f) {
        activeFavoritesFile = f;
        loadFavoritesFromFile(f);
        updateFavoriteButtonState(); // 立刻讓主圖愛心狀態跟著更新
        updateTopOutsideMainChartUi();

        // 可選：提示使用者目前最愛清單來源已切換
        //Toast.makeText(this,
        //        getString(R.string.toast_favorites_switched, f.getName()),
        //        Toast.LENGTH_SHORT).show();
    }
    private void showCsvLongPressMenu(androidx.appcompat.app.AlertDialog pickerDlg, java.io.File targetFile) {
        if (targetFile == null) return;

        CharSequence[] actions = new CharSequence[] {
                getString(R.string.csv_action_copy),
                getString(R.string.csv_action_delete)
        };

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(targetFile.getName())
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        showCopyCsvDialog(pickerDlg, targetFile);
                    } else {
                        showDeleteCsvConfirmDialog(pickerDlg, targetFile);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    private void showCopyCsvDialog(androidx.appcompat.app.AlertDialog pickerDlg, java.io.File src) {
        java.io.File dir = src.getParentFile();
        if (dir == null) {
            Toast.makeText(this, getString(R.string.error_csv_copy_failed), Toast.LENGTH_LONG).show();
            return;
        }

        String suggested = suggestCopyFileName(dir, src.getName());

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setText(suggested);
        et.setSelection(et.getText().length());

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_csv_copy_title)
                .setMessage(getString(R.string.dialog_csv_copy_msg, src.getName()))
                .setView(et)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null) // show 後接管
                .create();

        dlg.show();

        android.widget.Button ok = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        ok.setOnClickListener(v -> {
            String input = (et.getText() == null) ? "" : et.getText().toString().trim();
            String fileName = sanitizeCsvFileName(input);

            if (fileName == null) {
                Toast.makeText(this, getString(R.string.error_csv_invalid_filename), Toast.LENGTH_LONG).show();
                return; // 不 dismiss
            }

            java.io.File dst = new java.io.File(dir, fileName);
            if (dst.exists()) {
                Toast.makeText(this, getString(R.string.error_csv_name_exists, dst.getName()), Toast.LENGTH_LONG).show();
                return; // 不 dismiss
            }

            boolean okCopy = copyFile(src, dst);
            if (!okCopy) {
                Toast.makeText(this, getString(R.string.error_csv_copy_failed), Toast.LENGTH_LONG).show();
                return;
            }

            Toast.makeText(this, getString(R.string.toast_csv_copied, dst.getName()), Toast.LENGTH_SHORT).show();

            dlg.dismiss();
            if (pickerDlg != null) pickerDlg.dismiss();

            // 方案B：關掉後重新開
            openCsvListPicker();
        });
    }

    private String suggestCopyFileName(java.io.File dir, String originalName) {
        String base = (originalName == null) ? "list" : originalName.trim();

        // 去掉 .csv
        if (base.toLowerCase(java.util.Locale.US).endsWith(".csv")) {
            base = base.substring(0, base.length() - 4);
        }
        if (base.isEmpty()) base = "list";

        // 抓尾端數字：prefix + number
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)(\\d+)$")
                .matcher(base);

        String prefix;
        long startNum;
        int padWidth; // 保留原本數字寬度（例如 009 -> 010）

        if (m.find()) {
            prefix = m.group(1);
            String numStr = m.group(2);
            padWidth = numStr.length();

            long n;
            try {
                n = Long.parseLong(numStr);
            } catch (Exception e) {
                // 數字太大或解析失敗：退回一般策略
                prefix = base;
                padWidth = 0;
                n = 0;
            }
            startNum = n + 1; // ✅ 重點：尾端數字 +1
        } else {
            prefix = base;
            startNum = 1;     // ✅ 沒數字才從 1 開始
            padWidth = 0;
        }

        // 找到不撞名的檔名
        for (long n = startNum; n <= startNum + 9999; n++) {
            String numPart = (padWidth > 0)
                    ? String.format(java.util.Locale.US, "%0" + padWidth + "d", n)
                    : String.valueOf(n);

            String candidate = prefix + numPart + ".csv";
            if (!new java.io.File(dir, candidate).exists()) return candidate;
        }

        // 極端情況 fallback
        return prefix + System.currentTimeMillis() + ".csv";
    }

    private String sanitizeCsvFileName(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        // 不允許路徑穿越或分隔符
        if (s.contains("/") || s.contains("\\") || s.contains("\u0000")) return null;

        // 確保副檔名 .csv
        if (!s.toLowerCase(java.util.Locale.US).endsWith(".csv")) {
            s = s + ".csv";
        }

        // 避免只剩 .csv
        if (s.equalsIgnoreCase(".csv")) return null;

        return s;
    }

    private boolean copyFile(java.io.File src, java.io.File dst) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst, false);

            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            android.util.Log.e(TAG, "copyFile failed: " + e.getMessage(), e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }
    private boolean moveFile(java.io.File src, java.io.File dst) {
        try {
            if (src.renameTo(dst)) return true;
        } catch (Exception ignored) {}

        if (!copyFile(src, dst)) return false;
        try { src.delete(); } catch (Exception ignored) {}
        return true;
    }
    private void showDeleteCsvConfirmDialog(androidx.appcompat.app.AlertDialog pickerDlg, java.io.File target) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_csv_delete_title)
                .setMessage(getString(R.string.dialog_csv_delete_msg, target.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    boolean ok = false;
                    try {
                        ok = target.delete();
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "delete failed: " + e.getMessage(), e);
                    }

                    if (!ok) {
                        Toast.makeText(this, getString(R.string.error_csv_delete_failed, target.getName()), Toast.LENGTH_LONG).show();
                        return;
                    }

                    Toast.makeText(this, getString(R.string.toast_csv_deleted, target.getName()), Toast.LENGTH_SHORT).show();

                    if (pickerDlg != null) pickerDlg.dismiss();
                    openCsvListPicker(); // 方案B：重新開
                })
                .show();
    }
    private List<String> readFirstColumnTickersFromCsv(java.io.File file) {
        List<String> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 只取第一欄（簡易 CSV：逗號前）
                String col0 = line.split(",", -1)[0].trim();

                // 跳過 header
                if (col0.equalsIgnoreCase("ticker")) continue;

                // 正規化
                String t = col0.toUpperCase(Locale.US);
                if (t.isEmpty()) continue;
                if (seen.add(t)) out.add(t);
            }
        } catch (Exception e) {
            Log.e(TAG, "CSV read failed: " + e.getMessage());
        }
        return out;
    }
    private String buildMainStockLegendLabel() {
        String t = (currentStockId == null) ? "" : currentStockId.trim().toUpperCase(Locale.US);
        if (t.isEmpty()) return "||";

        // 1) 先看記憶體快取
        TickerInfo info = tickerMetaMap.get(t);

        // 2) 沒有就嘗試從內部檔案 /data/data/.../files/股票代碼.csv 讀一次（不觸發網路）
        if (info == null) {
            info = findTickerInfoFromInternalCache(t);
            if (info != null) tickerMetaMap.put(t, info);
        }

        // 3) 還找不到就用 screenerResults 當 fallback（若該 ticker 剛好來自篩選結果）
        String name = "";
        String ind = "";
        if (info != null) {
            name = (info.name == null) ? "" : info.name.trim();
            ind  = (info.industry == null) ? "" : info.industry.trim();
        } else {
            for (ScreenerResult r : screenerResults) {
                if (r == null || r.ticker == null) continue;
                if (t.equals(r.ticker.trim().toUpperCase(Locale.US))) {
                    name = (r.name == null) ? "" : r.name.trim();
                    ind  = (r.industry == null) ? "" : r.industry.trim();
                    break;
                }
            }
        }

        return t + "|" + name + "|" + ind;
    }

    @androidx.annotation.Nullable
    private TickerInfo findTickerInfoFromInternalCache(String tickerUpper) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), TICKERS_CACHE_FILE);
            if (!f.exists()) return null;

            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {

                String header = br.readLine();
                if (header == null) return null;
                if (header.startsWith("\uFEFF")) header = header.substring(1);

                String[] h = splitCsvLine(header);
                int iTicker = -1, iName = -1, iInd = -1;
                for (int i = 0; i < h.length; i++) {
                    String col = h[i].trim().toLowerCase(Locale.US);
                    if (col.equals("ticker")) iTicker = i;
                    else if (col.equals("name")) iName = i;
                    else if (col.equals("industry") || col.equals("category")) iInd = i;
                }

                String line;
                while ((line = br.readLine()) != null) {
                    String[] cols = splitCsvLine(line);
                    String tk = (iTicker >= 0 && iTicker < cols.length) ? cols[iTicker].trim() :
                            (cols.length > 0 ? cols[0].trim() : "");
                    if (tk.isEmpty()) continue;

                    String tkU = tk.toUpperCase(Locale.US);
                    if (!tkU.equals(tickerUpper)) continue;

                    String name = (iName >= 0 && iName < cols.length) ? cols[iName].trim() : "";
                    String ind  = (iInd >= 0 && iInd < cols.length) ? cols[iInd].trim() : "";
                    return new TickerInfo(tkU, name, ind);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 簡易 CSV split（支援引號） */
    private static String[] splitCsvLine(String line) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                if (inQ && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    cur.append('\"');
                    i++;
                } else {
                    inQ = !inQ;
                }
            } else if (c == ',' && !inQ) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ---------- helpers：放在 MainActivity 內任意位置（若你已經有同名可跳過） ----------

    @Override
    protected void onDestroy() {
        try {
            if (screenerReceiverRegistered) {
                unregisterReceiver(screenerReceiver);
                screenerReceiverRegistered = false;
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
    private void startScreening(ScreenerMode mode) {
        if (isScreening) return;
// 先記住本次篩選使用的參數（顯示結果用）
        activeLtThr = pLtThr;
        activeLtDays = pLtDays;

        activeGtThr = pGtThr;
        activeGtMin = pGtMin;
        activeGtMax = pGtMax;

        activeMaTf = pMaTf;
        activeMaPick = pMaPick;
        activeMaBandPct = pMaBandPct;
        activeMaDays = pMaDays;

        activeMacdDivBars = pMacdDivBars;
        activeMacdDivTf   = pMacdDivTf;
        activeMacdDivSide = pMacdDivSide;

        activeKdGcBars = pKdGcBars;
        activeKdGcTf   = pKdGcTf;

        this.screenerMode = mode;
        this.isScreening = true;
        setControlsEnabled(false);
        screenerButton.setText("0%");

        // Android 13+ 沒通知權限時，FGS 可能無法正常顯示通知而被系統終止
        //（建議你在 UI 層先申請 POST_NOTIFICATIONS 再啟動）
        android.content.Intent it = new android.content.Intent(this, ScreenerForegroundService.class);
        it.setAction(ScreenerForegroundService.ACTION_START);
        it.putExtra(ScreenerForegroundService.EXTRA_MODE, mode.name());

        it.putExtra(ScreenerForegroundService.EXTRA_LT_THR, activeLtThr);
        it.putExtra(ScreenerForegroundService.EXTRA_LT_DAYS, activeLtDays);

        it.putExtra(ScreenerForegroundService.EXTRA_GT_THR, activeGtThr);
        it.putExtra(ScreenerForegroundService.EXTRA_GT_MIN, activeGtMin);
        it.putExtra(ScreenerForegroundService.EXTRA_GT_MAX, activeGtMax);

        it.putExtra(ScreenerForegroundService.EXTRA_MA_TF, activeMaTf);
        it.putExtra(ScreenerForegroundService.EXTRA_MA_WINDOW,
                resolveMaWindowByTfAndPick(activeMaTf, activeMaPick));
        it.putExtra(ScreenerForegroundService.EXTRA_MA_BAND_PCT, activeMaBandPct);
        it.putExtra(ScreenerForegroundService.EXTRA_MA_DAYS, activeMaDays);
        it.putStringArrayListExtra(ScreenerForegroundService.EXTRA_INDUSTRIES,
                new java.util.ArrayList<>(selectedIndustries));

        it.putExtra(ScreenerForegroundService.EXTRA_MACD_DIV_BARS, activeMacdDivBars);
        it.putExtra(ScreenerForegroundService.EXTRA_MACD_DIV_TF,   activeMacdDivTf);
        it.putExtra(ScreenerForegroundService.EXTRA_MACD_DIV_SIDE, activeMacdDivSide);

        it.putExtra(ScreenerForegroundService.EXTRA_KD_GC_BARS, activeKdGcBars);
        it.putExtra(ScreenerForegroundService.EXTRA_KD_GC_TF,   activeKdGcTf);
        // ✅ 若使用「從檔案讀取」模式：把 CSV 路徑傳給 Service
        if (pendingTickerCsvPathForScreening != null && !pendingTickerCsvPathForScreening.trim().isEmpty()) {
            it.putExtra(ScreenerForegroundService.EXTRA_TICKER_CSV_PATH, pendingTickerCsvPathForScreening);
            pendingTickerCsvPathForScreening = null; // 用完清掉
        }
        androidx.core.content.ContextCompat.startForegroundService(this, it);
    }
    private void showScreenerModeDialog() {
        final boolean isZh = getResources().getConfiguration().getLocales().get(0)
                .getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh");

        final float density = getResources().getDisplayMetrics().density;
        final int dp2  = Math.round(2f  * density);
        final int dp6  = Math.round(6f  * density);
        final int dp10 = Math.round(10f * density);
        final int dp44 = Math.round(44f * density);
        final int dp40 = Math.round(40f * density);
        final int dp60 = Math.round(60f * density);

        android.text.InputFilter[] twoDigits = new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(2)
        };

        java.util.function.Function<Integer, android.widget.EditText> mk2d = (defVal) -> {
            android.widget.EditText e = new android.widget.EditText(this);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            e.setFilters(twoDigits);
            e.setText(String.valueOf(defVal));
            e.setSingleLine(true);
            e.setGravity(android.view.Gravity.CENTER);

            // 你已驗證這組高度/內距 OK
            e.setMinHeight(dp40);
            e.setPadding(e.getPaddingLeft(), dp2, e.getPaddingRight(), dp2);

            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dp44, dp40);
            lp.leftMargin = dp2;
            lp.rightMargin = dp2;
            e.setLayoutParams(lp);
            return e;
        };

        final String[] sideItems = getResources().getStringArray(R.array.div_side_items);
// 讓 Bottom/Top 也不會被切字，寬度建議比 tf picker 大一點
        final int dp72 = Math.round(72f * density);


        java.util.function.Function<String, android.widget.TextView> mkText = (s) -> {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(s);
            tv.setTextSize(16f);
            return tv;
        };

        java.util.function.Function<Integer, android.widget.Spinner> mkSpinner = (arrayResId) -> {
            android.widget.Spinner sp = new android.widget.Spinner(this);
            android.widget.ArrayAdapter<CharSequence> ad =
                    android.widget.ArrayAdapter.createFromResource(
                            this, arrayResId, android.R.layout.simple_spinner_item);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);

            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp2;
            lp.rightMargin = dp2;
            sp.setLayoutParams(lp);
            return sp;
        };

        java.util.function.Function<Integer, android.widget.NumberPicker> mkNp2d = (defVal) -> {
            android.widget.NumberPicker np = new android.widget.NumberPicker(this);
            np.setMinValue(1);
            np.setMaxValue(99);
            np.setValue(Math.max(1, Math.min(99, defVal)));
            np.setWrapSelectorWheel(true);
            // 允許點進去輸入
            np.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);

            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dp44, dp40);
            lp.leftMargin = dp6;
            lp.rightMargin = dp6;
            np.setLayoutParams(lp);
            return np;
        };

        // 把「時/日/周/月、Hour/Day/Week/Month、1h/1d/1wk/1mo」都映射成 spinner index
        java.util.function.Function<String, Integer> tfToIndex = (v) -> {
            if (v == null) return -1;
            String raw = v.trim();
            if (raw.isEmpty()) return -1;
            String up = raw.toUpperCase(java.util.Locale.ROOT);

            if ("時".equals(raw) || "HOUR".equals(up) || "1H".equals(up) || "1h".equals(raw)) return 0;
            if ("日".equals(raw) || "DAY".equals(up)  || "1D".equals(up) || "1d".equals(raw)) return 1;
            if ("周".equals(raw) || "WEEK".equals(up) || "1WK".equals(up) || "1wk".equals(raw)) return 2;
            if ("月".equals(raw) || "MONTH".equals(up)|| "1MO".equals(up) || "1mo".equals(raw)) return 3;
            return -1;
        };

        final String[] tfItems = getResources().getStringArray(R.array.div_tf_items);

        java.util.function.Function<String, android.widget.NumberPicker> mkTfPicker = (defTf) -> {
            android.widget.NumberPicker np = new android.widget.NumberPicker(this);
            np.setMinValue(0);
            np.setMaxValue(tfItems.length - 1);
            np.setDisplayedValues(tfItems);
            np.setWrapSelectorWheel(true);
            np.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);

            int idx = tfToIndex.apply(defTf);
            if (idx < 0 || idx >= tfItems.length) idx = 0;
            np.setValue(idx);

            // ✅ 固定寬度 + 固定高度
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dp44, dp60);
            lp.leftMargin = Math.round(2f * density);
            lp.rightMargin = Math.round(2f * density);
            np.setLayoutParams(lp);

            // ✅ 把 NumberPicker 自己的 padding 清掉（內部留白會變少）
            np.setPadding(0, 0, 0, 0);

            // ✅ 再把內部顯示文字的 EditText 左右 padding 清掉（這個才是「看起來很寬」的主因之一）
            try {
                int id = getResources().getIdentifier("numberpicker_input", "id", "android");
                android.widget.EditText input = np.findViewById(id);
                if (input != null) {
                    input.setPadding(0, input.getPaddingTop(), 0, input.getPaddingBottom());
                    input.setGravity(android.view.Gravity.CENTER);
                    // 可選：字太大也會擠，稍微小一點更好看
                    // input.setTextSize(16f);
                }
            } catch (Exception ignored) {}

            // ✅ 有些機型會吃 minWidth，保險起見也設一下
            np.setMinimumWidth(dp44);
            return np;
        };

        java.util.function.Function<String, Integer> sideToIndex = (v) -> {
            if (v == null) return -1;
            String raw = v.trim();
            if (raw.isEmpty()) return -1;
            String up = raw.toUpperCase(java.util.Locale.ROOT);

            if ("底".equals(raw) || "BOTTOM".equals(up)) return 0;
            if ("頂".equals(raw) || "TOP".equals(up)) return 1;
            return -1;
        };

        java.util.function.Function<String, android.widget.NumberPicker> mkSidePicker = (defSide) -> {
            android.widget.NumberPicker np = new android.widget.NumberPicker(this);
            np.setMinValue(0);
            np.setMaxValue(sideItems.length - 1);
            np.setDisplayedValues(sideItems);
            np.setWrapSelectorWheel(true);
            np.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);

            int idx = sideToIndex.apply(defSide);
            if (idx < 0 || idx >= sideItems.length) idx = 0;
            np.setValue(idx);

            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dp60, dp60);
            lp.leftMargin = dp2;
            lp.rightMargin = dp2;
            np.setLayoutParams(lp);

            np.setPadding(0, 0, 0, 0);

            try {
                int id = getResources().getIdentifier("numberpicker_input", "id", "android");
                android.widget.EditText input = np.findViewById(id);
                if (input != null) {
                    input.setPadding(0, input.getPaddingTop(), 0, input.getPaddingBottom());
                    input.setGravity(android.view.Gravity.CENTER);
                }
            } catch (Exception ignored) {}

            np.setMinimumWidth(dp72);
            return np;
        };

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp10, dp10, dp10, dp10);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(root);

        // ---------- Row 1: LT20 ----------
        final android.widget.EditText etLtThr  = mk2d.apply(pLtThr);
        final android.widget.EditText etLtDays = mk2d.apply(pLtDays);

        android.widget.LinearLayout row1 = new android.widget.LinearLayout(this);
        row1.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp2, 0, dp2);
        row1.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row1.addView(mkText.apply("KD40 <"));
        row1.addView(etLtThr);
        row1.addView(mkText.apply(isZh ? "連續" : "for"));
        row1.addView(etLtDays);
        row1.addView(mkText.apply(isZh ? "天" : "days"));
        row1.setClickable(true);

        // ---------- Row 2: GT45 ----------
        final android.widget.EditText etGtThr = mk2d.apply(pGtThr);
        final android.widget.EditText etGtMin = mk2d.apply(pGtMin);
        final android.widget.EditText etGtMax = mk2d.apply(pGtMax);

        android.widget.LinearLayout row2 = new android.widget.LinearLayout(this);
        row2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp2, 0, dp2);
        row2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row2.addView(mkText.apply("KD40 >"));
        row2.addView(etGtThr);
        row2.addView(mkText.apply(isZh ? "連續" : "for"));
        row2.addView(etGtMin);
        row2.addView(mkText.apply("~"));
        row2.addView(etGtMax);
        row2.addView(mkText.apply(isZh ? "天" : "days"));
        row2.setClickable(true);

        // ---------- Row 3: MA band (tf + MA1/MA2 with NumberPicker) ----------
        final android.widget.EditText etMaBand = mk2d.apply(pMaBandPct);
        final android.widget.EditText etMaDays = mk2d.apply(pMaDays);

        final android.widget.NumberPicker npMaTf = mkTfPicker.apply(pMaTf);

// ✅ MA1/MA2 改成 NumberPicker（同 tf 類型）
        final android.widget.NumberPicker npMaPick = new android.widget.NumberPicker(this);
        npMaPick.setWrapSelectorWheel(true);
        npMaPick.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);

// 外觀尺寸（跟你的 tf picker 一樣高度）
        android.widget.LinearLayout.LayoutParams lpMaPick =
                new android.widget.LinearLayout.LayoutParams(dp72, dp60);
        lpMaPick.leftMargin = dp2;
        lpMaPick.rightMargin = dp2;
        npMaPick.setLayoutParams(lpMaPick);
        npMaPick.setMinimumWidth(dp72);

// ✅ 先依目前 pMaTf 更新成 MAxx / MAyy，並套用 pMaPick(0/1)
        updateMaPickNumberPicker(npMaPick, pMaTf, pMaPick);

// ✅ tf 改變時，同步更新 MA picker 的顯示值（MA1/MA2 的數字會隨週期變）
        npMaTf.setOnValueChangedListener((picker, oldVal, newVal) -> {
            String tf = tfItems[newVal];           // newVal -> "時/日/周/月"
            int sel = npMaPick.getValue();         // 保留目前選的是 MA1(0) 還 MA2(1)
            updateMaPickNumberPicker(npMaPick, tf, sel);
        });

        android.widget.LinearLayout row3 = new android.widget.LinearLayout(this);
        row3.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp2, 0, dp2);
        row3.setGravity(android.view.Gravity.CENTER_VERTICAL);

// 你目前的顯示：tfPicker + MAxxPicker + "<"
        row3.addView(npMaTf);
        row3.addView(npMaPick);
        row3.addView(mkText.apply("±" +
                ""));

        row3.addView(etMaBand);
        row3.addView(mkText.apply("%"));
        row3.addView(mkText.apply(isZh ? " 連續" : " for"));
        row3.addView(etMaDays);
        row3.addView(mkText.apply(isZh ? "天" : "days"));
        row3.setClickable(true);

// ✅ 點 row3 時存參數（你原本 row3 click listener 要記得改用 npMaPick.getValue()）
// row3.setOnClickListener(v -> {
//     Integer band = readInt.apply(etMaBand);
//     Integer days = readInt.apply(etMaDays);
//     pMaBandPct = clampInt(band != null ? band : 3, 0, 99);
//     pMaDays    = clampInt(days != null ? days : 20, 1, 99);
//     pMaTf = tfItems[npMaTf.getValue()];
//     pMaPick = npMaPick.getValue(); // 0=MA1, 1=MA2
//     dlg.dismiss();
//     ensureTickerFileThenStart(ScreenerMode.MA60_3PCT);
// });

// ---------- Row 4: MACD divergence (one line) ----------
        final android.widget.EditText etMacdBars = mk2d.apply(pMacdDivBars);
        final android.widget.NumberPicker npMacdTf = mkTfPicker.apply(pMacdDivTf);
        final android.widget.NumberPicker npMacdSide = mkSidePicker.apply(pMacdDivSide);

        android.widget.LinearLayout row4 = new android.widget.LinearLayout(this);
        row4.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row4.setPadding(0, dp2, 0, dp2);
        row4.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row4.setClickable(true);

        if (isZh) {
            row4.addView(mkText.apply("最近"));
            row4.addView(etMacdBars);
            row4.addView(mkText.apply("根"));
            row4.addView(npMacdTf);
            row4.addView(mkText.apply("K柱"));
            row4.addView(npMacdSide);
            row4.addView(mkText.apply("部背離"));
        } else {
            row4.addView(mkText.apply("Last"));
            row4.addView(etMacdBars);
            row4.addView(npMacdTf);
            row4.addView(mkText.apply("bars"));
            row4.addView(npMacdSide);
            row4.addView(mkText.apply("divergence"));
        }

// ---------- Row 5: KD golden cross recent (one line) ----------
        final android.widget.EditText etKdGcBars = mk2d.apply(pKdGcBars);
        final android.widget.NumberPicker npKdGcTf = mkTfPicker.apply(pKdGcTf);

        android.widget.LinearLayout row5 = new android.widget.LinearLayout(this);
        row5.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row5.setPadding(0, dp2, 0, dp2);
        row5.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row5.setClickable(true);

        if (isZh) {
            row5.addView(mkText.apply("最近"));
            row5.addView(etKdGcBars);
            row5.addView(mkText.apply("根"));
            row5.addView(npKdGcTf);
            row5.addView(mkText.apply("K柱 KD黃金交叉"));
        } else {
            row5.addView(mkText.apply("Last"));
            row5.addView(etKdGcBars);
            row5.addView(npKdGcTf);
            row5.addView(mkText.apply("bars KD golden cross"));
        }

        // ---------- Row 6: CSV list ----------
        android.widget.TextView row6 = mkText.apply(getString(R.string.screener_mode_csv_list));
        row6.setPadding(0, dp6, 0, dp6);
        row6.setClickable(true);

        // add rows
        root.addView(row1);
        root.addView(row2);
        root.addView(row3);
        root.addView(row4);
        root.addView(row5);
        root.addView(row6);

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.screener_title)
                .setView(sv)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        java.util.function.Function<android.widget.EditText, Integer> readInt = (et) -> {
            try {
                String s = (et.getText() == null) ? "" : et.getText().toString().trim();
                if (s.isEmpty()) return null;
                return Integer.parseInt(s);
            } catch (Exception e) {
                return null;
            }
        };

        // clicks
        row1.setOnClickListener(v -> {
            Integer thr = readInt.apply(etLtThr);
            Integer days = readInt.apply(etLtDays);
            pLtThr  = clampInt(thr != null ? thr : 20, 0, 99);
            pLtDays = clampInt(days != null ? days : 20, 1, 99);
            dlg.dismiss();
            ensureTickerFileThenStart(ScreenerMode.LT20);
        });

        row2.setOnClickListener(v -> {
            Integer thr = readInt.apply(etGtThr);
            Integer mn  = readInt.apply(etGtMin);
            Integer mx  = readInt.apply(etGtMax);
            pGtThr = clampInt(thr != null ? thr : 45, 0, 99);
            pGtMin = clampInt(mn  != null ? mn  : 20, 1, 99);
            pGtMax = clampInt(mx  != null ? mx  : 30, 1, 99);
            if (pGtMin > pGtMax) { int t = pGtMin; pGtMin = pGtMax; pGtMax = t; }
            dlg.dismiss();
            ensureTickerFileThenStart(ScreenerMode.GT45);
        });

        row3.setOnClickListener(v -> {
            Integer band = readInt.apply(etMaBand);
            Integer days = readInt.apply(etMaDays);

            pMaBandPct = clampInt(band != null ? band : 3, 0, 99);
            pMaDays    = clampInt(days != null ? days : 20, 1, 99);

            pMaTf = tfItems[npMaTf.getValue()];
            pMaPick = npMaPick.getValue(); // ✅ 改這行（0=MA1, 1=MA2）

            dlg.dismiss();
            ensureTickerFileThenStart(ScreenerMode.MA60_3PCT);
        });

        row4.setOnClickListener(v -> {
            Integer bars = readInt.apply(etMacdBars);
            pMacdDivBars = clampInt(bars != null ? bars : 2, 1, 99);

            pMacdDivTf = tfItems[npMacdTf.getValue()];
            pMacdDivSide = sideItems[npMacdSide.getValue()];

            dlg.dismiss();
            ensureTickerFileThenStart(ScreenerMode.MACD_DIV_RECENT);
        });

        row5.setOnClickListener(v -> {
            Integer bars = readInt.apply(etKdGcBars);
            pKdGcBars = clampInt(bars != null ? bars : 2, 1, 99);

            pKdGcTf = tfItems[npKdGcTf.getValue()]; // tfItems = getStringArray(R.array.div_tf_items)

            dlg.dismiss();
            ensureTickerFileThenStart(ScreenerMode.KD_GC_RECENT);
        });

        row6.setOnClickListener(v -> {
            dlg.dismiss();
            openCsvListPicker();
        });

        dlg.show();
    }

    private int[] getMa1Ma2PeriodsByInterval(String interval) {
        // 必須跟 calculateMovingAverages() 完全一致
        switch (interval) {
            case "1h":
            case "1wk":
                return new int[]{35, 200};
            case "1mo":
                return new int[]{60, 120};
            case "1d":
            default:
                return new int[]{60, 240};
        }
    }

    private void updateMaPickNumberPicker(android.widget.NumberPicker np, String tf, int selectedIndex) {
        if (np == null) return;

        String interval = mapTfToInterval(tf); // 你現有的 mapTfToInterval()
        int[] p = getMa1Ma2PeriodsByInterval(interval);
        String[] labels = new String[]{"MA" + p[0], "MA" + p[1]};

        // NumberPicker 更新 displayed values 的正確順序（避免 IllegalArgumentException）
        np.setDisplayedValues(null);
        np.setMinValue(0);
        np.setMaxValue(labels.length - 1);
        np.setDisplayedValues(labels);

        np.setValue(Math.max(0, Math.min(1, selectedIndex)));
    }

    // pick: 0=MA1, 1=MA2
    private int resolveMaWindowByTfAndPick(String tf, int pick) {
        String interval = mapTfToInterval(tf); // 你已經有 mapTfToInterval()
        int[] p = getMa1Ma2PeriodsByInterval(interval);
        return (pick == 0) ? p[0] : p[1];
    }
    private void ensureTickerFileThenStart(ScreenerMode mode) {
        java.io.File f = TwTickerRepository.getCacheFile(this); // 你前面已加
        boolean needScrape = (f == null || !f.exists());

        if (needScrape) {
            showTickerScrapeDialogThen(() -> prepareIndustryThenStartScreening(mode));
        } else {
            prepareIndustryThenStartScreening(mode);
        }
    }
    // 你原本就有 clampInt；這裡避免你沒有 clampLearned
    private void prepareIndustryThenStartScreening(ScreenerMode mode) {
        if (isScreening) return;

        // 先把 UI 鎖住，避免使用者亂點（也可不鎖，看你喜好）
        setControlsEnabled(false);
        if (screenerButton != null) screenerButton.setText("…");

        new Thread(() -> {
            List<TickerInfo> tickers = TwTickerRepository.loadOrScrape(getApplicationContext());
            if (tickers == null || tickers.isEmpty()) {
                String reasonTmp = TwTickerRepository.getLastError();
                final String reasonFinal =
                        (reasonTmp == null || reasonTmp.trim().isEmpty())
                                ? getString(R.string.error_ticker_list_empty)
                                : reasonTmp;

                runOnUiThread(() -> {
                    // 還原 UI
                    if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
                    setControlsEnabled(true);
                    Toast.makeText(MainActivity.this, reasonFinal, Toast.LENGTH_LONG).show();
                });
                return;
            }

            // 收集產業列表（去空白、排序）
            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            for (TickerInfo ti : tickers) {
                if (ti == null) continue;
                String ind = (ti.industry == null) ? "" : ti.industry.trim();
                if (!ind.isEmpty()) set.add(ind);
            }

            final String[] items = set.toArray(new String[0]);
            runOnUiThread(() -> showIndustryPickerDialogThenStart(mode, items));
        }).start();
    }
    private void showIndustryPickerDialogThenStart(ScreenerMode mode, String[] industries) {
        final ScreenerMode modeFinal = mode;
        final String[] industriesFinal = (industries == null) ? new String[0] : industries;

        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String s : industriesFinal) {
            if (s == null) continue;
            String ind = s.trim();
            if (ind.isEmpty()) continue;
            if (ind.startsWith("上市")) continue;
            list.add(ind);
        }
        final String[] items = list.toArray(new String[0]);

        if (items.length == 0) {
            selectedIndustries.clear();
            saveSelectedIndustriesToPrefs();
            if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
            setControlsEnabled(true);
            startScreening(mode);
            return;
        }

        loadSelectedIndustriesFromPrefs();

        final boolean[] checked = new boolean[items.length];
        if (selectedIndustries.isEmpty()) {
            for (int i = 0; i < checked.length; i++) checked[i] = true;
        } else {
            for (int i = 0; i < items.length; i++) checked[i] = selectedIndustries.contains(items[i]);
        }

        final java.util.HashSet<String> tmp = new java.util.HashSet<>();
        for (int i = 0; i < items.length; i++) if (checked[i]) tmp.add(items[i]);

        // ---- 自訂 title：標題 + [全選(勾選)] ----
        android.widget.LinearLayout titleBar = new android.widget.LinearLayout(this);
        titleBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int pad = Math.round(12f * getResources().getDisplayMetrics().density);
        titleBar.setPadding(pad, pad, pad, pad);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText(getString(R.string.industry_picker_title));
        //tvTitle.setTextColor(android.graphics.Color.WHITE);

        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        android.widget.LinearLayout.LayoutParams lpTitle =
                new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(lpTitle);

        // 用 CheckBox 當「全選」開關
        androidx.appcompat.widget.AppCompatCheckBox cbAll = new androidx.appcompat.widget.AppCompatCheckBox(this);
        cbAll.setText(getString(R.string.btn_select_all)); // 文字可沿用你原本的 "全選"
       // cbAll.setTextColor(android.graphics.Color.WHITE);

        titleBar.addView(tvTitle);
        titleBar.addView(cbAll);

        // guard：避免我們「程式同步全選狀態」時，反過來觸發 cbAll listener 做全清/全選
        final boolean[] suppressCbAll = new boolean[] { false };

        // 初始化 cbAll 狀態：全部都勾 => cbAll 勾上，否則不勾
        boolean initAll = (tmp.size() == items.length);
        cbAll.setChecked(initAll);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setCustomTitle(titleBar)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                    String ind = items[which];
                    if (isChecked) tmp.add(ind); else tmp.remove(ind);

                    // 同步「全選」狀態（但不要觸發它去清空全部）
                    boolean allNow = (tmp.size() == items.length);
                    suppressCbAll[0] = true;
                    cbAll.setChecked(allNow);
                    suppressCbAll[0] = false;
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    selectedIndustries.clear();
                    selectedIndustries.addAll(tmp);
                    saveSelectedIndustriesToPrefs();

                    if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
                    setControlsEnabled(true);
                    startScreening(mode);
                })
                .setNeutralButton(R.string.screener_pick_from_file, null) // ✅ 新增
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
                    setControlsEnabled(true);
                })
                .create();

        dlg.show();
        android.widget.Button btnFromFile = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
        btnFromFile.setOnClickListener(v -> {
            dlg.dismiss();

            openCsvFilePickerForScreening(file -> {
                java.util.List<String> tickers = readFirstColumnTickersFromCsv(file);
                if (tickers == null || tickers.isEmpty()) {
                    Toast.makeText(this, getString(R.string.error_csv_no_tickers, file.getName()), Toast.LENGTH_LONG).show();
                    // 回到產業選單
                    showIndustryPickerDialogThenStart(modeFinal, industriesFinal);
                    return;
                }

                // ✅ 記住這次要用檔案做篩選
                pendingTickerCsvPathForScreening = file.getAbsolutePath();

                if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
                setControlsEnabled(true);
                startScreening(mode);

            }, () -> {
                // 取消選檔：回到產業選單
                showIndustryPickerDialogThenStart(modeFinal, industriesFinal);
            });
        });
        android.widget.ListView lv = dlg.getListView();

        // 「全選」勾選邏輯：勾上 => 全選；取消 => 全不選
        cbAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressCbAll[0]) return;

            tmp.clear();
            if (isChecked) {
                for (int i = 0; i < items.length; i++) {
                    checked[i] = true;
                    lv.setItemChecked(i, true);
                    tmp.add(items[i]);
                }
            } else {
                for (int i = 0; i < items.length; i++) {
                    checked[i] = false;
                    lv.setItemChecked(i, false);
                }
            }
        });
    }
    private void openCsvFilePickerForScreening(java.util.function.Consumer<java.io.File> onPicked,
                                               Runnable onCancel) {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, getString(R.string.error_csv_dir_unavailable), Toast.LENGTH_LONG).show();
            if (onCancel != null) onCancel.run();
            return;
        }

        java.io.File[] files = dir.listFiles((d, name) ->
                name != null && name.toLowerCase(java.util.Locale.US).endsWith(".csv"));

        if (files == null || files.length == 0) {
            Toast.makeText(this, getString(R.string.error_csv_not_found, dir.getAbsolutePath()), Toast.LENGTH_LONG).show();
            if (onCancel != null) onCancel.run();
            return;
        }

        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        android.widget.ListView lv = new android.widget.ListView(this);
        java.util.ArrayList<java.io.File> fileList = new java.util.ArrayList<>();
        for (java.io.File f : files) fileList.add(f);

        android.widget.ArrayAdapter<java.io.File> adapter =
                new android.widget.ArrayAdapter<java.io.File>(this, android.R.layout.simple_list_item_1, fileList) {
                    @Override public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                        android.view.View v = super.getView(position, convertView, parent);
                        android.widget.TextView tv = v.findViewById(android.R.id.text1);
                        java.io.File f = getItem(position);
                        tv.setText(f != null ? f.getName() : "");
                        return v;
                    }
                };
        lv.setAdapter(adapter);

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.screener_pick_csv_title)
                .setView(lv)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (onCancel != null) onCancel.run();
                })
                .create();

        lv.setOnItemClickListener((p, v, which, id) -> {
            java.io.File f = fileList.get(which);
            dlg.dismiss();
            if (onPicked != null) onPicked.accept(f);
        });

        dlg.show();
    }
    private void showFilteredAt(int idx, boolean forceDownload) {
        if (screenerResults.isEmpty()) return;

        int n = screenerResults.size();
        screenerIndex = (idx % n + n) % n;

        String t = screenerResults.get(screenerIndex).ticker;

        suppressNextStockIdFocusFetch = true;
        stockIdEditText.setText(t);

        // ✅ 輪播換主股票前：記住目前 K 柱像素寬度，下一輪重畫後還原
        rememberMainCandlePixelWidthForNextRedraw();

        executeFetchForFilteredTicker(t, forceDownload);

        carouselActive = true;
        updateTopOutsideMainChartUi();
    }
    private String getScreenerModeLabel(ScreenerMode mode) {
        if (mode == null) return "";

        switch (mode) {
            case LT20:
                return getString(
                        R.string.screener_params_lt20,
                        activeLtThr,
                        activeLtDays
                );

            case GT45:
                return getString(
                        R.string.screener_params_gt45,
                        activeGtThr,
                        activeGtMin,
                        activeGtMax
                );

            case MA60_3PCT:
                return getString(
                        R.string.screener_params_ma60,
                        activeMaBandPct,
                        activeMaDays
                );

            case MACD_DIV_RECENT:
                return getString(
                        R.string.screener_params_macd_div_recent,
                        activeMacdDivBars,
                        activeMacdDivTf,
                        activeMacdDivSide
                );

            case KD_GC_RECENT:
                return getString(
                        R.string.screener_params_kd_gc_recent,
                        activeKdGcBars,
                        activeKdGcTf
                );

            default:
                return mode.name();
        }
    }

    private void executeFetchForFilteredTicker(String ticker, boolean forceDownload) {
        // 你現有 fetchStockDataWithFallback 本身會抓主股、再抓對比
        // 若你想要 "forceDownload"，可在 YahooFinanceFetcher 加 cache key 或提供 bypass cache。
        long start = getStartTimeLimit(currentInterval, true);
        fetchStockDataWithFallback(ticker, currentInterval, start);
    }
    private List<ScreenerResult> readScreenerResultsFromCsv(String csvPath) {
        List<ScreenerResult> out = new ArrayList<>();
        if (csvPath == null || csvPath.trim().isEmpty()) return out;

        java.io.File f = new java.io.File(csvPath);
        if (!f.exists()) return out;

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            if (headerLine == null) return out;
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1); // BOM

            String[] header = splitCsvLine(headerLine);
            java.util.Map<String, Integer> col = new java.util.HashMap<>();
            for (int i = 0; i < header.length; i++) {
                String key = header[i] == null ? "" : header[i].trim().toLowerCase(Locale.US);
                if (!key.isEmpty()) col.put(key, i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] cols = splitCsvLine(line);

                String ticker = getCol(cols, col, "ticker");
                if (ticker == null || ticker.trim().isEmpty()) continue;

                String name = nz(getCol(cols, col, "name"));
                String industry = nz(getCol(cols, col, "industry"));

                double avgClose60 = parseDoubleOrNaN(getCol(cols, col, "avgclose60"));
                double latestClose = parseDoubleOrNaN(getCol(cols, col, "latestclose"));

                // 依輸出模式，可能是 LastK40 / Last_K9 / LastK
                Double lastK = parseNullableDouble(
                        firstNonEmpty(
                                getCol(cols, col, "lastk40"),
                                getCol(cols, col, "last_k9"),
                                getCol(cols, col, "lastk")
                        )
                );

                Double lastD = parseNullableDouble(
                        firstNonEmpty(
                                getCol(cols, col, "last_d9"),
                                getCol(cols, col, "lastd")
                        )
                );

                Integer runDays = parseNullableInt(
                        firstNonEmpty(
                                getCol(cols, col, "kd45_rundays"),
                                getCol(cols, col, "rundays")
                        )
                );

                Double ma60 = parseNullableDouble(
                        firstNonEmpty(
                                getCol(cols, col, "ma_60"),
                                getCol(cols, col, "ma60")
                        )
                );

                Double ma60DiffPct = parseNullableDouble(
                        firstNonEmpty(
                                getCol(cols, col, "ma60_diffpct"),
                                getCol(cols, col, "ma60diffpct")
                        )
                );

                String crossDate = nzOrNull(
                        firstNonEmpty(
                                getCol(cols, col, "cross_date"),
                                getCol(cols, col, "crossdate")
                        )
                );

                ScreenerResult r = new ScreenerResult(
                        ticker.trim().toUpperCase(Locale.US),
                        name,
                        industry,
                        avgClose60,
                        latestClose,
                        lastK,
                        lastD,
                        runDays,
                        ma60,
                        ma60DiffPct,
                        crossDate
                );
                out.add(r);
            }

        } catch (Exception e) {
            Log.w(TAG, "readScreenerResultsFromCsv failed: " + e.getMessage());
        }

        return out;
    }

    private String getCol(String[] cols, java.util.Map<String, Integer> col, String keyLower) {
        Integer i = col.get(keyLower);
        if (i == null) return null;
        if (i < 0 || i >= cols.length) return null;
        return cols[i] == null ? null : cols[i].trim();
    }

    private String nz(String s) { return s == null ? "" : s.trim(); }
    private String nzOrNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private String firstNonEmpty(String... ss) {
        if (ss == null) return null;
        for (String s : ss) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return null;
    }

    private double parseDoubleOrNaN(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s.trim()); }
        catch (Exception ignored) { return Double.NaN; }
    }

    private Double parseNullableDouble(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (Exception ignored) { return null; }
    }

    private Integer parseNullableInt(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (Exception ignored) { return null; }
    }
    private void navFiltered(int step) {
        if (isScreening || screenerResults.isEmpty()) return;
        showFilteredAt(screenerIndex + step, true);
    }

    private final android.content.BroadcastReceiver screenerReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if (intent == null) return;

                    String a = intent.getAction();
                    if (a == null) return;

                    if (ScreenerForegroundService.ACTION_PROGRESS.equals(a)) {
                        int done = intent.getIntExtra(ScreenerForegroundService.EXTRA_DONE, 0);
                        int total = intent.getIntExtra(ScreenerForegroundService.EXTRA_TOTAL, 0);

                        int pct = (total <= 0) ? 0 : Math.round(done * 100f / total);
                        if (screenerButton != null) {
                            screenerButton.setText(getString(R.string.toast_screener_progress, pct));
                        }
                        return;
                    }

                    if (ScreenerForegroundService.ACTION_FAIL.equals(a)) {
                        isScreening = false;
                        setControlsEnabled(true);
                        if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));

                        String err = intent.getStringExtra(ScreenerForegroundService.EXTRA_ERROR);
                        Toast.makeText(MainActivity.this, err == null ? "Failed" : err, Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (ScreenerForegroundService.ACTION_DONE.equals(a)) {
                        isScreening = false;
                        setControlsEnabled(true);
                        if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));

                        pendingExportCsv = intent.getStringExtra(ScreenerForegroundService.EXTRA_CSV_PATH);

                        java.io.File tmpFile = null;
                        if (pendingExportCsv != null && !pendingExportCsv.trim().isEmpty()) {
                            tmpFile = new java.io.File(pendingExportCsv);
                        }

                        // 先讀進記憶體：即使取消刪檔，也能照樣顯示結果
                        List<ScreenerResult> results = readScreenerResultsFromCsv(pendingExportCsv);

                        screenerResults.clear();
                        if (results != null) screenerResults.addAll(results);

                        screenerIndex = 0;
                        screenerSessionClosed = false;

                        if (screenerResults.isEmpty()) {
                            // 沒結果就不必存檔，順便刪 tmp
                            if (tmpFile != null) {
                                try { tmpFile.delete(); } catch (Exception ignored) {}
                            }
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.toast_screener_none, getScreenerModeLabel(screenerMode)),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }

                        Runnable continueAfterDone = () -> {
                            allowSaveOnSwipeUp = (pendingExportCsv != null && !pendingExportCsv.trim().isEmpty());

                            String modeLabel = getScreenerModeLabel(screenerMode);
                            String paramsLine = getActiveParamsText(screenerMode);
                            int count = screenerResults.size();
                            String msg = (screenerMode == ScreenerMode.LT20
                                    || screenerMode == ScreenerMode.GT45
                                    || screenerMode == ScreenerMode.MA60_3PCT
                                    || screenerMode == ScreenerMode.MACD_DIV_RECENT
                                    || screenerMode == ScreenerMode.KD_GC_RECENT)
                                    ? getString(R.string.dialog_screener_done_msg_with_params, "", paramsLine, count)
                                    : getString(R.string.dialog_screener_done_msg_with_params, modeLabel, paramsLine, count);

                            new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.dialog_screener_done_title)
                                    .setMessage(msg)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();

                            applyIntervalForScreener(screenerMode);
                            showFilteredAt(0, true);
                            carouselActive = true;
                            updateCarouselPosUi();
                        };

                        // ✅ 存檔前先問檔名（預填原本自動檔名）
                        finalizeScreenerExportWithPrompt(
                                tmpFile,
                                (tmpFile != null ? tmpFile.getName() : "TW_SCREENER.csv"),
                                finalFileOrNull -> {
                                    if (finalFileOrNull == null) {
                                        // 取消：不存檔
                                        pendingExportCsv = null;
                                    } else {
                                        // OK：存檔成功（可能同名保留或改名搬移後的檔）
                                        pendingExportCsv = finalFileOrNull.getAbsolutePath();
                                    }
                                    continueAfterDone.run();
                                }
                        );
                    }
                }
            };

    @Override
    public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {
    }
    private void finalizeScreenerExportWithPrompt(
            @androidx.annotation.Nullable java.io.File tmpFile,
            String suggestedName,
            java.util.function.Consumer<java.io.File> onFinish
    ) {
        // 沒 tmp 檔：就當作取消，繼續後續操作
        if (tmpFile == null || !tmpFile.exists()) {
            if (onFinish != null) onFinish.accept(null);
            return;
        }

        java.io.File dir = tmpFile.getParentFile();
        if (dir == null || !dir.exists()) {
            if (onFinish != null) onFinish.accept(null);
            return;
        }

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);

        String sug = (suggestedName == null) ? "" : suggestedName.trim();
        if (sug.isEmpty()) sug = tmpFile.getName();
        if (!sug.toLowerCase(java.util.Locale.US).endsWith(".csv")) sug += ".csv";

        et.setText(sug);
        if (et.getText() != null) et.setSelection(et.getText().length());

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_screener_save_title)
                .setMessage(R.string.dialog_screener_save_msg)
                .setView(et)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    // ✅ 取消：刪 tmp，不存檔，但繼續後續操作
                    try { tmpFile.delete(); } catch (Exception ignored) {}
                    // ✅ 取消：輪播檔名就沒有（或你想保留舊的也行）
                    activeCarouselFile = null;
                    // 不要硬設 carouselActive=true，讓 showFilteredAt 決定
                    updateTopOutsideMainChartUi();
                    if (onFinish != null) onFinish.accept(null);
                })
                .setPositiveButton(android.R.string.ok, null) // show 後接管
                .create();

        dlg.show();

        android.widget.Button okBtn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        okBtn.setOnClickListener(v -> {
            String input = (et.getText() == null) ? "" : et.getText().toString().trim();
            String fileName = sanitizeCsvFileName(input);
            if (fileName == null) {
                Toast.makeText(this, getString(R.string.error_csv_invalid_filename), Toast.LENGTH_LONG).show();
                return;
            }

            java.io.File outFile = new java.io.File(dir, fileName);

            // ✅ 允許檔名不改：同一個檔就直接保留，不用搬移
            boolean sameFile = outFile.getAbsolutePath().equals(tmpFile.getAbsolutePath());

            if (!sameFile && outFile.exists()) {
                Toast.makeText(this, getString(R.string.error_csv_name_exists, outFile.getName()), Toast.LENGTH_LONG).show();
                return;
            }

            boolean ok;
            java.io.File finalFile;

            if (sameFile) {
                ok = true;
                finalFile = tmpFile;
            } else {
                ok = moveFile(tmpFile, outFile); // rename 失敗就 copy+delete
                finalFile = outFile;
            }

            if (!ok) {
                Toast.makeText(this, getString(R.string.error_csv_save_failed), Toast.LENGTH_LONG).show();
                return;
            }

            Toast.makeText(this, getString(R.string.toast_csv_saved, finalFile.getName()), Toast.LENGTH_SHORT).show();

            // ✅ 檔名開頭「最愛」：用此檔案內容取代最愛清單，且後續 ♥/♡ 寫入同一檔
            if (isFavoritesCsvFileName(finalFile.getName())) {
                setActiveFavoritesFileAndReload(finalFile); // 這個方法必須會 activeFavoritesFile = finalFile
                Toast.makeText(this,
                        getString(R.string.toast_favorites_replaced, finalFile.getName()),
                        Toast.LENGTH_SHORT).show();
            }

            activeCarouselFile = finalFile;
            updateTopOutsideMainChartUi();

            dlg.dismiss();
            if (onFinish != null) onFinish.accept(finalFile);
        });
    }
    private void cancelScreeningService() {
        android.content.Intent it = new android.content.Intent(this, ScreenerForegroundService.class);
        it.setAction(ScreenerForegroundService.ACTION_CANCEL);

        // 用 startService 送 CANCEL 指令即可（Service 端接到後自行 stopSelf）
        startService(it);
    }
    private void onSwipeUpEsc() {
        // 篩選進行中：上滑 => 取消篩選
        if (isScreening) {
           // screenerCancelled.set(true);
            cancelScreeningService();

            Toast.makeText(this, getString(R.string.toast_screener_cancelled), Toast.LENGTH_SHORT).show();
            return;
        }

        if (screenerResults.isEmpty()) return;

        // CSV list 模式你已設 allowSaveOnSwipeUp=false；這裡就當作純結束提示也可以
        if (!screenerSessionClosed) {
            screenerSessionClosed = true;

            // ✅ 提示已存檔位置（若有）
            if (pendingExportCsv != null && !pendingExportCsv.trim().isEmpty()) {
                Toast.makeText(this, "Saved: " + pendingExportCsv, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Screening session ended", Toast.LENGTH_SHORT).show();
            }
        } else {
            // 已結束過就再提示一次（可省略）
            if (pendingExportCsv != null && !pendingExportCsv.trim().isEmpty()) {
                Toast.makeText(this, "Saved: " + pendingExportCsv, Toast.LENGTH_LONG).show();
            }
        }
    }
    private void initCharts() {
        setupMainChart();
        setupk_kdChart();
        setupIndicatorChart();

        mainChart.setOnChartGestureListener(this);

        // ✅ 導航手勢改掛在第二圖區（k_kdChart）
        initNavGesturesOnKdChart();
    }

    private void initNavGesturesOnKdChart() {
        navGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // ✅ 單點：切換 KD/對比/同時顯示
                cycleKkdViewMode();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (screenerResults.isEmpty()) return false;

                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float absX = Math.abs(dx);
                float absY = Math.abs(dy);

                final float MIN_PX = 120f;

                // 向右滑/向左滑：換股
                if (absX > absY && absX > MIN_PX) {
                    if (dx > 0) navFiltered(+1);  // 右滑 => 下一檔
                    else        navFiltered(-1);  // 左滑 => 上一檔
                    return true;
                }

                // 向上滑：結束/提示
                if (absY > absX && absY > MIN_PX && dy < 0) {
                    onSwipeUpEsc();
                    return true;
                }

                return false;
            }
        });

        // ✅ 只保留這個 listener（統一處理 fling + tap）
        k_kdChart.setEnabled(true);
        k_kdChart.setClickable(true);
        k_kdChart.setTouchEnabled(true);
        k_kdChart.setDragEnabled(false);
        k_kdChart.setScaleEnabled(false);
        k_kdChart.setHighlightPerTapEnabled(false);
        k_kdChart.setHighlightPerDragEnabled(false);

        k_kdChart.setOnTouchListener((v, ev) -> {
            v.onTouchEvent(ev);

            kkdTapDetector.onTouchEvent(ev);   // 單點切換 KD/VOL/COMP
            kkdSwipeDetector.onTouchEvent(ev); // ✅ 左右滑開始輪播/換下一檔

            if (ev.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });
    }
    private boolean applyLanguagePolicyBySystemLocaleAndReturnIfChanged() {
        java.util.Locale sys;

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.app.LocaleManager lm = getSystemService(android.app.LocaleManager.class);
            android.os.LocaleList sysLocales = (lm != null) ? lm.getSystemLocales()
                    : android.content.res.Resources.getSystem().getConfiguration().getLocales();
            sys = sysLocales.get(0);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.os.LocaleList sysLocales = android.content.res.Resources.getSystem().getConfiguration().getLocales();
            sys = sysLocales.get(0);
        } else {
            sys = android.content.res.Resources.getSystem().getConfiguration().locale;
        }

        // ✅ 你的政策：只要系統語言是中文（含簡中），就強制 zh-TW；其他一律英文
        boolean wantZhTw = "zh".equalsIgnoreCase(sys.getLanguage());

        androidx.core.os.LocaleListCompat target = wantZhTw
                ? androidx.core.os.LocaleListCompat.forLanguageTags("zh-TW")
                : androidx.core.os.LocaleListCompat.forLanguageTags("en");

        String currentTags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags();
        String targetTags = target.toLanguageTags();

        if (!targetTags.equals(currentTags)) {
            pendingInitAfterLocaleChange = true;
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(target);
            return true; // 會觸發重建，這輪不要再做初始化
        }
        return false;
    }

    private void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showDatePickerDialog() {
        final Calendar calendar = Calendar.getInstance();
        String currentData = startDateEditText.getText().toString().trim();

        if (currentData.isEmpty()) {
            long defaultTime = getStartTimeLimit(currentInterval, true);
            calendar.setTimeInMillis(defaultTime * 1000L);
        } else {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(currentData);
                if (d != null) calendar.setTime(d);
            } catch (Exception ignored) {}
        }

        DatePickerDialog dlg = new DatePickerDialog(
                MainActivity.this,
                (view, year, month, dayOfMonth) -> {
                    String selectedDate = String.format(Locale.US, "%d-%02d-%02d", year, month + 1, dayOfMonth);
                    startDateEditText.setText(selectedDate);

                    try {
                        Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(selectedDate);
                        if (d != null) fetchStockDataWithFallback(currentStockId, currentInterval, d.getTime() / 1000);
                    } catch (Exception e) {
                        Log.e(TAG, "解析日期失敗: " + e.getMessage());
                    }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dlg.show();
    }

    // 增加 boolean forceDefault 參數
    private long getStartTimeLimit(String interval, boolean forceDefault) {
        if (!forceDefault && startDateEditText != null) {
            String inputDate = startDateEditText.getText().toString().trim();
            if (!inputDate.isEmpty()) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                    sdf.setLenient(false);
                    Date parsedDate = sdf.parse(inputDate);
                    if (parsedDate != null) return parsedDate.getTime() / 1000;
                } catch (Exception e) {
                    Log.e("StartTimeLimit", "解析失敗，改用公式");
                }
            }
        }

        Calendar cal = Calendar.getInstance();
        switch (interval) {
            case "1d":  cal.add(Calendar.DAY_OF_YEAR, -730); break;
            case "1wk": cal.add(Calendar.WEEK_OF_YEAR, -300); break;
            case "1mo": cal.add(Calendar.MONTH, -180); break;
            default:    cal.add(Calendar.DAY_OF_YEAR, -365); break;
        }
        return cal.getTimeInMillis() / 1000;
    }

    private String determineComparisonSymbol(String stockId) {
        String cleanId = stockId.toUpperCase(Locale.US).trim();
        if (cleanId.equals("^TWII")) return "^TWOII";
        if (cleanId.equals("^TWOII")) return "^TWII";
        if (cleanId.endsWith(".TWO")) return "^TWOII";
        return "^TWII";
    }

    // 統一：若是 4,5 位數字且無後綴，走 .TW / .TWO fallback；否則直接抓
    private void fetchSymbolAutoFallback(String symbol, String interval, long startTime,
                                         YahooFinanceFetcher.DataFetchListener listener) {
        String base = symbol.toUpperCase(Locale.US).trim();

        boolean needFallback = !base.startsWith("^")
                && !base.contains(".")
                && base.matches("^\\d{4,5}$");  // ✅ 4或5位都補尾碼

        if (!needFallback) {
            yahooFinanceFetcher.fetchStockDataAsync(base, interval, startTime, listener);
            return;
        }

        String tw = base + ".TW";
        yahooFinanceFetcher.fetchStockDataAsync(tw, interval, startTime, new YahooFinanceFetcher.DataFetchListener() {
            @Override public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                listener.onDataFetched(data, fetchedSymbol);
            }

            @Override public void onError(String errorMessage) {
                String two = base + ".TWO";
                yahooFinanceFetcher.fetchStockDataAsync(two, interval, startTime, new YahooFinanceFetcher.DataFetchListener() {
                    @Override public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                        listener.onDataFetched(data, fetchedSymbol);
                    }

                    @Override public void onError(String finalError) {
                        listener.onError(finalError);
                    }
                });
            }
        });
    }

    private void clearAllCharts(String mainNoDataText) {
        runOnUiThread(() -> {
            mainChart.clear();
            if (mainNoDataText != null) mainChart.setNoDataText(mainNoDataText);
            mainChart.invalidate();

            k_kdChart.clear();
            k_kdChart.invalidate();

            indicatorChart.clear();
            indicatorChart.invalidate();

            vpView.clear();
            vpView.invalidate();
        });
    }

    private void fetchStockDataWithFallback(String symbol, String interval, long startTime) {
        String baseSymbol = symbol.toUpperCase(Locale.US).trim();

        // 指數或已帶後綴：直接走 entry
        if (baseSymbol.startsWith("^") || baseSymbol.contains(".")) {
            fetchStockDataEntry(baseSymbol, interval, startTime);
            return;
        }

        fetchSymbolAutoFallback(baseSymbol, interval, startTime, new YahooFinanceFetcher.DataFetchListener() {
            @Override
            public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                if (!data.isEmpty()) {
                    Log.d(TAG, String.format(Locale.US,
                            "DOWNLOAD SUCCESS: Symbol: %s, FULL Data Start Date: %s, Size: %d",
                            fetchedSymbol, data.get(0).getDate(), data.size()));
                }
                handleSuccessfulFetch(fetchedSymbol, data, interval);
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, baseSymbol + " 載入失敗: " + errorMessage);
                clearAllCharts(getString(R.string.error_load_failed, baseSymbol, errorMessage));
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                getString(R.string.error_load_failed, baseSymbol, errorMessage),
                                Toast.LENGTH_LONG).show()
                );
            }
        });
    }

    private void fetchStockDataEntry(String symbol, String interval, long startTime) {
        currentStockId = symbol.toUpperCase(Locale.US).trim();
        currentInterval = interval;
        displayedComparisonSymbol = determineComparisonSymbol(currentStockId);
        fetchMainAndComparisonData(currentStockId, currentInterval, displayedComparisonSymbol, startTime);
    }

    private void handleSuccessfulFetch(String successfulSymbol, List<StockDayPrice> data, String interval) {
        runOnUiThread(() -> {
            fullPriceList = data;
            currentStockId = successfulSymbol;
            currentInterval = interval;
            displayedComparisonSymbol = determineComparisonSymbol(currentStockId);
            fetchComparisonDataOnly(currentStockId, data, currentInterval, displayedComparisonSymbol);
        });
    }

    private void fetchComparisonDataOnly(String mainSymbol, List<StockDayPrice> mainData,
                                         String interval, String comparisonSymbol) {
        final String baseSymbol = comparisonSymbol.toUpperCase(Locale.US).trim();

        runOnUiThread(() -> Toast.makeText(
                this,
                getString(R.string.toast_loading_comparison, mainSymbol, baseSymbol),
                Toast.LENGTH_SHORT
        ).show());

        final long startTimeLimit = getStartTimeLimit(interval, isSwitchingInterval);
        comparisonPriceList.clear();

        final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());

        fetchSymbolAutoFallback(baseSymbol, interval, startTimeLimit, new YahooFinanceFetcher.DataFetchListener() {
            @Override
            public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                if (!data.isEmpty()) {
                    Log.d("StockFetcher", String.format(Locale.US,
                            "COMP_DOWNLOAD SUCCESS: Symbol: %s, Start: %s, Size: %d",
                            fetchedSymbol, data.get(0).getDate(), data.size()));
                }
                comparisonPriceList = data;
                onComparisonFetchComplete(errorMessages);
            }

            @Override
            public void onError(String errorMessage) {
                Log.e("StockFetcher", "COMP_DOWNLOAD FAILED: " + baseSymbol + " Error: " + errorMessage);
                errorMessages.add(getString(R.string.error_load_failed, baseSymbol, errorMessage));
                onComparisonFetchComplete(errorMessages);
            }
        });
    }

    private synchronized void onComparisonFetchComplete(List<String> errorMessages) {
        runOnUiThread(() -> {
            if (!errorMessages.isEmpty()) {
                String fullErrorMsg = String.join("\n", errorMessages);
                Toast.makeText(MainActivity.this,
                        getString(R.string.error_load_failed, fullErrorMsg,""),
                        Toast.LENGTH_LONG).show();
                Log.w(TAG, "對比指數載入失敗，但主股成功。繼續繪製。");
            }

            if (comparisonStockIdEditText != null) comparisonStockIdEditText.setText(displayedComparisonSymbol);
            if (stockIdEditText != null) stockIdEditText.setText(currentStockId);

            if (fullPriceList != null && !fullPriceList.isEmpty()) drawCharts(fullPriceList, currentStockId);
        });
    }

    private void fetchMainAndComparisonData(String mainSymbol, String interval,
                                            String comparisonSymbol, long startTimeLimit) {

        Log.d(TAG, String.format(Locale.US, "開始請求主股 %s 和對比指數 %s，間隔 %s。",
                mainSymbol, comparisonSymbol, interval));

        Toast.makeText(this,
                getString(R.string.toast_loading, mainSymbol, comparisonSymbol, displayText[currentIntervalIndex]),
                Toast.LENGTH_SHORT).show();

        final AtomicInteger fetchCount = new AtomicInteger(0);
        final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());

        yahooFinanceFetcher.fetchStockDataAsync(mainSymbol, interval, startTimeLimit, new YahooFinanceFetcher.DataFetchListener() {
            @Override
            public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                if (!data.isEmpty()) {
                    Log.d(TAG, String.format(Locale.US,
                            "DOWNLOAD SUCCESS: Symbol: %s, FULL Data Start Date: %s, Size: %d",
                            fetchedSymbol, data.get(0).getDate(), data.size()));
                }
                fullPriceList = data;
                onFetchComplete(mainSymbol, null, fetchCount, errorMessages);
            }

            @Override
            public void onError(String errorMessage) {
                errorMessages.add(getString(R.string.error_load_failed, mainSymbol, errorMessage));
                onFetchComplete(mainSymbol, errorMessage, fetchCount, errorMessages);
            }
        });

        yahooFinanceFetcher.fetchStockDataAsync(comparisonSymbol, interval, startTimeLimit, new YahooFinanceFetcher.DataFetchListener() {
            @Override
            public void onDataFetched(List<StockDayPrice> data, String fetchedSymbol) {
                if (!data.isEmpty()) {
                    Log.d(TAG, String.format(Locale.US,
                            "DOWNLOAD SUCCESS: Symbol: %s, FULL Data Start Date: %s, Size: %d",
                            fetchedSymbol, data.get(0).getDate(), data.size()));
                }
                comparisonPriceList = data;
                onFetchComplete(comparisonSymbol, null, fetchCount, errorMessages);
            }

            @Override
            public void onError(String errorMessage) {
                errorMessages.add(getString(R.string.error_load_failed, comparisonSymbol, errorMessage));
                onFetchComplete(comparisonSymbol, errorMessage, fetchCount, errorMessages);
            }
        });
    }

    private synchronized void onFetchComplete(String symbol, String error,
                                              AtomicInteger fetchCount, List<String> errorMessages) {
        if (fetchCount.incrementAndGet() != 2) return;

        runOnUiThread(() -> {
            String fullErrorMsg = String.join("\n", errorMessages);

            if (fullPriceList.isEmpty()) {
                clearAllCharts(getString(R.string.error_load_failed, fullErrorMsg,""));
                return;
            }

            if (!errorMessages.isEmpty()) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.error_load_failed, fullErrorMsg, ""),
                        Toast.LENGTH_LONG).show();
                Log.w(TAG, "對比指數載入失敗，但主股成功。繼續繪製。");
            }

            stockIdEditText.setText(currentStockId);
            drawCharts(fullPriceList, currentStockId);
        });
    }

    // X 軸日期格式化
    private class MyXAxisFormatter extends ValueFormatter {
        private final List<StockDayPrice> displayedDataList;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault());
        private final SimpleDateFormat inFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

        public MyXAxisFormatter(List<StockDayPrice> displayedDataList) {
            this.displayedDataList = displayedDataList;
        }

        @Override
        public String getAxisLabel(float value, AxisBase axis) {
            int index = (int) value;
            if (index < 0 || index >= displayedDataList.size()) return "";
            String dateString = displayedDataList.get(index).getDate();
            try {
                Date date = inFormat.parse(dateString);
                return (date == null) ? dateString : dateFormat.format(date);
            } catch (Exception e) {
                Log.e(TAG, "日期格式轉換錯誤: " + e.getMessage());
                return dateString;
            }
        }
    }

    private GestureDetector vpTapDetector;

    private void setupMainChart() {
        mainChart.getDescription().setEnabled(false);
        mainChart.setDrawGridBackground(false);
        mainChart.setHighlightFullBarEnabled(false);
        mainChart.setBackgroundColor(Color.BLACK);
        mainChart.setMaxVisibleValueCount(100000);

        Legend legend = mainChart.getLegend();
        legend.setEnabled(true);
        legend.setTextColor(Color.WHITE);
        legend.setWordWrapEnabled(true);
        legend.setForm(Legend.LegendForm.SQUARE);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(true);
        legend.setXOffset(20f);
        legend.setYOffset(5f);
        scaleLegendTextSizeBy(legend, 1.4f);

        mainChart.setDrawOrder(new CombinedChart.DrawOrder[]{
                CombinedChart.DrawOrder.CANDLE,
                CombinedChart.DrawOrder.LINE,
                CombinedChart.DrawOrder.SCATTER
        });

        XAxis xAxis = mainChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGranularity(1f);
        xAxis.setTextColor(Color.WHITE);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setDrawLabels(false);

        YAxis leftAxis = mainChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setDrawAxisLine(true);
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);

        YAxis rightAxis = mainChart.getAxisRight();
        rightAxis.setEnabled(false);

        mainChart.setViewPortOffsets(CHART_OFFSET_LEFT, CHART_OFFSET_TOP, CHART_OFFSET_RIGHT, CHART_OFFSET_BOTTOM);

        // 保留單點顯示 marker / 十字線
        mainChart.setDrawMarkers(true);
        mainChart.setHighlightPerTapEnabled(true);

        // 你要拿 double tap 來切換的話，建議關掉圖表預設的 double tap zoom（避免衝突）
        mainChart.setDoubleTapToZoomEnabled(false);

        // 改成雙擊切換
        mainTapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                // GestureDetector 要能吃到後續事件，onDown 必須回 true
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                cycleMainViewMode();
                return true;
            }
        });

        // 讓 Chart 自己先處理觸控（單點 highlight/marker、拖曳、縮放...），我們只額外偵測 double tap
        mainChart.setOnTouchListener((v, ev) -> {
            v.onTouchEvent(ev);                 // 交回給圖表：單點 marker 十字線就會正常

            syncKkdHighlightFromMain();         // ✅ 新增：同步 k_kdChart 的 highlight 讓它的 Marker 畫數字

            mainTapDetector.onTouchEvent(ev);   // 只在 double tap 時切換
            if (ev.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });

        // vpView 蓋住 mainChart 時：也要支援「雙擊切換」，並且把觸控轉送給 mainChart 以保留 marker 十字線
        vpTapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                cycleMainViewMode();
                return true;
            }
        });

        vpView.setOnTouchListener((v, ev) -> {
            vpTapDetector.onTouchEvent(ev);

            // 轉送給 mainChart：讓你在 vpView 上單點也能出現 marker/十字線
            MotionEvent copy = MotionEvent.obtain(ev);

            // 若 vpView 與 mainChart 完全對齊，這個 offset 會是 0；加上它比較保險
            float dx = vpView.getLeft() - mainChart.getLeft();
            float dy = vpView.getTop() - mainChart.getTop();
            copy.offsetLocation(dx, dy);

            mainChart.onTouchEvent(copy);

            syncKkdHighlightFromMain();             // ✅ 新增：vpView 上點擊也同步 k_kdChart

            copy.recycle();

            if (ev.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });
    }
    private void syncKkdHighlightFromMain() {
        if (mainChart == null || k_kdChart == null) return;

        // 只在成交量模式才同步
        if (kkdViewMode != KkdViewMode.VOL) {
            k_kdChart.highlightValues(null);
            k_kdChart.invalidate();
            return;
        }

        Highlight[] hs = mainChart.getHighlighted();
        if (hs == null || hs.length == 0) {
            k_kdChart.highlightValues(null);
            k_kdChart.invalidate();
            return;
        }

        if (k_kdChart.getData() == null) return;

        int barDataIndex = findCombinedBarDataIndex(k_kdChart.getData());
        if (barDataIndex < 0) {
            k_kdChart.highlightValues(null);
            k_kdChart.invalidate();
            return;
        }

        int xInt = Math.round(hs[0].getX());

        if (lastDisplayedListForKkd == null || xInt < 0 || xInt >= lastDisplayedListForKkd.size()) {
            k_kdChart.highlightValues(null);
            k_kdChart.invalidate();
            return;
        }

        float x = (float) xInt;

// ✅ y 要用「張」：要跟你 BarEntry 的 y 單位一致
        final float VOLUME_DIVISOR = 1000f; // 若你的 BarEntry 本來就用張，保持；若用股數就改 1f
        float yLots = (float) (lastDisplayedListForKkd.get(xInt).getVolume() / VOLUME_DIVISOR);

        Highlight hh = new Highlight(
                x, yLots,          // ✅ 關鍵：y 不要 0，改成實際 bar 的 y
                0f, 0f,
                0,                 // BarDataSet index
                YAxis.AxisDependency.LEFT
        );
        hh.setDataIndex(barDataIndex);

        // 保險：如果 setDataIndex 沒生效（極少數），就不要 highlightValue
        if (hh.getDataIndex() < 0) {
            k_kdChart.highlightValues(null);
            k_kdChart.invalidate();
            return;
        }

        k_kdChart.highlightValue(hh, false);
        k_kdChart.invalidate();
    }
    private int findCombinedBarDataIndex(@androidx.annotation.Nullable com.github.mikephil.charting.data.ChartData<?> data) {
        if (!(data instanceof com.github.mikephil.charting.data.CombinedData)) return -1;

        com.github.mikephil.charting.data.CombinedData cd =
                (com.github.mikephil.charting.data.CombinedData) data;

        java.util.List<com.github.mikephil.charting.data.BarLineScatterCandleBubbleData> all = cd.getAllData();
        if (all == null) return -1;

        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof com.github.mikephil.charting.data.BarData) return i;
        }
        return -1;
    }
    private void cycleMainViewMode() {
        currentViewModeIndex = (currentViewModeIndex + 1) % VIEW_MODES.length;
        if (lastDisplayedListForMain != null) {
            redrawMainOnly(lastDisplayedListForMain);
        }
    }

    private void redrawMainOnly(List<StockDayPrice> displayedList) {
        String mode = VIEW_MODES[currentViewModeIndex];

        if (mode.equals("ALL") || mode.equals("VP")) {
            vpView.setVisibility(View.VISIBLE);
            vpView.setVolumeProfile(volumeProfileData, minPriceGlobal, bucketSizeGlobal, NUM_PRICE_BUCKETS);
        } else {
            vpView.setVisibility(View.GONE);
            vpView.clear();
        }

        drawMainChartData(displayedList);
        // ✅ 每次主圖畫完後更新愛心 ♥/♡
        updateFavoriteButtonState();
        updateGapLabelVisibility();
        mainChart.invalidate();
        vpView.invalidate();
    }
    private void scaleLegendTextSizeBy(Legend legend, float factor) {
        float density = getResources().getDisplayMetrics().density;
        float curDp = legend.getTextSize() / density;   // px -> dp
        legend.setTextSize(curDp * factor);             // setTextSize 需要 dp
    }
    private GestureDetector kkdTapDetector;

    private void setupk_kdChart() {
        k_kdChart.getDescription().setEnabled(false);
        k_kdChart.setNoDataText("KD / 成交量(張) / 對比 K 線圖");
        k_kdChart.setBackgroundColor(Color.BLACK);

        Legend legend = k_kdChart.getLegend();
        legend.setEnabled(true);
        legend.setTextColor(Color.WHITE);
        legend.setWordWrapEnabled(true);
        legend.setForm(Legend.LegendForm.SQUARE);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(true);
        legend.setXOffset(20f);
        legend.setYOffset(0f);
        scaleLegendTextSizeBy(legend, 1.4f);

        // ✅ 加入 BAR
        k_kdChart.setDrawOrder(new CombinedChart.DrawOrder[]{
                CombinedChart.DrawOrder.BAR,
                CombinedChart.DrawOrder.CANDLE,
                CombinedChart.DrawOrder.LINE
        });

        XAxis xAxis = k_kdChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setDrawLabels(false);
        xAxis.setGranularity(1f);
        xAxis.setTextColor(Color.WHITE);

        YAxis leftAxis = k_kdChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setDrawLabels(true);
        leftAxis.setDrawAxisLine(true);
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.removeAllLimitLines();
        leftAxis.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);
        leftAxis.setEnabled(true);

        // ✅ 右軸永遠關閉
        YAxis rightAxis = k_kdChart.getAxisRight();
        rightAxis.setEnabled(false);

        k_kdChart.setViewPortOffsets(CHART_OFFSET_LEFT, CHART_OFFSET_TOP, CHART_OFFSET_RIGHT, CHART_OFFSET_BOTTOM);

        // 單點拿來切換，避免 highlight 干擾
        k_kdChart.setHighlightPerTapEnabled(false);

        kkdTapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                cycleKkdViewMode();
                return true;
            }
        });

        k_kdChart.setOnTouchListener((v, ev) -> {
            v.onTouchEvent(ev);             // 保留拖曳/縮放等
            kkdTapDetector.onTouchEvent(ev);// 單點切換
            if (ev.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });

        final int minFling = android.view.ViewConfiguration.get(this).getScaledMinimumFlingVelocity();

        kkdSwipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                if (Math.abs(dx) < Math.abs(dy) * 1.3f) return false;
                if (Math.abs(velocityX) < minFling) return false;

                boolean wasCarousel = carouselActive;

                // ✅ 尚未輪播：先啟動輪播（用最愛檔），但這次不加減序號
                if (!wasCarousel) {
                    startCarouselFromFavoritesIfNeeded();
                    if (!carouselActive) return false; // 最愛清單為空等情況

                    // 第一次進入輪播：顯示目前 index（通常是第 1 檔）
                    showFilteredAt(screenerIndex, true);
                    return true;
                }

                // ✅ 已在輪播：向右 +1，向左 -1
                int step = (dx > 0) ? 1 : -1;
                showFilteredAt(screenerIndex + step, true);
                return true;
            }
        });

        k_kdChart.setDrawMarkers(true);
        k_kdChart.setHighlightPerTapEnabled(false); // 仍然保留單點切換，不靠點擊出游標
        kkdVolumeMarker = new KkdVolumeMarkerView(this, lastDisplayedListForKkd);
        kkdVolumeMarker.setChartView(k_kdChart);
        k_kdChart.setMarker(kkdVolumeMarker);
    }
    private void cycleKkdViewMode() {
        switch (kkdViewMode) {
            case KD:   kkdViewMode = KkdViewMode.VOL;  break;
            case VOL:  kkdViewMode = KkdViewMode.COMP; break;
            case COMP: kkdViewMode = KkdViewMode.KD;   break;
        }

        // 只調整顯示，不重抓資料
        applyKkdViewModeToChart();

        //k_kdChart.fitScreen();
        k_kdChart.invalidate();
    }

    private void applyKkdViewModeToChart() {
        if (k_kdChart == null) return;

        // 右軸永遠關閉
        k_kdChart.getAxisRight().setEnabled(false);

        boolean showKd   = (kkdViewMode == KkdViewMode.KD);
        boolean showVol  = (kkdViewMode == KkdViewMode.VOL);
        boolean showComp = (kkdViewMode == KkdViewMode.COMP);

        // 1) KD Line 顯示/隱藏
        LineData ld = k_kdChart.getLineData();
        if (ld != null) {
            for (ILineDataSet s : ld.getDataSets()) {
                if (s instanceof LineDataSet) ((LineDataSet) s).setVisible(showKd);
            }
        }

        // 2) Volume Bar 顯示/隱藏
        BarData bd = k_kdChart.getBarData();
        if (bd != null) {
            for (IBarDataSet s : bd.getDataSets()) {
                if (s instanceof BarDataSet) ((BarDataSet) s).setVisible(showVol);
            }
        }

        // 3) Compare Candle 顯示/隱藏
        CandleData cd = k_kdChart.getCandleData();
        if (cd != null) {
            for (ICandleDataSet s : cd.getDataSets()) {
                if (s instanceof CandleDataSet) ((CandleDataSet) s).setVisible(showComp);
            }
        }

        // 4) 左軸依模式設定，且只有 KD 顯示 20/80 線
        YAxis left = k_kdChart.getAxisLeft();
        left.setEnabled(true);
        left.removeAllLimitLines();

        if (showKd) {
            left.setAxisMinimum(0f);
            left.setAxisMaximum(100f);

            LimitLine ll80 = new LimitLine(80f, "80");
            ll80.setLineColor(Color.RED);
            ll80.setLineWidth(0.8f);
            ll80.setTextColor(Color.RED);
            ll80.setTextSize(8f);

            LimitLine ll20 = new LimitLine(20f, "20");
            ll20.setLineColor(Color.GREEN);
            ll20.setLineWidth(0.8f);
            ll20.setTextColor(Color.GREEN);
            ll20.setTextSize(8f);

            left.addLimitLine(ll80);
            left.addLimitLine(ll20);

        } else if (showVol) {
            // 用 displayed list 算 max lots
            float maxLots = 0f;
            if (lastDisplayedListForKkd != null) {
                for (StockDayPrice p : lastDisplayedListForKkd) {
                    float lots = (float) (p.getVolume() / VOLUME_DIVISOR);
                    if (lots > maxLots) maxLots = lots;
                }
            }
            left.setAxisMinimum(0f);
            left.setAxisMaximum(Math.max(10f, maxLots * 1.2f));

        } else { // showComp
            // 用 candle entries 算 min/max（避免再對齊一次）
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            boolean ok = false;

            CandleData candleData = k_kdChart.getCandleData();
            if (candleData != null && candleData.getDataSetCount() > 0) {
                ICandleDataSet ds = candleData.getDataSetByIndex(0);
                if (ds != null && ds.getEntryCount() > 0) {
                    for (int i = 0; i < ds.getEntryCount(); i++) {
                        CandleEntry e = ds.getEntryForIndex(i);
                        if (e == null) continue;
                        min = Math.min(min, e.getLow());
                        max = Math.max(max, e.getHigh());
                        ok = true;
                    }
                }
            }

            if (ok) {
                float pad = (max - min) * 0.05f;
                if (Float.isNaN(pad) || pad == 0f) pad = 0.1f;
                left.setAxisMinimum(min - pad);
                left.setAxisMaximum(max + pad);
            } else {
                left.resetAxisMinimum();
                left.resetAxisMaximum();
            }
        }

        updateKkdLegendForMode();   // ✅ 圖例跟著模式切換
        updateComparisonInputVisibility();
        k_kdChart.notifyDataSetChanged();
        // ✅ 切換模式後處理 highlight：離開成交量就清掉；切到成交量就依 mainChart 同步
        if (kkdViewMode == KkdViewMode.VOL) {
            syncKkdHighlightFromMain();   // 會用 mainChart highlight 亮起對應 bar（若主圖沒游標就會清掉）
        } else {
            k_kdChart.highlightValues(null);
            k_kdChart.invalidate();
        }
    }
    private void showFavoriteFilePickerThenAddCurrent() {
        String ticker = getCurrentMainTickerKey();
        if (ticker == null || ticker.trim().isEmpty()) return;

        java.io.File dir = getExternalFilesDir(null);
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, getString(R.string.error_csv_dir_unavailable), Toast.LENGTH_LONG).show();
            return;
        }

        java.io.File[] files = dir.listFiles((d, name) ->
                name != null
                        && name.startsWith("最愛")
                        && name.toLowerCase(java.util.Locale.US).endsWith(".csv"));

        java.util.ArrayList<java.io.File> favFiles = new java.util.ArrayList<>();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (java.io.File f : files) favFiles.add(f);
        }

        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        items.add("+");
        for (java.io.File f : favFiles) items.add(f.getName());

        android.widget.ListView lv = new android.widget.ListView(this);
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        lv.setAdapter(ad);

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_pick_favorite_file_title)
                .setView(lv)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        lv.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos == 0) {
                showCreateFavoriteFileDialog(dlg);
                return;
            }

            java.io.File selected = favFiles.get(pos - 1);
            dlg.dismiss();
            selectFavoriteFileAndAddCurrent(selected);
        });

        dlg.show();
    }
    private void selectFavoriteFileAndAddCurrent(java.io.File f) {
        if (f == null) return;

        // 1) 切換目前最愛檔 + 載入清單（favoriteMap 會被替換）
        setActiveFavoritesFileAndReload(f);

        // 2) 把目前主股票加入（若已存在就不重複）
        boolean changed = addCurrentMainToFavoriteMapIfAbsent();

        // 3) 存檔（寫到 activeFavoritesFile）
        if (changed) {
            saveFavoritesToFile();
        }

        updateFavoriteButtonState();
        updateTopOutsideMainChartUi();

        Toast.makeText(this,
                getString(R.string.toast_added_to_favorite_file, f.getName()),
                Toast.LENGTH_SHORT).show();
    }

    private boolean addCurrentMainToFavoriteMapIfAbsent() {
        String ticker = getCurrentMainTickerKey();
        if (ticker == null || ticker.trim().isEmpty()) return false;

        ticker = ticker.trim().toUpperCase(java.util.Locale.US);
        if (favoriteMap.containsKey(ticker)) return false;

        String name = "";
        String industry = "";

        if (tickerMetaMap != null) {
            TickerInfo info = tickerMetaMap.get(ticker);
            if (info == null) {
                String base = ticker.replace(".TW", "").replace(".TWO", "");
                info = tickerMetaMap.get(base);
            }
            if (info != null) {
                name = info.name;
                industry = info.industry;
            }
        }

        favoriteMap.put(ticker, new FavoriteInfo(ticker, name, industry));
        return true;
    }
    private void showCreateFavoriteFileDialog(androidx.appcompat.app.AlertDialog parentDlg) {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, getString(R.string.error_csv_dir_unavailable), Toast.LENGTH_LONG).show();
            return;
        }

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);

        String suggested = suggestFavoriteFileName(dir); // 最愛.csv / 最愛1.csv / ...
        et.setText(suggested);
        if (et.getText() != null) et.setSelection(et.getText().length());

        final androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_new_favorite_file_title)
                .setMessage(R.string.dialog_new_favorite_file_msg)
                .setView(et)
                .setNegativeButton(android.R.string.cancel, null) // 取消：回上一層（parentDlg 還在）
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dlg.show();

        android.widget.Button ok = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        ok.setOnClickListener(v -> {
            String input = (et.getText() == null) ? "" : et.getText().toString().trim();
            String name = sanitizeCsvFileName(input);
            if (name == null) {
                Toast.makeText(this, getString(R.string.error_csv_invalid_filename), Toast.LENGTH_LONG).show();
                return;
            }
            if (!name.startsWith("最愛")) {
                Toast.makeText(this, getString(R.string.error_favorite_filename_prefix), Toast.LENGTH_LONG).show();
                return;
            }

            java.io.File f = new java.io.File(dir, name);
            if (f.exists()) {
                Toast.makeText(this, getString(R.string.error_csv_name_exists, f.getName()), Toast.LENGTH_LONG).show();
                return;
            }

            // 建立空的最愛檔（只有 header）
            try (java.io.BufferedWriter bw = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(f, false),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                bw.write("Ticker,Name,Industry\n");
                bw.flush();
            } catch (Exception e) {
                android.util.Log.e(TAG, "create favorite file failed", e);
                Toast.makeText(this, getString(R.string.error_csv_save_failed), Toast.LENGTH_LONG).show();
                return;
            }

            dlg.dismiss();

            // 回到上一個步驟：重開清單（簡單做法）
            if (parentDlg != null) parentDlg.dismiss();
            showFavoriteFilePickerThenAddCurrent();
        });
    }

    private String suggestFavoriteFileName(java.io.File dir) {
        java.io.File f0 = new java.io.File(dir, "最愛.csv");
        if (!f0.exists()) return "最愛.csv";

        for (int i = 1; i < 10000; i++) {
            java.io.File f = new java.io.File(dir, "最愛" + i + ".csv");
            if (!f.exists()) return f.getName();
        }
        return "最愛" + System.currentTimeMillis() + ".csv";
    }
    private void updateComparisonInputVisibility() {
        if (comparisonStockIdEditText == null) return;

        boolean show = (kkdViewMode == KkdViewMode.COMP);

        comparisonStockIdEditText.setVisibility(show ? View.VISIBLE : View.GONE);

        if (show) {
            // 進入對比K線時，把目前對比代碼顯示在輸入框
            if (displayedComparisonSymbol != null) {
                comparisonStockIdEditText.setText(displayedComparisonSymbol);
                comparisonStockIdEditText.setSelection(comparisonStockIdEditText.getText().length());
            }
        } else {
            // 離開對比K線時，避免輸入法/焦點卡著
            comparisonStockIdEditText.clearFocus();
            hideKeyboard(comparisonStockIdEditText);
        }
    }
    private void updateKkdLegendForMode() {
        if (k_kdChart == null) return;

        Legend legend = k_kdChart.getLegend();
        List<LegendEntry> entries = new ArrayList<>();

        if (kkdViewMode == KkdViewMode.KD) {
            LineData ld = k_kdChart.getLineData();
            if (ld != null) {
                for (int i = 0; i < ld.getDataSetCount(); i++) {
                    ILineDataSet ds = ld.getDataSetByIndex(i);
                    if (ds == null) continue;
                    if (!ds.isVisible()) continue;

                    LegendEntry e = new LegendEntry();
                    e.label = ds.getLabel();
                    e.form = Legend.LegendForm.LINE;
                    e.formLineWidth = 3f;
                    e.formSize = 10f;
                    e.formColor = ds.getColor();
                    entries.add(e);
                }
            }

        } else if (kkdViewMode == KkdViewMode.VOL) {
            BarData bd = k_kdChart.getBarData();
            if (bd != null && bd.getDataSetCount() > 0) {
                IBarDataSet ds = bd.getDataSetByIndex(0);
                if (ds != null && ds.isVisible()) {
                    LegendEntry e = new LegendEntry();
                    e.label = ds.getLabel(); // 你在 generateKkdVolumeBarDataInLots() 設的是「成交量(張)」
                    e.form = Legend.LegendForm.SQUARE;
                    e.formSize = 10f;

                    // BarDataSet 用多色時 getColor() 只會回第一個色，這裡用灰色比較穩
                    e.formColor = Color.GRAY;
                    entries.add(e);
                }
            }

        } else { // KkdViewMode.COMP
            CandleData cd = k_kdChart.getCandleData();
            if (cd != null && cd.getDataSetCount() > 0) {
                ICandleDataSet ds = cd.getDataSetByIndex(0);
                if (ds != null && ds.isVisible()) {
                    LegendEntry e = new LegendEntry();
                    e.label = ds.getLabel(); // 通常是 displayedComparisonSymbol
                    e.form = Legend.LegendForm.SQUARE;
                    e.formSize = 10f;

                    // 用 increasingColor 當 legend 色（你 CandleDataSet 有設定）
                    int color = Color.RED;
                    try {
                        if (ds instanceof CandleDataSet) {
                            color = ((CandleDataSet) ds).getIncreasingColor();
                        }
                    } catch (Exception ignored) {}
                    e.formColor = color;

                    entries.add(e);
                }
            }
        }

        legend.setCustom(entries);
    }
    private void setupIndicatorChart() {
        indicatorChart.setViewPortOffsets(CHART_OFFSET_LEFT, CHART_OFFSET_TOP, CHART_OFFSET_RIGHT, CHART_OFFSET_BOTTOM);
        indicatorChart.getDescription().setEnabled(false);
        indicatorChart.setNoDataText("MACD 指標區"); // 可留著
        indicatorChart.setBackgroundColor(Color.BLACK);

        Legend legend = indicatorChart.getLegend();
        legend.setTextColor(Color.WHITE);
        legend.setWordWrapEnabled(true);
        legend.setForm(Legend.LegendForm.SQUARE);
        legend.setEnabled(false);

        // ✅ 改這裡：要能接收單點
        indicatorChart.setTouchEnabled(true);
        indicatorChart.setScaleEnabled(false);
        indicatorChart.setDragEnabled(false);
        indicatorChart.setHighlightPerTapEnabled(false);

        // ✅ 單點切換模式
        indicatorChart.setOnChartGestureListener(new com.github.mikephil.charting.listener.OnChartGestureListener() {
            @Override public void onChartSingleTapped(android.view.MotionEvent me) {
                cycleIndicatorMode();
            }
            @Override public void onChartGestureStart(android.view.MotionEvent me,
                                                      com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture lastPerformedGesture) {}
            @Override public void onChartGestureEnd(android.view.MotionEvent me,
                                                    com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture lastPerformedGesture) {}
            @Override public void onChartLongPressed(android.view.MotionEvent me) {}
            @Override public void onChartDoubleTapped(android.view.MotionEvent me) {}
            @Override public void onChartFling(android.view.MotionEvent me1, android.view.MotionEvent me2, float velocityX, float velocityY) {}
            @Override public void onChartScale(android.view.MotionEvent me, float scaleX, float scaleY) {}
            @Override public void onChartTranslate(android.view.MotionEvent me, float dX, float dY) {}
        });

        XAxis xAxis = indicatorChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setDrawLabels(true);
        xAxis.setGranularity(1f);
        xAxis.setTextColor(Color.WHITE);
        xAxis.setLabelRotationAngle(-10f);
        xAxis.setYOffset(-20f);

        YAxis leftAxis = indicatorChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setDrawLabels(true);
        leftAxis.setDrawAxisLine(true);
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.setDrawZeroLine(true);
        leftAxis.setZeroLineColor(Color.GRAY);
        leftAxis.setZeroLineWidth(1f);
        leftAxis.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);

        indicatorChart.getAxisRight().setEnabled(false);

        updateIndicatorModeLabel();
    }
    private void cycleIndicatorMode() {
        switch (indicatorMode) {
            case MACD: indicatorMode = IndicatorMode.RSI; break;
            case RSI:  indicatorMode = IndicatorMode.DMI; break;
            case DMI:  indicatorMode = IndicatorMode.MACD; break;
        }
        updateIndicatorModeLabel();

        // 只重畫 indicator（不用整張重畫）
        if (lastDisplayedListForIndicator != null) {
            redrawIndicatorOnly(lastDisplayedListForIndicator);
        }
    }

    private void updateIndicatorModeLabel() {
        if (indicatorModeLabel == null) return;
        int resId = (indicatorMode == IndicatorMode.MACD) ? R.string.indicator_mode_macd
                : (indicatorMode == IndicatorMode.RSI) ? R.string.indicator_mode_rsi
                : R.string.indicator_mode_dmi;
        indicatorModeLabel.setText(resId);
    }

    private void redrawIndicatorOnly(List<StockDayPrice> displayedList) {
        if (displayedList == null || displayedList.isEmpty()) return;

        if (indicatorMode == IndicatorMode.RSI) {
            drawRsiIndicatorChartData(displayedList);
            updateRsiIndicatorAxis();
        } else if (indicatorMode == IndicatorMode.DMI) {
            drawDmiIndicatorChartData(displayedList);
            updateDmiIndicatorAxis(displayedList);
        } else {
            CombinedData combinedMacdData = drawIndicatorChartData(displayedList);
            if (combinedMacdData.getBarData() != null || combinedMacdData.getLineData() != null) {
                updateMacdIndicatorAxis(displayedList);
            }
        }

        //indicatorChart.fitScreen();
        indicatorChart.invalidate();
    }

    private void drawCharts(List<StockDayPrice> fullPriceList, String symbol) {
        String mode = VIEW_MODES[currentViewModeIndex];

        List<StockDayPrice> cleanedFull = OhlcCleaners.cleanOhlc(fullPriceList, currentInterval);
        if (cleanedFull == null || cleanedFull.isEmpty()) {
            clearAllCharts("無 " + symbol + " 歷史數據可供繪製。");
            return;
        }

        setKDJinterval(currentInterval);
        // calculateKDJ(cleanedFull, currentInterval); // 你已拿掉/不需要
        calculateVolumeProfile(cleanedFull);

        List<StockDayPrice> displayedList = getDisplayedList(cleanedFull);
        if (displayedList == null || displayedList.isEmpty()) {
            Log.w(TAG, "Chart Draw Warning: displayedList is unexpectedly empty after filtering.");
            clearAllCharts(null);
            return;
        }

        // 記住目前指標圖使用的資料，點擊 indicatorChart 切換模式時會用到
        lastDisplayedListForIndicator = displayedList;
        updateIndicatorModeLabel();

        coordinateMarker = new CoordinateMarkerView(this, displayedList);
        coordinateMarker.setChartView(mainChart);
        mainChart.setMarker(coordinateMarker);

        MyXAxisFormatter dateFormatter = new MyXAxisFormatter(displayedList);
        mainChart.getXAxis().setValueFormatter(dateFormatter);
        k_kdChart.getXAxis().setValueFormatter(dateFormatter);
        indicatorChart.getXAxis().setValueFormatter(dateFormatter);

        double maxPrice = displayedList.stream().mapToDouble(StockDayPrice::getHigh).max().orElse(0);
        double minPrice = displayedList.stream().mapToDouble(StockDayPrice::getLow).min().orElse(0);

        float range = (float) (maxPrice - minPrice);
        float pad = (range > 0f) ? (range * 0.08f) : 1f;

        YAxis leftAxis = mainChart.getAxisLeft();
        leftAxis.setAxisMinimum((float) minPrice - pad);
        leftAxis.setAxisMaximum((float) maxPrice + pad);

        if (mode.equals("ALL") || mode.equals("VP")) {
            vpView.setVisibility(View.VISIBLE);
            vpView.setVolumeProfile(volumeProfileData, minPriceGlobal, bucketSizeGlobal, NUM_PRICE_BUCKETS);
        } else {
            vpView.setVisibility(View.GONE);
            vpView.clear();
        }

        // 主圖
        drawMainChartData(displayedList);
        updateFavoriteButtonState();
        lastDisplayedListForMain = displayedList;

        // KD + 對比
        drawKDAndComparisonChartData(displayedList);
        lastDisplayedListForKkd = displayedList;
        if (kkdVolumeMarker != null) kkdVolumeMarker.setDataList(displayedList);

// 畫完資料後，依目前模式決定要顯示 KD/對比/兩者
        applyKkdViewModeToChart();
        resetKDJAndComparisonAxisLimits();

        // ✅ 指標圖：依目前 indicatorMode 畫 MACD / RSI / DMI（不再靠按鈕）
        redrawIndicatorOnly(displayedList);

        float chartXMax = displayedList.size() - 0.5f;
        float chartXMin = -0.5f;

        mainChart.getXAxis().setAxisMaximum(chartXMax);
        mainChart.getXAxis().setAxisMinimum(chartXMin);
        k_kdChart.getXAxis().setAxisMaximum(chartXMax);
        k_kdChart.getXAxis().setAxisMinimum(chartXMin);
        indicatorChart.getXAxis().setAxisMaximum(chartXMax);
        indicatorChart.getXAxis().setAxisMinimum(chartXMin);

        if (comparisonStockIdEditText != null) comparisonStockIdEditText.setText(displayedComparisonSymbol);

        if (!pendingRestoreCandleWidth) {
            mainChart.fitScreen();
            k_kdChart.fitScreen();
            indicatorChart.fitScreen();
        }

        updateGapLabelVisibility();
        resetKDJAndComparisonAxisLimits();

// ✅ 切 interval 後：保持K柱像素寬度
        restoreMainCandlePixelWidthIfNeeded();

        mainChart.invalidate();
        k_kdChart.invalidate();
        indicatorChart.invalidate();
        vpView.invalidate();
    }

    private void restoreMainCandlePixelWidthIfNeeded() {
        if (!pendingRestoreCandleWidth) return;
        pendingRestoreCandleWidth = false;

        if (mainChart == null || mainChart.getData() == null) return;

        XAxis x = mainChart.getXAxis();
        float newRange = x.getAxisMaximum() - x.getAxisMinimum();
        if (savedMainXRange <= 0f || newRange <= 0f) return;

        float currentScaleX = mainChart.getViewPortHandler().getScaleX();
        if (currentScaleX <= 0f) return;

        // ✅ 關鍵：保持每根K柱像素寬度不變
        float targetScaleX = savedMainScaleX * (newRange / savedMainXRange);
        float zoomFactorX = targetScaleX / currentScaleX;

        // 以右側為錨點縮放（通常符合你想看最新的習慣）
        mainChart.zoom(zoomFactorX, 1f,
                mainChart.getViewPortHandler().contentRight(),
                mainChart.getViewPortHandler().contentTop());

        // 同步副圖（你原本就有）
        syncChartsXAxisOnly();
        mainChart.post(this::syncChartsXAxisOnly);
    }
    private void rememberMainCandlePixelWidthForNextRedraw() {
        if (mainChart == null || mainChart.getData() == null) return;

        XAxis x = mainChart.getXAxis();
        float range = x.getAxisMaximum() - x.getAxisMinimum();
        if (range <= 0f || Float.isNaN(range) || Float.isInfinite(range)) return;

        savedMainScaleX = mainChart.getViewPortHandler().getScaleX();
        savedMainXRange = range;
        pendingRestoreCandleWidth = true;
    }
    private List<StockDayPrice> getDisplayedList(List<StockDayPrice> fullList) {
        return fullList;
    }
    private void setKDJinterval(String interval) {
        if ("1h".equals(interval) || "1d".equals(interval)) {
            currentKDNPeriod = 40;
        } else {
            currentKDNPeriod = 9;
        }
    }

    private LineData generateKDLineData(List<StockDayPrice> displayedList) {
        final int SKY_BLUE = Color.rgb(79, 195, 247);

        LineData lineData = new LineData();
        List<Entry> kEntries = new ArrayList<>();
        List<Entry> dEntries = new ArrayList<>();

        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            if (!Double.isNaN(p.kdK)) kEntries.add(new Entry(i, (float) p.kdK));
            if (!Double.isNaN(p.kdD)) dEntries.add(new Entry(i, (float) p.kdD));
        }

        String kLabel = String.format(Locale.US, "K%d", currentKDNPeriod);
        LineDataSet kSet = new LineDataSet(kEntries, kLabel);
        kSet.setColor(Color.LTGRAY);
        kSet.setLineWidth(1.5f);
        kSet.setDrawCircles(false);
        kSet.setDrawValues(false);
        kSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineData.addDataSet(kSet);

        String dLabel = String.format(Locale.US, "D%d", currentKDNPeriod);
        LineDataSet dSet = new LineDataSet(dEntries, dLabel);
        dSet.setColor(SKY_BLUE);
        dSet.setLineWidth(1.5f);
        dSet.setDrawCircles(false);
        dSet.setDrawValues(false);
        dSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineData.addDataSet(dSet);

        return lineData;
    }

    private static final float VOLUME_DIVISOR = 1000f; // getVolume() 若已是「張」就改成 1f

    private BarData generateKkdVolumeBarDataInLots(List<StockDayPrice> displayedList) {
        List<BarEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);

            float lots = (float) (p.getVolume() / VOLUME_DIVISOR); // ✅ 張
            entries.add(new BarEntry(i, lots));

            if (p.getClose() > p.getOpen()) colors.add(Color.RED);
            else if (p.getClose() < p.getOpen()) colors.add(Color.GREEN);
            else colors.add(Color.WHITE);
        }

        BarDataSet set = new BarDataSet(entries, "成交量(張)");
        set.setDrawValues(false);
        set.setColors(colors);
        set.setAxisDependency(YAxis.AxisDependency.LEFT); // ✅ 左軸

        // ✅ 讓被選到的 bar 變「亮」而不是變暗
        set.setHighlightEnabled(true);
        set.setHighLightColor(Color.YELLOW);   // 或 Color.WHITE
        try {
            set.setHighLightAlpha(255);        // 最亮（若你的版本沒有這個方法就刪掉 try 區塊）
        } catch (Throwable ignored) {}


        BarData barData = new BarData(set);
        barData.setBarWidth(0.8f);
        return barData;
    }

    private CombinedData drawIndicatorChartData(List<StockDayPrice> displayedList) {
        indicatorChart.clear();
        indicatorChart.getAxisLeft().removeAllLimitLines();

        CombinedData rawData = drawMacdChartsRaw(displayedList);
        CombinedData finalData = new CombinedData();

        if (rawData.getBarData() != null) {
            IBarDataSet rawBarSet = rawData.getBarData().getDataSetByIndex(0);
            ArrayList<BarEntry> barEntries = new ArrayList<>();
            ArrayList<Integer> colors = new ArrayList<>();

            for (int i = 0; i < rawBarSet.getEntryCount(); i++) {
                BarEntry e = (BarEntry) rawBarSet.getEntryForIndex(i);
                barEntries.add(new BarEntry(e.getX(), e.getY()));
                colors.add(e.getY() >= 0 ? Color.RED : Color.GREEN);
            }

            BarDataSet barDataSet = new BarDataSet(barEntries, "MACD OSC");
            barDataSet.setColors(colors);
            barDataSet.setDrawValues(false);
            barDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            barDataSet.setForm(Legend.LegendForm.NONE);
            finalData.setData(new BarData(barDataSet));
        }

        if (rawData.getLineData() != null) {
            LineData lineData = rawData.getLineData();
            for (ILineDataSet set : lineData.getDataSets()) {
                ((LineDataSet) set).setDrawCircles(false);
                ((LineDataSet) set).setAxisDependency(YAxis.AxisDependency.LEFT);
            }
            finalData.setData(lineData);
        }

        indicatorChart.setData(finalData);

        Legend legend = indicatorChart.getLegend();
        legend.setEnabled(false);
        legend.setWordWrapEnabled(true);

        YAxis leftAxis = indicatorChart.getAxisLeft();
        leftAxis.resetAxisMinimum();
        leftAxis.resetAxisMaximum();
        leftAxis.setDrawZeroLine(true);
        leftAxis.setZeroLineColor(Color.GRAY);
        leftAxis.setZeroLineWidth(0.8f);

        indicatorChart.notifyDataSetChanged();
        indicatorChart.invalidate();
        return rawData;
    }

    private void drawMainChartData(List<StockDayPrice> displayedList) {
        if (displayedList == null || displayedList.isEmpty()) return;

        String mode = VIEW_MODES[currentViewModeIndex];

        // 新的 3 種模式：K / VP / ALL
        boolean showCandle  = mode.equals("ALL") || mode.equals("K") || mode.equals("VP");
        boolean showMA      = mode.equals("ALL") || mode.equals("K");
        boolean showScatter = mode.equals("ALL") || mode.equals("K");

        CombinedData data = new CombinedData();
        ScatterData scatterData = new ScatterData();

        String goldColor = "#FFD700";
        String cyanColor = "#00FFFF";
        float smallSize = 17.5f;

        // Candle (K線)
        if (showCandle) {
            data.setData(generateCandleData(displayedList));
        } else {
            data.setData(new CandleData());
        }

        // MA 線
        if (showMA) {
            data.setData(generateLineData(displayedList));
        } else {
            data.setData(new LineData());
        }
        // Scatter（背離點/標記）：只在 ALL 或 K 顯示
        if (showScatter) {
            if (displayedList.size() >= 60 && "1d".equals(currentInterval)) {
                int d60Idx = displayedList.size() - 60;
                List<Entry> d60Entries = new ArrayList<>();
                d60Entries.add(new Entry(d60Idx, (float) displayedList.get(d60Idx).getHigh()));
                addScatterDataSet(scatterData, d60Entries, goldColor, 3, smallSize);
            }

            List<Entry> difUp = new ArrayList<>(), difDown = new ArrayList<>();
            List<Entry> histoUp = new ArrayList<>(), histoDown = new ArrayList<>();

            float yRange = mainChart.getAxisLeft().getAxisMaximum() - mainChart.getAxisLeft().getAxisMinimum();
            float offset = yRange * 0.015f;

            MacdDivergenceUtil.Result div = MacdDivergenceUtil.compute(displayedList);

            for (int idx : div.difBottom) {
                difUp.add(new Entry(idx, (float) displayedList.get(idx).getLow() - offset));
            }
            for (int idx : div.difTop) {
                difDown.add(new Entry(idx, (float) displayedList.get(idx).getHigh() + offset));
            }
            for (int idx : div.histBottom) {
                histoUp.add(new Entry(idx, (float) displayedList.get(idx).getLow() - offset));
            }
            for (int idx : div.histTop) {
                histoDown.add(new Entry(idx, (float) displayedList.get(idx).getHigh() + offset));
            }

            addScatterDataSet(scatterData, difUp, goldColor, 0, smallSize);
            addScatterDataSet(scatterData, difDown, goldColor, 1, smallSize);
            addScatterDataSet(scatterData, histoUp, cyanColor, 0, smallSize);
            addScatterDataSet(scatterData, histoDown, cyanColor, 1, smallSize);

            addGapPriceLabels(scatterData, displayedList);
            data.setData(scatterData);
        }

        mainChart.setData(data);

        // ----- Legend -----
        Legend legend = mainChart.getLegend();
        List<LegendEntry> customEntries = new ArrayList<>();

        // MA legend：只有在有畫 MA 時才顯示
        if (showMA && data.getLineData() != null) {
            List<ILineDataSet> sets = data.getLineData().getDataSets();
            ILineDataSet ma1 = (sets.size() >= 1) ? sets.get(0) : null;
            ILineDataSet ma2 = (sets.size() >= 2) ? sets.get(1) : null;

            if (ma1 != null) {
                customEntries.add(new LegendEntry(
                        ma1.getLabel(),
                        Legend.LegendForm.LINE, 10f, 3f, null,
                        ma1.getColor()
                ));
            }

            if (ma2 != null) {
                customEntries.add(new LegendEntry(
                        ma2.getLabel(),
                        Legend.LegendForm.LINE, 10f, 3f, null,
                        ma2.getColor()
                ));
            }
        }

        // 主股票 legend：只要有畫 K線就顯示
        if (showCandle) {
            int stockColor = Color.RED;
            if (data.getCandleData() != null && data.getCandleData().getDataSetCount() > 0) {
                try {
                    stockColor = data.getCandleData().getDataSetByIndex(0).getIncreasingColor();
                } catch (Exception ignored) {}
            }

            customEntries.add(new LegendEntry(
                    buildMainStockLegendLabel(),
                    Legend.LegendForm.SQUARE, 10f, 5f, null,
                    stockColor
            ));
        }

        legend.setCustom(customEntries);
        mainChart.notifyDataSetChanged();
        mainChart.invalidate();
    }

    private void addScatterDataSet(ScatterData data, List<Entry> entries, String color, int shapeType, float size) {
        if (entries == null || entries.isEmpty()) return;

        ScatterDataSet set = new ScatterDataSet(entries, "");
        set.setShapeRenderer(shapeType == 3 ? new CustomAsteriskRenderer() : new CustomTriangleRenderer(shapeType));
        set.setColor(Color.parseColor(color));
        set.setScatterShapeSize(size);
        set.setDrawValues(false);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setForm(Legend.LegendForm.NONE);
        data.addDataSet(set);
    }

    private CombinedData drawMacdChartsRaw(List<StockDayPrice> displayedList) {
        CombinedData combined = new CombinedData();

        List<BarEntry> histogramEntries = new ArrayList<>();
        List<Entry> difEntries = new ArrayList<>();
        List<Entry> deaEntries = new ArrayList<>();

        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            if (!Double.isNaN(p.macdHistogram)) histogramEntries.add(new BarEntry(i, (float) p.macdHistogram));
            if (!Double.isNaN(p.macdDIF)) difEntries.add(new Entry(i, (float) p.macdDIF));
            if (!Double.isNaN(p.macdDEA)) deaEntries.add(new Entry(i, (float) p.macdDEA));
        }

        BarDataSet histoSet = new BarDataSet(histogramEntries, "Histogram");
        histoSet.setDrawValues(false);
        histoSet.setBarBorderWidth(0.0f);

        List<Integer> histoColors = new ArrayList<>();
        for (BarEntry e : histogramEntries) histoColors.add(e.getY() >= 0 ? Color.RED : Color.GREEN);
        histoSet.setColors(histoColors);
        histoSet.setAxisDependency(YAxis.AxisDependency.LEFT);

        BarData barData = new BarData(histoSet);
        barData.setBarWidth(0.8f);
        combined.setData(barData);

    //    final int DIM_YELLOW = Color.rgb(100, 100, 0);
        final int SKY_BLUE = Color.rgb(79, 195, 247);

        LineData lineData = new LineData();

        LineDataSet difSet = new LineDataSet(difEntries, "DIF");
        difSet.setColor(Color.LTGRAY);
        difSet.setLineWidth(1.5f);
        difSet.setDrawCircles(false);
        difSet.setDrawValues(false);
        difSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineData.addDataSet(difSet);

        LineDataSet deaSet = new LineDataSet(deaEntries, "DEA");
        deaSet.setColor(SKY_BLUE);
        deaSet.setLineWidth(1.5f);
        deaSet.setDrawCircles(false);
        deaSet.setDrawValues(false);
        deaSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineData.addDataSet(deaSet);

        combined.setData(lineData);
        return combined;
    }

    private void updateMacdIndicatorAxis(List<StockDayPrice> displayedList) {
        double maxMacd = Double.NEGATIVE_INFINITY;
        double minMacd = Double.POSITIVE_INFINITY;

        for (StockDayPrice p : getDisplayedList(displayedList)) {
            if (!Double.isNaN(p.macdHistogram)) { maxMacd = Math.max(maxMacd, p.macdHistogram); minMacd = Math.min(minMacd, p.macdHistogram); }
            if (!Double.isNaN(p.macdDIF))       { maxMacd = Math.max(maxMacd, p.macdDIF);       minMacd = Math.min(minMacd, p.macdDIF); }
            if (!Double.isNaN(p.macdDEA))       { maxMacd = Math.max(maxMacd, p.macdDEA);       minMacd = Math.min(minMacd, p.macdDEA); }
        }

        YAxis ax = indicatorChart.getAxisLeft();
        float padding = (float) Math.max(Math.abs(maxMacd), Math.abs(minMacd)) * 0.1f;
        if (Float.isInfinite(padding) || Float.isNaN(padding) || padding < 0.1f) padding = 1f;

        ax.setAxisMaximum((float) maxMacd + padding);
        ax.setAxisMinimum((float) minMacd - padding);
    }

    private void resetKDJAndComparisonAxisLimits() {
        // 新規格下：它只負責依模式更新左軸/limitLines，右軸永遠關閉
        applyKkdViewModeToChart();
    }

    private void drawKDAndComparisonChartData(List<StockDayPrice> displayedMainList) {
        CombinedData combinedData = new CombinedData();
        Legend legend = k_kdChart.getLegend();
        List<LegendEntry> customEntries = new ArrayList<>();

        // 右軸永遠關閉（保險）
        k_kdChart.getAxisRight().setEnabled(false);

        // 1) KD Line
        LineData lineData = generateKDLineData(displayedMainList);
        if (lineData.getDataSetCount() > 0) {
            // 保險：全部走左軸
            for (int i = 0; i < lineData.getDataSetCount(); i++) {
                lineData.getDataSetByIndex(i).setAxisDependency(YAxis.AxisDependency.LEFT);
            }
            combinedData.setData(lineData);

            final int SKY_BLUE = Color.rgb(79, 195, 247);
            String kLegendText = String.format(Locale.getDefault(), "K%d", currentKDNPeriod);
            String dLegendText = String.format(Locale.getDefault(), "D%d", currentKDNPeriod);
            customEntries.add(new LegendEntry(kLegendText, Legend.LegendForm.LINE, 10f, 3f, null, Color.LTGRAY));
            customEntries.add(new LegendEntry(dLegendText, Legend.LegendForm.LINE, 10f, 3f, null, SKY_BLUE));
        } else {
            combinedData.setData(new LineData());
        }

        // 2) Volume Bar (lots)
        BarData barData = generateKkdVolumeBarDataInLots(displayedMainList);
        if (barData != null) {
            // 保險：走左軸
            if (barData.getDataSetCount() > 0) {
                barData.getDataSetByIndex(0).setAxisDependency(YAxis.AxisDependency.LEFT);
            }
            combinedData.setData(barData);
        } else {
            combinedData.setData(new BarData());
        }

        // 3) Compare Candle
        if (comparisonPriceList.isEmpty()) {
            k_kdChart.setNoDataText("KD/成交量/對比 K 線圖：無對比 K 線數據可供繪製。");
            combinedData.setData(new CandleData());
        } else {

            // ✅ 關鍵：對比資料也要 clean 成同一個 interval，否則月K/週K 日期很難對齊
            List<StockDayPrice> compClean = OhlcCleaners.cleanOhlc(comparisonPriceList, currentInterval);
            if (compClean == null || compClean.isEmpty()) {
                combinedData.setData(new CandleData());
            } else {
                // ✅ 也用相同的 displayed 規則（你的 getDisplayedList）
                List<StockDayPrice> compDisplayed = getDisplayedList(compClean);
                if (compDisplayed == null || compDisplayed.isEmpty()) {
                    combinedData.setData(new CandleData());
                } else {

                    int mainN = displayedMainList.size();
                    int compN = compDisplayed.size();
                    int takeN = Math.min(mainN, compN);

                    int startComp = compN - takeN;   // 對比取最後 takeN 根
                    int startX = mainN - takeN;      // 放到主資料尾端 -> 保證右對齊

                    List<CandleEntry> entries = new ArrayList<>(takeN);
                    for (int i = 0; i < takeN; i++) {
                        StockDayPrice cp = compDisplayed.get(startComp + i);
                        int x = startX + i;

                        entries.add(new CandleEntry(
                                x,
                                (float) cp.getHigh(),
                                (float) cp.getLow(),
                                (float) cp.getOpen(),
                                (float) cp.getClose()
                        ));
                    }

                    if (!entries.isEmpty()) {
                        String kLineLabel = displayedComparisonSymbol;

                        CandleDataSet set = new CandleDataSet(entries, kLineLabel);
                        set.setDrawIcons(false);
                        set.setShadowColor(Color.GRAY);
                        set.setShadowWidth(0.7f);

                        final int DECREASING_COLOR = Color.GREEN;
                        final int INCREASING_COLOR = Color.RED;

                        set.setDecreasingColor(DECREASING_COLOR);
                        set.setDecreasingPaintStyle(Paint.Style.STROKE);
                        set.setIncreasingColor(INCREASING_COLOR);
                        set.setIncreasingPaintStyle(Paint.Style.STROKE);
                        set.setNeutralColor(Color.WHITE);
                        set.setDrawValues(false);
                        set.setAxisDependency(YAxis.AxisDependency.LEFT);

                        combinedData.setData(new CandleData(set));
                        customEntries.add(new LegendEntry(kLineLabel, Legend.LegendForm.SQUARE, 10f, 0f, null, INCREASING_COLOR));
                    } else {
                        combinedData.setData(new CandleData());
                    }
                }
            }
        }

        //legend.setCustom(customEntries);

        k_kdChart.setData(combinedData);
        k_kdChart.notifyDataSetChanged();

        // 記住這份 list，切換模式/gesture end 都會用到
        lastDisplayedListForKkd = displayedMainList;

        // ✅ 依目前模式決定顯示 KD / VOL / COMP + 更新左軸範圍與 20/80
        applyKkdViewModeToChart();

        // 避免沿用舊 zoom/translate
        k_kdChart.fitScreen();
        k_kdChart.moveViewToX(-0.5f);

        k_kdChart.invalidate();
    }

    private CandleData generateCandleData(List<StockDayPrice> displayedList) {
        List<CandleEntry> entries = new ArrayList<>();
        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            entries.add(new CandleEntry(i, (float) p.getHigh(), (float) p.getLow(),
                    (float) p.getOpen(), (float) p.getClose()));
        }

        CandleDataSet set = new CandleDataSet(entries, "K Line");
        set.setDrawIcons(false);
        set.setShadowColor(Color.GRAY);
        set.setShadowWidth(0.7f);
        set.setDecreasingColor(Color.GREEN);
        set.setDecreasingPaintStyle(Paint.Style.FILL);
        set.setIncreasingColor(Color.RED);
        set.setIncreasingPaintStyle(Paint.Style.FILL);
        set.setNeutralColor(Color.WHITE);
        set.setDrawValues(false);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);

        return new CandleData(set);
    }

    private LineData generateLineData(List<StockDayPrice> displayedList) {
        LineData lineData = new LineData();

        addMaLine(lineData, displayedList, "MA35", p -> p.ma35, Color.YELLOW);
        addMaLine(lineData, displayedList, "MA60", p -> p.ma60, Color.MAGENTA);
        addMaLine(lineData, displayedList, "MA120", p -> p.ma120, Color.CYAN);
        addMaLine(lineData, displayedList, "MA200", p -> p.ma200,  Color.rgb(79, 195, 247));
        addMaLine(lineData, displayedList, "MA240", p -> p.ma240, Color.rgb(255, 165, 0));

        return lineData;
    }

    private interface MaGetter { double get(StockDayPrice p); }

    private void addMaLine(LineData lineData, List<StockDayPrice> list, String label, MaGetter getter, int color) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            double v = getter.get(list.get(i));
            if (!Double.isNaN(v)) entries.add(new Entry(i, (float) v));
        }
        if (entries.isEmpty()) return;

        LineDataSet set = new LineDataSet(entries, label);
        set.setColor(color);
        set.setLineWidth(1.5f);
        set.setDrawValues(false);
        set.setDrawCircles(false);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineData.addDataSet(set);
    }

    private void calculateVolumeProfile(List<StockDayPrice> prices) {
        volumeProfileData.clear();
        if (prices == null || prices.isEmpty()) return;

        minPriceGlobal = prices.stream().mapToDouble(StockDayPrice::getLow).min().orElse(0.0);
        double maxPrice = prices.stream().mapToDouble(StockDayPrice::getHigh).max().orElse(0.0);
        priceRangeGlobal = maxPrice - minPriceGlobal;
        if (priceRangeGlobal <= 0) return;

        bucketSizeGlobal = priceRangeGlobal / NUM_PRICE_BUCKETS;
        double bucketSize = bucketSizeGlobal;

        for (StockDayPrice p : prices) {
            double priceLevel = p.getClose();
            double volume = p.getVolume();

            int idx = (int) Math.floor((priceLevel - minPriceGlobal) / bucketSize);
            idx = Math.max(0, Math.min(NUM_PRICE_BUCKETS - 1, idx));

            volumeProfileData.put(idx, volumeProfileData.getOrDefault(idx, 0.0) + volume);
        }
    }

    // =========================================================
// 同步圖表手勢
// =========================================================
    @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}

    @Override
    public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {
        resetKDJAndComparisonAxisLimits();
        k_kdChart.notifyDataSetChanged();
        k_kdChart.invalidate();

        updateIndicatorAxisByMode();
        indicatorChart.notifyDataSetChanged();
        indicatorChart.invalidate();

        updateGapLabelVisibility();
        mainChart.invalidate();
        syncChartsXAxisOnly();

        if (vpView != null && vpView.getVisibility() == View.VISIBLE) vpView.invalidate();
    }

    @Override public void onChartLongPressed(MotionEvent me) {}
    @Override public void onChartDoubleTapped(MotionEvent me) {}
    @Override public void onChartSingleTapped(MotionEvent me) {}@Override
    public void onChartScale(MotionEvent me, float scaleX, float scaleY) {
        syncChartsXAxisOnly();

        resetKDJAndComparisonAxisLimits();
        updateIndicatorAxisByMode();
        updateGapLabelVisibility();

        if (vpView != null && vpView.getVisibility() == View.VISIBLE) vpView.invalidate();
        mainChart.invalidate();
    }
    @Override
    public void onChartTranslate(MotionEvent me, float dX, float dY) {
        syncChartsXAxisOnly();
        if (vpView != null && vpView.getVisibility() == View.VISIBLE) vpView.invalidate();
    }
    private void syncChartsXAxisOnly() {
        syncChartX(mainChart, k_kdChart);
        syncChartX(mainChart, indicatorChart);
    }

    private void syncChartX(
            com.github.mikephil.charting.charts.BarLineChartBase<?> src,
            com.github.mikephil.charting.charts.BarLineChartBase<?> dst
    ) {
        if (src == null || dst == null) return;
        if (src.getData() == null || dst.getData() == null) return;

        android.graphics.Matrix srcM = src.getViewPortHandler().getMatrixTouch();
        android.graphics.Matrix dstM = new android.graphics.Matrix(dst.getViewPortHandler().getMatrixTouch());

        float[] s = new float[9];
        float[] d = new float[9];
        srcM.getValues(s);
        dstM.getValues(d);

        // 只同步 X：縮放與平移
        d[android.graphics.Matrix.MSCALE_X] = s[android.graphics.Matrix.MSCALE_X];
        d[android.graphics.Matrix.MTRANS_X] = s[android.graphics.Matrix.MTRANS_X];

        dstM.setValues(d);
        dst.getViewPortHandler().refresh(dstM, dst, true);
    }

    private void updateIndicatorAxisByMode() {
        if (indicatorMode == IndicatorMode.RSI) {
            updateRsiIndicatorAxis();
        } else if (indicatorMode == IndicatorMode.DMI) {
            updateDmiIndicatorAxis(fullPriceList);
        } else {
            clearIndicatorExtraLines();
            updateMacdIndicatorAxis(fullPriceList);

            YAxis leftAxis = indicatorChart.getAxisLeft();
            leftAxis.setDrawZeroLine(true);
            leftAxis.setZeroLineColor(Color.GRAY);
            leftAxis.setZeroLineWidth(0.8f);
            indicatorChart.invalidate();
        }
    }

    private void updateDateInputByInterval(String interval) {
        long startTimeSeconds = getStartTimeLimit(interval, isSwitchingInterval);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        startDateEditText.setText(sdf.format(new Date(startTimeSeconds * 1000L)));
    }

    private void executeCustomDataFetch() {
        String input = stockIdEditText.getText().toString().trim();
        if (input.isEmpty()) input = currentStockId;

        String symbol = resolveForMainFetch(input);

        long finalStartTime;
        String dateStr = startDateEditText.getText().toString().trim();
        Log.d("CheckDate", "Raw input from EditText: '" + dateStr + "'");

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            sdf.setLenient(false);
            Date parsedDate = sdf.parse(dateStr);
            finalStartTime = parsedDate.getTime() / 1000;
            Log.d("CheckDate", "Parsed SUCCESS! Date: " + parsedDate + " Timestamp: " + finalStartTime);
        } catch (Exception e) {
            Log.e("CheckDate", "Parsed FAILED: " + e.getMessage());
            finalStartTime = getStartTimeLimit(currentInterval, isSwitchingInterval);
            Log.d("CheckDate", "Using Default (Fallback): " + finalStartTime);
        }
        fetchStockDataWithFallback(symbol, currentInterval, finalStartTime);
    }
    public class KkdVolumeMarkerView extends com.github.mikephil.charting.components.MarkerView {

        private final Paint textPaint;
        private List<StockDayPrice> dataList;

        // 若你的 volume 本來就是張，改成 1f
        private static final float VOLUME_DIVISOR = 1000f;

        public KkdVolumeMarkerView(Context context, List<StockDayPrice> dataList) {
            super(context, android.R.layout.select_dialog_item);
            this.dataList = dataList;

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(30f);
            textPaint.setColor(Color.YELLOW);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        public void setDataList(List<StockDayPrice> list) {
            this.dataList = list;
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        private float getLegendHeightPx(CombinedChart chart) {
            Legend lg = chart.getLegend();
            List<com.github.mikephil.charting.utils.FSize> lines = lg.getCalculatedLineSizes();
            float oneLine = Math.max(lg.getTextSize(), lg.getFormSize());
            if (lines == null || lines.isEmpty()) return oneLine;

            float h = 0f;
            for (com.github.mikephil.charting.utils.FSize s : lines) h += s.height;

            // 這裡你原本 main marker 用 lg.getYEntrySpace()，沿用同樣寫法即可
            h += lg.getYEntrySpace() * (lines.size() - 1);
            return Math.max(h, oneLine);
        }

        @Override
        public void draw(Canvas canvas, float posX, float posY) {
            if (getChartView() == null || getChartView().getHighlighted() == null
                    || getChartView().getHighlighted().length == 0) return;

            CombinedChart chart = (CombinedChart) getChartView();
            Highlight h = chart.getHighlighted()[0];

            int index = Math.round(h.getX());
            if (dataList == null || index < 0 || index >= dataList.size()) return;

            Legend lg = chart.getLegend();

            float left = chart.getViewPortHandler().contentLeft();
            float top  = chart.getViewPortHandler().contentTop();

            // 跟你 main marker 一樣：固定畫在圖例下方
            float baseX = left + lg.getXOffset() + dp(16);
            float baseY = top + lg.getYOffset() + getLegendHeightPx(chart) + dp(8);

            long lots = Math.round(dataList.get(index).getVolume() / VOLUME_DIVISOR); // 張數整數
            canvas.drawText(String.valueOf(lots), baseX, baseY, textPaint);
        }
    }
    public class CoordinateMarkerView extends com.github.mikephil.charting.components.MarkerView {
        private final Paint textPaint;
        private final Paint measurePaint;
        private final List<StockDayPrice> dataList;

        public CoordinateMarkerView(Context context, List<StockDayPrice> dataList) {
            super(context, android.R.layout.select_dialog_item);
            this.dataList = dataList;

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(30f);
            textPaint.setColor(Color.YELLOW);

            measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        private float getLegendHeightPx(CombinedChart chart) {
            Legend lg = chart.getLegend();
            List<com.github.mikephil.charting.utils.FSize> lines = lg.getCalculatedLineSizes();
            float oneLine = Math.max(lg.getTextSize(), lg.getFormSize());
            if (lines == null || lines.isEmpty()) return oneLine;

            float h = 0f;
            for (com.github.mikephil.charting.utils.FSize s : lines) h += s.height;
            h += lg.getYEntrySpace() * (lines.size() - 1);
            return Math.max(h, oneLine);
        }

        private String fmt(Float v) {
            if (v == null || Float.isNaN(v) || Float.isInfinite(v)) return "--";
            float av = Math.abs(v);
            return (av >= 100f)
                    ? String.format(Locale.US, "%.0f", v)
                    : String.format(Locale.US, "%.2f", v);
        }

        private Float getMaY(CombinedChart chart, int dsIndex, float x) {
            com.github.mikephil.charting.data.LineData ld = chart.getLineData();
            if (ld == null || dsIndex < 0 || dsIndex >= ld.getDataSetCount()) return null;

            com.github.mikephil.charting.interfaces.datasets.ILineDataSet ds = ld.getDataSetByIndex(dsIndex);
            if (ds == null || !ds.isVisible()) return null;

            com.github.mikephil.charting.data.Entry e = ds.getEntryForXValue(
                    x, Float.NaN, com.github.mikephil.charting.data.DataSet.Rounding.CLOSEST);
            return (e == null) ? null : e.getY();
        }

        private Float getClose(CombinedChart chart, float x) {
            com.github.mikephil.charting.data.CandleData cd = chart.getCandleData();
            if (cd == null || cd.getDataSetCount() <= 0) return null;

            com.github.mikephil.charting.interfaces.datasets.ICandleDataSet ds = cd.getDataSetByIndex(0);
            if (ds == null || !ds.isVisible()) return null;

            com.github.mikephil.charting.data.CandleEntry ce = ds.getEntryForXValue(
                    x, Float.NaN, com.github.mikephil.charting.data.DataSet.Rounding.CLOSEST);
            return (ce == null) ? null : ce.getClose();
        }@Override
        public void draw(Canvas canvas, float posX, float posY) {
            if (getChartView() == null || getChartView().getHighlighted() == null
                    || getChartView().getHighlighted().length == 0) return;

            CombinedChart chart = (CombinedChart) getChartView();
            Highlight h = chart.getHighlighted()[0];
            int index = Math.round(h.getX());
            if (index < 0 || index >= dataList.size()) return;

            Legend lg = chart.getLegend();

            float left = chart.getViewPortHandler().contentLeft();
            float top  = chart.getViewPortHandler().contentTop();

            // ✅ Legend 的 offset 是 dp，要轉 px
            float baseX = left + lg.getXOffset() + dp(16);
            float baseY = top + lg.getYOffset() + getLegendHeightPx(chart) + dp(8);
            // 取得要顯示的 3 個數字（MA1 / MA2 / Close）
            float x = h.getX();
            String ma1Text = fmt(getMaY(chart, 0, x));
            String ma2Text = fmt(getMaY(chart, 1, x));
            String closeText = fmt(getClose(chart, x));

            LegendEntry[] entries = lg.getEntries();
            if (entries == null) entries = new LegendEntry[0];
            int c = entries.length;

            // labelSizes 是 px（MPAndroidChart 已算好）
            List<com.github.mikephil.charting.utils.FSize> labelSizes = lg.getCalculatedLabelSizes();

            // fallback measure（用 legend 文字大小/字型）
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setTextSize(lg.getTextSize());
            if (lg.getTypeface() != null) lp.setTypeface(lg.getTypeface());

            // 數字要靠左
            textPaint.setTextAlign(Paint.Align.LEFT);

            float curX = baseX;

            // ✅ Legend spacing 都是 dp，要先轉 px（避免累積偏差）
            float f2tPx = dp(lg.getFormToTextSpace());
            float xEntrySpacePx = dp(lg.getXEntrySpace());

            for (int i = 0; i < c; i++) {
                // 依 legend entry 數量決定顯示哪個值（你目前 legend 順序：MA1、MA2、K線）
                String v;
                if (c == 1) {
                    v = closeText;
                } else if (c == 2) {
                    v = (i == 0) ? ma1Text : closeText;
                } else { // c >= 3
                    v = (i == 0) ? ma1Text : (i == 1 ? ma2Text : closeText);
                }

                // 畫在每個 entry block 的左邊界
                canvas.drawText(v, curX, baseY, textPaint);

                // label 寬（px）
                float labelW;
                if (labelSizes != null && i < labelSizes.size()) {
                    labelW = labelSizes.get(i).width;
                } else {
                    String label = (entries[i].label == null) ? "" : entries[i].label;
                    labelW = lp.measureText(label);
                }

                // form 寬（dp->px）
                boolean hasForm = (entries[i].form != Legend.LegendForm.NONE);
                float formWdp = (!Float.isNaN(entries[i].formSize)) ? entries[i].formSize : lg.getFormSize();
                float formWpx = dp(formWdp);

                // 推進到下一個 entry 起點
                float entryW = 0f;
                if (hasForm) {
                    entryW += formWpx;
                    if (labelW > 0f) entryW += f2tPx;
                }
                entryW += labelW;

                curX += entryW + xEntrySpacePx;
            }

            // 日期顯示維持不變（下一行、從 baseX 開始）
            textPaint.setTextAlign(Paint.Align.LEFT);
            String dateText = dataList.get(index).getDate();
            canvas.drawText(dateText, baseX, baseY + dp(16), textPaint);
        }
    }
    // RSI
    private CombinedData drawRsiIndicatorChartData(List<StockDayPrice> displayedList) {
        indicatorChart.clear();
        CombinedData combined = new CombinedData();

        ArrayList<Entry> entries = new ArrayList<>();
        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            if (p != null && !Double.isNaN(p.rsi)) entries.add(new Entry(i, (float) p.rsi));
        }

        if (!entries.isEmpty()) {
            LineDataSet set = new LineDataSet(entries, "RSI");
            set.setColor(Color.YELLOW);
            set.setDrawCircles(false);
            set.setDrawValues(false);
            set.setLineWidth(1.5f);
            combined.setData(new LineData(set));
        }

        combined.setData(new BarData());
        indicatorChart.setData(combined);

        YAxis leftAxis = indicatorChart.getAxisLeft();
        leftAxis.resetAxisMinimum();
        leftAxis.resetAxisMaximum();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);

        indicatorChart.notifyDataSetChanged();
        indicatorChart.invalidate();
        return combined;
    }

    private void updateRsiIndicatorAxis() {
        YAxis leftAxis = indicatorChart.getAxisLeft();
        leftAxis.resetAxisMinimum();
        leftAxis.resetAxisMaximum();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);

        addRsiDmiGridLines(leftAxis);
        indicatorChart.getAxisRight().setEnabled(false);
    }

    private void addRsiDmiGridLines(YAxis axis) {
        axis.removeAllLimitLines();
        axis.setDrawLimitLinesBehindData(false);

        for (float v : new float[]{25f, 50f, 75f}) {
            LimitLine ll = new LimitLine(v, String.valueOf((int) v));
            ll.setTextColor(Color.GRAY);
            ll.setTextSize(8f);
            ll.setLineColor(Color.parseColor("#80FFFFFF"));
            ll.setLineWidth(1.0f);
            ll.enableDashedLine(6f, 6f, 0f);
            axis.addLimitLine(ll);
        }
    }

    private void clearIndicatorExtraLines() {
        indicatorChart.getAxisLeft().removeAllLimitLines();
    }

    // DMI
    private void drawDmiIndicatorChartData(List<StockDayPrice> displayedList) {
        indicatorChart.clear();
        CombinedData combined = new CombinedData();

        ArrayList<Entry> pdi = new ArrayList<>();
        ArrayList<Entry> mdi = new ArrayList<>();
        ArrayList<Entry> adx = new ArrayList<>();

        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            if (p == null) continue;
            if (!Double.isNaN(p.dmiPDI)) pdi.add(new Entry(i, (float) p.dmiPDI));
            if (!Double.isNaN(p.dmiMDI)) mdi.add(new Entry(i, (float) p.dmiMDI));
            if (!Double.isNaN(p.dmiADX)) adx.add(new Entry(i, (float) p.dmiADX));
        }

        LineData lineData = new LineData();
        if (!pdi.isEmpty()) {
            LineDataSet s = new LineDataSet(pdi, "PDI");
            s.setColor(Color.RED);
            s.setDrawCircles(false);
            s.setDrawValues(false);
            lineData.addDataSet(s);
        }
        if (!mdi.isEmpty()) {
            LineDataSet s = new LineDataSet(mdi, "MDI");
            s.setColor(Color.GREEN);
            s.setDrawCircles(false);
            s.setDrawValues(false);
            lineData.addDataSet(s);
        }
        if (!adx.isEmpty()) {
            LineDataSet s = new LineDataSet(adx, "ADX");
            s.setColor(Color.WHITE);
            s.setDrawCircles(false);
            s.setDrawValues(false);
            lineData.addDataSet(s);
        }

        combined.setData(lineData);
        combined.setData(new BarData());
        indicatorChart.setData(combined);

        YAxis leftAxis = indicatorChart.getAxisLeft();
        leftAxis.resetAxisMinimum();
        leftAxis.resetAxisMaximum();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);

        indicatorChart.notifyDataSetChanged();
        indicatorChart.invalidate();
    }

    private void updateDmiIndicatorAxis(List<StockDayPrice> displayedList) {
        YAxis leftAxis = indicatorChart.getAxisLeft();
        leftAxis.resetAxisMinimum();
        leftAxis.resetAxisMaximum();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);

        indicatorChart.getAxisRight().setEnabled(false);
        addRsiDmiGridLines(leftAxis);
        indicatorChart.notifyDataSetChanged();
    }

    // Scatter shape renderers
    public static class CustomAsteriskRenderer implements com.github.mikephil.charting.renderer.scatter.IShapeRenderer {
        @Override
        public void renderShape(Canvas canvas,
                                com.github.mikephil.charting.interfaces.datasets.IScatterDataSet dataSet,
                                com.github.mikephil.charting.utils.ViewPortHandler viewPortHandler,
                                float x, float y, Paint renderPaint) {

            float shapeSize = dataSet.getScatterShapeSize();
            float r = shapeSize / 2f;

            renderPaint.setStyle(Paint.Style.STROKE);
            renderPaint.setStrokeWidth(Math.max(2f, shapeSize * 0.12f));
            renderPaint.setColor(dataSet.getColor());

            canvas.drawLine(x - r, y, x + r, y, renderPaint);
            canvas.drawLine(x, y - r, x, y + r, renderPaint);
            canvas.drawLine(x - r, y - r, x + r, y + r, renderPaint);
            canvas.drawLine(x - r, y + r, x + r, y - r, renderPaint);
        }
    }

    public static class CustomTriangleRenderer implements com.github.mikephil.charting.renderer.scatter.IShapeRenderer {
        private final int type; // 0: up, 1: down, 2: left
        public CustomTriangleRenderer(int type) { this.type = type; }

        @Override
        public void renderShape(Canvas canvas,
                                com.github.mikephil.charting.interfaces.datasets.IScatterDataSet dataSet,
                                com.github.mikephil.charting.utils.ViewPortHandler viewPortHandler,
                                float x, float y, Paint renderPaint) {

            renderPaint.setStyle(Paint.Style.FILL);
            renderPaint.setColor(dataSet.getColor());

            float s = dataSet.getScatterShapeSize();
            float h = s / 2f;

            Path path = new Path();
            if (type == 1) { // ▼
                path.moveTo(x - h, y - h);
                path.lineTo(x + h, y - h);
                path.lineTo(x, y + h);
            } else if (type == 2) { // ◀
                path.moveTo(x + h, y - h);
                path.lineTo(x + h, y + h);
                path.lineTo(x - h, y);
            } else { // ▲
                path.moveTo(x, y - h);
                path.lineTo(x - h, y + h);
                path.lineTo(x + h, y + h);
            }
            path.close();
            canvas.drawPath(path, renderPaint);
        }
    }

    public static class NoShapeRenderer implements com.github.mikephil.charting.renderer.scatter.IShapeRenderer {
        @Override
        public void renderShape(Canvas canvas,
                                com.github.mikephil.charting.interfaces.datasets.IScatterDataSet dataSet,
                                com.github.mikephil.charting.utils.ViewPortHandler viewPortHandler,
                                float x, float y, Paint renderPaint) {
            // 不畫任何形狀，只讓 ValueText 顯示
        }
    }

    private static class GapLabelCandidate {
        final boolean isUpGap;
        final float x, y, edge, gapSize;

        GapLabelCandidate(boolean isUpGap, float x, float y, float edge, float gapSize) {
            this.isUpGap = isUpGap;
            this.x = x;
            this.y = y;
            this.edge = edge;
            this.gapSize = gapSize;
        }
    }

    private void addGapPriceLabels(ScatterData scatterData, List<StockDayPrice> list) {
        if (list == null || list.size() < 2) return;

        final float MIN_GAP_PCT = 0.01f;
        final int MIN_X_SPACING = 6;
        final int MAX_LABELS_TOTAL = 20;
        final boolean PICK_LARGEST = false;

        double maxHigh = list.stream().mapToDouble(StockDayPrice::getHigh).max().orElse(0);
        double minLow = list.stream().mapToDouble(StockDayPrice::getLow).min().orElse(0);
        float yRange = (float) (maxHigh - minLow);
        float anchorShift = (yRange > 0f) ? (yRange * 0.05f) : 0.5f;

        List<GapLabelCandidate> candidates = new ArrayList<>();

        for (int i = 1; i < list.size(); i++) {
            StockDayPrice prev = list.get(i - 1);
            StockDayPrice curr = list.get(i);

            if (curr.getLow() > prev.getHigh()) {
                float edge = (float) prev.getHigh();
                float gapSize = (float) (curr.getLow() - prev.getHigh());
                float gapPct = gapSize / Math.max(edge, 0.0001f);
                if (gapPct >= MIN_GAP_PCT) {
                    float x = i - 0.5f;
                    float y = edge - anchorShift;
                    candidates.add(new GapLabelCandidate(true, x, y, edge, gapSize));
                }
            }

            if (curr.getHigh() < prev.getLow()) {
                float edge = (float) prev.getLow();
                float gapSize = (float) (prev.getLow() - curr.getHigh());
                float gapPct = gapSize / Math.max(edge, 0.015f);
                if (gapPct >= MIN_GAP_PCT) {
                    float x = i - 0.5f;
                    float y = edge - anchorShift;
                    candidates.add(new GapLabelCandidate(false, x, y, edge, gapSize));
                }
            }
        }

        if (candidates.isEmpty()) return;

        if (PICK_LARGEST) candidates.sort((a, b) -> Float.compare(b.gapSize, a.gapSize));
        else candidates.sort((a, b) -> Float.compare(b.x, a.x));

        List<GapLabelCandidate> selected = new ArrayList<>();
        for (GapLabelCandidate c : candidates) {
            if (selected.size() >= MAX_LABELS_TOTAL) break;
            boolean tooClose = false;
            for (GapLabelCandidate s : selected) {
                if (Math.abs(c.x - s.x) < MIN_X_SPACING) { tooClose = true; break; }
            }
            if (!tooClose) selected.add(c);
        }

        selected.sort((a, b) -> Float.compare(a.x, b.x));

        List<Entry> upEntries = new ArrayList<>();
        List<Entry> downEntries = new ArrayList<>();
        for (GapLabelCandidate c : selected) {
            Entry e = new Entry(c.x, c.y);
            e.setData(c.edge);
            if (c.isUpGap) upEntries.add(e);
            else downEntries.add(e);
        }

        if (!upEntries.isEmpty()) scatterData.addDataSet(buildGapLabelSet(upEntries, Color.CYAN, GAP_LABEL_UP));
        if (!downEntries.isEmpty()) scatterData.addDataSet(buildGapLabelSet(downEntries, Color.MAGENTA, GAP_LABEL_DOWN));
    }

    private ScatterDataSet buildGapLabelSet(List<Entry> entries, int textColor, String label) {
        ScatterDataSet set = new ScatterDataSet(entries, label);
        set.setShapeRenderer(new NoShapeRenderer());
        set.setDrawValues(true);
        set.setValueTextColor(textColor);
        set.setValueTextSize(10f);
        set.setDrawIcons(false);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setForm(Legend.LegendForm.NONE);

        set.setValueFormatter(new ValueFormatter() {
            @Override
            public String getPointLabel(Entry entry) {
                return String.format(Locale.US, "%.1f", entry.getData());
            }
        });
        return set;
    }

    private void updateGapLabelVisibility() {
        if (mainChart == null || mainChart.getData() == null || mainChart.getData().getScatterData() == null) return;

        float sx = mainChart.getViewPortHandler().getScaleX();
        float sy = mainChart.getViewPortHandler().getScaleY();
        boolean show = (sx > GAP_LABEL_SHOW_SCALE) || (sy > GAP_LABEL_SHOW_SCALE);

        for (IScatterDataSet ds : mainChart.getData().getScatterData().getDataSets()) {
            String label = ds.getLabel();
            if ((GAP_LABEL_UP.equals(label) || GAP_LABEL_DOWN.equals(label)) && ds instanceof ScatterDataSet) {
                ((ScatterDataSet) ds).setDrawValues(show);
            }
        }
    }
}