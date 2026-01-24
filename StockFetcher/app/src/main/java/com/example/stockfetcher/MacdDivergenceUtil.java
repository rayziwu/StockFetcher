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
    public static Result computePastOnly(List<StockDayPrice> list) {
        // 對齊 Python 預設：left=2, min_gap=5, lookback=35
        return computePastOnly(list, 2, 5, 35);
    }

    public static Result computePastOnly(List<StockDayPrice> list, int left, int minGap, int lookback) {
        Result out = new Result();
        if (list == null) return out;

        int n = list.size();
        int need = Math.max(10, left + minGap + 2);
        if (n < need) return out;

        for (int i = 0; i < n; i++) {
            if (i < Math.max(left, minGap)) continue;

            int lo = Math.max(0, i - lookback);
            int hi = i - minGap;
            if (hi <= lo) continue;

            double curHigh = list.get(i).getHigh();
            double curLow  = list.get(i).getLow();
            double curDif  = list.get(i).macdDIF;
            double curHist = list.get(i).macdHistogram;

            if (!isFinite(curHigh) || !isFinite(curLow) || !isFinite(curDif) || !isFinite(curHist)) continue;

            // 在 [lo, hi] 找過去視窗的最高/最低點（只看過去）
            int prevHighIdx = -1;
            double prevHigh = Double.NEGATIVE_INFINITY;

            int prevLowIdx = -1;
            double prevLow = Double.POSITIVE_INFINITY;

            for (int j = lo; j <= hi; j++) {
                double h = list.get(j).getHigh();
                double l = list.get(j).getLow();
                if (isFinite(h) && h > prevHigh) { prevHigh = h; prevHighIdx = j; }
                if (isFinite(l) && l < prevLow)  { prevLow  = l; prevLowIdx  = j; }
            }

            // past-only 額外約束：只用左側 left 根做「局部極值」
            boolean isLocalHighLeft = true;
            boolean isLocalLowLeft  = true;

            if (left > 0) {
                double maxH = Double.NEGATIVE_INFINITY;
                double minL = Double.POSITIVE_INFINITY;

                for (int j = i - left; j <= i; j++) {
                    double h = list.get(j).getHigh();
                    double l = list.get(j).getLow();
                    if (!isFinite(h) || !isFinite(l)) { isLocalHighLeft = false; isLocalLowLeft = false; break; }
                    if (h > maxH) maxH = h;
                    if (l < minL) minL = l;
                }
                if (isLocalHighLeft) isLocalHighLeft = (curHigh >= maxH);
                if (isLocalLowLeft)  isLocalLowLeft  = (curLow  <= minL);
            }

            // --- 頂背離（DIF / Hist 分開）---
            if (prevHighIdx != -1 && isLocalHighLeft) {
                double preDif  = list.get(prevHighIdx).macdDIF;
                double preHist = list.get(prevHighIdx).macdHistogram;
                double preHigh = list.get(prevHighIdx).getHigh();

                if (isFinite(preHigh) && isFinite(preDif) && curHigh > preHigh && curDif < preDif) {
                    out.difTop.add(i);
                }
                if (isFinite(preHigh) && isFinite(preHist) && curHigh > preHigh && curHist > 0.0 && curHist < preHist) {
                    out.histTop.add(i);
                }
            }

            // --- 底背離（DIF / Hist 分開）---
            if (prevLowIdx != -1 && isLocalLowLeft) {
                double preDif  = list.get(prevLowIdx).macdDIF;
                double preHist = list.get(prevLowIdx).macdHistogram;
                double preLow  = list.get(prevLowIdx).getLow();

                if (isFinite(preLow) && isFinite(preDif) && curLow < preLow && curDif > preDif) {
                    out.difBottom.add(i);
                }
                if (isFinite(preLow) && isFinite(preHist) && curLow < preLow && curHist < 0.0 && curHist > preHist) {
                    out.histBottom.add(i);
                }
            }
        }

        return out;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
    // For debug only
    // 只用來驗證 past-only：列印最後 tailBars 內「命中」的 idx 的關鍵數值
    public static void logPastOnlyTailHitsLastN(List<StockDayPrice> list, int tailBars) {
        if (list == null || list.isEmpty()) return;

        final int left = 2;
        final int minGap = 5;
        final int lookback = 35;

        int n = list.size();
        int start = Math.max(0, n - Math.max(1, tailBars));

        Result po = computePastOnly(list, left, minGap, lookback);

        java.util.TreeSet<Integer> hitIdx = new java.util.TreeSet<>();
        for (int idx : po.difTop)      if (idx >= start) hitIdx.add(idx);
        for (int idx : po.difBottom)   if (idx >= start) hitIdx.add(idx);
        for (int idx : po.histTop)     if (idx >= start) hitIdx.add(idx);
        for (int idx : po.histBottom)  if (idx >= start) hitIdx.add(idx);

        if (hitIdx.isEmpty()) return; // 只印命中的，沒命中就完全不印

        for (int idx : hitIdx) {
            StockDayPrice cur = list.get(idx);

            int prevHighIdx = findPrevHighIdxPastOnly(list, idx, minGap, lookback);
            int prevLowIdx  = findPrevLowIdxPastOnly(list, idx, minGap, lookback);

            double curHigh = cur.getHigh();
            double curLow  = cur.getLow();
            double curDif  = cur.macdDIF;
            double curHist = cur.macdHistogram;

            boolean hitDifTop     = po.difTop.contains(idx);
            boolean hitDifBottom  = po.difBottom.contains(idx);
            boolean hitHistTop    = po.histTop.contains(idx);
            boolean hitHistBottom = po.histBottom.contains(idx);

            android.util.Log.d("DivPO",
                    "PO HIT idx=" + idx +
                            " date=" + cur.getDate() +
                            " signals=" +
                            (hitDifTop ? " DIF_TOP" : "") +
                            (hitDifBottom ? " DIF_BOTTOM" : "") +
                            (hitHistTop ? " HIST_TOP" : "") +
                            (hitHistBottom ? " HIST_BOTTOM" : "")
            );

            android.util.Log.d("DivPO",
                    " curHigh=" + curHigh +
                            " curLow=" + curLow +
                            " curDif=" + curDif +
                            " curHist=" + curHist
            );

            // prevHigh (核對頂背離：要用 High[prevHighIdx])
            if (prevHighIdx >= 0 && prevHighIdx < n) {
                StockDayPrice preH = list.get(prevHighIdx);
                android.util.Log.d("DivPO",
                        " prevHighIdx=" + prevHighIdx +
                                " prevHighDate=" + preH.getDate() +
                                " prevHighPrice=" + preH.getHigh() +
                                " preDif=" + preH.macdDIF +
                                " preHist=" + preH.macdHistogram
                );
            } else {
                android.util.Log.d("DivPO", " prevHighIdx=" + prevHighIdx + " (N/A)");
            }

            // prevLow (核對底背離：要用 Low[prevLowIdx])
            if (prevLowIdx >= 0 && prevLowIdx < n) {
                StockDayPrice preL = list.get(prevLowIdx);
                android.util.Log.d("DivPO",
                        " prevLowIdx=" + prevLowIdx +
                                " prevLowDate=" + preL.getDate() +
                                " prevLowPrice=" + preL.getLow() +
                                " preDif=" + preL.macdDIF +
                                " preHist=" + preL.macdHistogram
                );
            } else {
                android.util.Log.d("DivPO", " prevLowIdx=" + prevLowIdx + " (N/A)");
            }
        }
    }

    // --- past-only 用的 prevHigh/prevLow 選取（必須跟 computePastOnly 一致）---
    private static int findPrevHighIdxPastOnly(List<StockDayPrice> list, int i, int minGap, int lookback) {
        int n = list.size();
        if (i < 0 || i >= n) return -1;

        int lo = Math.max(0, i - lookback);
        int hi = i - minGap;
        if (hi <= lo) return -1;

        int bestIdx = -1;
        double best = Double.NEGATIVE_INFINITY;

        for (int j = lo; j <= hi; j++) {
            double h = list.get(j).getHigh();
            if (isFinite(h) && h > best) {
                best = h;
                bestIdx = j;
            }
        }
        return bestIdx;
    }

    private static int findPrevLowIdxPastOnly(List<StockDayPrice> list, int i, int minGap, int lookback) {
        int n = list.size();
        if (i < 0 || i >= n) return -1;

        int lo = Math.max(0, i - lookback);
        int hi = i - minGap;
        if (hi <= lo) return -1;

        int bestIdx = -1;
        double best = Double.POSITIVE_INFINITY;

        for (int j = lo; j <= hi; j++) {
            double l = list.get(j).getLow();
            if (isFinite(l) && l < best) {
                best = l;
                bestIdx = j;
            }
        }
        return bestIdx;
    }

}