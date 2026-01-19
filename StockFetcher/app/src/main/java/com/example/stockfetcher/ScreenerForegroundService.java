package com.example.stockfetcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenerForegroundService extends Service {
    public static final String EXTRA_INDUSTRIES = "industries";
    public static final String ACTION_START = "com.example.stockfetcher.SCREENER_START";
    public static final String ACTION_CANCEL = "com.example.stockfetcher.SCREENER_CANCEL";

    public static final String ACTION_PROGRESS = "com.example.stockfetcher.SCREENER_PROGRESS";
    public static final String ACTION_DONE = "com.example.stockfetcher.SCREENER_DONE";
    public static final String ACTION_FAIL = "com.example.stockfetcher.SCREENER_FAIL";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_CSV_PATH = "csv_path";
    public static final String EXTRA_ERROR = "error";

    public static final String EXTRA_LT_THR = "lt_thr";
    public static final String EXTRA_LT_DAYS = "lt_days";

    public static final String EXTRA_GT_THR = "gt_thr";
    public static final String EXTRA_GT_MIN = "gt_min";
    public static final String EXTRA_GT_MAX = "gt_max";

    public static final String EXTRA_MA_BAND_PCT = "ma_band_pct";
    public static final String EXTRA_MA_DAYS = "ma_days";

    // ✅ [ADD] MACD divergence recent params
    public static final String EXTRA_MACD_DIV_BARS = "macd_div_bars";   // int
    public static final String EXTRA_MACD_DIV_TF   = "macd_div_tf";     // String: "時/日/周/月" or "HOUR/DAY/WEEK/MONTH"
    public static final String EXTRA_MACD_DIV_SIDE = "macd_div_side";   // String: "底/頂" or "BOTTOM/TOP"
    public static final String EXTRA_MA_TF = "ma_tf";
    public static final String EXTRA_MA_WINDOW = "ma_window";
    private static final String CH_ID = "screener_channel";
    private static final int NOTIF_ID = 1001;
    public static final String EXTRA_KD_GC_BARS = "kd_gc_bars";
    public static final String EXTRA_KD_GC_TF   = "kd_gc_tf";
    public static final String EXTRA_TICKER_CSV_PATH = "ticker_csv_path";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Future<?> job;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelJob();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (job != null && !job.isDone()) {
                // 已在跑，不重複啟動
                return START_STICKY;
            }

            String modeStr = intent.getStringExtra(EXTRA_MODE);
            final ScreenerMode mode = parseMode(modeStr);

            cancelled.set(false);
            acquireWakeLock();

            // 先以前景通知啟動（避免新系統直接砍）
            startForeground(NOTIF_ID, buildNotif("Screening…", 0, 0));

            java.util.ArrayList<String> inds = intent.getStringArrayListExtra(EXTRA_INDUSTRIES);
            final java.util.HashSet<String> industries = new java.util.HashSet<>();
            if (inds != null) {
                for (String s : inds) {
                    if (s != null && !s.trim().isEmpty()) industries.add(s.trim());
                }
            }

            // build overrides from intent
            ScreenerEngine.Overrides ov = new ScreenerEngine.Overrides();

            ov.ltThr = getIntOrNull(intent, EXTRA_LT_THR);
            ov.ltDays = getIntOrNull(intent, EXTRA_LT_DAYS);

            ov.gtThr = getIntOrNull(intent, EXTRA_GT_THR);
            ov.gtMin = getIntOrNull(intent, EXTRA_GT_MIN);
            ov.gtMax = getIntOrNull(intent, EXTRA_GT_MAX);


            ov.maBandPct = getIntOrNull(intent, EXTRA_MA_BAND_PCT);
            ov.maDays = getIntOrNull(intent, EXTRA_MA_DAYS);
            ov.maTf = getTrimmedStringOrNull(intent, EXTRA_MA_TF);
            ov.maWindow = getIntOrNull(intent, EXTRA_MA_WINDOW);

            // ✅ [ADD] MACD divergence overrides
            ov.macdDivBars = getIntOrNull(intent, EXTRA_MACD_DIV_BARS);
            ov.macdDivTf = getTrimmedStringOrNull(intent, EXTRA_MACD_DIV_TF);
            ov.macdDivSide = getTrimmedStringOrNull(intent, EXTRA_MACD_DIV_SIDE);
            ov.kdGcBars = getIntOrNull(intent, EXTRA_KD_GC_BARS);
            ov.kdGcTf   = getTrimmedStringOrNull(intent, EXTRA_KD_GC_TF);

            //Log.d("ScreenerSvc", "mode=" + mode
            //        + " ov.ltThr=" + ov.ltThr + " ov.ltDays=" + ov.ltDays
            //        + " ov.gtThr=" + ov.gtThr + " ov.gtMin=" + ov.gtMin + " ov.gtMax=" + ov.gtMax
            //        + " ov.maBandPct=" + ov.maBandPct + " ov.maDays=" + ov.maDays
            //        + " ov.macdDivBars=" + ov.macdDivBars + " ov.macdDivTf=" + ov.macdDivTf + " ov.macdDivSide=" + ov.macdDivSide);

            final String tickerCsvPath = intent.getStringExtra(EXTRA_TICKER_CSV_PATH);
            job = exec.submit(() -> runScreening(mode, industries, ov, tickerCsvPath));
            return START_STICKY;
        }

        return START_STICKY;
    }

    @Nullable
    private static String getTrimmedStringOrNull(Intent it, String key) {
        if (it == null || key == null) return null;
        if (!it.hasExtra(key)) return null;
        try {
            String s = it.getStringExtra(key);
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ✅ 更耐用：支援 int/long/string 形式的 extra
    @Nullable
    private static Integer getIntOrNull(Intent it, String key) {
        if (it == null || key == null) return null;
        if (!it.hasExtra(key)) return null;
        try {
            Object v = (it.getExtras() == null) ? null : it.getExtras().get(key);
            if (v == null) return null;

            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Long) return ((Long) v).intValue();
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (s.isEmpty()) return null;
                return Integer.parseInt(s);
            }

            // fallback: 嘗試 getIntExtra
            return it.getIntExtra(key, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private void runScreening(ScreenerMode mode,
                              java.util.HashSet<String> industries,
                              ScreenerEngine.Overrides ov,
                              @Nullable String tickerCsvPath) {
        try {
            YahooFinanceFetcher fetcher = new YahooFinanceFetcher();

            List<TickerInfo> tickers = TwTickerRepository.loadOrScrape(getApplicationContext());
            if (tickers == null || tickers.isEmpty()) {
                String err = TwTickerRepository.getLastError();
                if (err == null || err.trim().isEmpty()) err = "Ticker list is empty";
                sendFail(err);
                stopSelfSafely();
                return;
            }
            // ✅ 檔案過濾：若有指定 tickerCsvPath，就只篩檔案內的 tickers
            if (tickerCsvPath != null && !tickerCsvPath.trim().isEmpty()) {
                java.io.File f = new java.io.File(tickerCsvPath);
                java.util.HashSet<String> allow = readTickerSetFromCsv(f);

                if (allow.isEmpty()) {
                    sendFail("No tickers in file");
                    stopSelfSafely();
                    return;
                }

                java.util.ArrayList<TickerInfo> filtered = new java.util.ArrayList<>();
                for (TickerInfo ti : tickers) {
                    if (ti == null || ti.ticker == null) continue;
                    String tk = ti.ticker.trim().toUpperCase(java.util.Locale.US);
                    if (allow.contains(tk)) filtered.add(ti);
                }
                tickers = filtered;

                if (tickers.isEmpty()) {
                    sendFail("No tickers after file filter");
                    stopSelfSafely();
                    return;
                }

                // 檔案模式：不再做產業過濾（避免混合條件造成困惑）
                industries = null;
            }
            // 產業過濾：用傳進來的 industries
            if (industries != null && !industries.isEmpty()) {
                java.util.ArrayList<TickerInfo> filtered = new java.util.ArrayList<>();
                for (TickerInfo ti : tickers) {
                    if (ti == null) continue;
                    String ind = (ti.industry == null) ? "" : ti.industry.trim();
                    if (industries.contains(ind)) filtered.add(ti);
                }
                tickers = filtered;
            }

            if (tickers == null || tickers.isEmpty()) {
                sendFail("No tickers after industry filter");
                stopSelfSafely();
                return;
            }

            List<ScreenerResult> results = ScreenerEngine.run(
                    tickers,
                    mode,
                    fetcher,
                    (done, total) -> {
                        updateNotif(done, total);
                        sendProgress(done, total);
                    },
                    () -> cancelled.get(),
                    ov
            );

            if (cancelled.get()) {
                sendFail("Cancelled");
                stopSelfSafely();
                return;
            }

            File out = exportResultsToCsv(mode, results);
            sendDone(out == null ? "" : out.getAbsolutePath());
            stopSelfSafely();

        } catch (Exception e) {
            sendFail("Screening failed: " + e.getMessage());
            stopSelfSafely();
        }
    }

    private java.util.HashSet<String> readTickerSetFromCsv(java.io.File file) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        if (file == null || !file.exists()) return out;

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String col0 = line.split(",", -1)[0].trim();
                if (col0.equalsIgnoreCase("ticker")) continue;

                String t = col0.toUpperCase(java.util.Locale.US);
                if (!t.isEmpty()) out.add(t);
            }
        } catch (Exception ignored) {}

        return out;
    }
    private void cancelJob() {
        cancelled.set(true);
        if (job != null) job.cancel(true);
        stopSelfSafely();
    }

    private void stopSelfSafely() {
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void sendProgress(int done, int total) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_DONE, done);
        i.putExtra(EXTRA_TOTAL, total);
        sendBroadcast(i);
    }

    private void sendDone(String csvPath) {
        Intent i = new Intent(ACTION_DONE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_CSV_PATH, csvPath);
        sendBroadcast(i);
    }

    private void sendFail(String msg) {
        Intent i = new Intent(ACTION_FAIL);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ERROR, msg);
        sendBroadcast(i);
    }

    private void updateNotif(int done, int total) {
        Notification n = buildNotif("Screening…", done, total);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private Notification buildNotif(String text, int done, int total) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.screener_btn))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (total > 0) {
            b.setProgress(total, Math.max(0, done), false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Screening", NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(ch);
        }
    }

    private ScreenerMode parseMode(String s) {
        try { return ScreenerMode.valueOf(s); }
        catch (Exception ignored) { return ScreenerMode.LT20; }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock != null && wakeLock.isHeld()) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockFetcher:Screener");
            wakeLock.acquire(60 * 60 * 1000L); // 最多 1 小時，避免忘記 release
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------- CSV export --------
    @Nullable
    private File exportResultsToCsv(ScreenerMode mode, List<ScreenerResult> results) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return null;

            String tag;
            switch (mode) {
                case LT20: tag = "LT20"; break;
                case GT45: tag = "GT45"; break;
                case MA60_3PCT: tag = "MA±%"; break;
                case MACD_DIV_RECENT: tag = "MACD_DIV"; break;
                case KD_GC_RECENT: tag = "KD_GC"; break;   // ✅ 新增
                default: tag = "MODE"; break;


            }
            File out = new File(dir, "TW_" + tag + ".csv");

            try (FileWriter fw = new FileWriter(out, false)) {
                String header;
                if (mode == ScreenerMode.MA60_3PCT) {
                    header = "Ticker,Name,Industry,AvgClose60,LatestClose,MA_60,MA60_DiffPct\n";
                } else if (mode == ScreenerMode.GT45) {
                    header = "Ticker,Name,Industry,AvgClose60,LatestClose,LastK40,KD45_RunDays\n";
                } else if (mode == ScreenerMode.LT20) {
                    header = "Ticker,Name,Industry,AvgClose60,LatestClose,LastK40\n";
                } else if (mode == ScreenerMode.KD_GC_RECENT) {
                header = "Ticker,Name,Industry,AvgClose60,LatestClose,CrossDate,LastK,LastD\n";
                } else {
                    header = "Ticker,Name,Industry,AvgClose60,LatestClose\n";
                }
                fw.write(header);

                if (results != null) {
                    for (ScreenerResult r : results) {
                        if (r == null) continue;
                        fw.write(csv(r.ticker) + "," + csv(r.name) + "," + csv(r.industry) + ","
                                + num(r.avgClose60) + "," + num(r.latestClose));

                        if (mode == ScreenerMode.MA60_3PCT) {
                            fw.write("," + numObj(r.ma60) + "," + numObj(r.ma60DiffPct));
                        } else if (mode == ScreenerMode.GT45) {
                            fw.write("," + numObj(r.lastK) + "," + (r.runDays == null ? "" : r.runDays));
                        } else if (mode == ScreenerMode.LT20) {
                            fw.write("," + numObj(r.lastK));
                        }
                        fw.write("\n");
                    }
                }
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String csv(String s) {
        if (s == null) return "";
        boolean q = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!q) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String num(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "" : String.valueOf(v);
    }

    private String numObj(Double v) {
        return (v == null || Double.isNaN(v) || Double.isInfinite(v)) ? "" : String.valueOf(v);
    }
}