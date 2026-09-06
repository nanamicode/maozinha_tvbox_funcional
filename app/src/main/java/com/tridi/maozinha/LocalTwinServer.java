package com.tridi.maozinha;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class LocalTwinServer extends NanoWSD {
    public interface Control {
        void setAiEnabled(boolean enabled);
        boolean isAiEnabled();
    }

    private final Context context;
    private final Control control;
    private final Set<WebSocket> sockets = new CopyOnWriteArraySet<>();

    public LocalTwinServer(Context context, Control control) {
        super(8765);
        this.context = context.getApplicationContext();
        this.control = control;
    }

    @Override protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new WebSocket(handshake) {
            @Override protected void onOpen() {
                sockets.add(this);
                sendSafe("{\"type\":\"status\",\"state\":\""+(control.isAiEnabled()?"RODANDO":"PARADO")+"\",\"backend\":\"ANDROID-TFLITE\",\"profile\":\"b11-parity-v1\"}");
            }
            @Override protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
                sockets.remove(this);
            }
            @Override protected void onMessage(WebSocketFrame message) {}
            @Override protected void onPong(WebSocketFrame pong) {}
            @Override protected void onException(IOException exception) {
                sockets.remove(this);
            }
            private void sendSafe(String s) {
                try { send(s); } catch (IOException ignored) {}
            }
        };
    }

    @Override protected Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();
        if ("/api/start".equals(uri) && Method.POST.equals(session.getMethod())) {
            control.setAiEnabled(true);
            publishStatus("RODANDO");
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
        }
        if ("/api/stop".equals(uri) && Method.POST.equals(session.getMethod())) {
            control.setAiEnabled(false);
            publishStatus("PARADO");
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
        }
        if ("/".equals(uri)) uri="/index.html";
        if ("/ws".equals(uri)) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "WebSocket endpoint");
        String asset="web"+uri;
        try {
            InputStream in=context.getAssets().open(asset);
            String mime=mime(uri);
            return newChunkedResponse(Response.Status.OK,mime,in);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","Not found");
        }
    }

    public void publish(HandResult r, float aiFps, String state) {
        StringBuilder sb=new StringBuilder(2048);
        sb.append("{\"type\":\"frame\",\"backend\":\"ANDROID-TFLITE\",\"profile\":\"b11-parity-v1\",");
        sb.append("\"aiFps\":").append(aiFps).append(',');
        sb.append("\"latencyMs\":").append(r==null?0:r.latencyMs).append(',');
        sb.append("\"state\":\"").append(state).append("\",");
        sb.append("\"gesture\":\"").append(r==null?"-":r.gesture).append("\",");
        sb.append("\"landmarks\":[");
        if(r!=null){
            for(int i=0;i<21;i++){
                if(i>0) sb.append(',');
                sb.append("{\"x\":").append(r.xy[i*2])
                  .append(",\"y\":").append(r.xy[i*2+1])
                  .append(",\"z\":").append(r.z[i]).append('}');
            }
        }
        sb.append("]}");
        broadcastText(sb.toString());
    }

    public void publishStatus(String state) {
        broadcastText("{\"type\":\"status\",\"state\":\""+state+"\",\"backend\":\"ANDROID-TFLITE\",\"profile\":\"b11-parity-v1\"}");
    }

    private void broadcastText(String s) {
        for(WebSocket ws:sockets){
            try { ws.send(s); } catch(IOException ignored){}
        }
    }

    private static String mime(String uri) {
        if(uri.endsWith(".html")) return "text/html; charset=utf-8";
        if(uri.endsWith(".js")) return "text/javascript; charset=utf-8";
        if(uri.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }
}
