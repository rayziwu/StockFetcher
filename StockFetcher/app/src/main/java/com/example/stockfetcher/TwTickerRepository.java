package com.example.stockfetcher;

import android.content.Context;
import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.LinkedHashMap;

public final class TwTickerRepository {

    private static final String TAG = "TwTickerRepository";

    // 對齊 Python：BASE_URL = "https://isin.twse.com.tw/isin/C_public.jsp"
    private static final String BASE_URL = "https://isin.twse.com.tw/isin/C_public.jsp";
    private static final String CACHE_FILE = "股票代碼.csv"; //  externalFilesDir

    // 對齊 Python UA
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0 Safari/537.36";

    // 可在 MainActivity 顯示真正原因
    private static volatile String lastError = "";
    public static String getLastError() { return lastError == null ? "" : lastError; }
    private static void setErr(String msg, Throwable t) {
        lastError = msg;
        if (t != null) Log.w(TAG, msg, t); else Log.w(TAG, msg);
    }

    // -------- memory cache (avoid reading 股票代碼.csv twice in same process) --------
    private static final Object MEM_CACHE_LOCK = new Object();
    @androidx.annotation.Nullable
    private static volatile List<TickerInfo> memCache = null;
    private static volatile long memCacheMtime = -1L;
    @androidx.annotation.Nullable
    private static volatile String memCachePath = null;

    @androidx.annotation.Nullable
    private static List<TickerInfo> tryGetMemCache(Context ctx) {
        List<TickerInfo> c = memCache;
        if (c == null || c.isEmpty()) return null;

        File f = getCacheFile(ctx); // externalFilesDir/股票代碼.csv
        if (f != null && f.exists()) {
            long m = f.lastModified();
            String p = f.getAbsolutePath();
            if (p.equals(memCachePath) && m == memCacheMtime) {
                return c;
            }
            // file changed -> invalidate
            return null;
        }

        // file missing but we already have data in memory -> still usable
        return c;
    }

    private static void updateMemCache(Context ctx, List<TickerInfo> list) {
        if (list == null) return;
        File f = getCacheFile(ctx);
        String p = (f == null) ? null : f.getAbsolutePath();
        long m = (f != null && f.exists()) ? f.lastModified() : -1L;

        // 用 unmodifiable 避免外部誤改
        List<TickerInfo> snap = java.util.Collections.unmodifiableList(new ArrayList<>(list));

        memCache = snap;
        memCachePath = p;
        memCacheMtime = m;
    }

    private TwTickerRepository() {}
    // 從股票清單 cache 讀到的 meta（ticker -> info）
    public interface ScrapeProgressListener {
        void onLine(String msg);
    }

    public static File getCacheFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return (dir == null) ? null : new File(dir, CACHE_FILE);
    }
    /** 對齊 Python：先讀檔；失敗才爬取；成功後寫回 CSV（含 Name/Industry） */
    public static List<TickerInfo> loadOrScrape(Context ctx) {
        return loadOrScrape(ctx, null);
    }
    public static List<TickerInfo> loadOrScrape(Context ctx, @androidx.annotation.Nullable ScrapeProgressListener cb) {
        lastError = "";
        // ✅ 0) 先用記憶體快取（同一 process 第二次呼叫直接回傳，不讀檔）
        List<TickerInfo> mem = tryGetMemCache(ctx);
        if (mem != null && !mem.isEmpty()) return mem;

        // 1) 先讀 cache
        List<TickerInfo> cached = readCache(ctx);
        if (cached != null && !cached.isEmpty()) {
            updateMemCache(ctx, cached);
            return cached;
        };

        // 2) 沒 cache -> 顯示你指定的訊息
        if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_file_missing));
        if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_start));

        List<TickerInfo> out = new ArrayList<>();

        // 上市
        String twseName = ctx.getString(R.string.ticker_market_twse);
        if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_step_start, twseName));
        List<TickerInfo> twse = scrapeTickers("2", ".TW", "上市股票 (TWSE)");
        if (twse != null && !twse.isEmpty()) {
            out.addAll(twse);
            if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_step_ok, twse.size(), twseName));
        } else {
            if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_failed, getLastError()));
        }

        // 上櫃
        String tpexName = ctx.getString(R.string.ticker_market_tpex);
        if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_step_start, tpexName));
        List<TickerInfo> tpex = scrapeTickers("4", ".TWO", "上櫃股票 (TPEx)");
        if (tpex != null && !tpex.isEmpty()) {
            out.addAll(tpex);
            if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_step_ok, tpex.size(), tpexName));
        } else {
            if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_failed, getLastError()));
        }

        // 去重（同 ticker 只留一筆）
        if (!out.isEmpty()) {
            java.util.LinkedHashMap<String, TickerInfo> uniq = new java.util.LinkedHashMap<>();
            for (TickerInfo ti : out) {
                if (ti == null || ti.ticker == null) continue;
                String key = ti.ticker.trim().toUpperCase(java.util.Locale.US);
                if (key.isEmpty()) continue;
                if (!uniq.containsKey(key)) uniq.put(key, ti);
            }
            out = new ArrayList<>(uniq.values());
        }

        // 寫入 cache
        if (!out.isEmpty()) {
            writeCache(ctx, out);
            if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_saved, out.size()));
        } else {
            if (getLastError().isEmpty()) setErr("Ticker list empty: cache empty and scrape returned 0", null);
            if (cb != null) cb.onLine(ctx.getString(R.string.ticker_scrape_failed, getLastError()));
        }

        return out;
    }

    // -----------------------
    // Cache I/O
    // -----------------------
    private static List<TickerInfo> readCache(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return null;

            File f = new File(dir, CACHE_FILE);
            if (!f.exists()) return null;

            // 可能是 utf-8-sig（有 BOM）
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {

                String header = br.readLine();
                if (header == null) return null;

                header = stripUtf8Bom(header);
                String[] h = splitCsvLine(header);

                int iTicker = -1, iName = -1, iInd = -1;
                for (int i = 0; i < h.length; i++) {
                    String col = stripUtf8Bom(h[i]).trim().toLowerCase(Locale.US);
                    if (col.equals("ticker")) iTicker = i;
                    else if (col.equals("name")) iName = i;
                    else if (col.equals("industry") || col.equals("category")) iInd = i;
                }

                List<TickerInfo> out = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();

                String line;
                while ((line = br.readLine()) != null) {
                    line = stripUtf8Bom(line).trim();
                    if (line.isEmpty()) continue;

                    String[] cols = splitCsvLine(line);

                    String ticker = (iTicker >= 0 && iTicker < cols.length)
                            ? stripUtf8Bom(cols[iTicker]).trim()
                            : (cols.length > 0 ? stripUtf8Bom(cols[0]).trim() : "");

                    if (ticker.isEmpty()) continue;

                    String t = ticker.toUpperCase(Locale.US).trim();
                    if (t.isEmpty()) continue;
                    if (!seen.add(t)) continue;

                    String name = (iName >= 0 && iName < cols.length) ? stripUtf8Bom(cols[iName]).trim() : "";
                    String ind  = (iInd >= 0 && iInd < cols.length) ? stripUtf8Bom(cols[iInd]).trim() : "";

                    out.add(new TickerInfo(t, name, ind));
                }

                return out;
            }
        } catch (Exception e) {
            setErr("readCache failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static void writeCache(Context ctx, List<TickerInfo> list) {
        try {
            File f = new File(ctx.getExternalFilesDir(null), CACHE_FILE);

            try (java.io.BufferedWriter fw = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(f, false),
                            java.nio.charset.StandardCharsets.UTF_8))) {

                // ✅ UTF-8 BOM（utf-8-sig）→ Windows Excel 直接開不亂碼
                fw.write('\uFEFF');

                // 對齊 Python：輸出欄位含 Name/Industry
                fw.write("Ticker,Name,Industry\n");

                if (list != null) {
                    for (TickerInfo ti : list) {
                        if (ti == null || ti.ticker == null) continue;

                        fw.write(escapeCsv(ti.ticker));
                        fw.write(",");
                        fw.write(escapeCsv(ti.name == null ? "" : ti.name));
                        fw.write(",");
                        fw.write(escapeCsv(ti.industry == null ? "" : ti.industry));
                        fw.write("\n");
                    }
                }
            }
            updateMemCache(ctx, list);
        } catch (Exception e) {
            setErr("writeCache failed: " + e.getMessage(), e);
        }
    }

    private static String stripUtf8Bom(String s) {
        if (s == null) return "";
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    // -----------------------
    // Scrape (對齊 Python scrape_tickers)
    // -----------------------
    private static List<TickerInfo> scrapeTickers(String strMode, String suffix, String marketName) {
        List<TickerInfo> out = new ArrayList<>();
        HttpURLConnection conn = null;

        try {
            String urlStr = BASE_URL + "?strMode=" + strMode;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);

            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            if (code != 200) {
                setErr("Scrape " + marketName + " HTTP " + code + " (strMode=" + strMode + ")", null);
                return out;
            }

            InputStream is = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                is = new GZIPInputStream(is);
            }

            byte[] bytes = readAllBytes(is);
            String html = decodeCp950BestEffort(bytes);

            Document doc = Jsoup.parse(html);

            Element table = findTableContaining(doc, "有價證券代號及名稱");
            if (table == null) {
                setErr("Scrape " + marketName + " failed: no table found (strMode=" + strMode + ")", null);
                return out;
            }

            Elements trs = table.select("tr");
            if (trs.isEmpty()) {
                setErr("Scrape " + marketName + " failed: table empty (strMode=" + strMode + ")", null);
                return out;
            }

            int headerIdx = -1;
            List<String> headers = null;

            for (int i = 0; i < trs.size(); i++) {
                Elements cells = trs.get(i).select("th,td");
                List<String> h = new ArrayList<>();
                for (Element c : cells) h.add(c.text().trim());
                if (h.contains("有價證券代號及名稱")) {
                    headerIdx = i;
                    headers = h;
                    break;
                }
            }

            if (headerIdx < 0 || headers == null) {
                setErr("Scrape " + marketName + " failed: header row not found (strMode=" + strMode + ")", null);
                return out;
            }

            int colCodeName = indexOfHeader(headers, "有價證券代號及名稱");
            if (colCodeName < 0) {
                setErr("Scrape " + marketName + " failed: missing col 有價證券代號及名稱 (strMode=" + strMode + ")", null);
                return out;
            }

            int colIndustry = indexOfHeader(headers, "產業別");
            int colMarket = indexOfHeader(headers, "市場別");

            HashSet<String> seen = new HashSet<>();

            for (int i = headerIdx + 1; i < trs.size(); i++) {
                Elements cells = trs.get(i).select("th,td");
                if (cells.size() <= colCodeName) continue;

                String codeName = cells.get(colCodeName).text();
                CodeName cn = splitCodeName(codeName);
                if (cn.code == null) continue;

                // 必須是 4~6 位純數字
                if (!cn.code.matches("\\d{4,6}")) continue;

                // ✅ 排除權證/衍生性商品：名稱常含「購」「售」（你例子就是 54購02）
                String nm = (cn.name == null) ? "" : cn.name;
                if (nm.contains("購") || nm.contains("售")) continue;

                // ✅ 代碼規則：
                // - 4~5 位：全部允許（股票 + 多數 ETF/ETN）
                // - 6 位：只允許 00xxxx 或 02xxxx（避免 701029 類權證）
                if (cn.code.length() == 6) {
                    if (!(cn.code.startsWith("00") || cn.code.startsWith("02"))) continue;
                }

                // 市場別決定尾碼；非上市/上櫃跳過（避免創櫃/興櫃）
                String market = "";
                if (colMarket >= 0 && colMarket < cells.size()) {
                    market = cells.get(colMarket).text().trim();
                }

                String rowSuffix = null;
                if (!market.isEmpty()) {
                    if (market.contains("上市")) rowSuffix = ".TW";
                    else if (market.contains("上櫃")) rowSuffix = ".TWO";
                    else continue;
                } else if (suffix != null && !suffix.trim().isEmpty()) {
                    rowSuffix = suffix.trim();
                } else {
                    continue;
                }

                String ticker = cn.code + rowSuffix;

                // industry 先照你原本邏輯（股票不要被動）
                String industry = "";
                if (colIndustry >= 0 && colIndustry < cells.size()) {
                    industry = cells.get(colIndustry).text().trim();
                }
                if (industry.isEmpty() && !market.isEmpty()) industry = market;
                if (industry.isEmpty()) industry = marketName;

                // ✅ 只在 industry 是 fallback「上市/上櫃」時，依代碼前綴改成 ETF/ETN
                // 這樣股票真正產業別（半導體等）不會被改
                if ("上市".equals(industry) || "上櫃".equals(industry)) {
                    if (cn.code.startsWith("02")) industry = "ETN";
                    else if (cn.code.startsWith("00")) industry = "ETF";
                }

                String key = ticker.toUpperCase(Locale.US);
                if (!seen.add(key)) continue;

                out.add(new TickerInfo(ticker, cn.name, industry));
            }

            if (out.isEmpty()) {
                setErr("Scrape " + marketName + " got 0 tickers (strMode=" + strMode + ")", null);
            }
            return out;

        } catch (Exception e) {
            setErr("Scrape " + marketName + " exception (strMode=" + strMode + "): " + e.getMessage(), e);
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
        private static Element findTableContaining(Document doc, String mustContain) {
        Elements tables = doc.select("table");
        if (tables.isEmpty()) return null;

        for (Element t : tables) {
            if (t.text().contains(mustContain)) return t;
        }
        // 對齊 Python dfs[0]：找不到就退回第一個
        return tables.get(0);
    }

    private static int indexOfHeader(List<String> headers, String key) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h == null) continue;
            if (h.equals(key) || h.contains(key)) return i;
        }
        return -1;
    }

    // -----------------------
    // split code/name (對齊 Python _split_code_name)
    // -----------------------
    private static class CodeName {
        final String code; // 4 digits or null
        final String name;
        CodeName(String code, String name) { this.code = code; this.name = name; }
    }

    private static CodeName splitCodeName(String codeName) {
        if (codeName == null) return new CodeName(null, "");
        String s = codeName.replace('\u3000', ' ').trim();  // 全形空白轉半形
        if (s.isEmpty()) return new CodeName(null, "");

        String[] parts = s.split("\\s+", 2);
        String code = parts[0].trim();
        String name = (parts.length >= 2) ? parts[1].trim() : "";

        // 只要是數字就收，長度後面再由 scrapeTickers 判斷
        if (!code.matches("\\d+")) return new CodeName(null, "");
        return new CodeName(code, name);
    }

    // -----------------------
    // Byte/charset helpers
    // -----------------------
    private static byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String decodeCp950BestEffort(byte[] bytes) {
        // 對齊 Python cp950：Android/Java 常用 MS950 / Big5
        for (String cs : new String[]{"MS950", "Big5", "Cp950", "UTF-8"}) {
            try {
                return new String(bytes, Charset.forName(cs));
            } catch (Exception ignored) {}
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // -----------------------
    // CSV helpers (簡易支援引號)
    // -----------------------
    private static String escapeCsv(String s) {
        if (s == null) return "";
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String[] splitCsvLine(String line) {
        ArrayList<String> out = new ArrayList<>();
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
}