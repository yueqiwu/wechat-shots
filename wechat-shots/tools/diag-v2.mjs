import * as core from '../app/js/core.js';
import * as pipeline from '../app/js/pipeline.js';
function mulberry32(a){return function(){a|=0;a=(a+0x6D2B79F5)|0;let t=Math.imul(a^(a>>>15),1|a);t=(t+Math.imul(t^(t>>>7),61|t))^t;return((t^(t>>>14))>>>0)/4294967296;};}
const AW=120,AH=300,HEAD=26,FOOT=34,BAND_TOP=HEAD,BAND_BOT=AH-FOOT,DOC_H=9000;
const rnd=mulberry32(777);const doc=new Uint8Array(AW*DOC_H);
for(let y=0;y<DOC_H;y++){const texty=rnd()<0.55;const shade=rnd()*18;for(let x=0;x<AW;x++)
  doc[y*AW+x]=Math.max(0,Math.min(255,Math.round((texty?(rnd()>0.5?28:238):212)+shade-9)));}
const header=new Uint8Array(AW*HEAD);for(let y=0;y<HEAD;y++)for(let x=0;x<AW;x++)header[y*AW+x]=40+((x*7+y*3)%60);
const footer=new Uint8Array(AW*FOOT);for(let y=0;y<FOOT;y++)for(let x=0;x<AW;x++)footer[y*AW+x]=225+((x+y)%20);
function speedAt(t){if(t<0.5)return 0;if(t<2)return 400*(t-0.5)/1.5;if(t<3.2)return 2600;if(t<3.9)return 0;if(t<5.6)return 900;if(t<6.6)return -700;return 0;}
const DUR=7.6;const POS=new Float64Array(7602);{let p=0;for(let i=0;i<=7600;i++){POS[i]=p;p+=speedAt((i+0.5)/1000)/1000;}}
function posAt(t){const x=Math.max(0,Math.min(7600,t*1000));const i=Math.floor(x);const f=x-i;return POS[i]*(1-f)+POS[Math.min(7601,i+1)]*f;}
function frameAt(t){const pos=posAt(t);const f=new Uint8Array(AW*AH);
for(let y=0;y<AH;y++){if(y<HEAD){f.set(header.subarray(y*AW,(y+1)*AW),y*AW);continue;}if(y>=BAND_BOT){f.set(footer.subarray((y-BAND_BOT)*AW,(y-BAND_BOT+1)*AW),y*AW);continue;}
const d0=Math.round(y-BAND_TOP+pos);for(let x=0;x<AW;x++){const d=d0;f[y*AW+x]=(d>=0&&d<DOC_H)?doc[d*AW+x]:250;}}
return f;}
function sample(fps,t0,t1){const out=[];const n=Math.max(2,Math.round((t1-t0)*fps));for(let i=0;i<n;i++){const t=t0+(i+0.5)*(t1-t0)/n;out.push({t,gray:frameAt(t)});}return out;}
let frames=sample(8,0,DUR);
const band=pipeline.detectBand(frames,AW,AH);
pipeline.prepFrames(frames,AW,AH,band);
let track=core.trackOffsets(frames.map(f=>f.sig),band.h,{bins:8});
console.log('round0 frames',frames.length,'bad',track.bad.length,track.bad.slice(0,5).map(b=>b.reason+'@'+b.i+' ncc='+b.ncc.toFixed(2)));
for(let r=0;r<4&&track.bad.length;r++){
  const plan=pipeline.refillPlan(frames,track,8,r,600);
  if(!plan.length)break;
  console.log(' refill round',r,'intervals',plan.length,'framesToAdd',plan.reduce((s,p)=>s+p.n,0));
  const add=[];
  for(const it of plan)for(let i=0;i<it.n;i++){const t=it.t0+(i+0.5)*(it.t1-it.t0)/it.n;add.push({t,gray:frameAt(t)});}
  pipeline.prepFrames(add,AW,AH,band);frames=frames.concat(add).sort((a,b)=>a.t-b.t);
  track=core.trackOffsets(frames.map(f=>f.sig),band.h,{bins:8});
  console.log(' after round',r,'frames',frames.length,'bad',track.bad.length,track.bad.slice(0,4).map(b=>b.reason+'@'+b.i+' ncc='+b.ncc.toFixed(2)));
}
if(process.argv[2]==='raw'){
  const base=sample(8,0,7.6);
  pipeline.prepFrames(base,AW,AH,band);
  const tr=core.trackOffsets(base.map(f=>f.sig),band.h,{bins:8});
  console.log('base frames',base.length,'bad',tr.bad.length);
  for(let i=1;i<base.length;i++){
    const t=base[i].t;
    if(t<1.8||t>3.4)continue;
    console.log(' i',i,'t',t.toFixed(3),'estStep',Math.round(tr.step[i]),'trueStep',Math.round(posAt(t)-posAt(base[i-1].t)),'ncc',tr.ncc[i].toFixed(2),'agree',Math.round(tr.agree[i]));
  }
  process.exit(0);
}
const t0=posAt(frames[0].t);
console.log('bad pairs:', JSON.stringify(track.bad.map(b=>({i:b.i,t:+frames[b.i].t.toFixed(3),dt:+(frames[b.i].t-frames[b.i-1].t).toFixed(3),ncc:+b.ncc.toFixed(2),reason:b.reason,estStep:Math.round(track.step[b.i]),trueStep:Math.round(posAt(frames[b.i].t)-posAt(frames[b.i-1].t))}))));
const errAll=[];for(let i=0;i<frames.length;i++)errAll.push(Math.abs(track.pos[i]-(posAt(frames[i].t)-t0)));
console.log('frames with |err|>4:',errAll.filter(e=>e>4).length,'/',frames.length);
{ let firstBad=-1;
  for(let i=0;i<frames.length;i++){const truth=posAt(frames[i].t)-t0;if(Math.abs(track.pos[i]-truth)>4){firstBad=i;break;}}
  console.log('first divergent index',firstBad);
  for(let i=Math.max(0,firstBad-3);i<Math.min(frames.length,firstBad+6);i++){
    console.log(' i',i,'t',frames[i].t.toFixed(3),'dt',(frames[i].t-(i?frames[i-1].t:0)).toFixed(3),'est',Math.round(track.pos[i]),'truth',Math.round(posAt(frames[i].t)-t0),'step',Math.round(track.step[i]),'trueStep',i?Math.round(posAt(frames[i].t)-posAt(frames[i-1].t)):0,'ncc',(track.ncc[i]||0).toFixed(2));
  }
}
console.log('first 10 frames:');
for(let i=0;i<10;i++)console.log(' i',i,'t',frames[i].t.toFixed(3),'pos',Math.round(track.pos[i]),'truth',Math.round(posAt(frames[i].t)-t0),'ncc',(track.ncc[i]||0).toFixed(2),'step',Math.round(track.step[i]));
