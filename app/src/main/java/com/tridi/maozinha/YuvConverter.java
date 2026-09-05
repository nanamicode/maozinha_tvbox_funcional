package com.tridi.maozinha;

import android.media.Image;
import java.nio.ByteBuffer;

final class YuvConverter {
    private YuvConverter() {}

    static int[] toArgb(Image image, int[] reuse) {
        int w=image.getWidth(), h=image.getHeight();
        int[] out=(reuse!=null && reuse.length==w*h)?reuse:new int[w*h];

        Image.Plane[] p=image.getPlanes();
        ByteBuffer yb=p[0].getBuffer(), ub=p[1].getBuffer(), vb=p[2].getBuffer();
        int yRow=p[0].getRowStride(), yPix=p[0].getPixelStride();
        int uRow=p[1].getRowStride(), uPix=p[1].getPixelStride();
        int vRow=p[2].getRowStride(), vPix=p[2].getPixelStride();

        for(int y=0;y<h;y++) {
            int yi=y*yRow;
            int uvi=(y>>1);
            for(int x=0;x<w;x++) {
                int Y=yb.get(yi+x*yPix)&255;
                int U=ub.get(uvi*uRow+(x>>1)*uPix)&255;
                int V=vb.get(uvi*vRow+(x>>1)*vPix)&255;
                int c=Math.max(0,Y-16), d=U-128, e=V-128;
                int r=(298*c+409*e+128)>>8;
                int g=(298*c-100*d-208*e+128)>>8;
                int b=(298*c+516*d+128)>>8;
                r=r<0?0:(r>255?255:r);
                g=g<0?0:(g>255?255:g);
                b=b<0?0:(b>255?255:b);
                out[y*w+x]=0xff000000|(r<<16)|(g<<8)|b;
            }
        }
        return out;
    }
}
