import * as core from '../app/js/core.js';
function mulberry32(a){return function(){a|=0;a=(a+0x6D2B79F5)|0;let t=Math.imul(a^(a>>>15),1|a);t=(t+Math.imul(t^(t>>>7),61|t))^t;return((t^(t>>>14))>>>0)/4294967296;};}
const AW=120,AH=300,HEAD=26,BAND_BOT=266,DOC_H=9000;
const rnd=mulberry32(777);const doc=new Uint8Array(AW*DOC_H);
for(let y=0;y<DOC_H;y++){const texty=rnd()<0.55;const shade=rnd()*18;for(let x=0;x<AW;x++)
  doc[y*AW+x]=Math.max(0,Math.min(255,Math.round((texty?(rnd()>0.5?28:238):212)+shade-9)));}
function frameAt(pos){const f=new Uint8Array(AW*AH);
for(let y=0;y<AH;y++){const d0=Math.round(y-HEAD+pos);for(let x=0;x<AW;x++)f[y*AW+x]=(d0>=0&&d0<DOC_H)?doc[d0*AW+x]:250;}
return f;}
const band={top:26,bot:266,h:240};
function sig(pos){return core.rowSig(core.cropGray(frameAt(pos),AW,AH,band.top,band.bot),AW,band.h,8);}
const A=sig(0);
console.log('sig stats: min',Math.min(...A),'max',Math.max(...A),'mean',(Array.from(A).reduce((s,v)=>s+v,0)/A.length).toFixed(1));
for(const d of [324]){
  const B=sig(d);
  const all=[];
  for(let s=-216;s<=216;s++){const r=core.alignAt(A,B,240,8,s,24,1);if(r)all.push({s,ncc:r.ncc,rows:r.rows});}
  all.sort((x,y)=>y.ncc-x.ncc);
  console.log('true shift -'+d,'top5',JSON.stringify(all.slice(0,5).map(v=>({s:v.s,ncc:+v.ncc.toFixed(3),rows:v.rows}))));
  const mp=core.matchPair(A,B,240,8,216,24,0,72);
  console.log(' matchPair ->',JSON.stringify({shift:mp.shift,ncc:+mp.ncc.toFixed(3),sat:mp.sat}));
}
