package com.example.stockfetcher;

import android.content.Context;
import android.util.Log;

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
import java.util.List;
import java.util.Locale;

public final class TwTickerRepository {
    private static final String TAG = "TwTickerRepository";

    // 參照 Python 版：TWSE/TPEx 的公開清單
    private static final String BASE_URL = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=";

    // 存在 app 內部儲存（不需要額外權限）
    private static final String CACHE_FILE = "股票代碼.csv";

    private TwTickerRepository() {}

    public static List<TickerInfo> loadOrScrape(Context ctx) {
        // 1) 先讀快取
        List<TickerInfo> cached = readCache(ctx);
        if (cached != null && !cached.isEmpty()) return cached;

        // 2) 失敗才抓網路
        List<TickerInfo> out = new ArrayList<>();
        out.addAll(scrape("2", ".TW", "上市(TWSE)"));   // strMode=2
        out.addAll(scrape("4", ".TWO", "上櫃(TPEx)"));  // strMode=4

        // 3) 存回快取
        if (!out.isEmpty()) writeCache(ctx, out);

        return out;
    }

    private static List<TickerInfo> readCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;

            List<TickerInfo> out = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {

                String header = br.readLine();
                if (header == null) return null;

                // 允許兩種格式：
                //  A) Ticker,Name,Industry
                //  B) 第一欄視為 ticker
                int idxTicker = -1, idxName = -1, idxIndustry = -1;
                String[] h = splitCsvLine(header);
                for (int i = 0; i < h.length; i++) {
                    String col = h[i].trim().toLowerCase(Locale.US);
                    if (col.equals("ticker")) idxTicker = i;
                    else if (col.equals("name")) idxName = i;
                    else if (col.equals("industry") || col.equals("category")) idxIndustry = i;
                }

                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] cols = splitCsvLine(line);
                    String ticker;
                    String name = "";
                    String industry = "";

                    if (idxTicker >= 0 && idxTicker < cols.length) {
                        ticker = cols[idxTicker].trim();
                        if (idxName >= 0 && idxName < cols.length) name = cols[idxName].trim();
                        if (idxIndustry >= 0 && idxIndustry < cols.length) industry = cols[idxIndustry].trim();
                    } else {
                        // 沒有 header / 沒有 Ticker 欄位：第一欄就是 ticker
                        ticker = cols.length > 0 ? cols[0].trim() : "";
                    }

                    ticker = normalizeTicker(ticker);
                    if (ticker.isEmpty()) continue;
                    out.add(new TickerInfo(ticker, name, industry));
                }
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "readCache failed: " + e.getMessage());
            return null;
        }
    }

    private static void writeCache(Context ctx, List<TickerInfo> list) {
        File f = new File(ctx.getFilesDir(), CACHE_FILE);
        try (FileWriter fw = new FileWriter(f, false)) {
            fw.write("Ticker,Name,Industry\n");
            for (TickerInfo t : list) {
                if (t == null || t.ticker == null) continue;
                fw.write(escapeCsv(t.ticker));
                fw.write(",");
                fw.write(escapeCsv(t.name == null ? "" : t.name));
                fw.write(",");
                fw.write(escapeCsv(t.industry == null ? "" : t.industry));
                fw.write("\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "writeCache failed: " + e.getMessage());
        }
    }

    private static List<TickerInfo> scrape(String strMode, String suffix, String marketName) {
        List<TickerInfo> out = new ArrayList<>();
        HttpURLConnection conn = null;

        try {
            URL url = new URL(BASE_URL + strMode);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + url);
                return out;
            }

            byte[] bytes = readAllBytes(conn.getInputStream());

            // TWSE 這頁常用 cp950/big5
            String html = decodeBestEffort(bytes);

            // 解析第一個 table（簡化版）
            String table = extractFirstTable(html);
            if (table.isEmpty()) return out;

            List<List<String>> rows = extractTableRows(table);
            if (rows.isEmpty()) return out;

            // 找 header row 來定位欄位
            int headerRowIdx = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).toString().contains("有價證券代號及名稱")) {
                    headerRowIdx = i;
                    break;
                }
            }
            if (headerRowIdx < 0) return out;

            List<String> header = rows.get(headerRowIdx);
            HashMap<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                idx.put(header.get(i), i);
            }

            Integer iCodeName = idx.get("有價證券代號及名稱");
            Integer iIndustry = idx.get("產業別");
            Integer iMarket = idx.get("市場別");

            if (iCodeName == null) return out;

            for (int r = headerRowIdx + 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row.size() <= iCodeName) continue;

                String codeName = row.get(iCodeName);
                CodeName cn = splitCodeName(codeName);
                if (cn.code == null) continue;

                String ticker = cn.code + suffix;
                String industry = "";
                if (iIndustry != null && iIndustry < row.size()) industry = safe(row.get(iIndustry));
                if (industry.isEmpty() && iMarket != null && iMarket < row.size()) industry = safe(row.get(iMarket));
                if (industry.isEmpty()) industry = marketName;

                out.add(new TickerInfo(ticker, cn.name, industry));
            }

            Log.d(TAG, "scrape " + suffix + " count=" + out.size());
            return out;

        } catch (Exception e) {
            Log.w(TAG, "scrape failed " + suffix + ": " + e.getMessage());
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- Helpers ----------

    private static String normalizeTicker(String s) {
        if (s == null) return "";
        s = s.trim().toUpperCase(Locale.US);
        if (s.isEmpty()) return "";
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static class CodeName {
        final String code;  // 4 digits
        final String name;
        CodeName(String code, String name) { this.code = code; this.name = name; }
    }

    private static CodeName splitCodeName(String cell) {
        if (cell == null) return new CodeName(null, "");
        String s = cell.trim();
        if (s.isEmpty()) return new CodeName(null, "");

        // 常見格式："2330　台積電" (含全形空白 U+3000)，也可能是一般空白
        String[] parts = s.split("\u3000");
        if (parts.length == 1) parts = s.split("\\s+");

        String code = parts.length > 0 ? parts[0].trim() : "";
        StringBuilder name = new StringBuilder();
        for (int i = 1; i < parts.length; i++) name.append(parts[i].trim());

        if (code.matches("^\\d{4}$")) return new CodeName(code, name.toString());
        return new CodeName(null, name.toString());
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String decodeBestEffort(byte[] bytes) {
        for (String cs : new String[]{"MS950", "Big5", "UTF-8"}) {
            try {
                return new String(bytes, Charset.forName(cs));
            } catch (Exception ignored) {}
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String extractFirstTable(String html) {
        String lower = html.toLowerCase(Locale.US);
        int i0 = lower.indexOf("<table");
        if (i0 < 0) return "";
        int i1 = lower.indexOf("</table>", i0);
        if (i1 < 0) return "";
        return html.substring(i0, i1 + "</table>".length());
    }

    private static List<List<String>> extractTableRows(String tableHtml) {
        List<List<String>> rows = new ArrayList<>();

        // 非嚴格 HTML parser：抓 <tr> ... </tr>
        java.util.regex.Pattern trP = java.util.regex.Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
        java.util.regex.Matcher trM = trP.matcher(tableHtml);

        java.util.regex.Pattern tdP = java.util.regex.Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
        while (trM.find()) {
            String tr = trM.group(1);
            java.util.regex.Matcher tdM = tdP.matcher(tr);

            List<String> cells = new ArrayList<>();
            while (tdM.find()) {
                String cellHtml = tdM.group(1);
                String text = htmlToText(cellHtml);
                cells.add(text);
            }
            if (!cells.isEmpty()) rows.add(cells);
        }

        return rows;
    }

    private static String htmlToText(String html) {
        // 去 tags
        String s = html.replaceAll("(?is)<[^>]+>", "");
        // 常見 entity 簡易處理
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.trim();
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // 支援簡單 CSV（含引號）
    private static String[] splitCsvLine(String line) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '\"') {
                if (inQ && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    cur.append('\"'); // escaped quote
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