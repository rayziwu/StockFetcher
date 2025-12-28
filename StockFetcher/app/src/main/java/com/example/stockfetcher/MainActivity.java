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

    private Button intervalSwitchButton, viewModeButton, indicatorModeButton;
    private EditText stockIdEditText, startDateEditText, comparisonStockIdEditText;

    private boolean isSwitchingInterval = false;
    private boolean suppressNextStockIdFocusFetch = false;

    private String currentStockId = "2330.TW";
    private final String[] VIEW_MODES = {"ALL", "K", "Vo", "VP"};
    private int currentViewModeIndex = 0;

    private final String[] INTERVALS = {"1d", "1wk", "1mo", "1h"};
    private int currentIntervalIndex = 0;
    private String currentInterval = INTERVALS[currentIntervalIndex];
    private String[] displayText;

    private IndicatorMode indicatorMode = IndicatorMode.MACD;
    private int currentKDNPeriod = 9;

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

    private CoordinateMarkerView coordinateMarker;
    // 從股票清單 cache 讀到的 meta（ticker -> info）
    private final Map<String, TickerInfo> tickerMetaMap = new HashMap<>();
    private static final String TICKERS_CACHE_FILE = "股票代碼.csv";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyLanguagePolicyBySystemLocale();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                return; // 等使用者回應後再啟動篩選/Service
            }
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        initUi();
        loadSelectedIndustriesFromPrefs();
 //     showIndustryPickerAfterTickerListReady();

        initCharts();
        preloadTickerMetaIfCsvExists();
        // 程式啟動時，預設繪製
        fetchStockDataWithFallback(currentStockId, currentInterval,
                getStartTimeLimit(currentInterval, isSwitchingInterval));
    }
    private void registerScreenerReceiverOnce() {
        if (screenerReceiverRegistered) return;

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
    @Override
    protected void onStart() {
        super.onStart();

        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(ScreenerForegroundService.ACTION_PROGRESS);
        f.addAction(ScreenerForegroundService.ACTION_DONE);
        f.addAction(ScreenerForegroundService.ACTION_FAIL);

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // ✅ Android 13+ 必須指定 exported/not_exported
            registerReceiver(screenerReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenerReceiver, f);
        }
    }
    @Override
    protected void onStop() {
          super.onStop();
    }

    private void showIndustryPickerAfterTickerListReady() {
        // 你若希望每次都跳，就拿掉這行
        if (!selectedIndustries.isEmpty()) return;

        new Thread(() -> {
            List<TickerInfo> tickers = TwTickerRepository.loadOrScrape(getApplicationContext());
            if (tickers == null || tickers.isEmpty()) return;

            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            for (TickerInfo ti : tickers) {
                if (ti == null) continue;
                String ind = (ti.industry == null) ? "" : ti.industry.trim();
                if (!ind.isEmpty()) set.add(ind);
            }
            final String[] items = set.toArray(new String[0]);
            final boolean[] checked = new boolean[items.length];

            // 預設全選
            for (int i = 0; i < checked.length; i++) checked[i] = true;

            runOnUiThread(() -> {
                final java.util.HashSet<String> tmp = new java.util.HashSet<>();
                for (String s : items) tmp.add(s);

                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("選擇產業類別（可複選）")
                        .setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                            String ind = items[which];
                            if (isChecked) tmp.add(ind); else tmp.remove(ind);
                        })
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            selectedIndustries.clear();
                            selectedIndustries.addAll(tmp);
                            // 若全取消，視為不限制（或你也可改成「不允許空集合」）
                            saveSelectedIndustriesToPrefs();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start();
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
    private void preloadTickerMetaIfCsvExists() {
        if (tickerMetaLoadedOnce) return;

        java.io.File internal = new java.io.File(getFilesDir(), TICKERS_CACHE_FILE);

        // 兼容：如果你有改成存 externalFilesDir，也一起找（不影響原本 internal）
        java.io.File external = null;
        try {
            java.io.File extDir = getExternalFilesDir(null);
            if (extDir != null) external = new java.io.File(extDir, TICKERS_CACHE_FILE);
        } catch (Exception ignored) {}

        java.io.File f = internal.exists() ? internal : (external != null && external.exists() ? external : null);
        if (f == null) return;

        loadTickerMetaFromCsv(f);
        tickerMetaLoadedOnce = true;
    }

    private void loadTickerMetaFromCsv(java.io.File file) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {

            String header = br.readLine();
            if (header == null) return;
            if (header.startsWith("\uFEFF")) header = header.substring(1); // BOM

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
                if (line.trim().isEmpty()) continue;

                String[] cols = splitCsvLine(line);

                String tk = (iTicker >= 0 && iTicker < cols.length) ? cols[iTicker].trim()
                        : (cols.length > 0 ? cols[0].trim() : "");

                if (tk.isEmpty() || tk.equalsIgnoreCase("ticker")) continue;

                String tku = tk.trim().toUpperCase(Locale.US);

                String name = (iName >= 0 && iName < cols.length) ? cols[iName].trim() : "";
                String ind  = (iInd >= 0 && iInd < cols.length) ? cols[iInd].trim() : "";

                // 存 full ticker
                tickerMetaMap.put(tku, new TickerInfo(tku, name, ind));

                // 兼容：如果是 2330.TW / 2330.TWO，也順便存 2330（避免使用者沒打後綴時查不到）
                if (tku.matches("^\\d{4}\\.(TW|TWO)$")) {
                    String base = tku.substring(0, 4);
                    // 若已存在 base key，保留先來的（可依你需求改成偏好 .TW）
                    if (!tickerMetaMap.containsKey(base)) {
                        tickerMetaMap.put(base, new TickerInfo(base, name, ind));
                    }
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "loadTickerMetaFromCsv failed: " + e.getMessage());
        }
    }
    private void bindViews() {
        intervalSwitchButton = findViewById(R.id.intervalSwitchButton);
        viewModeButton = findViewById(R.id.viewModeButton);
        indicatorModeButton = findViewById(R.id.indicatorModeButton);

        startDateEditText = findViewById(R.id.startDateEditText);
        comparisonStockIdEditText = findViewById(R.id.comparisonStockIdEditText);
        stockIdEditText = findViewById(R.id.stockIdEditText);

        mainChart = findViewById(R.id.mainChart);
        k_kdChart = findViewById(R.id.k_kdChart);
        indicatorChart = findViewById(R.id.indicatorChart);
        vpView = findViewById(R.id.vpView);
        screenerButton = findViewById(R.id.screenerButton);
    }

    private void initUi() {
        yahooFinanceFetcher = new YahooFinanceFetcher();
        vpView.bindMainChart(mainChart);

        displayText = getResources().getStringArray(R.array.interval_display_text);
        intervalSwitchButton.setText(displayText[currentIntervalIndex]);

        // view mode button：固定高度 + 初始文字 + 切換
        //viewModeButton.getLayoutParams().height =
        //        (int) (24 * getResources().getDisplayMetrics().density);
        updateViewModeButtonText();
        viewModeButton.setOnClickListener(v -> {
            currentViewModeIndex = (currentViewModeIndex + 1) % VIEW_MODES.length;
            updateViewModeButtonText();
            if (fullPriceList != null && !fullPriceList.isEmpty()) drawCharts(fullPriceList, currentStockId);
        });

        // indicator mode button：切換
        updateIndicatorModeButtonText();
        indicatorModeButton.setOnClickListener(v -> {
            indicatorMode = (indicatorMode == IndicatorMode.MACD) ? IndicatorMode.RSI
                    : (indicatorMode == IndicatorMode.RSI) ? IndicatorMode.DMI
                    : IndicatorMode.MACD;
            updateIndicatorModeButtonText();
            if (fullPriceList != null && !fullPriceList.isEmpty()) drawCharts(fullPriceList, currentStockId);
        });

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

            String stockId = stockIdEditText.getText().toString().trim();
            if (!stockId.isEmpty() && !stockId.equalsIgnoreCase(currentStockId)) {
                fetchStockDataWithFallback(stockId, currentInterval,
                        getStartTimeLimit(currentInterval, isSwitchingInterval));
            } else if (stockId.isEmpty()) {
                stockIdEditText.setText(currentStockId);
            }
            hideKeyboard(v);
        });

        stockIdEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;

            String stockId = v.getText().toString().trim();
            if (!stockId.isEmpty() && !stockId.equalsIgnoreCase(currentStockId)) {
                fetchStockDataWithFallback(stockId, currentInterval,
                        getStartTimeLimit(currentInterval, isSwitchingInterval));
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
        if (viewModeButton != null) viewModeButton.setEnabled(enabled);
        if (indicatorModeButton != null) indicatorModeButton.setEnabled(enabled);

        if (stockIdEditText != null) stockIdEditText.setEnabled(enabled);
        if (startDateEditText != null) startDateEditText.setEnabled(enabled);
        if (comparisonStockIdEditText != null) comparisonStockIdEditText.setEnabled(enabled);

        // 篩選按鈕保持可用（用於顯示進度/提示）fviewModeButton.getLayoutParams().height
        if (screenerButton != null) screenerButton.setEnabled(true);
    }
    private void applyIntervalForScreener(ScreenerMode mode) {
        if (mode == null) return;

        final String target;
        switch (mode) {
            case KD9_MO_GC: target = "1mo"; break;
            case KD9_WK_GC: target = "1wk"; break;
            case LT20:
            case GT45:
            case MA60_3PCT:
            default: target = "1d"; break;
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
    }
    private void openCsvListPicker() {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, getString(R.string.error_csv_dir_unavailable), Toast.LENGTH_LONG).show();
            return;
        }

        java.io.File[] files = dir.listFiles((d, name) -> name != null && name.toLowerCase(Locale.US).endsWith(".csv"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, getString(R.string.error_csv_not_found, dir.getAbsolutePath()), Toast.LENGTH_LONG).show();
            return;
        }

        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        CharSequence[] items = new CharSequence[files.length];
        for (int i = 0; i < files.length; i++) items[i] = files[i].getName();

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.screener_pick_csv_title)
                .setItems(items, (dlg, which) -> {
                    java.io.File f = files[which];
                    List<String> tickers = readFirstColumnTickersFromCsv(f);
                    if (tickers.isEmpty()) {
                        Toast.makeText(this, getString(R.string.error_csv_no_tickers, f.getName()), Toast.LENGTH_LONG).show();
                        return;
                    }

                    // 轉成 screenerResults（假設 ScreenerResult 有 public field: ticker）
                    screenerResults.clear();
                    for (String t : tickers) {
                        String tk = (t == null) ? "" : t.trim().toUpperCase(Locale.US);
                        if (tk.isEmpty()) continue;

                        // CSV list mode：只有 ticker，其它欄位先填空/未知
                        ScreenerResult r = new ScreenerResult(
                                tk,            // ticker
                                "",            // name
                                "",            // industry
                                Double.NaN,    // avgClose60 (unknown)
                                Double.NaN,    // latestClose (unknown)
                                null,          // lastK
                                null,          // lastD
                                null,          // runDays
                                null,          // ma60
                                null,          // ma60DiffPct
                                null           // crossDate
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

                    showFilteredAt(0, true);
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
    private void exportScreenerResultsToCsv() {
        if (screenerResults.isEmpty()) {
            Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show();
            return;
        }

        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(this,
                    getString(R.string.error_screen_failed, "Export dir unavailable"),
                    Toast.LENGTH_LONG).show();
            return;
        }

        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());

        // Tag 對齊 Python
        final String tag;
        if (screenerMode == null) {
            tag = "MODE";
        } else {
            switch (screenerMode) {
                case LT20:      tag = "LT20"; break;
                case GT45:      tag = "GT45"; break;
                case MA60_3PCT: tag = "MA60_3PCT_40D"; break;
                case KD9_MO_GC: tag = "KD9_MO_GC"; break;
                case KD9_WK_GC: tag = "KD9_WK_GC"; break;
                default:        tag = "MODE"; break;
            }
        }

        String filename = "TW_SCREENER_" + tag + "_" + ts + ".csv";
        java.io.File outFile = new java.io.File(dir, filename);

        try (java.io.FileWriter fw = new java.io.FileWriter(outFile, false)) {

            // ===== Header (依模式變動，對齊 Python 輸出欄位概念) =====
            String header;
            if (screenerMode == ScreenerMode.MA60_3PCT) {
                header = "Ticker,Name,Industry,AvgClose60,LatestClose,MA_60,MA60_DiffPct\n";
            } else if (screenerMode == ScreenerMode.GT45) {
                header = "Ticker,Name,Industry,AvgClose60,LatestClose,LastK40,KD45_RunDays\n";
            } else if (screenerMode == ScreenerMode.LT20) {
                header = "Ticker,Name,Industry,AvgClose60,LatestClose,LastK40\n";
            } else if (screenerMode == ScreenerMode.KD9_MO_GC || screenerMode == ScreenerMode.KD9_WK_GC) {
                header = "Ticker,Name,Industry,AvgClose60,LatestClose,Last_K9,Last_D9,Cross_Date\n";
            } else {
                // fallback：輸出完整欄位
                header = "Ticker,Name,Industry,AvgClose60,LatestClose,LastK,LastD,RunDays,MA_60,MA60_DiffPct,Cross_Date\n";
            }

            fw.write(header);

            // ===== Rows =====
            for (ScreenerResult r : screenerResults) {
                if (r == null) continue;

                // base
                String base =
                        csv(r.ticker) + "," +
                                csv(r.name) + "," +
                                csv(r.industry) + "," +
                                numOrEmpty(r.avgClose60) + "," +
                                numOrEmpty(r.latestClose);

                if (screenerMode == ScreenerMode.MA60_3PCT) {
                    fw.write(base + "," +
                            numOrEmpty(r.ma60) + "," +
                            numOrEmpty(r.ma60DiffPct) +
                            "\n");
                } else if (screenerMode == ScreenerMode.GT45) {
                    fw.write(base + "," +
                            numOrEmpty(r.lastK) + "," +                  // LastK40
                            (r.runDays == null ? "" : r.runDays) +
                            "\n");
                } else if (screenerMode == ScreenerMode.LT20) {
                    fw.write(base + "," +
                            numOrEmpty(r.lastK) +                         // LastK40
                            "\n");
                } else if (screenerMode == ScreenerMode.KD9_MO_GC || screenerMode == ScreenerMode.KD9_WK_GC) {
                    fw.write(base + "," +
                            numOrEmpty(r.lastK) + "," +                   // Last_K9
                            numOrEmpty(r.lastD) + "," +                   // Last_D9
                            csv(r.crossDate) +
                            "\n");
                } else {
                    // fallback 全欄位
                    fw.write(base + "," +
                            numOrEmpty(r.lastK) + "," +
                            numOrEmpty(r.lastD) + "," +
                            (r.runDays == null ? "" : r.runDays) + "," +
                            numOrEmpty(r.ma60) + "," +
                            numOrEmpty(r.ma60DiffPct) + "," +
                            csv(r.crossDate) +
                            "\n");
                }
            }

            pendingExportCsv = outFile.getAbsolutePath();
            Toast.makeText(this, "Saved: " + pendingExportCsv, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.error_screen_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

// ---------- helpers：放在 MainActivity 內任意位置（若你已經有同名可跳過） ----------

    private String csv(String s) {
        if (s == null) return "";
        String v = s;
        boolean needQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        if (!needQuote) return v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private String numOrEmpty(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.valueOf(v);
    }

    private String numOrEmpty(Double v) {
        if (v == null) return "";
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.valueOf(v);
    }
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

        this.screenerMode = mode;
        this.isScreening = true;
        setControlsEnabled(false);
        screenerButton.setText("0%");

        // Android 13+ 沒通知權限時，FGS 可能無法正常顯示通知而被系統終止
        //（建議你在 UI 層先申請 POST_NOTIFICATIONS 再啟動）
        android.content.Intent it = new android.content.Intent(this, ScreenerForegroundService.class);
        it.setAction(ScreenerForegroundService.ACTION_START);
        it.putExtra(ScreenerForegroundService.EXTRA_MODE, mode.name());
        it.putStringArrayListExtra(ScreenerForegroundService.EXTRA_INDUSTRIES,
                new java.util.ArrayList<>(selectedIndustries));

        androidx.core.content.ContextCompat.startForegroundService(this, it);
    }
    private void showScreenerModeDialog() {
        final CharSequence[] items = new CharSequence[] {
                getString(R.string.screener_mode_lt20),
                getString(R.string.screener_mode_gt45),
                getString(R.string.screener_mode_ma60_3pct),
                getString(R.string.screener_mode_kd9_mo_gc),
                getString(R.string.screener_mode_kd9_wk_gc),
                getString(R.string.screener_mode_csv_list),
        };

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.screener_title)
                .setItems(items, (dlg, which) -> {
                    switch (which) {
                        case 0: prepareIndustryThenStartScreening(ScreenerMode.LT20); break;
                        case 1: prepareIndustryThenStartScreening(ScreenerMode.GT45); break;
                        case 2: prepareIndustryThenStartScreening(ScreenerMode.MA60_3PCT); break;
                        case 3: prepareIndustryThenStartScreening(ScreenerMode.KD9_MO_GC); break;
                        case 4: prepareIndustryThenStartScreening(ScreenerMode.KD9_WK_GC); break;
                        case 5: openCsvListPicker(); break;
                    }
                })
                .show();
    }
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
        if (industries == null) industries = new String[0];

        // ✅ 先過濾掉「上市...」並產生 final items（避免 lambda 引用到非 final 的 industries）
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String s : industries) {
            if (s == null) continue;
            String ind = s.trim();
            if (ind.isEmpty()) continue;
            if (ind.startsWith("上市")) continue; // 移除「上市...」
            list.add(ind);
        }
        final String[] items = list.toArray(new String[0]);

        // 沒有可選產業就直接開始（等同全市場）
        if (items.length == 0) {
            selectedIndustries.clear();
            saveSelectedIndustriesToPrefs();
            if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
            setControlsEnabled(true);
            startScreening(mode);
            return;
        }

        loadSelectedIndustriesFromPrefs();

        // 預設：若沒選過則全選；有選過就照上次
        final boolean[] checked = new boolean[items.length];
        if (selectedIndustries.isEmpty()) {
            for (int i = 0; i < checked.length; i++) checked[i] = true;
        } else {
            for (int i = 0; i < items.length; i++) checked[i] = selectedIndustries.contains(items[i]);
        }

        // 暫存選擇（final 參考可在 lambda 中使用）
        final java.util.HashSet<String> tmp = new java.util.HashSet<>();
        for (int i = 0; i < items.length; i++) {
            if (checked[i]) tmp.add(items[i]);
        }

        // ---- 自訂 title：標題 + [全選] [全不選] ----
        android.widget.LinearLayout titleBar = new android.widget.LinearLayout(this);
        titleBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int pad = Math.round(12f * getResources().getDisplayMetrics().density);
        titleBar.setPadding(pad, pad, pad, pad);

        android.widget.TextView tvTitle = new android.widget.TextView(this);
      //tvTitle.setText("選擇產業類別（可複選）");
        tvTitle.setText(getString(R.string.industry_picker_title));
        tvTitle.setTextColor(android.graphics.Color.WHITE);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        android.widget.LinearLayout.LayoutParams lpTitle =
                new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(lpTitle);

        android.widget.Button btnAll = new android.widget.Button(this);
        //btnAll.setText("全選");
        btnAll.setText(getString(R.string.btn_select_all));
        android.widget.Button btnNone = new android.widget.Button(this);
        //btnNone.setText("全不選");
        btnNone.setText(getString(R.string.btn_select_none));
        titleBar.addView(tvTitle);
        titleBar.addView(btnAll);
        titleBar.addView(btnNone);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setCustomTitle(titleBar)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                    String ind = items[which];
                    if (isChecked) tmp.add(ind); else tmp.remove(ind);
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    selectedIndustries.clear();
                    selectedIndustries.addAll(tmp);

                    // 若全不選：視為不限制產業（全市場）
                    saveSelectedIndustriesToPrefs();

                    if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
                    setControlsEnabled(true);

                    startScreening(mode);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (screenerButton != null) screenerButton.setText(getString(R.string.screener_btn));
                    setControlsEnabled(true);
                })
                .create();

        dlg.show();

        android.widget.ListView lv = dlg.getListView();

        // 全選
        btnAll.setOnClickListener(v -> {
            tmp.clear();
            for (int i = 0; i < items.length; i++) {
                checked[i] = true;
                lv.setItemChecked(i, true);
                tmp.add(items[i]);
            }
        });

        // 全不選
        btnNone.setOnClickListener(v -> {
            tmp.clear();
            for (int i = 0; i < items.length; i++) {
                checked[i] = false;
                lv.setItemChecked(i, false);
            }
        });
    }

    private void onScreeningFailed(String msg) {
        isScreening = false;
        screenerButton.setText(getString(R.string.screener_btn));
        setControlsEnabled(true);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void onScreeningDone(ScreenerMode mode, List<ScreenerResult> results) {
        isScreening = false;
        screenerButton.setText(getString(R.string.screener_btn));
        setControlsEnabled(true);

        screenerResults.clear();
        if (results != null) screenerResults.addAll(results);

// 重置自動匯出旗標
        screenerAutoExported = false;
        pendingExportCsv = null;
        screenerIndex = 0;
        screenerSessionClosed = false;
        allowSaveOnSwipeUp = true;

        if (screenerResults.isEmpty()) {
            Toast.makeText(this,
                    getString(R.string.toast_screener_none, getScreenerModeLabel(mode)),
                    Toast.LENGTH_LONG).show();
            return;
        }

// ✅ 自動匯出（只做一次）
        if (!screenerAutoExported) {
            exportScreenerResultsToCsv();
            screenerAutoExported = true;
        }

// ✅ 用 toast_screener_done（你 strings.xml 已有）
        Toast.makeText(this,
                getString(R.string.toast_screener_done, getScreenerModeLabel(mode), screenerResults.size()),
                Toast.LENGTH_LONG).show();

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_screener_done_title)
                .setMessage(getString(R.string.dialog_screener_done_msg,
                        getScreenerModeLabel(mode), screenerResults.size()))
                .setPositiveButton(android.R.string.ok, null)
                .show();

        // 建議：觀看結果時切到與篩選一致的 interval（對齊 Python）
        applyIntervalForScreener(mode);

        showFilteredAt(0, true);
    }
    private void showFilteredAt(int idx, boolean forceDownload) {
        if (screenerResults.isEmpty()) return;

        int n = screenerResults.size();
        screenerIndex = (idx % n + n) % n;

        String t = screenerResults.get(screenerIndex).ticker;

        // 更新輸入框（避免 focus 觸發額外 fetch）
        suppressNextStockIdFocusFetch = true;
        stockIdEditText.setText(t);

        // 篩選結果顯示時：比照 Python，把日期改成「最近六個月」也可以
        // 這裡保留你的 date input 設計：你若要完全對齊 Python，可改成 6 個月前的 1 日
        executeFetchForFilteredTicker(t, forceDownload);
    }
    private String getScreenerModeLabel(ScreenerMode mode) {
        if (mode == null) return "";
        switch (mode) {
            case LT20:
                return getString(R.string.screener_mode_lt20);
            case GT45:
                return getString(R.string.screener_mode_gt45);
            case MA60_3PCT:
                return getString(R.string.screener_mode_ma60_3pct);
            case KD9_MO_GC:
                return getString(R.string.screener_mode_kd9_mo_gc);
            case KD9_WK_GC:
                return getString(R.string.screener_mode_kd9_wk_gc);
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

                        List<ScreenerResult> results = readScreenerResultsFromCsv(pendingExportCsv);

                        screenerResults.clear();
                        if (results != null) screenerResults.addAll(results);

                        screenerIndex = 0;
                        screenerSessionClosed = false;
                        allowSaveOnSwipeUp = true;

                        if (screenerResults.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.toast_screener_none, getScreenerModeLabel(screenerMode)),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }

                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_screener_done, getScreenerModeLabel(screenerMode), screenerResults.size()),
                                Toast.LENGTH_LONG).show();

                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.dialog_screener_done_title)
                                .setMessage(getString(R.string.dialog_screener_done_msg,
                                        getScreenerModeLabel(screenerMode), screenerResults.size()))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();

                        // 對齊篩選用的 interval（你原本就有）
                        applyIntervalForScreener(screenerMode);

                        // ✅ 顯示第一檔，之後左右滑就會輪播
                        showFilteredAt(0, true);
                    }
                }
            };

    @Override
    public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {
     //   if (screenerResults.isEmpty()) return;

     //   float dx = me2.getX() - me1.getX();
     //   float dy = me2.getY() - me1.getY();

     //   float absX = Math.abs(dx);
     //   float absY = Math.abs(dy);

     //   final float MIN_PX = 120f; // 你可依手感微調

        // 以位移方向判斷（對齊你的指定：向右滑=右鍵、向左滑=左鍵、向上滑=ESC）
     //   if (absX > absY && absX > MIN_PX) {
     //   if (dx > 0) navFiltered(+1);   // 向右滑 => 下一檔（Right）
     //       else        navFiltered(-1);   // 向左滑 => 上一檔（Left）
     //       return;
     //   }

     //   if (absY > absX && absY > MIN_PX) {
     //       if (dy < 0) onSwipeUpEsc();    // 向上滑 => ESC
     //   }
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
    private void askExportOnceThenCloseSession() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_export_title)
                .setMessage(R.string.dialog_export_msg)
                .setPositiveButton(R.string.btn_yes, (d, w) -> exportScreenerResultsToCsv())
                .setNegativeButton(R.string.btn_no, null)
                .show();
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

                // 向上滑：結束/提示（你目前 onSwipeUpEsc 也包含取消篩選的邏輯）
                if (absY > absX && absY > MIN_PX && dy < 0) {
                    onSwipeUpEsc();
                    return true;
                }

                return false;
            }
        });

        // 讓第二圖區吃掉手勢，不把 touch 交給 chart（避免它自己處理拖拉/高亮）
        k_kdChart.setClickable(true);
        k_kdChart.setOnTouchListener((v, event) -> {
            navGestureDetector.onTouchEvent(event);
            return true; // ✅ consume，避免影響 chart 自己的觸控
        });
    }

    private void applyLanguagePolicyBySystemLocale() {
        Locale sys;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList sysLocales = Resources.getSystem().getConfiguration().getLocales();
            sys = sysLocales.get(0);
        } else {
            sys = Resources.getSystem().getConfiguration().locale;
        }

        String lang = sys.getLanguage();
        String country = sys.getCountry();

        boolean forceTraditionalChinese =
                "zh".equals(lang) && ("TW".equals(country) || "HK".equals(country) || "CN".equals(country));

        LocaleListCompat target = forceTraditionalChinese
                ? LocaleListCompat.forLanguageTags("zh-TW")
                : LocaleListCompat.forLanguageTags("en");

        String currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        String targetTags = target.toLanguageTags();
        if (!targetTags.equals(currentTags)) AppCompatDelegate.setApplicationLocales(target);
    }

    private void updateViewModeButtonText() {
        if (viewModeButton == null) return;
        int[] res = {
                R.string.view_mode_all,
                R.string.view_mode_k,
                R.string.view_mode_vo,
                R.string.view_mode_vp
        };
        viewModeButton.setText(res[currentViewModeIndex]);
    }

    private void updateIndicatorModeButtonText() {
        int resId = (indicatorMode == IndicatorMode.MACD) ? R.string.indicator_macd
                : (indicatorMode == IndicatorMode.RSI) ? R.string.indicator_rsi
                : R.string.indicator_dmi;
        indicatorModeButton.setText(resId);
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

    // 統一：若是 4 位數字且無後綴，走 .TW / .TWO fallback；否則直接抓
    private void fetchSymbolAutoFallback(String symbol, String interval, long startTime,
                                         YahooFinanceFetcher.DataFetchListener listener) {
        String base = symbol.toUpperCase(Locale.US).trim();

        boolean needFallback = base.matches("^\\d{4}$") && !base.startsWith("^") && !base.contains(".");
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
                clearAllCharts(getString(R.string.error_load_failed, baseSymbol));
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                getString(R.string.error_load_failed, baseSymbol),
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
                CombinedChart.DrawOrder.BAR,
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
        rightAxis.setEnabled(true);
        rightAxis.setDrawGridLines(false);
        rightAxis.setAxisMinimum(0f);
        rightAxis.setTextColor(Color.GRAY);
        rightAxis.setDrawLabels(false);

        mainChart.setViewPortOffsets(CHART_OFFSET_LEFT, CHART_OFFSET_TOP, CHART_OFFSET_RIGHT, CHART_OFFSET_BOTTOM);
        mainChart.setDrawMarkers(true);
    }
    private void scaleLegendTextSizeBy(Legend legend, float factor) {
        float density = getResources().getDisplayMetrics().density;
        float curDp = legend.getTextSize() / density;   // px -> dp
        legend.setTextSize(curDp * factor);             // setTextSize 需要 dp
    }
    private void setupk_kdChart() {
        k_kdChart.getDescription().setEnabled(false);
        k_kdChart.setNoDataText("KD 指標區 / 比較 K 線圖");
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


        k_kdChart.setDrawOrder(new CombinedChart.DrawOrder[]{
                CombinedChart.DrawOrder.CANDLE,
                CombinedChart.DrawOrder.LINE
        });

        k_kdChart.setTouchEnabled(false);
        k_kdChart.setScaleEnabled(false);
        k_kdChart.setDragEnabled(false);

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
        leftAxis.setAxisMinimum(0f);
        leftAxis.removeAllLimitLines();
        leftAxis.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);

        YAxis rightAxis = k_kdChart.getAxisRight();
        rightAxis.setEnabled(true);
        rightAxis.setDrawGridLines(false);
        rightAxis.setDrawLabels(true);
        rightAxis.setDrawAxisLine(true);
        rightAxis.setTextColor(Color.YELLOW);
        rightAxis.setAxisMinimum(0f);
        rightAxis.setAxisMaximum(100f);
        rightAxis.setDrawZeroLine(false);

        k_kdChart.setViewPortOffsets(CHART_OFFSET_LEFT, CHART_OFFSET_TOP, CHART_OFFSET_RIGHT, CHART_OFFSET_BOTTOM);
    }

    private void setupIndicatorChart() {
        indicatorChart.setViewPortOffsets(CHART_OFFSET_LEFT, CHART_OFFSET_TOP, CHART_OFFSET_RIGHT, CHART_OFFSET_BOTTOM);
        indicatorChart.getDescription().setEnabled(false);
        indicatorChart.setNoDataText("MACD 指標區");
        indicatorChart.setBackgroundColor(Color.BLACK);

        Legend legend = indicatorChart.getLegend();
        legend.setTextColor(Color.WHITE);
        legend.setWordWrapEnabled(true);
        legend.setForm(Legend.LegendForm.SQUARE);
        legend.setEnabled(false);

        indicatorChart.setTouchEnabled(false);
        indicatorChart.setScaleEnabled(false);
        indicatorChart.setDragEnabled(false);

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
    }

    private void drawCharts(List<StockDayPrice> fullPriceList, String symbol) {
        String mode = VIEW_MODES[currentViewModeIndex];

        if (fullPriceList == null || fullPriceList.isEmpty()) {
            Log.d(TAG, "Chart Draw Failed: fullPriceList is empty for symbol: " + symbol);
            clearAllCharts("無 " + symbol + " 歷史數據可供繪製。");
            return;
        }

        List<StockDayPrice> displayedList = getDisplayedList(fullPriceList);
        if (displayedList == null || displayedList.isEmpty()) {
            Log.w(TAG, "Chart Draw Warning: displayedList is unexpectedly empty after filtering.");
            clearAllCharts(null);
            return;
        }

        if (coordinateMarker == null) {
            coordinateMarker = new CoordinateMarkerView(this, displayedList);
            coordinateMarker.setChartView(mainChart);
            mainChart.setMarker(coordinateMarker);
        }

        calculateKDJ(displayedList, currentInterval);
        calculateMACD(displayedList);
        calculateVolumeProfile(displayedList);

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

        double maxVolume = displayedList.stream().mapToDouble(StockDayPrice::getVolume).max().orElse(0);
        YAxis rightAxis = mainChart.getAxisRight();
        rightAxis.setAxisMaximum((float) maxVolume * 4f);
        rightAxis.setAxisMinimum(0f);

        drawMainChartData(displayedList);
        drawKDAndComparisonChartData(displayedList);

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

        float chartXMax = displayedList.size() - 0.5f;
        float chartXMin = -0.5f;

        mainChart.getXAxis().setAxisMaximum(chartXMax);
        mainChart.getXAxis().setAxisMinimum(chartXMin);
        k_kdChart.getXAxis().setAxisMaximum(chartXMax);
        k_kdChart.getXAxis().setAxisMinimum(chartXMin);
        indicatorChart.getXAxis().setAxisMaximum(chartXMax);
        indicatorChart.getXAxis().setAxisMinimum(chartXMin);

        if (comparisonStockIdEditText != null) comparisonStockIdEditText.setText(displayedComparisonSymbol);

        mainChart.fitScreen();
        updateGapLabelVisibility();
        resetKDJAndComparisonAxisLimits();

        k_kdChart.fitScreen();
        indicatorChart.fitScreen();

        mainChart.invalidate();
        k_kdChart.invalidate();
        indicatorChart.invalidate();
        vpView.invalidate();

        resetKDJAndComparisonAxisLimits();
    }

    private List<StockDayPrice> getDisplayedList(List<StockDayPrice> fullList) {
        return fullList;
    }

    private void calculateKDJ(List<StockDayPrice> prices, String interval) {
        final int M1 = 3, M2 = 3;

        final int N;
        if ("1h".equals(interval) || "1d".equals(interval)) {
            N = 40;
            Log.d(TAG, "KD週期設定: " + interval + " 使用長週期 N=40");
        } else {
            N = 9;
            Log.d(TAG, "KD週期設定: " + interval + " 使用標準週期 N=9");
        }
        currentKDNPeriod = N;
        if (prices.size() < N) return;

        double prevK = 50.0, prevD = 50.0;

        for (int i = 0; i < prices.size(); i++) {
            if (i < N - 1) continue;

            double highestHigh = Double.NEGATIVE_INFINITY;
            double lowestLow = Double.POSITIVE_INFINITY;
            for (int j = i - N + 1; j <= i; j++) {
                highestHigh = Math.max(highestHigh, prices.get(j).getHigh());
                lowestLow = Math.min(lowestLow, prices.get(j).getLow());
            }

            double close = prices.get(i).getClose();
            double rsv = (highestHigh != lowestLow)
                    ? ((close - lowestLow) / (highestHigh - lowestLow)) * 100.0
                    : 50.0;

            double curK = ((M1 - 1.0) / M1) * prevK + (1.0 / M1) * rsv;
            double curD = ((M2 - 1.0) / M2) * prevD + (1.0 / M2) * curK;

            prices.get(i).kdK = curK;
            prices.get(i).kdD = curD;

            prevK = curK;
            prevD = curD;
        }
    }

    private void calculateMACD(List<StockDayPrice> prices) {
        final int SHORT_PERIOD = 12;
        final int LONG_PERIOD = 26;
        final int SIGNAL_PERIOD = 9;
        if (prices.size() < LONG_PERIOD) return;

        double[] emaShort = calculateEMA(prices, SHORT_PERIOD);
        double[] emaLong = calculateEMA(prices, LONG_PERIOD);

        for (int i = 0; i < prices.size(); i++) {
            prices.get(i).macdDIF = (i >= LONG_PERIOD - 1) ? (emaShort[i] - emaLong[i]) : Double.NaN;
        }

        double prevDEA = 0.0;
        final double mDEA = 2.0 / (SIGNAL_PERIOD + 1.0);

        for (int i = 0; i < prices.size(); i++) {
            double dif = prices.get(i).macdDIF;

            if (Double.isNaN(dif)) {
                prices.get(i).macdDEA = Double.NaN;
                continue;
            }

            if (i < LONG_PERIOD - 1 + SIGNAL_PERIOD - 1) {
                prices.get(i).macdDEA = dif;
                prevDEA = dif;
            } else {
                double dea = (dif - prevDEA) * mDEA + prevDEA;
                prices.get(i).macdDEA = dea;
                prevDEA = dea;
            }
        }

        for (StockDayPrice p : prices) {
            p.macdHistogram = (!Double.isNaN(p.macdDIF) && !Double.isNaN(p.macdDEA))
                    ? (p.macdDIF - p.macdDEA)
                    : Double.NaN;
        }
    }

    private double[] calculateEMA(List<StockDayPrice> prices, int period) {
        double[] ema = new double[prices.size()];
        final double m = 2.0 / (period + 1.0);

        double sum = 0.0;
        for (int i = 0; i < period; i++) sum += prices.get(i).getClose();
        if (prices.size() >= period) ema[period - 1] = sum / period;

        for (int i = period; i < prices.size(); i++) {
            ema[i] = (prices.get(i).getClose() - ema[i - 1]) * m + ema[i - 1];
        }

        for (int i = 0; i < period - 1 && i < prices.size(); i++) ema[i] = Double.NaN;
        return ema;
    }

    private LineData generateKDLineData(List<StockDayPrice> displayedList) {
        final int DIM_YELLOW = Color.rgb(100, 100, 0);
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
        kSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        lineData.addDataSet(kSet);

        String dLabel = String.format(Locale.US, "D%d", currentKDNPeriod);
        LineDataSet dSet = new LineDataSet(dEntries, dLabel);
        dSet.setColor(SKY_BLUE);
        dSet.setLineWidth(1.5f);
        dSet.setDrawCircles(false);
        dSet.setDrawValues(false);
        dSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        lineData.addDataSet(dSet);

        return lineData;
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

        CombinedData data = new CombinedData();
        ScatterData scatterData = new ScatterData();

        String goldColor = "#FFD700";
        String cyanColor = "#00FFFF";
        float smallSize = 17.5f;

        if (mode.equals("ALL") || mode.equals("K")) {
            data.setData(generateCandleData(displayedList));
            data.setData(generateLineData(displayedList));
        } else {
            data.setData(new CandleData());
            data.setData(new LineData());
        }

        if (mode.equals("ALL") || mode.equals("Vo")) {
            data.setData(generateBarData(displayedList));
        } else {
            data.setData(new BarData());
        }

        if (mode.equals("ALL") || mode.equals("K")) {
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

            for (int i = 5; i < displayedList.size() - 2; i++) {
                if (isLocalHigh(displayedList, i)) {
                    int prevH = findPreviousLocalHigh(displayedList, i);
                    if (prevH != -1) {
                        double curP = displayedList.get(i).getHigh();
                        double preP = displayedList.get(prevH).getHigh();
                        if (curP > preP && displayedList.get(i).macdDIF < displayedList.get(prevH).macdDIF)
                            difDown.add(new Entry(i, (float) curP + offset));
                        if (curP > preP && displayedList.get(i).macdHistogram < displayedList.get(prevH).macdHistogram
                                && displayedList.get(i).macdHistogram > 0)
                            histoDown.add(new Entry(i, (float) curP + offset));
                    }
                }
                if (isLocalLow(displayedList, i)) {
                    int prevL = findPreviousLocalLow(displayedList, i);
                    if (prevL != -1) {
                        double curP = displayedList.get(i).getLow();
                        double preP = displayedList.get(prevL).getLow();
                        if (curP < preP && displayedList.get(i).macdDIF > displayedList.get(prevL).macdDIF)
                            difUp.add(new Entry(i, (float) curP - offset));
                        if (curP < preP && displayedList.get(i).macdHistogram > displayedList.get(prevL).macdHistogram
                                && displayedList.get(i).macdHistogram < 0)
                            histoUp.add(new Entry(i, (float) curP - offset));
                    }
                }
            }

            addScatterDataSet(scatterData, difUp, goldColor, 0, smallSize);
            addScatterDataSet(scatterData, difDown, goldColor, 1, smallSize);
            addScatterDataSet(scatterData, histoUp, cyanColor, 0, smallSize);
            addScatterDataSet(scatterData, histoDown, cyanColor, 1, smallSize);

            addGapPriceLabels(scatterData, displayedList);
            data.setData(scatterData);
        }

        mainChart.setData(data);

        Legend legend = mainChart.getLegend();
        List<LegendEntry> customEntries = new ArrayList<>();

// 只在主圖有畫 K/MA 時才顯示（你原本就是這樣）
        if ((mode.equals("ALL") || mode.equals("K")) && data.getLineData() != null) {

            // 取出「目前真的畫在主圖上的兩條 MA 線」的 dataset（依你現況就是兩條）
            List<ILineDataSet> sets = data.getLineData().getDataSets();
            ILineDataSet ma1 = (sets.size() >= 1) ? sets.get(0) : null;
            ILineDataSet ma2 = (sets.size() >= 2) ? sets.get(1) : null;

            // 1) MA數字1
            if (ma1 != null) {
                customEntries.add(new LegendEntry(
                        ma1.getLabel(),
                        Legend.LegendForm.LINE, 10f, 3f, null,
                        ma1.getColor()
                ));
            }

            // 2) MA數字2
            if (ma2 != null) {
                customEntries.add(new LegendEntry(
                        ma2.getLabel(),
                        Legend.LegendForm.LINE, 10f, 3f, null,
                        ma2.getColor()
                ));
            }

            // 3) 主要股票代碼|股票名稱|產業別
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

    private boolean isLocalHigh(List<StockDayPrice> list, int i) {
        if (i < 2 || i > list.size() - 3) return false;
        double v = list.get(i).getHigh();
        return v > list.get(i - 1).getHigh() && v > list.get(i - 2).getHigh()
                && v > list.get(i + 1).getHigh() && v > list.get(i + 2).getHigh();
    }

    private boolean isLocalLow(List<StockDayPrice> list, int i) {
        if (i < 2 || i > list.size() - 3) return false;
        double v = list.get(i).getLow();
        return v < list.get(i - 1).getLow() && v < list.get(i - 2).getLow()
                && v < list.get(i + 1).getLow() && v < list.get(i + 2).getLow();
    }

    private int findPreviousLocalHigh(List<StockDayPrice> list, int currentIdx) {
        for (int j = currentIdx - 5; j > Math.max(0, currentIdx - 35); j--) {
            if (isLocalHigh(list, j)) return j;
        }
        return -1;
    }

    private int findPreviousLocalLow(List<StockDayPrice> list, int currentIdx) {
        for (int j = currentIdx - 5; j > Math.max(0, currentIdx - 35); j--) {
            if (isLocalLow(list, j)) return j;
        }
        return -1;
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

        final int DIM_YELLOW = Color.rgb(100, 100, 0);
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
        YAxis rightAxis = k_kdChart.getAxisRight();
        rightAxis.setEnabled(true);
        rightAxis.setDrawLabels(false);
        rightAxis.setAxisMinimum(0f);
        rightAxis.setAxisMaximum(200f);
        rightAxis.removeAllLimitLines();

        LimitLine ll80 = new LimitLine(80f, "80");
        ll80.setLineColor(Color.RED);
        ll80.setLineWidth(0.8f);
        ll80.setTextColor(Color.RED);
        ll80.setTextSize(8f);
        rightAxis.addLimitLine(ll80);

        LimitLine ll20 = new LimitLine(20f, "20");
        ll20.setLineColor(Color.GREEN);
        ll20.setLineWidth(0.8f);
        ll20.setTextColor(Color.GREEN);
        ll20.setTextSize(8f);
        rightAxis.addLimitLine(ll20);

        YAxis leftAxis = k_kdChart.getAxisLeft();
        if (comparisonPriceList.isEmpty() || k_kdChart.getVisibleXRange() <= 0) {
            leftAxis.setEnabled(false);
            return;
        }
        leftAxis.setEnabled(true);

        float minX = k_kdChart.getLowestVisibleX();
        float maxX = k_kdChart.getHighestVisibleX();

        double maxPrice = Double.NEGATIVE_INFINITY;
        double minPrice = Double.POSITIVE_INFINITY;

        Map<String, StockDayPrice> compMap = new HashMap<>();
        for (StockDayPrice p : comparisonPriceList) compMap.put(p.getDate(), p);

        List<StockDayPrice> displayedMainList = getDisplayedList(fullPriceList);

        for (int i = 0; i < displayedMainList.size(); i++) {
            if (i < minX || i > maxX) continue;
            StockDayPrice cp = compMap.get(displayedMainList.get(i).getDate());
            if (cp == null) continue;
            maxPrice = Math.max(maxPrice, cp.getHigh());
            minPrice = Math.min(minPrice, cp.getLow());
        }

        if (Double.isInfinite(maxPrice) || Double.isInfinite(minPrice) || minPrice <= 0) {
            leftAxis.setEnabled(false);
            return;
        }

        float padding = (float) (maxPrice - minPrice) * 0.05f;
        if (Float.isNaN(padding) || padding == 0f) padding = 0.1f;

        leftAxis.setAxisMinimum((float) minPrice - padding);
        leftAxis.setAxisMaximum((float) maxPrice + padding);
    }

    private void drawKDAndComparisonChartData(List<StockDayPrice> displayedMainList) {
        CombinedData combinedData = new CombinedData();
        Legend legend = k_kdChart.getLegend();
        List<LegendEntry> customEntries = new ArrayList<>();

        LineData lineData = generateKDLineData(displayedMainList);
        if (lineData.getDataSetCount() > 0) {
            combinedData.setData(lineData);

            final int DIM_WHITE = Color.rgb(120, 120, 120);
            final int DIM_YELLOW = Color.rgb(100, 100, 0);
            final int SKY_BLUE = Color.rgb(79, 195, 247);

            String kLegendText = String.format(Locale.getDefault(), "K%d", currentKDNPeriod);
            String dLegendText = String.format(Locale.getDefault(), "D%d", currentKDNPeriod);

            customEntries.add(new LegendEntry(kLegendText, Legend.LegendForm.LINE, 10f, 3f, null, DIM_WHITE));
            customEntries.add(new LegendEntry(dLegendText, Legend.LegendForm.LINE, 10f, 3f, null, DIM_YELLOW));
        }

        if (comparisonPriceList.isEmpty()) {
            k_kdChart.setNoDataText("KD 指標區 / 無對比 K 線數據可供繪製。");
            k_kdChart.getAxisLeft().setEnabled(false);
        } else {
            Map<String, StockDayPrice> compMap = new HashMap<>();
            for (StockDayPrice p : comparisonPriceList) compMap.put(p.getDate(), p);

            List<CandleEntry> entries = new ArrayList<>();
            for (int i = 0; i < displayedMainList.size(); i++) {
                StockDayPrice cp = compMap.get(displayedMainList.get(i).getDate());
                if (cp == null) continue;
                entries.add(new CandleEntry(i, (float) cp.getHigh(), (float) cp.getLow(),
                        (float) cp.getOpen(), (float) cp.getClose()));
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

                resetKDJAndComparisonAxisLimits();
            } else {
                k_kdChart.getAxisLeft().setEnabled(false);
            }
        }

        legend.setCustom(customEntries);
        k_kdChart.setData(combinedData);
        k_kdChart.notifyDataSetChanged();
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

    private BarData generateBarData(List<StockDayPrice> displayedList) {
        List<BarEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            entries.add(new BarEntry(i, (float) p.getVolume()));

            if (p.getClose() > p.getOpen()) colors.add(Color.RED);
            else if (p.getClose() < p.getOpen()) colors.add(Color.GREEN);
            else colors.add(Color.WHITE);
        }

        BarDataSet set = new BarDataSet(entries, "Volume");
        set.setDrawValues(false);
        set.setColors(colors);
        set.setAxisDependency(YAxis.AxisDependency.RIGHT);

        BarData barData = new BarData(set);
        barData.setBarWidth(0.8f);
        return barData;
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
        syncChartXAxisOnly(mainChart, k_kdChart);
        syncChartXAxisOnly(mainChart, indicatorChart);
    }

    private void syncChartXAxisOnly(CombinedChart src, CombinedChart dst) {
        if (src == null || dst == null) return;

        Matrix srcM = src.getViewPortHandler().getMatrixTouch();
        float[] sv = new float[9];
        srcM.getValues(sv);

        Matrix dstM = dst.getViewPortHandler().getMatrixTouch();
        float[] dv = new float[9];
        dstM.getValues(dv);

        // 只跟隨 X：縮放 + 平移
        dv[Matrix.MSCALE_X] = sv[Matrix.MSCALE_X];
        dv[Matrix.MTRANS_X] = sv[Matrix.MTRANS_X];

        // 關閉 Y 跟隨（鎖死 Y）
        dv[Matrix.MSCALE_Y] = 1f;
        dv[Matrix.MTRANS_Y] = 0f;

        // 通常 skew 不用，但保險歸零
        dv[Matrix.MSKEW_X] = 0f;
        dv[Matrix.MSKEW_Y] = 0f;

        dstM.setValues(dv);
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
        String symbol = stockIdEditText.getText().toString().trim();
        if (symbol.isEmpty()) symbol = currentStockId;

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
    public class CoordinateMarkerView extends com.github.mikephil.charting.components.MarkerView {
        private final Paint textPaint;
        private final List<StockDayPrice> dataList;

        public CoordinateMarkerView(Context context, List<StockDayPrice> dataList) {
            super(context, android.R.layout.select_dialog_item);
            this.dataList = dataList;
            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(30f);
            textPaint.setColor(Color.YELLOW);
        }
        // CoordinateMarkerView 內新增
        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        private float getLegendHeightPx(CombinedChart chart) {
            Legend lg = chart.getLegend();

            // MPAndroidChart 會在繪製 legend 時把每一行的 size 算好放進去
            List<com.github.mikephil.charting.utils.FSize> lines = lg.getCalculatedLineSizes();

// 若還沒算到（極少數第一次），用一行高度當 fallback
            float oneLine = Math.max(lg.getTextSize(), lg.getFormSize());

            if (lines == null || lines.isEmpty()) return oneLine;

            float h = 0f;
            for (com.github.mikephil.charting.utils.FSize s : lines) h += s.height;

// 行距：兩行之間加 yEntrySpace
            h += lg.getYEntrySpace() * (lines.size() - 1);
            return Math.max(h, oneLine);
        }

        @Override
        public void draw(Canvas canvas, float posX, float posY) {
            if (getChartView() == null || getChartView().getHighlighted() == null
                    || getChartView().getHighlighted().length == 0) return;

            Highlight h = getChartView().getHighlighted()[0];
            int index = (int) h.getX();
            if (index < 0 || index >= dataList.size()) return;

            CombinedChart chart = (CombinedChart) getChartView();
            Legend lg = chart.getLegend();

            float left = chart.getViewPortHandler().contentLeft();
            float top  = chart.getViewPortHandler().contentTop();

            float baseX = left + lg.getXOffset() + dp(4);
            float baseY = top + lg.getYOffset() + getLegendHeightPx(chart) + dp(8); // ✅ 固定在圖例下方

            String priceText = String.format(Locale.US, "%.2f", h.getY());
            String dateText  = dataList.get(index).getDate();

            canvas.drawText(priceText, baseX, baseY, textPaint);
            canvas.drawText(dateText,  baseX, baseY + dp(16), textPaint);
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