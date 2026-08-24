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

/** 生成无需网络资源的堆栈 HTML，默认展示按公共调用前缀聚合的火焰图。 */
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
        StringBuilder html = new StringBuilder(48 * 1024);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>RheaTrace 堆栈报告</title><style>")
                .append("*{box-sizing:border-box}body{margin:0;background:#f6f7f9;color:#172033;")
                .append("font:14px/1.55 -apple-system,BlinkMacSystemFont,Segoe UI,Microsoft YaHei,sans-serif}")
                .append(".top{position:sticky;top:0;z-index:2;background:#fff;border-bottom:1px solid #e4e7ed;padding:14px 20px}")
                .append("h1{font-size:18px;margin:0 0 10px}.controls{display:flex;gap:10px;flex-wrap:wrap}")
                .append("select,input,button{border:1px solid #cfd5df;border-radius:6px;background:#fff;padding:7px 9px}")
                .append("input{min-width:280px}.meta{color:#697386;margin-top:8px}.report{margin:16px;background:#fff;")
                .append("border:1px solid #e4e7ed;border-radius:8px;overflow:hidden}.header,.row{display:grid;")
                .append("grid-template-columns:minmax(0,1fr) 150px}.header{font-weight:600;background:#fafafa;")
                .append("border-bottom:1px solid #e4e7ed}.cell{padding:10px 14px}.time{border-left:1px solid #e4e7ed;")
                .append("text-align:right;font-variant-numeric:tabular-nums}details{border-bottom:1px solid #edf0f4}")
                .append("details:last-child{border-bottom:0}summary{list-style:none;cursor:pointer}")
                .append("summary::-webkit-details-marker{display:none}.method{white-space:nowrap;overflow:hidden;")
                .append("text-overflow:ellipsis}.frames{background:#fbfcfe}.frame{display:grid;grid-template-columns:")
                .append("minmax(0,1fr) 150px;min-height:32px}.frame:hover,.row:hover{background:#eef4ff}")
                .append(".app{color:#155eef;font-weight:500}.badge{font-size:12px;color:#697386;margin-left:8px}")
                .append(".empty{padding:32px;text-align:center;color:#697386}.tree-node{border-bottom:0}")
                .append(".hidden{display:none}")
                .append(".flame-shell{overflow:auto;background:#17191d;color:#fff;padding:12px 14px 18px}")
                .append(".flame-legend{color:#b8c0cc;font-size:12px;padding:0 0 10px;white-space:nowrap}")
                .append(".flame-canvas{position:relative;min-width:1100px;background:#202329;border-top:1px solid #3a4049}")
                .append(".flame-row{position:relative;height:34px}.flame-cell{position:absolute;top:2px;height:30px;")
                .append("padding:4px 7px;border:1px solid rgba(0,0,0,.35);border-radius:2px;overflow:hidden;")
                .append("white-space:nowrap;text-overflow:ellipsis;cursor:pointer;color:#fff;font-size:12px;line-height:20px;")
                .append("text-shadow:0 1px 1px rgba(0,0,0,.55);transition:filter .1s,opacity .1s}")
                .append(".flame-cell:hover{filter:brightness(1.22);outline:2px solid #fff;z-index:3}")
                .append(".flame-cell.dim{opacity:.16}.flame-cell.app{box-shadow:inset 0 -3px 0 #75a7ff}")
                .append(".flame-label{overflow:hidden;text-overflow:ellipsis;display:block}.flame-metric{opacity:.8;")
                .append("font-size:11px;margin-left:7px}.flame-empty{padding:70px;text-align:center;color:#aeb7c4}")
                .append("@media(max-width:700px){.header,.row,.frame{grid-template-columns:minmax(0,1fr) 90px}")
                .append(".report{margin:8px}input{min-width:180px}.flame-shell{padding:8px}.flame-canvas{min-width:900px}}")
                .append("</style></head><body><div class=\"top\"><h1>RheaTrace 线上堆栈报告</h1>")
                .append("<div class=\"controls\"><select id=\"thread\"></select>")
                .append("<select id=\"view\"><option value=\"flame\" selected>聚合火焰图</option>")
                .append("<option value=\"tree\">聚合调用树</option><option value=\"segments\">时间堆栈明细</option></select>")
                .append("<select id=\"metric\"><option value=\"samples\" selected>宽度：样本数</option>")
                .append("<option value=\"exact\">宽度：精确区间</option></select>")
                .append("<select id=\"sort\"><option value=\"chronological\">按时间</option>")
                .append("<option value=\"duration\">按耗时/样本</option></select>")
                .append("<input id=\"search\" placeholder=\"搜索类名或方法名\"></div><div class=\"meta\" id=\"meta\"></div></div>")
                .append("<main class=\"report\"><div id=\"columns\" class=\"header\"><div class=\"cell\">method</div>")
                .append("<div class=\"cell time\">耗时 (ms)</div></div><div id=\"content\"></div></main>")
                .append("<script id=\"report-data\" type=\"application/json\">")
                .append(json).append("</script><script>")
                .append("const R=JSON.parse(document.getElementById('report-data').textContent);")
                .append("const T=document.getElementById('thread'),V=document.getElementById('view'),M=document.getElementById('metric'),S=document.getElementById('sort'),")
                .append("Q=document.getElementById('search'),C=document.getElementById('content'),H=document.getElementById('columns');")
                .append("const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));")
                .append("const ms=n=>n==null?'--':(n/1e6>=100?(n/1e6).toFixed(0):(n/1e6).toFixed(2).replace(/\\.?0+$/,''));")
                .append("const app=n=>R.appName&&String(n).includes(R.appName)?' app':'';")
                .append("const textOf=n=>String(n?.method??n?.displayName??'');")
                .append("for(const t of R.threads){const o=document.createElement('option');o.value=t.tid;")
                .append("o.textContent=t.threadName+' ('+t.tid+')';T.appendChild(o)}")
                .append("const d=R.renderDefaults||{};let selected=R.threads.find(t=>String(t.tid)===String(d.thread)||t.threadName===d.thread);")
                .append("if(!selected)selected=R.threads.find(t=>t.tid===R.processId)||R.threads[0];if(selected)T.value=selected.tid;")
                .append("S.value=d.sort||'chronological';V.value=d.view||'flame';M.value=d.flameMetric||'samples';")
                .append("function frame(f,i,dur,type,count){return '<div class=\"frame\" title=\"'+esc(type||'')+'\"><div class=\"cell method'+app(f.method)+'\" style=\"padding-left:'+(24+i*20)+'px\">'+esc(f.displayName)+'<span class=\"badge\">样本 '+count+'</span></div><div class=\"cell time\" title=\"'+(dur==null?'点采样，无精确耗时':dur+' ns')+'\">'+ms(dur)+'</div></div>'}")
                .append("function segments(t){let a=[...t.segments];if(S.value==='duration')a.sort((x,y)=>(y.exactDurationNs??-1)-(x.exactDurationNs??-1));")
                .append("const q=Q.value.toLowerCase();a=a.filter(x=>!q||x.stack.some(f=>textOf(f).toLowerCase().includes(q)));")
                .append("if(!a.length)return '<div class=\"empty\">没有匹配的堆栈</div>';return a.map((x,k)=>{const f=x.stack[0]||{displayName:'<empty>',method:''};")
                .append("return '<details '+(k===0?'open':'')+'><summary class=\"row\"><div class=\"cell method'+app(f.method)+'\">'+esc(f.displayName)+'<span class=\"badge\">'+esc(x.eventType)+' · '+x.sampleCount+' 个样本</span></div><div class=\"cell time\" title=\"'+(x.exactDurationNs==null?'点采样，无精确耗时':x.exactDurationNs+' ns')+'\">'+ms(x.exactDurationNs)+'</div></summary><div class=\"frames\">'+x.stack.map((v,i)=>frame(v,i,i===0?x.exactDurationNs:null,x.eventType,x.sampleCount)).join('')+'</div></details>'}).join('')}")
                .append("function node(n,depth){const q=Q.value.toLowerCase(),own=!q||textOf(n).toLowerCase().includes(q),kids=(n.children||[]).map(c=>node(c,depth+1)).join('');")
                .append("if(!own&&!kids)return '';return '<details class=\"tree-node\" open><summary class=\"row\"><div class=\"cell method'+app(n.method)+'\" style=\"padding-left:'+(14+depth*20)+'px\">'+esc(n.displayName)+'<span class=\"badge\">样本 '+n.sampleCount+'</span></div><div class=\"cell time\" title=\"'+(n.exactDurationNs==null?'点采样，无精确耗时':n.exactDurationNs+' ns')+'\">'+ms(n.exactDurationNs)+'</div></summary>'+kids+'</details>'}")
                .append("function flameWeight(n){if(M.value==='exact'){const d=Number(n.exactDurationNs);return Number.isFinite(d)&&d>0?d:0}return Math.max(0,Number(n.sampleCount||0))}")
                .append("function flameMatch(n,q){return !q||textOf(n).toLowerCase().includes(q)||String(n.displayName||'').toLowerCase().includes(q)||(n.children||[]).some(c=>flameMatch(c,q))}")
                .append("function prepare(n){const kids=(n.children||[]).map(prepare).filter(x=>x.visual>0);return {n:n,kids:kids,visual:flameWeight(n)}}")
                .append("function sorted(a){return [...a].sort((x,y)=>S.value==='duration'?y.visual-x.visual:0)}")
                .append("function color(s){let h=0;for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))|0;h=Math.abs(h)%360;return 'hsl('+h+',55%,45%)'}")
                .append("function flame(t){const roots=(t.callTree||[]).map(prepare).filter(x=>x.visual>0);if(!roots.length)return '<div class=\"flame-shell\"><div class=\"flame-empty\">当前指标没有可聚合的数据</div></div>';")
                .append("const total=roots.reduce((s,n)=>s+n.visual,0),rows=[];const q=Q.value.toLowerCase();")
                .append("function put(p,x,w,depth){(rows[depth]||(rows[depth]=[])).push({p:p,x:x,w:w});const kids=p.kids;")
                .append("const childTotal=kids.reduce((s,n)=>s+n.visual,0);if(!childTotal)return;let cx=x;const denominator=Math.max(childTotal,p.visual);")
                .append("for(const child of sorted(kids)){const childWidth=w*(child.visual/denominator);put(child,cx,childWidth,depth+1);cx+=childWidth}}")
                .append("let rootX=0;for(const root of sorted(roots)){const rootWidth=root.visual/total;put(root,rootX,rootWidth,0);rootX+=rootWidth}")
                .append("const maxDepth=rows.length,content=rows.map(row=>'<div class=\"flame-row\">'+row.map(({p,x,w})=>{const n=p.n;")
                .append("const dim=q&&!flameMatch(n,q)?' dim':'';const metric=M.value==='exact'?ms(n.exactDurationNs)+' ms':'样本 '+n.sampleCount;")
                .append("const title=textOf(n)+' · '+metric+(n.selfDurationNs!=null?' · 未归属区间 '+ms(n.selfDurationNs)+' ms':'');")
                .append("return '<div class=\"flame-cell'+app(n.method)+dim+'\" style=\"left:'+(x*100)+'%;width:'+(w*100)+'%;background:'+color(textOf(n))+'\" title=\"'+esc(title)+'\"><span class=\"flame-label\">'+esc(n.displayName||n.method)+'<span class=\"flame-metric\">'+esc(metric)+'</span></span></div>'}).join('')+'</div>').join('');")
                .append("const legend=M.value==='exact'?'横向宽度按精确区间并集计算；只有点样本的节点不显示。':'横向宽度按聚合样本数计算；点样本不虚构耗时。';return '<div class=\"flame-shell\"><div class=\"flame-legend\">'+legend+' 纵向为调用深度，悬停查看方法和指标。</div><div class=\"flame-canvas\" style=\"height:'+(maxDepth*34)+'px\">'+content+'</div></div>'}")
                .append("function render(){const t=R.threads.find(x=>String(x.tid)===T.value);if(!t){C.innerHTML='<div class=\"empty\">没有可解析线程</div>';return}")
                .append("H.style.display=V.value==='flame'?'none':'grid';C.innerHTML=V.value==='flame'?flame(t):(V.value==='tree'?(t.callTree.map(n=>node(n,0)).join('')||'<div class=\"empty\">没有匹配的调用树</div>'):segments(t));")
                .append("document.getElementById('meta').textContent='窗口 '+ms(R.durationNs)+' ms · 点采样 '+R.pointSampleCount+' · 精确区间 '+R.exactRecordCount+' · 精确覆盖 '+ms(R.exactCoveredDurationNs)+' ms'}")
                .append("[T,V,M,S].forEach(x=>x.addEventListener('change',render));Q.addEventListener('input',render);render();")
                .append("</script></body></html>");
        Files.write(output.toPath(), html.toString().getBytes(StandardCharsets.UTF_8));
    }
}
