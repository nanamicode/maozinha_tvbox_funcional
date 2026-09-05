package com.tridi.maozinha;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

public final class OverlayView extends View {
    private static final int[][] EDGES = {
            {0,1},{1,2},{2,3},{3,4},
            {0,5},{5,6},{6,7},{7,8},
            {5,9},{9,10},{10,11},{11,12},
            {9,13},{13,14},{14,15},{15,16},
            {13,17},{17,18},{18,19},{19,20},{0,17}
    };

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint point = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile HandResult result;
    private volatile float cameraFps;
    private volatile float aiFps;
    private volatile String status = "INICIALIZANDO";
    private int sourceW = 640, sourceH = 480;

    public OverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        line.setColor(Color.rgb(124, 58, 237));
        line.setStrokeWidth(7f);
        line.setStrokeCap(Paint.Cap.ROUND);
        point.setColor(Color.WHITE);
        text.setColor(Color.WHITE);
        text.setTextSize(30f);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setSourceSize(int w, int h) { sourceW = w; sourceH = h; }
    public void setResult(HandResult r, float cFps, float iFps, String s) {
        result = r; cameraFps = cFps; aiFps = iFps; status = s;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        final float sx = getWidth() / (float) sourceW;
        final float sy = getHeight() / (float) sourceH;
        HandResult r = result;
        if (r != null && r.xy != null && r.xy.length >= 42) {
            for (int[] e : EDGES) {
                int a = e[0] * 2, b = e[1] * 2;
                c.drawLine(r.xy[a]*sx, r.xy[a+1]*sy,
                           r.xy[b]*sx, r.xy[b+1]*sy, line);
            }
            for (int i=0;i<21;i++) {
                float radius = (i==0 || i==4 || i==8 || i==12 || i==16 || i==20) ? 11f : 8f;
                c.drawCircle(r.xy[i*2]*sx, r.xy[i*2+1]*sy, radius, point);
            }
        }

        Paint panel = new Paint();
        panel.setColor(0xAA000000);
        c.drawRoundRect(24,24,760,190,18,18,panel);
        c.drawText("MAOZINHA EDGE  v0.1.0", 48, 65, text);
        String metrics = String.format(java.util.Locale.US,
                "CAM %.1f FPS  |  IA %.1f FPS  |  %s", cameraFps, aiFps, status);
        c.drawText(metrics, 48, 105, text);
        if (r != null) {
            String info = String.format(java.util.Locale.US,
                    "%s  |  %s  |  %.0f%%  |  %d ms%s",
                    r.rightHand ? "DIREITA" : "ESQUERDA", r.gesture,
                    r.score*100f, r.latencyMs, r.palmDetectorRan ? "  DETECT" : "  TRACK");
            c.drawText(info, 48, 150, text);
        } else {
            c.drawText("Nenhuma mao detectada", 48, 150, text);
        }
    }
}
