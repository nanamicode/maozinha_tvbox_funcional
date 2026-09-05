package com.tridi.maozinha;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ModelUtils {
    static ByteBuffer loadAsset(Context context, String name) throws Exception {
        InputStream in = context.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        in.close();
        byte[] bytes = out.toByteArray();
        ByteBuffer b = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        b.put(bytes);
        b.rewind();
        return b;
    }

    static float sigmoid(float x) {
        if (x >= 0f && x <= 1f) return x;
        if (x < -100f) x = -100f;
        if (x > 100f) x = 100f;
        return (float)(1.0 / (1.0 + Math.exp(-x)));
    }

    static int sample(int[] rgb, int w, int h, float x, float y) {
        int ix = Math.round(x), iy = Math.round(y);
        if (ix < 0 || iy < 0 || ix >= w || iy >= h) return 0;
        return rgb[iy*w + ix];
    }

    static void putRgb01(ByteBuffer out, int c) {
        out.putFloat(((c >> 16) & 255) / 255f);
        out.putFloat(((c >> 8) & 255) / 255f);
        out.putFloat((c & 255) / 255f);
    }
}
