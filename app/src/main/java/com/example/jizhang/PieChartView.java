package com.example.jizhang;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 自绘环形图，不依赖任何第三方图表库。
 * 光影版：扇区径向渐变（内深外亮）做体积感，环下柔和投影，端头圆角。
 */
public class PieChartView extends View {

    private final List<DbHelper.CategorySum> data = new ArrayList<>();
    private double total = 0;
    private String symbol = "¥";
    private final Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int bgColor;
    private final int primaryText;
    private final int secondaryText;

    public PieChartView(Context c, AttributeSet a) {
        super(c, a);
        bgColor = ContextCompat.getColor(c, R.color.appBg);
        primaryText = ContextCompat.getColor(c, R.color.textPrimary);
        secondaryText = ContextCompat.getColor(c, R.color.textSecondary);
        textPaint.setTextAlign(Paint.Align.CENTER);
        slicePaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStyle(Paint.Style.STROKE);
        // 阴影层需要软件绘制
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setData(List<DbHelper.CategorySum> d, String symbol) {
        this.symbol = symbol;
        data.clear();
        data.addAll(d);
        total = 0;
        for (DbHelper.CategorySum cs : d) total += cs.sum;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, w);   // 正方形
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        float cx = w / 2f, cy = w / 2f;

        if (total <= 0) {
            textPaint.setTextSize(sp(15));
            textPaint.setColor(secondaryText);
            canvas.drawText("本月暂无支出", cx, cy + sp(5), textPaint);
            return;
        }

        float pad = w * 0.075f;
        float outerR = w / 2f - pad;
        float innerR = outerR * 0.66f;
        float ringW = outerR - innerR;
        float midR = (outerR + innerR) / 2f;
        RectF rect = new RectF(cx - midR, cy - midR, cx + midR, cy + midR);

        // 1) 环下柔和投影：画一圈与背景同色的环，只让阴影露出来
        shadowPaint.setStrokeWidth(ringW);
        shadowPaint.setColor(bgColor);
        shadowPaint.setShadowLayer(w * 0.035f, 0, w * 0.018f, 0x2E000000);
        canvas.drawCircle(cx, cy, midR, shadowPaint);

        // 连续环形 + 细缝：体积感靠径向渐变和投影，不做圆角端头（会把环切碎）
        float gapDeg = 1.8f;
        slicePaint.setStrokeCap(Paint.Cap.BUTT);
        slicePaint.setStrokeWidth(ringW);
        float start = -90f;
        for (DbHelper.CategorySum cs : data) {
            float sweep = (float) (cs.sum / total * 360.0);
            int base = CatStyle.chartColor(cs.category);

            // 径向渐变：内缘略深 → 外缘提亮，弧面隆起的体积感
            int inner = CatStyle.darken(base, 0.86f);
            int outer = CatStyle.lighten(base, 0.18f);
            slicePaint.setShader(new RadialGradient(cx, cy, outerR,
                    new int[]{inner, inner, base, outer},
                    new float[]{0f, innerR / outerR, (innerR / outerR + 1f) / 2f, 1f},
                    Shader.TileMode.CLAMP));

            canvas.drawArc(rect, start + gapDeg / 2f,
                    Math.max(sweep - gapDeg, 0.5f), false, slicePaint);
            start += sweep;
        }
        slicePaint.setShader(null);

        // 中心文字
        textPaint.setTextSize(sp(13));
        textPaint.setColor(secondaryText);
        canvas.drawText("总支出", cx, cy - sp(8), textPaint);
        textPaint.setTextSize(sp(22));
        textPaint.setColor(primaryText);
        textPaint.setFakeBoldText(true);
        canvas.drawText(symbol + String.format("%.2f", total), cx, cy + sp(18), textPaint);
        textPaint.setFakeBoldText(false);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
