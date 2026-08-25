/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace.stack;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 生成无需网络资源的堆栈 HTML，默认展示按估算耗时聚合的火焰图。 */
public final class StackHtmlRenderer {

    public void write(JSONObject report, File output) throws IOException {
        if (output.getParentFile() != null && !output.getParentFile().exists()
                && !output.getParentFile().mkdirs()) {
            throw new IOException("无法创建 HTML 输出目录: " + output.getParent());
        }
        String json = report.toString()
                .replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e");
        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>RheaTrace 堆栈报告</title><style>");
        appendCss(html);
        html.append("</style></head><body>");
        appendBody(html);
        html.append("<script id=\"report-data\" type=\"application/json\">")
                .append(json).append("</script><script>");
        appendScript(html);
        html.append("</script></body></html>");
        Files.write(output.toPath(), html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendCss(StringBuilder html) {
        html.append("*{box-sizing:border-box}body{margin:0;background:#f6f7f9;color:#172033;")
                .append("font:14px/1.55 -apple-system,BlinkMacSystemFont,Segoe UI,Microsoft YaHei,sans-serif}")
                .append(".top{position:sticky;top:0;z-index:5;background:#fff;border-bottom:1px solid #e4e7ed;padding:14px 20px}")
                .append("h1{font-size:18px;margin:0 0 10px}.controls{display:flex;gap:10px;flex-wrap:wrap;align-items:center}")
                .append("select,input,button{border:1px solid #cfd5df;border-radius:6px;background:#fff;padding:7px 9px}")
                .append("button{cursor:pointer}button:disabled,select:disabled{opacity:.45;cursor:not-allowed}")
                .append("input{min-width:280px}.viewport-controls{display:none;gap:5px;align-items:center}")
                .append(".viewport-controls button{min-width:34px}.zoom-label{min-width:42px;text-align:center;color:#566176}")
                .append(".meta{color:#697386;margin-top:8px}.report{margin:16px;background:#fff;border:1px solid #e4e7ed;")
                .append("border-radius:8px;overflow:hidden}.header,.row{display:grid;grid-template-columns:minmax(0,1fr) 150px}")
                .append(".report.tree-view .header,.report.tree-view .row{grid-template-columns:minmax(0,1fr) 145px 145px 145px}")
                .append(".header{font-weight:600;background:#fafafa;border-bottom:1px solid #e4e7ed}.cell{padding:10px 14px}")
                .append(".time{border-left:1px solid #e4e7ed;text-align:right;font-variant-numeric:tabular-nums}")
                .append("details{border-bottom:1px solid #edf0f4}details:last-child{border-bottom:0}")
                .append("summary{list-style:none;cursor:pointer}summary::-webkit-details-marker{display:none}")
                .append(".method{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.frames{background:#fbfcfe}")
                .append(".frame{display:grid;grid-template-columns:minmax(0,1fr) 150px;min-height:32px}")
                .append(".frame:hover,.row:hover{background:#eef4ff}.app{color:#155eef;font-weight:500}")
                .append(".badge{font-size:12px;color:#697386;margin-left:8px}.estimated-text{color:#a15c00;font-weight:600}")
                .append(".empty{padding:32px;text-align:center;color:#697386}.tree-node{border-bottom:0}.hidden{display:none}")
                .append(".visual-shell{overflow:auto;background:#17191d;color:#fff;padding:12px 14px 18px;cursor:grab;user-select:none}")
                .append(".visual-shell.dragging{cursor:grabbing}.visual-legend{color:#b8c0cc;font-size:12px;padding:0 0 10px;white-space:nowrap}")
                .append(".visual-canvas{position:relative;background:#202329;border-top:1px solid #3a4049}")
                .append(".flame-row{position:relative;height:34px}.visual-cell{position:absolute;padding:4px 7px;")
                .append("border:1px solid rgba(0,0,0,.35);border-radius:2px;overflow:hidden;white-space:nowrap;")
                .append("text-overflow:ellipsis;cursor:pointer;color:#fff;font-size:12px;line-height:20px;")
                .append("text-shadow:0 1px 1px rgba(0,0,0,.55);transition:filter .1s,opacity .1s}")
                .append(".visual-cell:hover,.visual-cell.selected{filter:brightness(1.24);outline:2px solid #fff;z-index:4}")
                .append(".visual-cell.dim{opacity:.13}.visual-cell.app{box-shadow:inset 0 -3px 0 #75a7ff}")
                .append(".flame-cell{top:2px;height:30px}.flame-label{overflow:hidden;text-overflow:ellipsis;display:block}")
                .append(".flame-metric{opacity:.8;font-size:11px;margin-left:7px}.flame-empty{padding:70px;text-align:center;color:#aeb7c4}")
                .append(".timeline-axis{position:absolute;left:0;top:0;height:30px;border-bottom:1px solid #4a5059;background:#1b1e23}")
                .append(".timeline-tick{position:absolute;top:0;height:100%;border-left:1px solid #555c66;color:#b8c0cc;font-size:10px;padding-left:4px}")
                .append(".timeline-sample-tick{position:absolute;top:30px;width:1px;height:6px;background:rgba(255,255,255,.5);pointer-events:none}")
                .append(".timeline-cell{height:24px;line-height:14px;padding:4px 5px}.timeline-cell.estimated{border-style:dashed;opacity:.76}")
                .append(".timeline-cell.exact{border-style:solid;z-index:2}.timeline-cell.estimated:hover,.timeline-cell.estimated.selected{opacity:1}")
                .append(".timeline-label{display:block;overflow:hidden;text-overflow:ellipsis}.visual-note{color:#ffcf70;font-weight:600}")
                .append("@media(max-width:700px){.header,.row,.frame{grid-template-columns:minmax(0,1fr) 90px}")
                .append(".report.tree-view .header,.report.tree-view .row{grid-template-columns:minmax(0,1fr) 82px 82px 82px}")
                .append(".report{margin:8px}input{min-width:180px}.visual-shell{padding:8px}}");
    }

    private static void appendBody(StringBuilder html) {
        html.append("<div class=\"top\"><h1>RheaTrace 线上堆栈报告</h1><div class=\"controls\">")
                .append("<select id=\"thread\"></select><select id=\"view\">")
                .append("<option value=\"flame\" selected>聚合火焰图</option>")
                .append("<option value=\"timeline\">采样时间轴</option>")
                .append("<option value=\"tree\">聚合调用树</option>")
                .append("<option value=\"segments\">时间堆栈明细</option></select>")
                .append("<select id=\"metric\"><option value=\"estimated\" selected>宽度：估算耗时</option>")
                .append("<option value=\"samples\">宽度：样本数</option>")
                .append("<option value=\"exact\">宽度：精确区间</option></select>")
                .append("<select id=\"sort\"><option value=\"chronological\">按时间</option>")
                .append("<option value=\"duration\">按耗时/样本</option></select>")
                .append("<input id=\"search\" placeholder=\"搜索类名或方法名\">")
                .append("<span class=\"viewport-controls\" id=\"viewport-controls\">")
                .append("<button id=\"zoom-out\" title=\"缩小\">−</button>")
                .append("<button id=\"zoom-reset\" title=\"重置缩放\">重置</button>")
                .append("<button id=\"zoom-in\" title=\"放大\">＋</button>")
                .append("<span class=\"zoom-label\" id=\"zoom-label\">1×</span></span></div>")
                .append("<div class=\"meta\" id=\"meta\"></div></div>")
                .append("<main class=\"report\" id=\"report\"><div id=\"columns\" class=\"header\">")
                .append("<div class=\"cell\">method</div><div class=\"cell time\">耗时 (ms)</div></div>")
                .append("<div id=\"content\"></div></main>");
    }

    private static void appendScript(StringBuilder html) {
        html.append("const R=JSON.parse(document.getElementById('report-data').textContent);")
                .append("const T=document.getElementById('thread'),V=document.getElementById('view'),M=document.getElementById('metric'),")
                .append("S=document.getElementById('sort'),Q=document.getElementById('search'),C=document.getElementById('content'),")
                .append("H=document.getElementById('columns'),P=document.getElementById('report'),Z=document.getElementById('viewport-controls'),ZL=document.getElementById('zoom-label');")
                .append("const MIN_CELL_PX=2,LABEL_PX=40,MIN_ZOOM=1,MAX_ZOOM=64,BASE_WIDTH=1100;let zoom=1;")
                .append("const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));")
                .append("const ms=n=>n==null?'--':(n/1e6>=100?(n/1e6).toFixed(0):(n/1e6).toFixed(2).replace(/\\.?0+$/,''));")
                .append("const app=n=>R.appName&&String(n).includes(R.appName)?' app':'';")
                .append("const textOf=n=>String(n?.method??n?.displayName??'');const canvasWidth=()=>Math.max(BASE_WIDTH,C.clientWidth-28)*zoom;")
                .append("for(const t of R.threads){const o=document.createElement('option');o.value=t.tid;o.textContent=t.threadName+' ('+t.tid+')';T.appendChild(o)}")
                .append("const d=R.renderDefaults||{};let selected=R.threads.find(t=>String(t.tid)===String(d.thread)||t.threadName===d.thread);")
                .append("if(!selected)selected=R.threads.find(t=>t.tid===R.processId)||R.threads[0];if(selected)T.value=selected.tid;")
                .append("S.value=d.sort||'chronological';V.value=d.view||'flame';M.value=d.flameMetric||'estimated';")
                .append("function frame(f,i,dur,type,count){return '<div class=\"frame\" title=\"'+esc(type||'')+'\"><div class=\"cell method'+app(f.method)+'\" style=\"padding-left:'+(24+i*20)+'px\">'+esc(f.displayName)+'<span class=\"badge\">样本 '+count+'</span></div><div class=\"cell time\" title=\"'+(dur==null?'点采样，无精确耗时':dur+' ns')+'\">'+ms(dur)+'</div></div>'}")
                .append("function segments(t){let a=[...t.segments];if(S.value==='duration')a.sort((x,y)=>(y.exactDurationNs??-1)-(x.exactDurationNs??-1));")
                .append("const q=Q.value.toLowerCase();a=a.filter(x=>!q||x.stack.some(f=>textOf(f).toLowerCase().includes(q)));")
                .append("if(!a.length)return '<div class=\"empty\">没有匹配的堆栈</div>';return a.map((x,k)=>{const f=x.stack[0]||{displayName:'<empty>',method:''};")
                .append("return '<details '+(k===0?'open':'')+'><summary class=\"row\"><div class=\"cell method'+app(f.method)+'\">'+esc(f.displayName)+'<span class=\"badge\">'+esc(x.eventType)+' · '+x.sampleCount+' 个样本</span></div><div class=\"cell time\" title=\"'+(x.exactDurationNs==null?'点采样，无精确耗时':x.exactDurationNs+' ns')+'\">'+ms(x.exactDurationNs)+'</div></summary><div class=\"frames\">'+x.stack.map((v,i)=>frame(v,i,i===0?x.exactDurationNs:null,x.eventType,x.sampleCount)).join('')+'</div></details>'}).join('')}")
                .append("function eventText(n){const e=n.eventTypes;return Array.isArray(e)?e.join(', '):(e||'--')}")
                .append("function node(n,depth){const q=Q.value.toLowerCase(),own=!q||textOf(n).toLowerCase().includes(q),kids=(n.children||[]).map(c=>node(c,depth+1)).join('');")
                .append("if(!own&&!kids)return '';const events=eventText(n),totalTitle='估算总耗时 '+n.estimatedDurationNs+' ns · 样本 '+n.sampleCount+' · '+events,selfTitle='未归属估算耗时 '+n.estimatedSelfDurationNs+' ns，不代表 CPU 自耗时',exactTitle=n.exactDurationNs==null?'没有真实 duration Hook 区间':'精确区间 '+n.exactDurationNs+' ns · '+events;return '<details class=\"tree-node\" open><summary class=\"row\"><div class=\"cell method'+app(n.method)+'\" style=\"padding-left:'+(14+depth*20)+'px\" title=\"'+esc(textOf(n)+' · 样本 '+n.sampleCount+' · '+events)+'\">'+esc(n.displayName)+'<span class=\"badge\">样本 '+n.sampleCount+'</span></div><div class=\"cell time estimated-text\" title=\"'+esc(totalTitle)+'\">'+ms(n.estimatedDurationNs)+'</div><div class=\"cell time estimated-text\" title=\"'+esc(selfTitle)+'\">'+ms(n.estimatedSelfDurationNs)+'</div><div class=\"cell time\" title=\"'+esc(exactTitle)+'\">'+ms(n.exactDurationNs)+'</div></summary>'+kids+'</details>'}")
                .append("function flameWeight(n){if(M.value==='exact'){const v=n.exactDurationNs;return v==null?0:Math.max(0,Number(v))}if(M.value==='estimated')return Math.max(0,Number(n.estimatedDurationNs||0));return Math.max(0,Number(n.sampleCount||0))}")
                .append("function flameMatch(n,q){return !q||textOf(n).toLowerCase().includes(q)||String(n.displayName||'').toLowerCase().includes(q)||(n.children||[]).some(c=>flameMatch(c,q))}")
                .append("function prepare(n){const kids=(n.children||[]).map(prepare).filter(x=>x.visual>0);return{n,kids,visual:flameWeight(n)}}")
                .append("function sorted(a){return[...a].sort((x,y)=>S.value==='duration'?y.visual-x.visual:0)}")
                .append("function color(s){let h=0;for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))|0;return'hsl('+(Math.abs(h)%360)+',55%,45%)'}")
                .append("function metricText(n){if(M.value==='exact')return'精确 '+ms(n.exactDurationNs)+' ms';if(M.value==='estimated')return'估算 '+ms(n.estimatedDurationNs)+' ms';return'样本 '+n.sampleCount}")
                .append("function flame(t){const roots=(t.callTree||[]).map(prepare).filter(x=>x.visual>0);if(!roots.length)return'<div class=\"visual-shell\"><div class=\"flame-empty\">当前指标没有可聚合的数据</div></div>';")
                .append("const total=roots.reduce((s,n)=>s+n.visual,0),rows=[],q=Q.value.toLowerCase(),width=canvasWidth();")
                .append("function put(p,x,w,depth){(rows[depth]||(rows[depth]=[])).push({p,x,w});const kids=p.kids,childTotal=kids.reduce((s,n)=>s+n.visual,0);if(!childTotal)return;let cx=x,denominator=Math.max(childTotal,p.visual);for(const child of sorted(kids)){const childWidth=w*(child.visual/denominator);put(child,cx,childWidth,depth+1);cx+=childWidth}}")
                .append("let rootX=0;for(const root of sorted(roots)){const rootWidth=root.visual/total;put(root,rootX,rootWidth,0);rootX+=rootWidth}")
                .append("const content=rows.map(row=>'<div class=\"flame-row\">'+row.map(({p,x,w})=>{const n=p.n,px=Math.max(MIN_CELL_PX,w*width),dim=q&&!flameMatch(n,q)?' dim':'',metric=metricText(n),label=px>=LABEL_PX?'<span class=\"flame-label\">'+esc(n.displayName||n.method)+'<span class=\"flame-metric\">'+esc(metric)+'</span></span>':'';")
                .append("const title=textOf(n)+' · '+metric+(M.value==='estimated'?' · 估算值，不是精确耗时 · 未归属估算 '+ms(n.estimatedSelfDurationNs)+' ms':'');return'<div class=\"visual-cell flame-cell'+app(n.method)+dim+'\" style=\"left:'+(x*width)+'px;width:'+px+'px;background:'+color(textOf(n))+'\" title=\"'+esc(title)+'\">'+label+'</div>'}).join('')+'</div>').join('');")
                .append("const legend=M.value==='exact'?'横向宽度按精确区间并集计算；只有点样本的节点不显示。':M.value==='estimated'?'<span class=\"visual-note\">横向宽度为估算耗时</span>：点样本延伸到下一条同线程记录，最多 2×采样间隔；不是精确方法耗时。':'横向宽度按聚合样本数计算；点样本不虚构精确耗时。';")
                .append("return'<div class=\"visual-shell\"><div class=\"visual-legend\">'+legend+' 纵向为调用深度；Ctrl+滚轮缩放，拖动平移。</div><div class=\"visual-canvas flame-canvas\" style=\"width:'+width+'px;height:'+(rows.length*34)+'px\">'+content+'</div></div>'}")
                .append("function timelineAxis(width,duration){const ticks=Math.max(2,Math.min(200,Math.floor(width/120))),parts=[];for(let i=0;i<=ticks;i++){const ratio=i/ticks;parts.push('<span class=\"timeline-tick\" style=\"left:'+(ratio*width)+'px\">'+ms(duration*ratio)+' ms</span>')}return'<div class=\"timeline-axis\" style=\"width:'+width+'px\">'+parts.join('')+'</div>'}")
                .append("function buildTimelineSlices(segments){const ordered=segments.map((s,index)=>({s,index})).sort((a,b)=>Number(a.s.startOffsetNs)-Number(b.s.startOffsetNs)||a.index-b.index),active=new Map(),out=[],sampleTimes=new Set();let maxDepth=1;")
                .append("for(let order=0;order<ordered.length;order++){const item=ordered[order],s=item.s,start=Math.max(0,Number(s.startOffsetNs)||0),rawEnd=Number(s.durationKind==='EXACT'&&s.endOffsetNs!=null?s.endOffsetNs:s.estimatedEndOffsetNs),end=Math.max(start,Number.isFinite(rawEnd)?rawEnd:start),kind=s.durationKind==='EXACT'?'exact':'estimated',path=[],stack=s.stack||[],stackSearch=stack.map(textOf).join(' ').toLowerCase();sampleTimes.add(start);maxDepth=Math.max(maxDepth,stack.length);")
                .append("for(let depth=0;depth<stack.length;depth++){const frame=stack[depth],identity=textOf(frame),key=depth+'\\u0001'+path.concat(identity).join('\\u0002')+'\\u0001'+kind,previous=active.get(key),count=Math.max(1,Number(s.sampleCount)||1);path.push(identity);if(previous&&previous.lastOrder===order-1&&start<=previous.end){previous.end=Math.max(previous.end,end);previous.lastOrder=order;previous.sampleCount+=count;previous.eventTypes.add(String(s.eventType||'--'));previous.sampleTimes.push(start);previous.searchText+=' '+stackSearch}else{const slice={frame,depth,start,end,kind,lastOrder:order,sampleCount:count,eventTypes:new Set([String(s.eventType||'--')]),sampleTimes:[start],searchText:stackSearch};active.set(key,slice);out.push(slice)}}}")
                .append("return{slices:out,sampleTimes:[...sampleTimes].sort((a,b)=>a-b),maxDepth}}")
                .append("function sampleTimesText(a){const shown=a.slice(0,8).map(ms).join(', ');return shown+(a.length>8?' ... 共 '+a.length+' 个':'')+' ms'}")
                .append("function timeline(t){const duration=Math.max(1,Number(R.durationNs||0)),width=canvasWidth(),q=Q.value.toLowerCase(),model=buildTimelineSlices(t.segments),cells=[],ticks=[];")
                .append("for(const time of model.sampleTimes){const x=Math.max(0,Math.min(duration,time))/duration*width;ticks.push('<span class=\"timeline-sample-tick\" style=\"left:'+x+'px\" title=\"真实采样点 '+ms(time)+' ms\"></span>')}")
                .append("for(const slice of model.slices){const x=Math.max(0,Math.min(duration,slice.start))/duration*width,end=Math.max(slice.start,Math.min(duration,slice.end))/duration*width,w=Math.max(MIN_CELL_PX,end-x),match=!q||slice.searchText.includes(q),dim=match?'':' dim',kindText=slice.kind==='exact'?'精确':'估算',span=Math.max(0,slice.end-slice.start),label=w>=LABEL_PX?'<span class=\"timeline-label\">'+esc(slice.frame.displayName||slice.frame.method)+'</span>':'',events=[...slice.eventTypes].join(', '),title=textOf(slice.frame)+' · '+kindText+'区间 ['+ms(slice.start)+', '+ms(slice.end)+') ms · '+span+' ns · 样本 '+slice.sampleCount+' · 真实采样时刻 '+sampleTimesText(slice.sampleTimes)+' · '+events+(slice.kind==='estimated'?' · 非精确耗时':'');cells.push('<div class=\"visual-cell timeline-cell '+slice.kind+app(slice.frame.method)+dim+'\" style=\"left:'+x+'px;top:'+(38+slice.depth*26)+'px;width:'+w+'px;background:'+color(textOf(slice.frame))+'\" title=\"'+esc(title)+'\">'+label+'</div>')}")
                .append("const height=40+model.maxDepth*26,policy=R.estimationPolicy||{};return'<div class=\"visual-shell\"><div class=\"visual-legend\">公共调用前缀按真实时间连续合并；<span class=\"visual-note\">虚线为估算区间</span>，实线为真实 duration，尺下短刻度为真实采样时刻。估算上限 '+ms(policy.maxPointDurationNs)+' ms，不代表精确执行时间。Ctrl+滚轮缩放，拖动平移。</div><div class=\"visual-canvas timeline-canvas\" style=\"width:'+width+'px;height:'+height+'px\">'+timelineAxis(width,duration)+ticks.join('')+cells.join('')+'</div></div>'}")
                .append("function selectedThread(){return R.threads.find(x=>String(x.tid)===T.value)}")
                .append("function render(){const t=selectedThread();if(!t){C.innerHTML='<div class=\"empty\">没有可解析线程</div>';return}const visual=V.value==='flame'||V.value==='timeline',tree=V.value==='tree';P.classList.toggle('tree-view',tree);H.innerHTML=tree?'<div class=\"cell\">method</div><div class=\"cell time estimated-text\">估算总耗时 (ms)</div><div class=\"cell time estimated-text\">估算自耗时 (ms)</div><div class=\"cell time\">精确区间 (ms)</div>':'<div class=\"cell\">method</div><div class=\"cell time\">耗时 (ms)</div>';H.style.display=visual?'none':'grid';Z.style.display=visual?'inline-flex':'none';M.disabled=V.value!=='flame';S.disabled=V.value==='timeline';")
                .append("C.innerHTML=V.value==='flame'?flame(t):V.value==='timeline'?timeline(t):V.value==='tree'?(t.callTree.map(n=>node(n,0)).join('')||'<div class=\"empty\">没有匹配的调用树</div>'):segments(t);ZL.textContent=zoom+'×';")
                .append("document.getElementById('meta').textContent='窗口 '+ms(R.durationNs)+' ms · 点采样 '+R.pointSampleCount+' · 精确区间 '+R.exactRecordCount+' · 估算覆盖 '+ms(t.estimatedCoveredDurationNs)+' ms · 精确覆盖 '+ms(R.exactCoveredDurationNs)+' ms';attachViewport()}")
                .append("function zoomAt(next,clientX){const old=C.querySelector('.visual-shell');let ratio=.5,anchor=0;if(old){const rect=old.getBoundingClientRect();anchor=clientX==null?old.clientWidth/2:Math.max(0,clientX-rect.left);ratio=(old.scrollLeft+anchor)/Math.max(1,old.scrollWidth)}zoom=Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,next));render();const fresh=C.querySelector('.visual-shell');if(fresh)fresh.scrollLeft=Math.max(0,ratio*fresh.scrollWidth-anchor)}")
                .append("function attachViewport(){const shell=C.querySelector('.visual-shell');if(!shell)return;shell.addEventListener('wheel',e=>{if(!e.ctrlKey)return;e.preventDefault();zoomAt(e.deltaY<0?zoom*2:zoom/2,e.clientX)},{passive:false});let dragging=false,startX=0,startScroll=0;shell.addEventListener('pointerdown',e=>{if(e.button!==0)return;dragging=true;startX=e.clientX;startScroll=shell.scrollLeft;shell.classList.add('dragging');shell.setPointerCapture(e.pointerId)});shell.addEventListener('pointermove',e=>{if(dragging)shell.scrollLeft=startScroll-(e.clientX-startX)});const stop=e=>{if(!dragging)return;dragging=false;shell.classList.remove('dragging');if(shell.hasPointerCapture(e.pointerId))shell.releasePointerCapture(e.pointerId)};shell.addEventListener('pointerup',stop);shell.addEventListener('pointercancel',stop);for(const cell of shell.querySelectorAll('.visual-cell'))cell.addEventListener('click',e=>{e.stopPropagation();for(const old of shell.querySelectorAll('.selected'))old.classList.remove('selected');cell.classList.add('selected')})}")
                .append("document.getElementById('zoom-in').addEventListener('click',()=>zoomAt(zoom*2));document.getElementById('zoom-out').addEventListener('click',()=>zoomAt(zoom/2));document.getElementById('zoom-reset').addEventListener('click',()=>zoomAt(1));")
                .append("T.addEventListener('change',()=>{zoom=1;render()});V.addEventListener('change',()=>{zoom=1;render()});M.addEventListener('change',render);S.addEventListener('change',render);Q.addEventListener('input',render);render();");
    }
}
