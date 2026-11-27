/*
 * Copyright 2026 Exelere.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools.gui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A small live picture of the voice: one bar for each recent volume level, the newest on the right.
 */
public class WaveformView extends View {
    private static final int BARS = 28;
    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float loudest = 0.0001f;   // the levels have no fixed scale, we compare them to the loudest one heard
    private boolean active = false;

    public WaveformView(Context context) {
        super(context);
        paint.setColor(0xFF00B4FF);
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFF00B4FF);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** @param isActive false draws a quiet flat line */
    public void setActive(boolean isActive) {
        if (active != isActive) {
            active = isActive;
            if (!active) {
                java.util.Arrays.fill(levels, 0);
            }
            invalidate();
        }
    }

    public void addLevel(float level) {
        if (!active || Float.isNaN(level)) {
            return;
        }
        level = Math.abs(level);
        loudest = Math.max(loudest * 0.995f, level);
        System.arraycopy(levels, 1, levels, 0, BARS - 1);
        levels[BARS - 1] = Math.min(1f, level / loudest);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float step = width / BARS;
        float thickness = step * 0.55f;
        paint.setAlpha(active ? 255 : 90);
        for (int i = 0; i < BARS; i++) {
            float barHeight = Math.max(thickness, levels[i] * height);
            float left = i * step + (step - thickness) / 2f;
            float top = (height - barHeight) / 2f;
            bar.set(left, top, left + thickness, top + barHeight);
            canvas.drawRoundRect(bar, thickness / 2f, thickness / 2f, paint);
        }
    }
}
