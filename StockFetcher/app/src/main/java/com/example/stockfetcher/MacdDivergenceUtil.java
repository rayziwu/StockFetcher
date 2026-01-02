package com.example.stockfetcher;

import java.util.ArrayList;
import java.util.List;

public final class MacdDivergenceUtil {

    private MacdDivergenceUtil() {}

    public enum Side { BOTTOM, TOP }

    public static final class Result {
        public final List<Integer> difBottom = new ArrayList<>();   // 你原本 difUp
        public final List<Integer> difTop = new ArrayList<>();      // 你原本 difDown
        public final List<Integer> histBottom = new ArrayList<>();  // 你原本 histoUp
        public final List<Integer> histTop = new ArrayList<>();     // 你原本 histoDown

        public boolean hasInLastBars(int totalSize, int lastBars, Side side) {
            int start = Math.max(0, totalSize - Math.max(1, lastBars));
            if (side == Side.BOTTOM) {
                return hasAnyIndexFrom(difBottom, start) || hasAnyIndexFrom(histBottom, start);
            } else {
                return hasAnyIndexFrom(difTop, start) || hasAnyIndexFrom(histTop, start);
            }
        }

        private static boolean hasAnyIndexFrom(List<Integer> idxs, int start) {
            for (int idx : idxs) if (idx >= start) return true;
            return false;
        }
    }

    // ✅ 背離判斷邏輯完全沿用你 drawMainChartData 內的條件：DIF/Hist 都算（OR）
    public static Result compute(List<StockDayPrice> list) {
        Result out = new Result();
        if (list == null || list.size() < 8) return out;

        for (int i = 5; i < list.size() - 2; i++) {

            // 頂背離：價格創高，但 DIF/Hist 沒創高
            if (isLocalHigh(list, i)) {
                int prevH = findPreviousLocalHigh(list, i);
                if (prevH != -1) {
                    double curP = list.get(i).getHigh();
                    double preP = list.get(prevH).getHigh();

                    if (curP > preP && list.get(i).macdDIF < list.get(prevH).macdDIF) {
                        out.difTop.add(i);
                    }
                    if (curP > preP
                            && list.get(i).macdHistogram < list.get(prevH).macdHistogram
                            && list.get(i).macdHistogram > 0) {
                        out.histTop.add(i);
                    }
                }
            }

            // 底背離：價格創低，但 DIF/Hist 沒創低
            if (isLocalLow(list, i)) {
                int prevL = findPreviousLocalLow(list, i);
                if (prevL != -1) {
                    double curP = list.get(i).getLow();
                    double preP = list.get(prevL).getLow();

                    if (curP < preP && list.get(i).macdDIF > list.get(prevL).macdDIF) {
                        out.difBottom.add(i);
                    }
                    if (curP < preP
                            && list.get(i).macdHistogram > list.get(prevL).macdHistogram
                            && list.get(i).macdHistogram < 0) {
                        out.histBottom.add(i);
                    }
                }
            }
        }
        return out;
    }

    // ===== 你提供的四個方法：原封不動搬來（改 static）=====

    private static boolean isLocalHigh(List<StockDayPrice> list, int i) {
        if (i < 2 || i > list.size() - 3) return false;
        double v = list.get(i).getHigh();
        return v > list.get(i - 1).getHigh() && v > list.get(i - 2).getHigh()
                && v > list.get(i + 1).getHigh() && v > list.get(i + 2).getHigh();
    }

    private static boolean isLocalLow(List<StockDayPrice> list, int i) {
        if (i < 2 || i > list.size() - 3) return false;
        double v = list.get(i).getLow();
        return v < list.get(i - 1).getLow() && v < list.get(i - 2).getLow()
                && v < list.get(i + 1).getLow() && v < list.get(i + 2).getLow();
    }

    private static int findPreviousLocalHigh(List<StockDayPrice> list, int currentIdx) {
        for (int j = currentIdx - 5; j > Math.max(0, currentIdx - 35); j--) {
            if (isLocalHigh(list, j)) return j;
        }
        return -1;
    }

    private static int findPreviousLocalLow(List<StockDayPrice> list, int currentIdx) {
        for (int j = currentIdx - 5; j > Math.max(0, currentIdx - 35); j--) {
            if (isLocalLow(list, j)) return j;
        }
        return -1;
    }
}