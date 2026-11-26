import * as core from '../app/js/core.js';
import * as pipeline from '../app/js/pipeline.js';
function mulberry32(a){return function(){a|=0;a=(a+0x6D2B79F5)|0;let t=Math.imul(a^(a>>>15),1|a);t=(t+Math.imul(t^(t>>>7),61|t))^t;return((t^(t>>>14))>>>0)/4294967296;};}
const AW=120,AH=300,HEAD=26,BAND_BOT=266,DOC_H=9000;
const rnd=mulberry32(777);const doc=new Uint8Array(AW*DOC_H);
for(let y=0;y<DOC_H;y++){const texty=rnd()<0.55;const shade=rnd()*18;for(let x=0;x<AW;x++)
  doc[y*AW+x]=Math.max(0,Math.min(255,Math.round((texty?(rnd()>0.5?28:238):212)+shade-9)));}
const header=new Uint8Array(AW*HEAD);for(let y=0;y<HEAD;y++)for(let x=0;x<AW;x++)header[y*AW+x]=40+((x*7+y*3)%60);
const footer=new Uint8Array(AW*6);for(let y=0;y<6;y++)for(let x=0;x<AW;x++)footer[y*AW+x]=225;
function speedAt(t){if(t<0.5)return 0;if(t<2)return 400*(t-0.5)/1.5;if(t<3.2)return 2600;if(t<3.9)return 0;if(t<5.6)return 900;if(t<6.6)return -700;return 0;}
const POS=new Float64Array(7602);{let p=0;for(let i=0;i<=7600;i++){POS[i]=p;p+=speedAt((i+0.5)/1000)/1000;}}
function posAt(t){const x=Math.max(0,Math.min(7600,t*1000));const i=Math.floor(x);const f=x-i;return POS[i]*(1-f)+POS[Math.min(7601,i+1)]*f;}
function frameAt(t){const pos=posAt(t);const f=new Uint8Array(AW*AH);
for(let y=0;y<AH;y++){if(y<HEAD){f.set(header.subarray(y*AW,(y+1)*AW),y*AW);continue;}
const d0=Math.round(y-HEAD+pos);for(let x=0;x<AW;x++)f[y*AW+x]=(d0>=0&&d0<DOC_H)?doc[d0*AW+x]:250;}
return f;}
const band={top:26,bot:266,h:240};
const tA=2.056,tB=2.18;
const A=frameAt(tA),B=frameAt(tB);
console.log('true pos',posAt(tA).toFixed(1),posAt(tB).toFixed(1),'delta',(posAt(tB)-posAt(tA)).toFixed(1));
const sa=core.rowSig(core.cropGray(A,AW,AH,band.top,band.bot),AW,band.h,8);
const sb=core.rowSig(core.cropGray(B,AW,AH,band.top,band.bot),AW,band.h,8);
const all=[];
for(let s=-216;s<=216;s++){const r=core.alignAt(sa,sb,240,8,s,60,1);if(r)all.push({s,ncc:+r.ncc.toFixed(3),rows:r.rows,raw:+r.cost.toFixed(1)});}
all.sort((x,y)=>y.ncc-x.ncc);
console.log('full-row top5',JSON.stringify(all.slice(0,5)));
const c5=[];
for(let s=-216;s<=216;s++){const r=core.alignAt(sa,sb,240,8,s,60,5);if(r)c5.push({s,ncc:+r.ncc.toFixed(3),rows:r.rows});}
c5.sort((x,y)=>y.ncc-x.ncc);
console.log('rowstep5 top5',JSON.stringify(c5.slice(0,5)));
// 真实重叠区检查：s = -(324) 超出范围；看看 s=-216 时逐像素差异
const mp=core.matchPair(sa,sb,240,8,216,60,-172,72);
console.log('matchPair center=-172 ->',JSON.stringify({shift:mp.shift,ncc:+mp.ncc.toFixed(3),sat:mp.sat}));
