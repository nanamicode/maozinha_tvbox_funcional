import asyncio, json, math, sys, time, webbrowser
from pathlib import Path
import cv2
import numpy as np
import tensorflow as tf
from aiohttp import web

PORT = 8765

def resource_path(*parts):
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    return base.joinpath(*parts)

PROFILE = json.loads(resource_path("shared","edge_profile.json").read_text(encoding="utf-8"))

def sigmoid(x):
    x=float(x)
    if 0.0 <= x <= 1.0:
        return x
    x=max(-100.0,min(100.0,x))
    return 1.0/(1.0+math.exp(-x))

def build_anchors():
    a=[]
    for y in range(24):
        for x in range(24):
            for _ in range(2):
                a.append(((x+.5)/24.0,(y+.5)/24.0))
    for y in range(12):
        for x in range(12):
            for _ in range(6):
                a.append(((x+.5)/12.0,(y+.5)/12.0))
    return np.asarray(a,np.float32)

ANCHORS=build_anchors()

class PalmDetector:
    def __init__(self, model_path):
        self.i=tf.lite.Interpreter(model_path=str(model_path),num_threads=int(PROFILE["threads"]))
        self.i.allocate_tensors()
        self.ins=self.i.get_input_details()
        self.outs=self.i.get_output_details()

    def detect(self, rgb):
        h,w=rgb.shape[:2]
        m=max(w,h)
        px=(m-w)//2
        py=(m-h)//2
        sq=np.zeros((m,m,3),np.uint8)
        sq[py:py+h,px:px+w]=rgb
        img=cv2.resize(sq,(192,192),interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
        self.i.set_tensor(self.ins[0]["index"],img[None])
        self.i.invoke()
        vals=[self.i.get_tensor(o["index"]) for o in self.outs]
        boxes=next(v for v in vals if v.size==2016*18).reshape(-1,18)
        scores=next(v for v in vals if v.size==2016).reshape(-1)
        probs=np.asarray([sigmoid(v) for v in scores],np.float32)
        best=int(np.argmax(probs))
        conf=float(probs[best])
        if conf < float(PROFILE["palmScoreThreshold"]):
            return None
        b=boxes[best]
        ax,ay=ANCHORS[best]
        cx=b[0]/192.0+ax
        cy=b[1]/192.0+ay
        box=max(abs(float(b[2]/192.0)),abs(float(b[3]/192.0)))
        k0x=b[4]/192.0+ax
        k0y=b[5]/192.0+ay
        k2x=b[8]/192.0+ax
        k2y=b[9]/192.0+ay
        rot=math.pi*.5-math.atan2(-(k2y-k0y),k2x-k0x)
        while rot>math.pi:
            rot-=math.tau
        while rot<-math.pi:
            rot+=math.tau
        rcx=cx+0.5*box*math.sin(rot)
        rcy=cy-0.5*box*math.cos(rot)
        return {
            "cx": rcx*m-px,
            "cy": rcy*m-py,
            "size": float(PROFILE["palmRoiScale"])*box*m,
            "rotation": rot,
            "score": conf
        }

class LandmarkDetector:
    def __init__(self, model_path):
        self.i=tf.lite.Interpreter(model_path=str(model_path),num_threads=int(PROFILE["threads"]))
        self.i.allocate_tensors()
        self.ins=self.i.get_input_details()
        self.outs=self.i.get_output_details()

    def run(self,rgb,roi):
        h,w=rgb.shape[:2]
        M=cv2.getRotationMatrix2D((roi["cx"],roi["cy"]),-math.degrees(roi["rotation"]),1.0)
        rotated=cv2.warpAffine(rgb,M,(w,h),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
        size=max(1,int(round(roi["size"])))
        x=int(round(roi["cx"]-size/2))
        y=int(round(roi["cy"]-size/2))
        crop=np.zeros((size,size,3),np.uint8)
        x0=max(0,x); y0=max(0,y); x1=min(w,x+size); y1=min(h,y+size)
        if x1>x0 and y1>y0:
            crop[y0-y:y1-y,x0-x:x1-x]=rotated[y0:y1,x0:x1]
        inp=cv2.resize(crop,(224,224),interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
        self.i.set_tensor(self.ins[0]["index"],inp[None])
        self.i.invoke()
        vals=[self.i.get_tensor(o["index"]).reshape(-1) for o in self.outs]
        lm_candidates=[v for v in vals if v.size==63]
        lm=lm_candidates[0]
        scalars=[v for v in vals if v.size==1]
        scalar_probs=[sigmoid(v[0]) for v in scalars]
        presence=max(scalar_probs)
        handed=scalar_probs[0] if len(scalar_probs)==1 else min(scalar_probs)
        if presence < float(PROFILE["landmarkScoreThreshold"]):
            return None
        c=math.cos(roi["rotation"])
        s=math.sin(roi["rotation"])
        pts=[]
        for i in range(21):
            lx=(float(lm[i*3])/224.0-.5)*roi["size"]
            ly=(float(lm[i*3+1])/224.0-.5)*roi["size"]
            pts.append({
                "x": roi["cx"]+lx*c-ly*s,
                "y": roi["cy"]+lx*s+ly*c,
                "z": float(lm[i*3+2])
            })
        return {"points":pts,"score":presence,"right":handed>.5}

class Tracker:
    def __init__(self):
        self.palm=PalmDetector(resource_path("models","hand_detection.tflite"))
        self.land=LandmarkDetector(resource_path("models","hand_landmark_full.tflite"))
        self.roi=None
        self.age=0

    def roi_from_points(self,pts,score):
        ids=(0,1,2,3,5,6,9,10,13,14,17,18)
        xs=[pts[i]["x"] for i in ids]
        ys=[pts[i]["y"] for i in ids]
        cx=(min(xs)+max(xs))/2
        cy=(min(ys)+max(ys))/2
        wrist=pts[0]
        axisx=(((pts[5]["x"]+pts[13]["x"])*.5)+pts[9]["x"])*.5
        axisy=(((pts[5]["y"]+pts[13]["y"])*.5)+pts[9]["y"])*.5
        rot=math.pi*.5-math.atan2(-(axisy-wrist["y"]),axisx-wrist["x"])
        size=max(max(xs)-min(xs),max(ys)-min(ys))*float(PROFILE["trackingRoiScale"])
        cx += float(PROFILE["trackingShift"])*size*math.sin(rot)
        cy -= float(PROFILE["trackingShift"])*size*math.cos(rot)
        return {"cx":cx,"cy":cy,"size":max(48.0,size),"rotation":rot,"score":score}

    def gesture(self,p):
        def d2(a,b):
            return (p[a]["x"]-p[b]["x"])**2+(p[a]["y"]-p[b]["y"])**2
        def far(tip,pip):
            return d2(tip,0)>d2(pip,0)*1.32
        f=[far(8,6),far(12,10),far(16,14),far(20,18)]
        n=sum(f)
        if n>=4:
            return "MAO ABERTA"
        if n==0:
            return "PUNHO"
        if f[0] and not any(f[1:]):
            return "APONTANDO"
        return "GESTO"

    def process(self,bgr):
        t=time.perf_counter()
        rgb=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB)
        ran=False
        roi=self.roi
        if roi is None or self.age>=int(PROFILE["redetectEveryFrames"]):
            roi=self.palm.detect(rgb)
            ran=True
            self.age=0
        if roi is None:
            self.roi=None
            return None
        out=self.land.run(rgb,roi)
        if out is None:
            roi=self.palm.detect(rgb)
            ran=True
            self.age=0
            if roi is None:
                self.roi=None
                return None
            out=self.land.run(rgb,roi)
            if out is None:
                self.roi=None
                return None
        self.roi=self.roi_from_points(out["points"],out["score"])
        self.age+=1
        return {
            "landmarks":out["points"],
            "score":out["score"],
            "rightHand":out["right"],
            "gesture":self.gesture(out["points"]),
            "latencyMs":int((time.perf_counter()-t)*1000),
            "palmDetectorRan":ran
        }

class DigitalTwinApp:
    def __init__(self):
        self.clients=set()
        self.running=False
        self.tracker=None
        self.cap=None
        self.ai_fps=0.0
        self.fps_n=0
        self.fps_t=time.perf_counter()

    async def broadcast(self,obj):
        if not self.clients:
            return
        data=json.dumps(obj,separators=(",",":"))
        dead=[]
        for ws in tuple(self.clients):
            try:
                await ws.send_str(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.clients.discard(ws)

    async def websocket(self,req):
        ws=web.WebSocketResponse(heartbeat=20)
        await ws.prepare(req)
        self.clients.add(ws)
        await ws.send_json({
            "type":"status",
            "state":"RODANDO" if self.running else "PARADO",
            "backend":"WINDOWS-TFLITE",
            "profile":PROFILE["profileVersion"]
        })
        try:
            async for _ in ws:
                pass
        finally:
            self.clients.discard(ws)
        return ws

    async def start_ai(self,req):
        if not self.running:
            self.running=True
            asyncio.create_task(self.loop())
        return web.json_response({"ok":True})

    async def stop_ai(self,req):
        self.running=False
        if self.cap is not None:
            self.cap.release()
            self.cap=None
        await self.broadcast({
            "type":"status","state":"PARADO",
            "backend":"WINDOWS-TFLITE","profile":PROFILE["profileVersion"]
        })
        return web.json_response({"ok":True})

    async def loop(self):
        try:
            if self.tracker is None:
                self.tracker=Tracker()
            self.cap=cv2.VideoCapture(0,cv2.CAP_DSHOW)
            self.cap.set(cv2.CAP_PROP_FRAME_WIDTH,int(PROFILE["cameraWidth"]))
            self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT,int(PROFILE["cameraHeight"]))
            self.cap.set(cv2.CAP_PROP_BUFFERSIZE,1)
            while self.running:
                ok,frame=await asyncio.to_thread(self.cap.read)
                if not ok:
                    await self.broadcast({
                        "type":"status","state":"SEM CAMERA",
                        "backend":"WINDOWS-TFLITE","profile":PROFILE["profileVersion"]
                    })
                    await asyncio.sleep(.5)
                    continue
                if frame.shape[1]!=int(PROFILE["cameraWidth"]) or frame.shape[0]!=int(PROFILE["cameraHeight"]):
                    frame=cv2.resize(frame,(int(PROFILE["cameraWidth"]),int(PROFILE["cameraHeight"])))
                out=await asyncio.to_thread(self.tracker.process,frame)
                self.fps_n+=1
                now=time.perf_counter()
                dt=now-self.fps_t
                if dt>=1:
                    self.ai_fps=self.fps_n/dt
                    self.fps_n=0
                    self.fps_t=now
                if out:
                    out.update({
                        "type":"frame","aiFps":self.ai_fps,
                        "state":"DETECT" if out["palmDetectorRan"] else "TRACK",
                        "backend":"WINDOWS-TFLITE","profile":PROFILE["profileVersion"]
                    })
                    await self.broadcast(out)
                else:
                    await self.broadcast({
                        "type":"frame","landmarks":[],"aiFps":self.ai_fps,
                        "latencyMs":0,"state":"PROCURANDO","gesture":"-",
                        "backend":"WINDOWS-TFLITE","profile":PROFILE["profileVersion"]
                    })
                await asyncio.sleep(0)
        except Exception as e:
            self.running=False
            await self.broadcast({
                "type":"status","state":"ERRO: "+str(e)[:80],
                "backend":"WINDOWS-TFLITE","profile":PROFILE["profileVersion"]
            })

async def main():
    state=DigitalTwinApp()
    app=web.Application()
    app.router.add_get("/ws",state.websocket)
    app.router.add_post("/api/start",state.start_ai)
    app.router.add_post("/api/stop",state.stop_ai)
    webroot=resource_path("web")
    async def index(req):
        return web.FileResponse(webroot/"index.html")
    app.router.add_get("/",index)
    app.router.add_static("/",webroot,show_index=False)
    runner=web.AppRunner(app)
    await runner.setup()
    site=web.TCPSite(runner,"127.0.0.1",PORT)
    await site.start()
    webbrowser.open("http://127.0.0.1:%d"%PORT)
    print("Maozinha Digital Twin em http://127.0.0.1:%d"%PORT)
    await asyncio.Event().wait()

if __name__=="__main__":
    asyncio.run(main())
