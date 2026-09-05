package com.tridi.maozinha;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.FrameLayout;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_CAMERA=7;
    private static final int W=640,H=480;

    private TextureView preview;
    private OverlayView overlay;
    private HandlerThread cameraThread,inferenceThread;
    private Handler cameraHandler,inferenceHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandTracker tracker;
    private volatile boolean trackerReady=false;
    private final AtomicBoolean inferenceBusy=new AtomicBoolean(false);
    private int[] rgbReuse;

    private long camWindowStart=0,aiWindowStart=0;
    private int camFrames=0,aiFrames=0;
    private volatile float cameraFps=0,aiFps=0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        FrameLayout root=new FrameLayout(this);
        preview=new TextureView(this);
        preview.setSurfaceTextureListener(this);
        overlay=new OverlayView(this);
        overlay.setSourceSize(W,H);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);

        cameraThread=new HandlerThread("camera");
        cameraThread.start();
        cameraHandler=new Handler(cameraThread.getLooper());
        inferenceThread=new HandlerThread("hand-ai",Process.THREAD_PRIORITY_DISPLAY);
        inferenceThread.start();
        inferenceHandler=new Handler(inferenceThread.getLooper());

        overlay.setResult(null,0,0,"CARREGANDO MODELOS");
        inferenceHandler.post(() -> {
            try {
                tracker=new HandTracker(getApplicationContext());
                trackerReady=true;
                runOnUiThread(() -> overlay.setResult(null,cameraFps,aiFps,"PRONTO"));
            } catch(Exception e) {
                runOnUiThread(() -> overlay.setResult(null,cameraFps,aiFps,
                        "ERRO MODELO: "+shortMsg(e)));
            }
        });

        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] grants) {
        super.onRequestPermissionsResult(req,perms,grants);
        if(req==REQ_CAMERA && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED && preview.isAvailable())
            openCamera();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st,int width,int height) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st,int w,int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void openCamera() {
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraManager cm=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
            String chosen=null;
            for(String id:cm.getCameraIdList()) {
                CameraCharacteristics cc=cm.getCameraCharacteristics(id);
                Integer facing=cc.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null && facing==CameraCharacteristics.LENS_FACING_EXTERNAL) { chosen=id; break; }
                if(chosen==null) chosen=id;
            }
            if(chosen==null) {
                overlay.setResult(null,cameraFps,aiFps,"SEM CAMERA");
                return;
            }
            final String id=chosen;
            cm.openCamera(id,new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { camera=c; createSession(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera=null; }
                @Override public void onError(CameraDevice c,int error) {
                    c.close(); camera=null;
                    runOnUiThread(() -> overlay.setResult(null,cameraFps,aiFps,"CAM ERROR "+error));
                }
            },cameraHandler);
        } catch(Exception e) {
            overlay.setResult(null,cameraFps,aiFps,"ERRO CAMERA: "+shortMsg(e));
        }
    }

    private void createSession() {
        try {
            if(camera==null || !preview.isAvailable()) return;
            SurfaceTexture st=preview.getSurfaceTexture();
            st.setDefaultBufferSize(W,H);
            Surface previewSurface=new Surface(st);
            reader=ImageReader.newInstance(W,H,android.graphics.ImageFormat.YUV_420_888,2);
            reader.setOnImageAvailableListener(this::onImage,cameraHandler);

            final CaptureRequest.Builder req=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(previewSurface);
            req.addTarget(reader.getSurface());
            req.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            req.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(java.util.Arrays.asList(previewSurface,reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session=s;
                            try { s.setRepeatingRequest(req.build(),null,cameraHandler); }
                            catch(CameraAccessException e) {
                                overlay.setResult(null,cameraFps,aiFps,"CAPTURE ERROR");
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            overlay.setResult(null,cameraFps,aiFps,"SESSION ERROR");
                        }
                    },cameraHandler);
        } catch(Exception e) {
            overlay.setResult(null,cameraFps,aiFps,"SESSION: "+shortMsg(e));
        }
    }

    private void onImage(ImageReader ir) {
        Image image=ir.acquireLatestImage();
        if(image==null) return;
        countCameraFrame();
        if(!trackerReady || !inferenceBusy.compareAndSet(false,true)) {
            image.close();
            return;
        }
        inferenceHandler.post(() -> {
            HandResult result=null;
            String status="TRACK";
            try {
                rgbReuse=YuvConverter.toArgb(image,rgbReuse);
                result=tracker.process(rgbReuse,image.getWidth(),image.getHeight());
                countAiFrame();
                status=result==null?"PROCURANDO":(result.palmDetectorRan?"DETECT":"TRACK");
            } catch(Throwable t) {
                status="IA ERROR: "+shortMsg(t);
            } finally {
                image.close();
                inferenceBusy.set(false);
            }
            final HandResult r=result;
            final String s=status;
            runOnUiThread(() -> overlay.setResult(r,cameraFps,aiFps,s));
        });
    }

    private void countCameraFrame() {
        long now=SystemClock.elapsedRealtime();
        if(camWindowStart==0) camWindowStart=now;
        camFrames++;
        long dt=now-camWindowStart;
        if(dt>=1000) { cameraFps=camFrames*1000f/dt; camFrames=0; camWindowStart=now; }
    }
    private void countAiFrame() {
        long now=SystemClock.elapsedRealtime();
        if(aiWindowStart==0) aiWindowStart=now;
        aiFrames++;
        long dt=now-aiWindowStart;
        if(dt>=1000) { aiFps=aiFrames*1000f/dt; aiFrames=0; aiWindowStart=now; }
    }

    private void closeCamera() {
        try { if(session!=null) session.close(); } catch(Exception ignored) {}
        try { if(camera!=null) camera.close(); } catch(Exception ignored) {}
        try { if(reader!=null) reader.close(); } catch(Exception ignored) {}
        session=null; camera=null; reader=null;
    }

    @Override protected void onDestroy() {
        closeCamera();
        if(inferenceHandler!=null) inferenceHandler.post(() -> { if(tracker!=null) tracker.close(); });
        if(cameraThread!=null) cameraThread.quitSafely();
        if(inferenceThread!=null) inferenceThread.quitSafely();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static String shortMsg(Throwable t) {
        String s=t.getMessage();
        if(s==null || s.isEmpty()) s=t.getClass().getSimpleName();
        return s.length()>28?s.substring(0,28):s;
    }
}
