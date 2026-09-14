package io.jirrafe.core.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `graph.html`: one self-contained page (no network access), a force layout of the class graph
 * coloured by community or layer, with search and a detail pane. Beyond [MAX_CLASSES] classes the
 * page shows the finest communities instead, so it stays usable on large projects.
 */
object Html {
    private const val MAX_CLASSES = 2500

    @Serializable
    private class VNode(val id: String, val name: String, val group: Int, val layer: String?, val size: Int, val info: String)

    @Serializable
    private class VEdge(val s: Int, val t: Int, val w: Double)

    @Serializable
    private class Data(val mode: String, val nodes: List<VNode>, val edges: List<VEdge>, val groups: List<String>)

    fun render(r: Knowledge.Result): String {
        val g = r.g
        val leaves = r.leafCommunities()
        val leafOf = IntArray(g.classes.size)
        leaves.forEachIndexed { li, c -> c.members.forEach { leafOf[it] = li } }
        val data = if (g.classes.size <= MAX_CLASSES) {
            val nodes = g.classes.mapIndexed { i, c ->
                VNode(
                    c.id, g.shortName(c.id), leafOf[i], r.layers[i], r.scores.inDegree[i],
                    listOfNotNull(c.module?.let { "module $it" }, c.artifact, "in-degree ${r.scores.inDegree[i]}", "betweenness ${"%.1f".format(r.scores.betweenness[i])}",
                        leaves.getOrNull(leafOf[i])?.let { "community ${it.id} ${it.label}" }).joinToString("\n"),
                )
            }
            val edges = g.deps.flatMapIndexed { i, m -> m.map { (j, w) -> VEdge(i, j, w) } }
            Data("classes", nodes, edges, leaves.map { it.label })
        } else {
            val agg = HashMap<Pair<Int, Int>, Double>()
            for (i in g.deps.indices) for ((j, w) in g.deps[i]) if (leafOf[i] != leafOf[j]) agg.merge(leafOf[i] to leafOf[j], w, Double::plus)
            val nodes = leaves.mapIndexed { li, c ->
                VNode(c.id, c.label, li, null, c.members.size, "${c.members.size} classes\n" + c.gods.joinToString("\n") { g.shortName(g.classes[it].id) })
            }
            Data("communities", nodes, agg.map { (k, w) -> VEdge(k.first, k.second, w) }, leaves.map { it.label })
        }
        val json = Json.encodeToString(Data.serializer(), data).replace("</", "<\\/")
        return PAGE.replace("/*DATA*/", json)
    }

    private val PAGE = """<!doctype html>
<meta charset="utf-8">
<title>jirrafe graph</title>
<style>
html,body{margin:0;height:100%;font:13px system-ui,sans-serif;background:#111;color:#ddd;overflow:hidden}
#bar{position:fixed;top:0;left:0;right:0;padding:8px;background:#1b1b1b;display:flex;gap:8px;align-items:center;z-index:2}
#bar input{flex:1;max-width:360px;padding:4px 8px;background:#222;color:#ddd;border:1px solid #444;border-radius:4px}
#bar select{background:#222;color:#ddd;border:1px solid #444;border-radius:4px;padding:4px}
#info{position:fixed;right:0;top:40px;bottom:0;width:320px;overflow:auto;padding:12px;background:#1b1b1b;white-space:pre-wrap;word-break:break-all;display:none}
canvas{display:block}
</style>
<div id="bar"><b>jirrafe</b><input id="q" placeholder="filter (class, package, community)"><select id="color"><option value="group">colour by community</option><option value="layer">colour by layer</option></select><span id="count"></span></div>
<canvas id="c"></canvas>
<div id="info"></div>
<script>
const data=/*DATA*/;
const N=data.nodes,E=data.edges;
const canvas=document.getElementById('c'),ctx=canvas.getContext('2d');
const LAYERS={controller:'#e6194b',service:'#3cb44b',repository:'#4363d8',client:'#f58231',config:'#911eb4',model:'#42d4f4',util:'#f032e6'};
let W,H,tx=0,ty=0,scale=1,drag=null,hover=-1,selected=-1,filter='',colorBy='group';
function resize(){W=canvas.width=innerWidth;H=canvas.height=innerHeight-40;canvas.style.marginTop='40px'}
addEventListener('resize',resize);resize();
document.getElementById('count').textContent=N.length+' '+data.mode+', '+E.length+' edges';
const groups=data.groups.length;
N.forEach((n,i)=>{const a=(n.group/Math.max(groups,1))*Math.PI*2,r=Math.min(W,H)*0.35*Math.sqrt(0.2+0.8*Math.random());
 n.x=W/2+Math.cos(a)*r+(Math.random()-.5)*40;n.y=H/2+Math.sin(a)*r+(Math.random()-.5)*40;n.vx=0;n.vy=0;n.r=3+Math.sqrt(n.size)*1.5;n.deg=0});
E.forEach(e=>{N[e.s].deg++;N[e.t].deg++});
function color(n){if(colorBy==='layer')return n.layer?LAYERS[n.layer]:'#666';return 'hsl('+((n.group*137.508)%360)+',65%,55%)'}
function matches(n){if(!filter)return true;const f=filter.toLowerCase();return n.id.toLowerCase().includes(f)||n.name.toLowerCase().includes(f)||(data.groups[n.group]||'').toLowerCase().includes(f)}
let alpha=1;
function step(){
 if(alpha<0.005)return;
 const k=Math.sqrt(W*H/Math.max(N.length,1));
 for(let i=0;i<N.length;i++){const a=N[i];a.fx=0;a.fy=0;
  for(let j=i+1;j<N.length;j++){const b=N[j];let dx=a.x-b.x,dy=a.y-b.y,d2=dx*dx+dy*dy+0.01;if(d2>k*k*25)continue;const f=k*k/d2*(a.group===b.group?1:1.6);dx*=f;dy*=f;a.fx+=dx;a.fy+=dy;b.fx-=dx;b.fy-=dy}}
 E.forEach(e=>{const a=N[e.s],b=N[e.t];const dx=b.x-a.x,dy=b.y-a.y,d=Math.sqrt(dx*dx+dy*dy)+0.01;const f=d/k*(0.3+Math.min(e.w,5)*0.1);a.fx+=dx/d*f*d*0.05;a.fy+=dy/d*f*d*0.05;b.fx-=dx/d*f*d*0.05;b.fy-=dy/d*f*d*0.05});
 N.forEach(n=>{if(n===drag)return;n.fx+=(W/2-n.x)*0.01;n.fy+=(H/2-n.y)*0.01;n.vx=(n.vx+n.fx*0.02)*0.6;n.vy=(n.vy+n.fy*0.02)*0.6;const v=Math.hypot(n.vx,n.vy),m=k*0.5*alpha;if(v>m){n.vx*=m/v;n.vy*=m/v}n.x+=n.vx;n.y+=n.vy});
 alpha*=0.985;
}
function draw(){
 ctx.setTransform(1,0,0,1,0,0);ctx.fillStyle='#111';ctx.fillRect(0,0,W,H);ctx.setTransform(scale,0,0,scale,tx,ty);
 ctx.lineWidth=0.6/scale;
 E.forEach(e=>{const a=N[e.s],b=N[e.t];const on=selected<0?(matches(a)&&matches(b)):(e.s===selected||e.t===selected);ctx.strokeStyle=on?'rgba(200,200,200,0.35)':'rgba(120,120,120,0.06)';ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke()});
 N.forEach((n,i)=>{const on=matches(n);ctx.globalAlpha=on?1:0.12;ctx.fillStyle=color(n);ctx.beginPath();ctx.arc(n.x,n.y,n.r,0,Math.PI*2);ctx.fill();
  if(i===selected||i===hover){ctx.strokeStyle='#fff';ctx.lineWidth=2/scale;ctx.stroke()}
  if(on&&(scale*n.r>6||i===hover||i===selected||n.deg>8)){ctx.fillStyle='#eee';ctx.font=(11/scale)+'px system-ui';ctx.fillText(n.name,n.x+n.r+2/scale,n.y+4/scale)}});
 ctx.globalAlpha=1;
}
function loop(){step();draw();requestAnimationFrame(loop)}loop();
function at(x,y){const px=(x-tx)/scale,py=(y-ty)/scale;let best=-1,bd=1e9;N.forEach((n,i)=>{const d=Math.hypot(n.x-px,n.y-py);if(d<n.r+4/scale&&d<bd){bd=d;best=i}});return best}
let pan=null;
canvas.onmousedown=e=>{const i=at(e.offsetX,e.offsetY);if(i>=0){drag=N[i];alpha=Math.max(alpha,0.3)}else pan={x:e.offsetX-tx,y:e.offsetY-ty}};
canvas.onmousemove=e=>{if(drag){drag.x=(e.offsetX-tx)/scale;drag.y=(e.offsetY-ty)/scale;alpha=Math.max(alpha,0.1)}else if(pan){tx=e.offsetX-pan.x;ty=e.offsetY-pan.y}else hover=at(e.offsetX,e.offsetY)};
canvas.onmouseup=e=>{if(drag&&!pan){const i=at(e.offsetX,e.offsetY);select(i)}drag=null;pan=null};
canvas.onwheel=e=>{e.preventDefault();const f=e.deltaY<0?1.15:1/1.15;tx=e.offsetX-(e.offsetX-tx)*f;ty=e.offsetY-(e.offsetY-ty)*f;scale*=f};
function select(i){selected=i;const box=document.getElementById('info');if(i<0){box.style.display='none';return}
 const n=N[i];const outs=E.filter(e=>e.s===i).map(e=>N[e.t].name),ins=E.filter(e=>e.t===i).map(e=>N[e.s].name);
 box.style.display='block';box.textContent=n.id+'\n'+(n.layer?'layer '+n.layer+'\n':'')+n.info+'\n\ndepends on ('+outs.length+'):\n  '+outs.slice(0,40).join('\n  ')+'\n\nused by ('+ins.length+'):\n  '+ins.slice(0,40).join('\n  ')}
document.getElementById('q').oninput=e=>{filter=e.target.value};
document.getElementById('color').onchange=e=>{colorBy=e.target.value};
</script>
"""
}
