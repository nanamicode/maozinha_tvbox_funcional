package com.tridi.maozinha;

import android.content.Context;
import java.nio.ByteBuffer;

final class HandTracker implements AutoCloseable {
    private final PalmDetector palm;
    private final HandLandmarkDetector landmark;
    private PalmDetector.Roi tracked;
    private int trackingAge=0;

    HandTracker(Context context) throws Exception {
        ByteBuffer palmModel=ModelUtils.loadAsset(context,"hand_detection.tflite");
        ByteBuffer landmarkModel=ModelUtils.loadAsset(context,"hand_landmark_full.tflite");
        palm=new PalmDetector(palmModel);
        landmark=new HandLandmarkDetector(landmarkModel);
    }

    HandResult process(int[] rgb,int w,int h) {
        long t0=System.nanoTime();
        boolean ranPalm=false;
        PalmDetector.Roi roi=tracked;

        // Re-detect periodically to correct drift; immediately reacquire if tracking failed.
        if(roi==null || trackingAge>=30) {
            roi=palm.detect(rgb,w,h);
            ranPalm=true;
            trackingAge=0;
        }
        if(roi==null) { tracked=null; return null; }

        HandLandmarkDetector.Landmarks l=landmark.run(rgb,w,h,roi);
        if(l==null) {
            roi=palm.detect(rgb,w,h);
            ranPalm=true;
            trackingAge=0;
            if(roi==null) { tracked=null; return null; }
            l=landmark.run(rgb,w,h,roi);
            if(l==null) { tracked=null; return null; }
        }

        tracked=roiFromLandmarks(l.xy,w,h,l.score);
        trackingAge++;
        String gesture=gesture(l.xy);
        long ms=(System.nanoTime()-t0)/1_000_000L;
        return new HandResult(l.xy,l.z,l.score,l.right,gesture,ms,ranPalm);
    }

    private static PalmDetector.Roi roiFromLandmarks(float[] p,int w,int h,float score) {
        int[] ids={0,1,2,3,5,6,9,10,13,14,17,18};
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
        for(int id:ids) {
            float x=p[id*2],y=p[id*2+1];
            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
            minY=Math.min(minY,y); maxY=Math.max(maxY,y);
        }
        float wristX=p[0], wristY=p[1];
        float axisX=(((p[10]+p[26])*0.5f)+p[18])*0.5f;
        float axisY=(((p[11]+p[27])*0.5f)+p[19])*0.5f;
        float rotation=(float)(Math.PI*0.5-Math.atan2(-(axisY-wristY),axisX-wristX));
        float cx=(minX+maxX)*0.5f, cy=(minY+maxY)*0.5f;
        float size=Math.max(maxX-minX,maxY-minY)*2.15f;
        // Shift slightly toward fingertips.
        cx += 0.10f*size*(float)Math.sin(rotation);
        cy -= 0.10f*size*(float)Math.cos(rotation);
        size=Math.max(48f,Math.min(size,Math.max(w,h)*1.2f));
        return new PalmDetector.Roi(cx,cy,size,rotation,score);
    }

    private static String gesture(float[] p) {
        boolean index=farther(p,8,6,0), middle=farther(p,12,10,0),
                ring=farther(p,16,14,0), pinky=farther(p,20,18,0);
        int n=(index?1:0)+(middle?1:0)+(ring?1:0)+(pinky?1:0);
        if(n>=4) return "MAO ABERTA";
        if(n==0) return "PUNHO";
        if(index && !middle && !ring && !pinky) return "APONTANDO";
        return "GESTO";
    }

    private static boolean farther(float[] p,int tip,int pip,int wrist) {
        return dist2(p,tip,wrist) > dist2(p,pip,wrist)*1.32f;
    }
    private static float dist2(float[] p,int a,int b) {
        float dx=p[a*2]-p[b*2],dy=p[a*2+1]-p[b*2+1];
        return dx*dx+dy*dy;
    }

    @Override public void close(){palm.close();landmark.close();}
}
