package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.lang.ref.WeakReference;

public class MulticolorMotionBackgroundDrawable extends Drawable {
    private int[] colors = new int[]{
            0xff426D57,
            0xffF7E48B,
            0xff87A284,
            0xffFDF6CA
    };

    private WeakReference<View> parentView;

    /**
     * from 0 to 8
     */
    public float posAnimationProgress = 1.0f;

    private RectF rect = new RectF();
    private Bitmap currentBitmap;
    private Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float backgroundAlpha = 1f;


    public MulticolorMotionBackgroundDrawable() {
        super();
        init();
    }

    public MulticolorMotionBackgroundDrawable(int c1, int c2, int c3, int c4) {
        super();
        setColors(c1, c2, c3, c4);
        init();
    }

    @SuppressLint("NewApi")
    private void init() {
        currentBitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888);
        Utilities.generateGradient(currentBitmap, true, getPhase(), getGradientProgress(), currentBitmap.getWidth(), currentBitmap.getHeight(), currentBitmap.getRowBytes(), colors);
    }

    private int getPhase() {
        return 7- ((int) posAnimationProgress) % 8;
    }

    private float getGradientProgress() {
        return posAnimationProgress - (int) posAnimationProgress;
    }

    public Bitmap getBitmap() {
        return currentBitmap;
    }

    public static boolean isDark(int color1, int color2, int color3, int color4) {
        int averageColor = AndroidUtilities.getAverageColor(color1, color2);
        if (color3 != 0) {
            averageColor = AndroidUtilities.getAverageColor(averageColor, color3);
        }
        if (color4 != 0) {
            averageColor = AndroidUtilities.getAverageColor(averageColor, color4);
        }
        float[] hsb = AndroidUtilities.RGBtoHSB(Color.red(averageColor), Color.green(averageColor), Color.blue(averageColor));
        return hsb[2] < 0.3f;
    }

    public float getPosAnimationProgress() {
        return posAnimationProgress;
    }

    public void setPosAnimationProgress(float posAnimationProgress) {
        this.posAnimationProgress = posAnimationProgress;
        Utilities.generateGradient(currentBitmap, true, getPhase(), getGradientProgress(), currentBitmap.getWidth(), currentBitmap.getHeight(), currentBitmap.getRowBytes(), colors);
    }

    public int[] getColors() {
        return colors;
    }

    public void setParentView(View view) {
        parentView = new WeakReference<>(view);
    }

    public void setColors(int c1, int c2, int c3, int c4, Bitmap bitmap) {
        colors[0] = c1;
        colors[1] = c2;
        colors[2] = c3;
        colors[3] = c4;
        Utilities.generateGradient(bitmap, true, getPhase(), getGradientProgress(), currentBitmap.getWidth(), currentBitmap.getHeight(), currentBitmap.getRowBytes(), colors);
    }

    public void setColors(int c1, int c2, int c3, int c4) {
        if (isSameColors(c1, c2, c3, c4)) {
            return;
        }
        colors[0] = c1;
        colors[1] = c2;
        colors[2] = c3;
        colors[3] = c4;
        if (currentBitmap != null) {
            Utilities.generateGradient(currentBitmap, true, getPhase(), getGradientProgress(), currentBitmap.getWidth(), currentBitmap.getHeight(), currentBitmap.getRowBytes(), colors);
        }
    }

    public boolean isSameColors(int c1, int c2, int c3, int c4) {
        return colors[0] == c1 && colors[1] == c2 && colors[2] == c3 && colors[3] == c4;
    }

    public void drawBackground(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        canvas.save();
        int bitmapWidth = currentBitmap.getWidth();
        int bitmapHeight = currentBitmap.getHeight();
        float w = bounds.width();
        float h = bounds.height();
        float maxScale = Math.max(w / bitmapWidth, h / bitmapHeight);
        float width = bitmapWidth * maxScale;
        float height = bitmapHeight * maxScale;
        float x = (w - width) / 2;
        float y = (h - height) / 2;

        rect.set(x, y, x + width, y + height);
        Paint bitmapPaint = paint;
        int wasAlpha = bitmapPaint.getAlpha();
        bitmapPaint.setAlpha((int) (wasAlpha * backgroundAlpha));
        canvas.drawBitmap(currentBitmap, null, rect, bitmapPaint);
        bitmapPaint.setAlpha(wasAlpha);

        canvas.restore();
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        canvas.save();
        int bitmapWidth = currentBitmap.getWidth();
        int bitmapHeight = currentBitmap.getHeight();
        float w = bounds.width();
        float h = bounds.height();
        float maxScale = Math.max(w / bitmapWidth, h / bitmapHeight);
        float width = bitmapWidth * maxScale;
        float height = bitmapHeight * maxScale;
        float x = (w - width) / 2;
        float y = (h - height) / 2;

        rect.set(x, y, x + width, y + height);
        Paint bitmapPaint = paint;
        int wasAlpha = bitmapPaint.getAlpha();
        bitmapPaint.setAlpha((int) (wasAlpha * backgroundAlpha));
        canvas.drawBitmap(currentBitmap, null, rect, bitmapPaint);
        bitmapPaint.setAlpha(wasAlpha);

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    public boolean isOneColor() {
        return colors[0] == colors[1] && colors[0] == colors[2] && colors[0] == colors[3];
    }
}