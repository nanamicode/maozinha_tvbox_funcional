package com.tridi.maozinha;

import org.tensorflow.lite.Interpreter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

final class PalmDetector implements AutoCloseable {
    static final class Roi {
        float cx, cy, size, rotation, score;
        Roi(float cx,float cy,float size,float rotation,float score) {
            this.cx=cx; this.cy=cy; this.size=size; this.rotation=rotation; this.score=score;
        }
    }

    private static final int IN = 192;
    private static final int N = 2016;
    private final Interpreter interpreter;
    private final ByteBuffer input = ByteBuffer.allocateDirect(IN*IN*3*4).order(ByteOrder.nativeOrder());
    private final float[][][] boxes = new float[1][N][18];
    private final float[][][] scores = new float[1][N][1];
    private final float[][] anchors = buildAnchors();
    private final Map<Integer,Object> outputs = new HashMap<>();

    PalmDetector(ByteBuffer model) {
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(2);
        interpreter = new Interpreter(model, o);
        outputs.put(0, boxes);
        outputs.put(1, scores);
    }

    Roi detect(int[] rgb, int w, int h) {
        input.rewind();
        final float max = Math.max(w,h);
        final float padX = (max-w)/2f, padY = (max-h)/2f;
        for (int y=0;y<IN;y++) {
            float sy = ((y+0.5f)/IN)*max - padY;
            for (int x=0;x<IN;x++) {
                float sx = ((x+0.5f)/IN)*max - padX;
                ModelUtils.putRgb01(input, ModelUtils.sample(rgb,w,h,sx,sy));
            }
        }
        input.rewind();
        interpreter.runForMultipleInputsOutputs(new Object[]{input}, outputs);

        int best=-1;
        float bestScore=0.55f;
        for (int i=0;i<N;i++) {
            float s = ModelUtils.sigmoid(scores[0][i][0]);
            if (s > bestScore) { bestScore=s; best=i; }
        }
        if (best < 0) return null;

        float[] b = boxes[0][best];
        float ax = anchors[best][0], ay = anchors[best][1];
        float cx = b[0]/192f + ax;
        float cy = b[1]/192f + ay;
        float bw = b[2]/192f, bh = b[3]/192f;
        float boxSize = Math.max(Math.abs(bw), Math.abs(bh));
        float kp0x = b[4]/192f + ax, kp0y = b[5]/192f + ay;
        float kp2x = b[8]/192f + ax, kp2y = b[9]/192f + ay;

        float dx=kp2x-kp0x, dy=kp2y-kp0y;
        float rotation = (float)(Math.PI*0.5 - Math.atan2(-dy, dx));
        while (rotation > Math.PI) rotation -= (float)(2*Math.PI);
        while (rotation < -Math.PI) rotation += (float)(2*Math.PI);

        float roiCx = cx + 0.5f*boxSize*(float)Math.sin(rotation);
        float roiCy = cy - 0.5f*boxSize*(float)Math.cos(rotation);

        // Undo square letterbox back to image-normalized coordinates.
        float px = roiCx*max - padX;
        float py = roiCy*max - padY;
        float roiSizePx = 2.6f*boxSize*max;
        return new Roi(px, py, roiSizePx, rotation, bestScore);
    }

    private static float[][] buildAnchors() {
        float[][] a = new float[N][2];
        int k=0;
        // stride 8 layer => 24x24, 2 anchors/cell
        for (int y=0;y<24;y++) for (int x=0;x<24;x++) for(int q=0;q<2;q++) {
            a[k][0]=(x+0.5f)/24f; a[k][1]=(y+0.5f)/24f; k++;
        }
        // three stride-16 layers grouped => 12x12, 6 anchors/cell
        for (int y=0;y<12;y++) for (int x=0;x<12;x++) for(int q=0;q<6;q++) {
            a[k][0]=(x+0.5f)/12f; a[k][1]=(y+0.5f)/12f; k++;
        }
        return a;
    }

    @Override public void close() { interpreter.close(); }
}
