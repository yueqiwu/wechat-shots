import * as core from '../app/js/core.js';
function mulberry32(a){return function(){a|=0;a=(a+0x6D2B79F5)|0;let t=Math.imul(a^(a>>>15),1|a);t=(t+Math.imul(t^(t>>>7),61|t))^t;return((t^(t>>>14))>>>0)/4294967296;};}
const AW=120,AH=300,HEAD=26,FOOT=34,DOC_H=9000;
const rnd=mulberry32(777);const doc=new Uint8Array(AW*DOC_H);
for(let y=0;y<DOC_H;y++){const texty=rnd()<0.55;const shade=rnd()*18;for(let x=0;x<AW;x++)
  doc[y*AW+x]=Math.max(0,Math.min(255,Math.round((texty?(rnd()>0.5?28:238):212)+shade-9)));}
const header=new Uint8Array(AW*HEAD);for(let y=0;y<HEAD;y++)for(let x=0;x<AW;x++)header[y*AW+x]=40+((x*7+y*3)%60);
function speedAt(t){if(t<0.5)return 0;if(t<2)return 400*(t-0.5)/1.5;if(t<3.2)return 2600;if(t<3.9)return 0;if(t<5.6)return 900;if(t<6.6)return -700;return 0;}
const POS=new Float64Array(7602);{let p=0;for(let i=0;i<=7600;i++){POS[i]=p;p+=speedAt((i+0.5)/1000)/1000;}}
function posAt(t){const x=Math.max(0,Math.min(7600,t*1000));const i=Math.floor(x);const f=x-i;return POS[i]*(1-f)+POS[Math.min(7601,i+1)]*f;}
function frameAt(t){const pos=posAt(t);const f=new Uint8Array(AW*AH);
for(let y=0;y<AH;y++){if(y<HEAD){f.set(header.subarray(y*AW,(y+1)*AW),y*AW);continue;}
const d0=Math.round(y-HEAD+pos);for(let x=0;x<AW;x++)f[y*AW+x]=(d0>=0&&d0<DOC_H)?doc[d0*AW+x]:250;}
return f;}
const band={top:26,bot:266,h:240};
const a=frameAt(0.561),b=frameAt(0.5765);
let maxd=0;for(let i=0;i<a.length;i++)maxd=Math.max(maxd,Math.abs(a[i]-b[i]));
console.log('pixel maxdiff',maxd);
const sa=core.rowSig(core.cropGray(a,AW,AH,band.top,band.bot),AW,band.h,8);
const sb=core.rowSig(core.cropGray(b,AW,AH,band.top,band.bot),AW,band.h,8);
console.log('sig len',sa.length,'constructor',sa.constructor.name,'range',Math.min(...sa.slice(0,50)),Math.max(...sa.slice(0,50)));
const self=core.alignAt(sa,sa,band.h,8,0,24,1);
const pair=core.alignAt(sa,sb,band.h,8,0,24,1);
console.log('ncc self',self.ncc.toFixed(4),'raw',self.cost.toFixed(2));
console.log('ncc pair',pair.ncc.toFixed(4),'raw',pair.cost.toFixed(2));
console.log('sig first 16 a',Array.from(sa.slice(0,16)).join(','));
console.log('sig first 16 b',Array.from(sb.slice(0,16)).join(','));
