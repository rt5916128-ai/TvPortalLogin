package com.roman.tvportal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Рисует "мышиный" курсор поверх страницы. Касания пропускает насквозь. */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private float x = -1, y = -1;
    private boolean active = true;

    public CursorView(Context c) { super(c); init(); }
    public CursorView(Context c, AttributeSet a) { super(c, a); init(); }
    public CursorView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        float s = d * 1.4f;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 18 * s);
        arrow.lineTo(4.5f * s, 14 * s);
        arrow.lineTo(8 * s, 21 * s);
        arrow.lineTo(11 * s, 19.5f * s);
        arrow.lineTo(7.5f * s, 12.8f * s);
        arrow.lineTo(13 * s, 12.8f * s);
        arrow.close();
        setWillNotDraw(false);
    }

    public float getCursorX() { return x; }
    public float getCursorY() { return y; }

    public void setCursor(float nx, float ny) {
        x = nx;
        y = ny;
        invalidate();
    }

    public void setActive(boolean a) {
        active = a;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (x < 0 || x > w || y < 0 || y > h) {
            x = w / 2f;
            y = h / 2f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE || x < 0) return;
        int save = canvas.save();
        canvas.translate(x, y);
        fill.setAlpha(active ? 255 : 110);
        stroke.setAlpha(active ? 255 : 110);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restoreToCount(save);
    }
}
