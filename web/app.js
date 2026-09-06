import * as THREE from '/vendor/three.module.js';

const canvas=document.querySelector('#gl');
const renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:true});
renderer.setPixelRatio(Math.min(devicePixelRatio,2));
const scene=new THREE.Scene();
const camera=new THREE.PerspectiveCamera(38,1,.1,100);
camera.position.set(0,.4,7.2);
scene.add(new THREE.HemisphereLight(0xffffff,0x252536,2));
const key=new THREE.DirectionalLight(0xffffff,4);key.position.set(4,6,8);scene.add(key);
const rim=new THREE.DirectionalLight(0x7c3aed,5);rim.position.set(-5,1,-4);scene.add(rim);

const root=new THREE.Group();root.rotation.x=-.18;scene.add(root);
const purple=new THREE.MeshStandardMaterial({color:0x7c3aed,metalness:.65,roughness:.25});
const dark=new THREE.MeshStandardMaterial({color:0x30303a,metalness:.85,roughness:.2});
const chrome=new THREE.MeshStandardMaterial({color:0xc7c7d1,metalness:.9,roughness:.15});
const joints=[];
for(let i=0;i<21;i++){const s=new THREE.Mesh(new THREE.SphereGeometry(i===0?.13:.095,16,12),chrome);root.add(s);joints.push(s)}
const edges=[[0,1],[1,2],[2,3],[3,4],[0,5],[5,6],[6,7],[7,8],[5,9],[9,10],[10,11],[11,12],[9,13],[13,14],[14,15],[15,16],[13,17],[17,18],[18,19],[19,20],[0,17]];
const bones=edges.map((e,i)=>{const g=new THREE.CylinderGeometry(i<4?.08:.07,i<4?.08:.07,1,12);const m=new THREE.Mesh(g,i%4===0?purple:dark);root.add(m);return m});
const palm=new THREE.Mesh(new THREE.BoxGeometry(1.45,1.45,.28),dark);root.add(palm);
const palmPlate=new THREE.Mesh(new THREE.BoxGeometry(1.12,1.12,.34),purple);root.add(palmPlate);

const tmp=new THREE.Vector3(),mid=new THREE.Vector3(),up=new THREE.Vector3(0,1,0);
function boneBetween(mesh,a,b){const pa=joints[a].position,pb=joints[b].position;mid.copy(pa).add(pb).multiplyScalar(.5);mesh.position.copy(mid);tmp.copy(pb).sub(pa);mesh.scale.set(1,tmp.length(),1);mesh.quaternion.setFromUnitVectors(up,tmp.clone().normalize())}
function setHand(data){
  const pts=data.landmarks;if(!pts||pts.length!==21)return;
  const mirror=document.querySelector('#mirror').checked?-1:1;
  const depth=+document.querySelector('#depth').value;
  const wrist=pts[0],mcp=pts[9];
  const palmScale=Math.max(.08,Math.hypot(mcp.x-wrist.x,mcp.y-wrist.y));
  for(let i=0;i<21;i++){const p=pts[i];joints[i].position.set(mirror*(p.x-wrist.x)/palmScale*1.25,-(p.y-wrist.y)/palmScale*1.25-1.4,-(p.z||0)/224*depth)}
  edges.forEach((e,i)=>boneBetween(bones[i],e[0],e[1]));
  const c=joints[0].position.clone().add(joints[5].position).add(joints[9].position).add(joints[13].position).add(joints[17].position).multiplyScalar(.2);
  palm.position.copy(c);palmPlate.position.copy(c);
  palm.rotation.z=palmPlate.rotation.z=Math.atan2(joints[17].position.y-joints[5].position.y,joints[17].position.x-joints[5].position.x)-Math.PI/2;
}
function resize(){const w=canvas.clientWidth,h=canvas.clientHeight;if(canvas.width!==w||canvas.height!==h){renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix()}}
function render(){resize();renderer.render(scene,camera);requestAnimationFrame(render)}render();

const el=id=>document.getElementById(id);
async function command(name){try{await fetch('/api/'+name,{method:'POST'})}catch(e){}}
el('start').onclick=()=>command('start');el('stop').onclick=()=>command('stop');
let ws;
function connect(){
  ws=new WebSocket('ws://'+location.host+'/ws');
  ws.onopen=()=>{el('badge').textContent='CONECTADO'};
  ws.onclose=()=>{el('badge').textContent='RECONECTANDO';setTimeout(connect,1000)};
  ws.onmessage=e=>{const d=JSON.parse(e.data);if(d.type==='frame'){setHand(d);el('fps').textContent=(d.aiFps||0).toFixed(1);el('latency').textContent=(d.latencyMs||0)+' ms';el('state').textContent=d.state||'-';el('gesture').textContent=d.gesture||'-';el('backend').textContent=d.backend||'-';el('profile').textContent=d.profile||'-'}else if(d.type==='status'){el('state').textContent=d.state||'-';el('backend').textContent=d.backend||'-';el('profile').textContent=d.profile||'-'}}
}
connect();