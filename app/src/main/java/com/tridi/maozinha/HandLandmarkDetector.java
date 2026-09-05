package com.tridi.maozinha;

import org.tensorflow.lite.Interpreter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

final class HandLandmarkDetector implements AutoCloseable {
    static final class Landmarks {
        final float[] xy = new float[42];
        final float[] z = new float[21];
        float score;
        boolean right;
    }

    private static final int IN=224;
    private final Interpreter interpreter;
    private final ByteBuffer input=ByteBuffer.allocateDirect(IN*IN*3*4).order(ByteOrder.nativeOrder());
    private final float[][] lm=new float[1][63];
    private final float[][] presence=new float[1][1];
    private final float[][] handedness=new float[1][1];
    private final float[][] world=new float[1][63];
    private final Map<Integer,Object> outputs=new HashMap<>();

    HandLandmarkDetector(ByteBuffer model) {
        Interpreter.Options o=new Interpreter.Options();
        o.setNumThreads(2);
        interpreter=new Interpreter(model,o);
        // Shipped MediaPipe hand landmark model output order.
        outputs.put(0,lm); outputs.put(1,presence); outputs.put(2,handedness); outputs.put(3,world);
    }

    Landmarks run(int[] rgb,int w,int h,PalmDetector.Roi roi) {
        input.rewind();
        float cos=(float)Math.cos(roi.rotation), sin=(float)Math.sin(roi.rotation);
        for(int y=0;y<IN;y++) {
            float ly=((y+0.5f)/IN-0.5f)*roi.size;
            for(int x=0;x<IN;x++) {
                float lx=((x+0.5f)/IN-0.5f)*roi.size;
                float sx=roi.cx + lx*cos - ly*sin;
                float sy=roi.cy + lx*sin + ly*cos;
                ModelUtils.putRgb01(input,ModelUtils.sample(rgb,w,h,sx,sy));
            }
        }
        input.rewind();
        interpreter.runForMultipleInputsOutputs(new Object[]{input},outputs);
        float score=ModelUtils.sigmoid(presence[0][0]);
        if(score<0.45f) return null;
        Landmarks out=new Landmarks();
        out.score=score;
        out.right=ModelUtils.sigmoid(handedness[0][0])>0.5f;
        for(int i=0;i<21;i++) {
            float lx=(lm[0][i*3]/IN-0.5f)*roi.size;
            float ly=(lm[0][i*3+1]/IN-0.5f)*roi.size;
            out.xy[i*2]=roi.cx + lx*cos - ly*sin;
            out.xy[i*2+1]=roi.cy + lx*sin + ly*cos;
            out.z[i]=lm[0][i*3+2];
        }
        return out;
    }

    @Override public void close(){interpreter.close();}
}
