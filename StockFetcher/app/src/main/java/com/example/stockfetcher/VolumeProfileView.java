package com.example.stockfetcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.mikephil.charting.charts.CombinedChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.utils.MPPointD;
import com.github.mikephil.charting.utils.Transformer;

import java.util.Collections;
import java.util.Map;

public class VolumeProfileView extends View {

    private CombinedChart mainChart;

    private Map<Integer, Double> volumeByBucket = Collections.emptyMap();
    private double minPrice = 0.0;
    private double bucketSize = 0.0;
    private int numBuckets = 0;

    private float maxVolume = 1f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pocPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public VolumeProfileView(Context context) {
        super(context);
        init();
    }

    public VolumeProfileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VolumeProfileView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);

        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(Color.parseColor("#80F08080"));

        pocPaint.setStyle(Paint.Style.STROKE);
        pocPaint.setStrokeWidth(2f);
        pocPaint.setColor(Color.parseColor("#FFFFD700"));
    }

    public void bindMainChart(CombinedChart chart) {
        this.mainChart = chart;
        invalidate();
    }

    public void setVolumeProfile(Map<Integer, Double> vol,
                                 double minPrice,
                                 double bucketSize,
                                 int numBuckets) {
        this.volumeByBucket = (vol != null) ? vol : Collections.emptyMap();
        this.minPrice = minPrice;
        this.bucketSize = bucketSize;
        this.numBuckets = numBuckets;

        double max = 0.0;
        for (double v : this.volumeByBucket.values()) {
            if (v > max) max = v;
        }
        this.maxVolume = (max > 0) ? (float) max : 1f;

        invalidate();
    }

    public void clear() {
        this.volumeByBucket = Collections.emptyMap();
        this.maxVolume = 1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mainChart == null) return;
        if (volumeByBucket == null || volumeByBucket.isEmpty()) return;
        if (bucketSize <= 0 || numBuckets <= 0) return;

        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) return;

        Transformer transformer = mainChart.getTransformer(YAxis.AxisDependency.LEFT);

        // chart 尚未有資料/尚未layout時，lowestVisibleX 可能怪，保守用 0
        float xForTransform = mainChart.getLowestVisibleX();
        if (Float.isNaN(xForTransform) || Float.isInfinite(xForTransform)) xForTransform = 0f;

        // POC（最大量bucket）
        int pocIndex = -1;
        double pocVol = -1;
        for (int i = 0; i < numBuckets; i++) {
            double v = volumeByBucket.getOrDefault(i, 0.0);
            if (v > pocVol) { pocVol = v; pocIndex = i; }
        }

        for (int i = 0; i < numBuckets; i++) {
            double vol = volumeByBucket.getOrDefault(i, 0.0);
            if (vol <= 0) continue;

            double low = minPrice + i * bucketSize;
            double high = minPrice + (i + 1) * bucketSize;

            MPPointD pHigh = transformer.getPixelForValues(xForTransform, (float) high);
            MPPointD pLow  = transformer.getPixelForValues(xForTransform, (float) low);

            float top = (float) Math.min(pHigh.y, pLow.y);
            float bottom = (float) Math.max(pHigh.y, pLow.y);

            MPPointD.recycleInstance(pHigh);
            MPPointD.recycleInstance(pLow);

            if (bottom < 0 || top > h) continue;

            top = Math.max(0f, top);
            bottom = Math.min(h, bottom);

            float len = (float) (vol / maxVolume) * w;
            if (len < 1f) len = 1f;

            canvas.drawRect(0f, top, len, bottom, barPaint);
        }

        if (pocIndex >= 0) {
            double pocLow = minPrice + pocIndex * bucketSize;
            double pocHigh = minPrice + (pocIndex + 1) * bucketSize;
            double pocMid = (pocLow + pocHigh) / 2.0;

            MPPointD p = transformer.getPixelForValues(xForTransform, (float) pocMid);
            float y = (float) p.y;
            MPPointD.recycleInstance(p);

         //   pocPaint.setColor(Color.parseColor("#40FFD700")); // 半透明金色
            pocPaint.setStrokeWidth(1f);                      // 細一點
            if (y >= 0 && y <= h) {
                canvas.drawLine(0f, y, w, y, pocPaint);
            }
        }
    }
}