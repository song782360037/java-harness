#!/usr/bin/env bash
set -u
cd /e/projects/java-harness/video-assets
AB="agent-browser"
step() { echo; echo "== $* =="; }

step "设置视口 1600x900"
timeout 30 $AB set viewport 1600 900 2>&1 | tail -1
sleep 2

step "返回工作区"
timeout 30 $AB eval '(function(){const x=[...document.querySelectorAll("button")].find(e=>e.textContent.includes("返回工作区")); if(x){x.click();return "clicked";} return "no-btn";})()' 2>&1 | tail -1
sleep 2

step "新建对话 -> 欢迎页"
timeout 30 $AB eval '(function(){const x=document.querySelector("button.new-session"); if(x){x.click();return "clicked";} return "nf";})()' 2>&1 | tail -1
sleep 3
timeout 60 $AB screenshot shots/hd-01-welcome.png 2>&1 | tail -1

step "打开项目分析会话"
timeout 30 $AB eval '(function(){const x=[...document.querySelectorAll("button.session-item")].find(e=>e.textContent.includes("项目分析与真实工具调用")); if(x){x.click();return "clicked";} return "nf";})()' 2>&1 | tail -1
sleep 3
timeout 30 $AB eval '(function(){const t=document.querySelector(".timeline"); if(t)t.scrollTop=0; return "scrolltop";})()' 2>&1 | tail -1
sleep 1
timeout 60 $AB screenshot shots/hd-02-chat.png 2>&1 | tail -1

step "展开执行详情面板"
timeout 30 $AB eval '(function(){const x=[...document.querySelectorAll("button")].find(e=>e.textContent.trim().startsWith("已完成")||e.textContent.trim().startsWith("已取消")); if(x){x.click();return "clicked";} return "nf";})()' 2>&1 | tail -1
sleep 2
timeout 60 $AB screenshot shots/hd-03-rundetail.png 2>&1 | tail -1

step "切到 Diff 会话"
timeout 30 $AB eval '(function(){const x=[...document.querySelectorAll("button.session-item")].find(e=>e.textContent.includes("文件 Diff")); if(x){x.click();return "clicked";} return "nf";})()' 2>&1 | tail -1
sleep 3
step "展开 Greeting.java 的 Diff"
timeout 30 $AB eval '(function(){const c=[...document.querySelectorAll("article.file-change-card")].find(e=>e.textContent.includes("Greeting.java")); if(!c) return "nf"; const b=c.querySelector("button.file-change-toggle"); if(b && !c.textContent.includes("@@")) { b.click(); } c.scrollIntoView({block:"center"}); return "ok";})()' 2>&1 | tail -1
sleep 2
timeout 60 $AB screenshot shots/hd-04-diff.png 2>&1 | tail -1

step "打开设置"
timeout 30 $AB eval '(function(){const x=[...document.querySelectorAll("button")].find(e=>e.textContent.includes("设置与偏好")); if(x){x.click();return "clicked";} return "nf";})()' 2>&1 | tail -1
sleep 2
step "模型与提供商"
timeout 30 $AB eval '(function(){const x=[...document.querySelectorAll("button,nav *")].find(e=>e.textContent.trim()==="模型与提供商"); if(x){x.click();return "clicked";} return "nf";})()' 2>&1 | tail -1
sleep 2
step "脱敏私有地址"
timeout 30 $AB eval '(function(){let n=0; document.querySelectorAll("input").forEach(i=>{const v=(i.value||""); if(v.includes("http")||v.includes("songst")){i.style.filter="blur(8px)"; n++;}}); return "blurred "+n;})()' 2>&1 | tail -1
sleep 1
timeout 60 $AB screenshot shots/hd-05-provider.png 2>&1 | tail -1

step "完成"
ls -la shots/hd-*.png
