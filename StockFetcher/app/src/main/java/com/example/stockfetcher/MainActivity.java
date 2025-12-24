// *** 註解版本號：3.02版
package com.example.stockfetcher;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyLanguagePolicyBySystemLocale();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initUi();
        initCharts();

        // 程式啟動時，預設繪製
        fetchStockDataWithFallback(currentStockId, currentInterval,
                getStartTimeLimit(currentInterval, isSwitchingInterval));
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
    }

    private void initUi() {
        yahooFinanceFetcher = new YahooFinanceFetcher();
        vpView.bindMainChart(mainChart);

        displayText = getResources().getStringArray(R.array.interval_display_text);
        intervalSwitchButton.setText(displayText[currentIntervalIndex]);

        // view mode button：固定高度 + 初始文字 + 切換
        viewModeButton.getLayoutParams().height =
                (int) (24 * getResources().getDisplayMetrics().density);
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
    }

    private void initCharts() {
        setupMainChart();
        setupk_kdChart();
        setupIndicatorChart();
        mainChart.setOnChartGestureListener(this);
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
                        getString(R.string.error_load_failed, fullErrorMsg),
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
                clearAllCharts(getString(R.string.error_load_failed, fullErrorMsg));
                return;
            }

            if (!errorMessages.isEmpty()) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.error_load_failed, fullErrorMsg),
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
        legend.setXOffset(5f);
        legend.setYOffset(20f);

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
        legend.setXOffset(5f);
        legend.setYOffset(0f);

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

        LineData lineData = new LineData();
        List<Entry> kEntries = new ArrayList<>();
        List<Entry> dEntries = new ArrayList<>();

        for (int i = 0; i < displayedList.size(); i++) {
            StockDayPrice p = displayedList.get(i);
            if (!Double.isNaN(p.kdK)) kEntries.add(new Entry(i, (float) p.kdK));
            if (!Double.isNaN(p.kdD)) dEntries.add(new Entry(i, (float) p.kdD));
        }

        String kLabel = String.format(Locale.US, "K%d線", currentKDNPeriod);
        LineDataSet kSet = new LineDataSet(kEntries, kLabel);
        kSet.setColor(Color.LTGRAY);
        kSet.setLineWidth(1.5f);
        kSet.setDrawCircles(false);
        kSet.setDrawValues(false);
        kSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        lineData.addDataSet(kSet);

        String dLabel = String.format(Locale.US, "D%d線", currentKDNPeriod);
        LineDataSet dSet = new LineDataSet(dEntries, dLabel);
        dSet.setColor(DIM_YELLOW);
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

        if ((mode.equals("ALL") || mode.equals("K"))
                && data.getCandleData() != null && data.getCandleData().getDataSetCount() > 0) {
            customEntries.add(new LegendEntry(
                    currentStockId + " K線",
                    Legend.LegendForm.SQUARE, 10f, 5f, null,
                    data.getCandleData().getDataSetByIndex(0).getIncreasingColor()
            ));
        }

        if ((mode.equals("ALL") || mode.equals("K")) && data.getLineData() != null) {
            for (ILineDataSet set : data.getLineData().getDataSets()) {
                if (set.isVisible() && set.getForm() != Legend.LegendForm.NONE) {
                    customEntries.add(new LegendEntry(
                            set.getLabel(), Legend.LegendForm.LINE, 2f, 5f, null, set.getColor()
                    ));
                }
            }
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

        LineData lineData = new LineData();

        LineDataSet difSet = new LineDataSet(difEntries, "DIF (白)");
        difSet.setColor(Color.LTGRAY);
        difSet.setLineWidth(1.5f);
        difSet.setDrawCircles(false);
        difSet.setDrawValues(false);
        difSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineData.addDataSet(difSet);

        LineDataSet deaSet = new LineDataSet(deaEntries, "DEA (黃)");
        deaSet.setColor(DIM_YELLOW);
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

            String kLegendText = String.format(Locale.getDefault(), "K%d線", currentKDNPeriod);
            String dLegendText = String.format(Locale.getDefault(), "D%d線", currentKDNPeriod);

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
                String kLineLabel = displayedComparisonSymbol + " K線";
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

        addMaLine(lineData, displayedList, "MA 35", p -> p.ma35, Color.YELLOW);
        addMaLine(lineData, displayedList, "MA 60", p -> p.ma60, Color.MAGENTA);
        addMaLine(lineData, displayedList, "MA 120", p -> p.ma120, Color.CYAN);
        addMaLine(lineData, displayedList, "MA 200", p -> p.ma200, Color.BLUE);
        addMaLine(lineData, displayedList, "MA 240", p -> p.ma240, Color.rgb(255, 165, 0));

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
    @Override public void onChartSingleTapped(MotionEvent me) {}
    @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {}
    @Override
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