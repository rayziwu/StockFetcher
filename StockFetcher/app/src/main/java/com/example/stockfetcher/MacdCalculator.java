package com.example.stockfetcher;

import java.util.List;

public final class MacdCalculator {
    private MacdCalculator() {}

    public static void calculateMACD(List<StockDayPrice> prices) {
        if (prices == null || prices.isEmpty()) return;

        final int SHORT = 12;
        final int LONG  = 26;
        final int SIGNAL = 9;

        // 清空舊值
        for (StockDayPrice p : prices) {
            p.macdDIF = Double.NaN;
            p.macdDEA = Double.NaN;
            p.macdHistogram = Double.NaN;
        }

        int n = prices.size();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = prices.get(i).getClose();

        double[] ema12 = emaAdjustFalse(close, SHORT);
        double[] ema26 = emaAdjustFalse(close, LONG);

        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(ema12[i]) && Double.isFinite(ema26[i])) {
                dif[i] = ema12[i] - ema26[i];
            } else {
                dif[i] = Double.NaN;
            }
        }

        double[] dea = emaAdjustFalse(dif, SIGNAL);

        for (int i = 0; i < n; i++) {
            prices.get(i).macdDIF = dif[i];
            prices.get(i).macdDEA = dea[i];

            // Python: Hist = DIF - Signal (沒有 *2)
            if (Double.isFinite(dif[i]) && Double.isFinite(dea[i])) {
                prices.get(i).macdHistogram = dif[i] - dea[i];
            }
        }
    }

    /**
     * 等價於 pandas: series.ewm(span=span, adjust=False).mean()
     * alpha = 2/(span+1)
     * ema[t] = alpha*x[t] + (1-alpha)*ema[t-1]
     *
     * seed：第一個 finite 值作為 ema 起點；之前維持 NaN。
     */
    private static double[] emaAdjustFalse(double[] x, int span) {
        int n = x.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Double.NaN;
        if (n == 0 || span <= 0) return out;

        double alpha = 2.0 / (span + 1.0);

        int first = -1;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(x[i])) { first = i; break; }
        }
        if (first < 0) return out;

        out[first] = x[first];
        for (int i = first + 1; i < n; i++) {
            double xi = x[i];
            if (!Double.isFinite(xi)) {
                out[i] = out[i - 1];
            } else {
                out[i] = alpha * xi + (1.0 - alpha) * out[i - 1];
            }
        }
        return out;
    }
}