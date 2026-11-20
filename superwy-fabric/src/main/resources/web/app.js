'use strict';
const $=id=>document.getElementById(id);
let token=sessionStorage.getItem('superwy-token')||'',admin=false,state={},capabilities={},orders=[],loading=false;
sessionStorage.removeItem('superwy-orders');
// /superwy web 生成的链接：#token=… 自动登录，读完立刻从地址栏移除。
function tokenFromLink(){const linked=new URLSearchParams(location.hash.slice(1)).get('token');if(!linked)return false;token=linked.trim();sessionStorage.setItem('superwy-token',token);history.replaceState(null,'',location.pathname+location.search);return true;}
tokenFromLink();
// 已打开的页面里粘贴链接只改变 # 部分，不会刷新页面。
addEventListener('hashchange',()=>{if(tokenFromLink())load();});
let selectedOrderId=null,connectionUrl=null;
const frpNames={stopped:'已停止',starting:'正在启动',ready:'已连接',reconnecting:'正在重连',failed:'连接失败'};
async function loadNetwork(){if(!admin)return;const n=await api('admin/network');if(connectionUrl&&connectionUrl!==n.publicUrl){$('connect-command').value='';connectionUrl=null;}$('frp-status').textContent='FRP '+(frpNames[n.state]||n.state)+' · 本机 '+n.localUrl+(n.publicUrl?' · 公网 '+n.publicUrl:'')+(n.errorMessage?' · '+n.errorMessage:'');}
const meshRoles={off:'未开启',authority:'本机是调度机器人',worker:'本机是工人'};
const jobKinds={simple:'单项',batch:'批量',projection:'投影装盒'},jobStates={queued:'排队中',active:'执行中',done:'已完成',cancelled:'已取消'};
function ago(ms){const s=Math.round(ms/1000);return s<60?s+' 秒前':Math.round(s/60)+' 分钟前';}
function shortId(id){return id?id.slice(-8):'—';}
function meshTable(target,text){const p=document.createElement('p');p.className='muted';p.textContent=text;target.replaceChildren(p);}
async function meshAction(path,confirmText){if(confirmText&&!confirm(confirmText))return;await api('admin/mesh/'+path,'POST',{});await loadMesh();}
async function loadMesh(){
  if(!admin)return;const m=await api('admin/mesh');
  let text='超级调度：'+(meshRoles[m.role]||m.role)+' · 调度机器人 '+m.authorityName;
  if(!m.keyAvailable)text+=' · 缺少 private jar 凭证，无法开启';
  if(m.role==='worker')text+=m.connected?' · 已连接 '+m.authorityUrl:' · 未连接'+(m.error?'：'+m.error:'');
  if(m.role==='authority'&&m.authorityUrl)text+=' · 公网 '+m.authorityUrl;
  $('mesh-status').textContent=text+' · 本机任务块 '+m.blocks;
  const form=$('mesh-settings');if(m.settings&&!form.contains(document.activeElement))for(const k of ['authorityName','offlineAfterSeconds','reassignAfterSeconds','chunkSlots','parcelChunkBoxes'])form.elements[k].value=m.settings[k];
  table($('mesh-own'),['任务块','所属任务','本机订单','状态'],(m.ownBlocks||[]).map(b=>[shortId(b.chunkId),shortId(b.orderId),b.localOrders.map(shortId).join('、')||'创建中',b.finished?'已结束，等待调度确认':b.heldOnly?'只交付身上的货':'执行中']));
  if(m.role==='off'){for(const id of ['mesh-robots','mesh-jobs','mesh-locks'])meshTable($(id),'超级调度未开启');for(const id of ['mesh-stat-robots','mesh-stat-queued','mesh-stat-active'])$(id).textContent='—';return;}
  let o;try{o=await api('admin/mesh/overview');}catch(e){for(const id of ['mesh-robots','mesh-jobs','mesh-locks'])meshTable($(id),'无法读取调度机器人数据：'+e.message);return;}
  const robots=o.robots||[],jobs=o.jobs||[];
  $('mesh-stat-robots').textContent=robots.filter(r=>r.online).length+' / '+robots.length;
  $('mesh-stat-queued').textContent=jobs.filter(j=>j.state==='queued').length;
  $('mesh-stat-active').textContent=jobs.filter(j=>j.state==='active').length;
  table($('mesh-robots'),['机器人','状态','正在做','当前任务块','位置','空槽','版本','操作'],robots.map(r=>{
    const actions=document.createElement('div');
    if(!r.online&&r.chunkId&&!r.orphaned)actions.append(button('立即改派',()=>meshAction('chunks/'+r.chunkId+'/reassign','跳过等待，把 '+r.name+' 未交付的部分改派给其他机器人？')));
    if(!r.online)actions.append(button('移除记录',()=>meshAction('robots/'+r.name+'/forget')));
    const state=(r.online?'在线':'离线')+(r.name===o.authorityName?' · 调度':'')+(r.paused?' · 已暂停':r.ready?' · 可接单':' · 未就绪')+' · '+ago(r.lastSeenMillisAgo);
    const block=r.chunkId?shortId(r.chunkId)+' · 已交 '+r.blockDelivered+'/'+r.blockCount+(r.orphaned?'（已改派）':''):'空闲';
    return [r.name,state,r.activity,block,r.position,r.freeSlots,r.version,actions];
  }));
  table($('mesh-jobs'),['任务','类型','状态','前方','机器人','已交付 / 总数','未派出','任务块','操作'],jobs.map(j=>{
    const actions=document.createElement('div');
    if(j.state==='queued'||j.state==='active'){actions.append(button('置顶',()=>meshAction('jobs/'+j.orderId+'/top')));actions.append(button('取消',()=>meshAction('jobs/'+j.orderId+'/cancel','取消任务 '+shortId(j.orderId)+'？正在执行的任务块会立即停止。')));}
    return [shortId(j.orderId),jobKinds[j.kind]||j.kind,jobStates[j.state]||j.state,j.state==='queued'?j.ahead:'—',j.robots,j.delivered+' / '+j.requested+(j.shortfall?'（缺 '+j.shortfall+'）':''),j.remaining,j.blocks,actions];
  }));
  table($('mesh-locks'),['收货假人','占用机器人'],(o.locks||[]).map(l=>[l.name,l.robot]));
}
setInterval(()=>{if(admin&&!document.querySelector('[data-view="mesh"]').hidden&&$('mesh-auto').checked&&!document.hidden)loadMesh().catch(()=>{});},3000);
async function copyConnection(){const value=$('connect-command').value;if(!value)throw Error('请先生成连接指令');if(navigator.clipboard?.writeText){try{await navigator.clipboard.writeText(value);return;}catch(e){}}$('connect-command').focus();$('connect-command').select();if(!document.execCommand('copy'))throw Error('请手动复制已选中的连接指令');}
const terminal=new Set(['stored_offline','partial_stored_offline','cancelled','failed']);
const names={queued:'已排队',validating:'校验中',reserving:'预留中',fetching:'取货中',packing:'装箱中',delivery_preflight:'交付预检',spawning:'召唤假人',transferring:'交付中',verifying:'核对收货',despawning:'假人下线中',stored_offline:'已收货并下线',partial_stored_offline:'部分收货并下线',waiting_inventory:'等待背包空间',clearing_inventory:'定期存箱清理主背包',waiting_stock:'等待库存',waiting_boxes:'等待空潜影盒',waiting_recipient:'等待收货容量',recovering:'需要核对恢复',delivery_uncertain:'交付结果待核对',cancelled:'已取消',failed:'已结束（有异常）'};
const reasonNames={QUICKSHULKER_CLIENT_INCOMPATIBLE:'已安装的 QuickShulker 客户端接口不兼容，源盒保留在背包',QUICKSHULKER_MENU_MISMATCH:'QuickShulker 打开的界面不是潜影盒，已停止',SOURCE_BOX_CHANGED:'源盒实际内容与缓存不一致，已停止',SOURCE_BOX_SYNC_TIMEOUT:'源盒取物后的同步超时，已停止',SOURCE_BOX_RETURN_BLOCKED:'源盒原槽被占用，盒子保留在机器人背包',SOURCE_BOX_RETURN_MISMATCH:'源盒归还后的原槽内容不符，已停止',OUT_OF_STOCK:'缺货，订单已停止并在游戏内公告',SOURCE_CHANGED:'来源物品与缓存不符，订单已停止',ORDER_TIMEOUT:'执行超过本单时限，任务已强制停止',USER_CANCELLED:'已立即取消，现有物品留在原位',EXPIRED:'订单有效期已结束',FREE_CARGO_SLOTS_REQUIRED:'背包主物品栏需要空槽，现有物品不会自动移动',QUICKSHULKER_REQUIRED_FOR_NESTED_SOURCE:'箱内源潜影盒需要服务端 QuickShulker Multi；地面潜影盒无需此依赖',GCA_INVENTORY_OPEN_TIMEOUT:'右键假人未打开背包，请检查 GCA openFakePlayerInventory 规则',GCA_INVENTORY_LAYOUT_UNSUPPORTED:'假人背包布局不匹配 GCA，已停止转移',RECIPIENT_MENU_MISMATCH:'假人背包界面与服务端库存不一致，已停止转移',CANCEL_CARGO_RETAINED_ON_ROBOT:'本单物品保留在机器人背包，请核对后解除',RECIPIENT_FULL:'假人物品栏已满，订单已停止，货物留在原位',CLEAR_MANAGED_INVENTORY_FIRST:'工作区有物品（快捷栏允许保留），请清理后核对恢复',RECOVERY_STORAGE_REQUIRED:'请先设置安全箱：/superwy station recovery',RECOVERY_STORAGE_FULL:'安全箱已满，腾出空间后自动继续',CURSOR_NOT_EMPTY:'请先放下鼠标光标携带的物品',CLOSE_PLAYER_MENU:'界面未关闭，订单已停止；关闭后可重新下单',RECOVERY_EQUALS_BOX_SUPPLY:'安全箱不能与空盒供应箱相同'};
const scanStateNames={idle:'空闲',offline:'未连接',running:'扫描运行中',reading:'读取容器',discovering:'发现容器',resuming:'恢复扫描',stopped:'已停止',disabled:'扫描关闭',paused_for_order:'取货优先，扫描暂停',complete:'本轮完成',complete_with_skips:'完成（含跳过）',storage_error:'数据库异常',protocol_error:'扫描协议异常'};
const featureNames={nbtBlockQuery:'容器 NBT 查询',quickShulker:'QuickShulker 协议',carpetPlayerCommand:'Carpet 命令',recipientInventoryRead:'实体库存读取',recipientReceipt:'收货核对',navigation:'Baritone 寻路'};
function toast(text,error=false){$('toast').textContent=text;$('toast').hidden=false;$('toast').className=error?'bad':'';}
async function api(path,method='GET',body,extra={}){const response=await fetch('/api/v1/'+path,{method,headers:{Authorization:'Bearer '+token,...(body!==undefined?{'Content-Type':'application/json'}:{}),...extra},body:body===undefined?undefined:JSON.stringify(body)});const data=await response.json();if(!response.ok){const e=new Error(data.error?.message||data.error?.code||response.statusText);e.status=response.status;throw e;}return data;}
function table(target,headers,data){const table=document.createElement('table'),head=document.createElement('thead'),tr=document.createElement('tr');for(const text of headers){const th=document.createElement('th');th.textContent=text;tr.append(th);}head.append(tr);table.append(head);const body=document.createElement('tbody');for(const row of data){const tr=document.createElement('tr');for(const value of row){const td=document.createElement('td');if(value instanceof Node)td.append(value);else td.textContent=value??'—';tr.append(td);}body.append(tr);}table.append(body);target.replaceChildren(table);if(!data.length){const p=document.createElement('p');p.className='muted';p.textContent='暂无数据';target.append(p);}}
function button(text,callback){const b=document.createElement('button');b.textContent=text;b.addEventListener('click',()=>Promise.resolve().then(callback).catch(e=>toast(e.message,true)));return b;}
function pos(text){const values=text.trim().split(/[,，\s]+/).map(Number);if(values.length!==3||values.some(v=>!Number.isInteger(v)))throw Error('请输入三个整数坐标');return {x:values[0],y:values[1],z:values[2]};}
function showOrder(o){selectedOrderId=o.orderId;$('order-detail').textContent=JSON.stringify(o,null,2);}
async function loadOrder(o){showOrder(await api('orders/'+encodeURIComponent(o.orderId)));}
function recentOrders(all){const live=all.filter(o=>!terminal.has(o.state));const history=all.filter(o=>terminal.has(o.state)).sort((a,b)=>Date.parse(b.updatedAt||b.createdAt||0)-Date.parse(a.updatedAt||a.createdAt||0)).slice(0,10);return [...live,...history];}
function renderOrders(){table($('orders-table'),['订单','排队位置','收货假人','状态','取出 / 收货','原因','操作'],orders.map(o=>{const actions=document.createElement('div');actions.append(button('详情',()=>loadOrder(o)));if(admin&&['recovering','delivery_uncertain'].includes(o.state))actions.append(button('核对后解除',async()=>{if(!confirm('仅在已核对机器人工作区、来源箱及收货假人的物品后解除。此操作结束本订单，不会重新发货。确认已核对？'))return;await api('admin/orders/'+encodeURIComponent(o.orderId)+'/resolve','POST',{confirmation:'I_VERIFIED_ITEMS_AND_RECIPIENT'});await load();}));if(!terminal.has(o.state))actions.append(button('取消',async()=>{await api('orders/'+encodeURIComponent(o.orderId)+'/cancel','POST');toast('订单已立即取消，已停止本单操作');await load();}));return [o.clientRequestId||o.orderId,o.queue?.position?'第 '+o.queue.position+' 位 · 前方 '+o.queue.ahead+' 单':'—',o.delivery?.fakePlayerName||o.fakePlayerName,names[o.state]||o.state,(o.fetched??o.lines?.reduce((n,l)=>n+l.fetched,0)??0)+' / '+(o.delivered??o.lines?.reduce((n,l)=>n+l.delivered,0)??0),(o.errorMessage||reasonNames[o.blockingReason||o.errorCode]||((o.blockingReason||o.errorCode)?'操作异常，请查看订单详情':'—'))+(o.missingItems?.length?' · '+o.missingItems.join(', '):'')+(o.needsItemReview?' · 请核对留存物品':''),actions];}));}
function renderAdmin(){
 if(state.workflow){const form=$('workflow-form');if(form&&!form.contains(document.activeElement)){for(const key of ['returnEntry','boxSupply']){const p=state.workflow[key];form.elements[key].value=[p.x,p.y,p.z].join(',');}form.elements.packingRoundSlots.value=state.workflow.packingRoundSlots;form.elements.maxCarriedParcels.value=state.workflow.maxCarriedParcels;}}

 const scanEnabled=state.scanEnabled!==false;$('scan-toggle').textContent=scanEnabled?'关闭扫描，使用历史记录':'开启扫描';$('scan-all').disabled=!scanEnabled;
 const updated=Math.max(0,...(state.regions||[]).map(r=>r.lastScanAt||0));const due=(state.regions||[]).filter(r=>r.enabled&&!r.scanPaused&&r.world===state.world&&r.dimension===state.dimension).map(r=>Math.max(r.lastScanAt||0,r.lastSuccessAt||0)+(state.scanProtocol==='servux'?600:(r.intervalSeconds||3600))*1000);const next=due.length?Math.min(...due):0;$('scan-policy').textContent=(scanEnabled?'定时扫描开启':'扫描已关闭，使用历史库存')+' · '+(state.scanProtocol==='servux'?'Servux（实验性）':'F3')+' · 库存无时间过期限制 · 最近核实：'+(updated?new Date(updated).toLocaleString():'暂无记录')+(scanEnabled&&next?' · 下次扫描：'+(next<=Date.now()?'已到期，等待执行':new Date(next).toLocaleString()):'');
 const scan=state.scan||{};$('scan-label').textContent=(scan.regionId||'')+' · '+(scanStateNames[scan.state||'idle']||'扫描状态未知');$('scan-counts').textContent='读取成功 '+(scan.processed||0)+' / 发现 '+(scan.discovered||0)+'；跳过 '+(scan.skippedContainers||0)+' 个容器、'+(scan.skippedChunks||0)+' 个区块；待扫描 '+(scan.remainingContainerCount??scan.remainingContainers?.length??0)+'；区块覆盖 '+(scan.coveredChunks||0)+' / '+(scan.totalChunks||0);$('scan-progress').value=['complete','complete_with_skips'].includes(scan.state)?100:(scan.discovered?Math.min(100,(scan.processed+(scan.skippedContainers||0))/scan.discovered*100):0);
 $('scan-errors').textContent=(scan.protocolMessage?scan.protocolMessage+'\n':'')+(scan.failures||[]).slice(-8).map(f=>JSON.stringify(f.position||f.chunk||{})+' · '+(f.message||'扫描失败，已跳过')).join('\n');$('scan-errors').className='notice';
 $('world-info').textContent='机器人版本：'+(state.modVersion||'旧版')+'\n世界：'+state.world+'\n维度：'+state.dimension+'\n选择：'+JSON.stringify(state.selection)+'\n导航：'+({idle:'空闲',stopped:'已停止',moving:'移动中',calculating:'规划路径',failed:'寻路失败'}[state.navigation?.state]||'空闲')+(state.navigation?.errorMessage?' · '+state.navigation.errorMessage:'')+(state.navigation?.target?' · 目标 '+state.navigation.target.join(', '):'');
 $('stat-containers').textContent=(state.scanRegions||[]).filter(r=>r.running).length;
 $('region-list').replaceChildren();for(const r of state.regions||[]){const row=document.createElement('div');row.className='region-row';const text=document.createElement('div');const scan=(state.scanRegions||[]).find(x=>x.regionId===r.id);const labels={running:'扫描运行中',reading:'读取容器',discovering:'发现容器',stopped:'已单独停止',disabled:'扫描关闭',paused_for_order:'取货优先，暂时让行',complete:'本轮完成',complete_with_skips:'完成（含跳过）',idle:'未启动'};text.textContent=r.id+' · '+JSON.stringify(r.min)+' → '+JSON.stringify(r.max)+' · '+(state.scanProtocol==='servux'?600:r.intervalSeconds)+' 秒 · '+(labels[scan?.state]||scanStateNames[scan?.state]||(r.scanPaused?'已单独停止':'其他世界'))+(scan?' · 已读取 '+(scan.processed||0)+' / '+(scan.discovered||0)+' · 待处理 '+(scan.remainingContainerCount??scan.remainingContainers?.length??0):'');const actions=document.createElement('div');const start=button('开始 / 继续',async()=>{await api('admin/scan/start','POST',{regionId:r.id});toast('区域 '+r.id+' 已启动，与其他区域轮流推进');await load();});start.disabled=!scan||state.scanEnabled===false;actions.append(start);const stop=button('停止此区域',async()=>{await api('admin/scan/stop','POST',{regionId:r.id});toast('区域 '+r.id+' 已停止并保存断点，其他区域继续');await load();});stop.disabled=!scan||r.scanPaused;actions.append(stop);actions.append(button('删除',async()=>{await api('admin/regions/'+encodeURIComponent(r.id),'DELETE');await load();}));row.append(text,actions);$('region-list').append(row);}

}
async function load(){
 if(!token||loading)return;loading=true;
 try{
  capabilities=await api('capabilities');$('app-version').textContent='FABRIC · 26.1.2 · v'+(capabilities.modVersion||'未知');$('login').hidden=true;$('app').hidden=false;
  try{state=await api('admin/dashboard');admin=true;orders=recentOrders(state.orders||[]);}catch(e){if(e.status!==403)throw e;admin=false;state={};const queue=await api('queue?warehouseId=main&historyLimit=10');orders=recentOrders(queue.orders||[]);}
  if(selectedOrderId&&!orders.some(o=>o.orderId===selectedOrderId)){selectedOrderId=null;$('order-detail').textContent='记录已移出最近历史列表，选择订单查看详情。';}
  document.querySelectorAll('.admin').forEach(el=>el.hidden=!admin);
  $('connection').textContent=capabilities.connectionState==='online'?'客户端在线':'客户端离线';$('online-dot').classList.toggle('on',capabilities.connectionState==='online');
  $('stat-variants').textContent=admin?(state.regions||[]).length:'—';$('stat-orders').textContent=orders.filter(o=>!terminal.has(o.state)).length;$('accepting').textContent=capabilities.canAcceptOrders?'可接单':'等待就绪';
  $('blocking').textContent=(capabilities.blockingMessages||[]).join(' · ');$('features').replaceChildren();for(const [key,value] of Object.entries(capabilities.features||{})){const div=document.createElement('div');div.className='feature-row';const label=document.createElement('span'),status=document.createElement('span');label.textContent=featureNames[key]||key;status.textContent=value==='available'?'可用':value==='unavailable'?'不可用':'待实测';status.className=value==='available'?'good':'muted';div.append(label,status);$('features').append(div);}
  $('permitted-names').textContent='获准假人名称：'+(capabilities.allowedFakePlayerNames?.join('、')||'管理员可指定合法名称');renderOrders();if(admin){renderAdmin();if(!document.querySelector('[data-view="access"]').hidden)await loadNetwork();if(!document.querySelector('[data-view="mesh"]').hidden)await loadMesh();}
 }catch(e){if(e.status===401){$('login').hidden=false;$('app').hidden=true;}toast(e.message,true);}finally{loading=false;}
}
document.querySelectorAll('[data-page]').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('[data-page]').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('[data-view]').forEach(s=>s.hidden=s.dataset.view!==b.dataset.page);$('page-title').textContent=b.textContent;}));
$('login-form').addEventListener('submit',e=>{e.preventDefault();token=$('token').value.trim();sessionStorage.setItem('superwy-token',token);$('token').value='';load();});
$('logout').addEventListener('click',()=>{for(const key of ['superwy-token','superwy-orders','superwy-pending'])sessionStorage.removeItem(key);location.reload();});
$('scan-toggle').addEventListener('click',async()=>{try{await api('admin/scan/'+(state.scanEnabled===false?'enable':'disable'),'POST',{});await load();}catch(e){toast(e.message,true);}});
$('refresh').addEventListener('click',load);
for(const action of ['start','stop'])$('frp-'+action).addEventListener('click',async()=>{try{await api('admin/frp/'+action,'POST',{});await loadNetwork();}catch(e){toast(e.message,true);}});
$('connect-form').addEventListener('submit',async e=>{e.preventDefault();try{const r=await api('admin/connect','POST',{});connectionUrl=r.publicUrl;$('connect-command').value=r.command;await copyConnection();toast('连接指令已复制，玩家执行后填写收货假人名');}catch(e){toast(e.message,true);}});
$('copy-connect').addEventListener('click',()=>copyConnection().then(()=>toast('连接指令已复制')).catch(e=>toast(e.message,true)));
document.querySelector('[data-page="access"]').addEventListener('click',()=>loadNetwork().catch(e=>toast(e.message,true)));
document.querySelector('[data-page="mesh"]').addEventListener('click',()=>loadMesh().catch(e=>toast(e.message,true)));
$('mesh-settings').addEventListener('submit',async e=>{e.preventDefault();const f=e.target.elements;try{await api('admin/mesh/settings','PUT',{authorityName:f.authorityName.value.trim(),offlineAfterSeconds:Number(f.offlineAfterSeconds.value),reassignAfterSeconds:Number(f.reassignAfterSeconds.value),chunkSlots:Number(f.chunkSlots.value),parcelChunkBoxes:Number(f.parcelChunkBoxes.value)});toast('调度设置已保存');await loadMesh();}catch(err){toast(err.message,true);}});
$('mesh-events-load').addEventListener('click',async()=>{try{const d=await api('admin/mesh/debug');$('mesh-events').textContent=(d.events||[]).slice().reverse().join('\n')||'暂无事件';}catch(e){toast(e.message,true);}});
$('mesh-debug-download').addEventListener('click',async()=>{try{const d=await api('admin/mesh/debug');const url=URL.createObjectURL(new Blob([JSON.stringify(d,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='superwy-mesh-'+Date.now()+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch(e){toast(e.message,true);}});
$('mesh-locks-clear').addEventListener('click',()=>meshAction('locks/clear','清除全部收货假人锁？只在机器人卡在“等待收货假人”时使用。').catch(e=>toast(e.message,true)));
for(const action of ['enable','disable'])$('mesh-'+action).addEventListener('click',async()=>{try{await api('admin/mesh/'+action,'POST',{});await loadMesh();}catch(e){toast(e.message,true);}});
for(const [id,path] of [['pause','pause'],['resume','resume'],['scan-all','scan']])$(id).addEventListener('click',async()=>{try{await api('admin/'+path,'POST',{});toast('操作已提交');await load();}catch(e){toast(e.message,true);}});
$('region-form').addEventListener('submit',async e=>{e.preventDefault();try{const f=new FormData(e.target);await api('admin/regions/'+encodeURIComponent(f.get('id')),'PUT',{min:pos(f.get('min')),max:pos(f.get('max')),intervalSeconds:Number(f.get('intervalSeconds'))});toast('区域已保存');await load();}catch(e){toast(e.message,true);}});
let submitting=false;
$('order-form').addEventListener('submit',async e=>{e.preventDefault();if(submitting)return;submitting=true;$('submit-order').disabled=true;try{const f=new FormData(e.target);const items=f.get('items').trim().split(/\n/).filter(x=>x.trim()).map(line=>{const [itemId,raw,hash,...extra]=line.trim().split(/\s+/);const count=Number(raw);if(!Number.isInteger(count)||count<1||extra.length)throw Error('清单格式错误：物品ID 整数数量 [组件散列]');return {itemId,count,...(hash?{match:{mode:'exact_components',componentHash:hash}}:{})};});const request={warehouseId:'main',items,delivery:{method:'carpet_fake_player',fakePlayerName:f.get('fakePlayerName')},options:{allowPartial:false}};
 const body=JSON.stringify(request);let pending=JSON.parse(sessionStorage.getItem('superwy-pending')||'null');if(!pending||pending.body!==body){pending={body,key:crypto.randomUUID()};sessionStorage.setItem('superwy-pending',JSON.stringify(pending));}
 const o=await api('orders','POST',request,{'Idempotency-Key':pending.key});sessionStorage.removeItem('superwy-pending');showOrder(o);toast('订单已接收：'+o.orderId);await load();
 }catch(e){toast('未完成提交：'+e.message+'。网络重试会保留原幂等键。',true);}finally{submitting=false;$('submit-order').disabled=false;}});
load();setInterval(()=>{if(!document.hidden)load();},5000);

$('workflow-form').addEventListener('submit',async e=>{e.preventDefault();try{const f=e.target.elements;await api('admin/workflow','PUT',{returnEntry:pos(f.returnEntry.value),boxSupply:pos(f.boxSupply.value),packingRoundSlots:Number(f.packingRoundSlots.value),maxCarriedParcels:Number(f.maxCarriedParcels.value)});toast('取盒与清理箱设置已保存');}catch(error){toast(error.message,true);}});

// 缺货对比：读取 Litematica 材料列表 JSON 的 Missing，重新扫描仓库后与库存对比，只列出不够的物品。
let shortageItems=null,shortageNames=null,shortageRows=[],shortageRunning=false,shortageStop=false;
const scanBusy=new Set(['running','reading','discovering','resuming','paused_for_order']);
// 同一 ID 在实体分组里会再出现一次（如 tnt→被激活的TNT），只保留前面的物品名
async function itemNames(){
  if(shortageNames)return shortageNames;const map=new Map();
  try{const text=await (await fetch('/names.csv')).text();for(const line of text.split(/\r?\n/).slice(1)){const [id,,zh]=line.split(',');const key='minecraft:'+id?.trim();if(id&&zh&&!id.startsWith('@')&&!map.has(key))map.set(key,zh.trim());}}catch(e){}
  return shortageNames=map;
}
function itemName(id){return shortageNames?.get(id)||id;}
function stacks(n){const g=Math.floor(n/64),r=n%64;return g?'（'+g+' 组'+(r?' + '+r:'')+'）':'';}
function parseMaterialList(text){
  let data;try{data=JSON.parse(text);}catch(e){throw Error('不是 JSON 文件；请在 Litematica 材料列表里导出 JSON 格式');}
  if(!Array.isArray(data?.Materials))throw Error('JSON 中没有 Materials 列表，不是 Litematica 材料列表导出');
  const merged=new Map();
  for(const m of data.Materials){const id=String(m.Item||'').trim(),n=Number(m.Missing);if(!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(id)||!Number.isInteger(n)||n<=0)continue;merged.set(id,(merged.get(id)||0)+n);}
  return {title:data.Title||data.Name||'',total:data.Materials.length,items:[...merged].map(([itemId,count])=>({itemId,count}))};
}
function shortageBusy(on){shortageRunning=on;$('shortage-rescan').disabled=on||!shortageItems?.length;$('shortage-cached').disabled=on||!shortageItems?.length;$('shortage-cancel').hidden=!on;$('shortage-file').disabled=on;}
function shortageStatus(text,progress){$('shortage-status').textContent=text;$('shortage-progress').hidden=progress==null;if(progress!=null)$('shortage-progress').value=progress;}
$('shortage-file').addEventListener('change',async e=>{
  const file=e.target.files[0];shortageItems=null;shortageBusy(false);if(!file)return;
  try{const list=parseMaterialList(await file.text());shortageItems=list.items;await itemNames();$('shortage-file-info').textContent=file.name+' · 共 '+list.total+' 种材料，其中 '+list.items.length+' 种投影还缺';$('shortage-file-info').className='muted';}
  catch(err){$('shortage-file-info').textContent=err.message;$('shortage-file-info').className='bad';}
  shortageBusy(false);
});
async function waitForScan(){
  const started=Date.now();let first=true;
  for(;;){
    if(shortageStop)return '已停止等待，以下按当前库存缓存对比';
    const d=await api('admin/dashboard');const regions=d.scanRegions||[];
    const busy=regions.filter(r=>scanBusy.has(r.state)),stopped=regions.filter(r=>r.state==='stopped').map(r=>r.regionId);
    const broken=regions.filter(r=>['protocol_error','storage_error'].includes(r.state));
    if(broken.length)return '扫描出错（'+broken.map(r=>r.regionId).join('、')+'），以下按当前库存缓存对比';
    if(!busy.length&&!first)return '全部仓库已重新扫描'+(stopped.length?'（已单独停止的区域未扫描：'+stopped.join('、')+'）':'');
    first=false;
    const done=busy.reduce((n,r)=>n+(r.processed||0)+(r.skippedContainers||0),0),all=busy.reduce((n,r)=>n+(r.discovered||0),0);
    const waiting=busy.some(r=>r.state==='paused_for_order');
    shortageStatus('正在重新扫描 '+busy.map(r=>r.regionId).join('、')+' · 已读取 '+done+' / '+all+' 个容器'+(waiting?' · 有订单在执行，扫描暂时让行':'')+' · 已等待 '+Math.round((Date.now()-started)/1000)+' 秒',all?Math.min(100,done/all*100):0);
    await new Promise(r=>setTimeout(r,3000));
  }
}
async function runShortage(rescan){
  if(shortageRunning||!shortageItems?.length)return;shortageStop=false;shortageBusy(true);
  try{
    let note='按当前库存缓存对比（未重新扫描）';
    if(rescan){shortageStatus('正在启动扫描…',0);await api('admin/scan','POST',{});note=await waitForScan();}
    shortageStatus('正在对比库存…',null);
    const r=await api('admin/shortage','POST',{items:shortageItems});await itemNames();
    shortageRows=r.items.map(i=>({name:itemName(i.itemId),id:i.itemId,shortage:i.shortage,needed:i.needed,available:i.available}));
    $('shortage-title').textContent='缺货清单 · '+shortageRows.length+' 种';
    table($('shortage-table'),['物品','缺少数量'],shortageRows.map(i=>[i.name,i.shortage+' 个'+stacks(i.shortage)]));
    if(!shortageRows.length){$('shortage-table').replaceChildren();const p=document.createElement('p');p.className='good';p.textContent='仓库库存足够，没有缺货';$('shortage-table').append(p);}
    $('shortage-copy').disabled=!shortageRows.length;
    shortageStatus(note+' · 对比 '+r.checked+' 种，缺货 '+shortageRows.length+' 种 · '+new Date().toLocaleTimeString(),null);
  }catch(e){shortageStatus('对比失败：'+e.message,null);toast(e.message,true);}
  finally{shortageBusy(false);}
}
$('shortage-rescan').addEventListener('click',()=>runShortage(true));
$('shortage-cached').addEventListener('click',()=>runShortage(false));
$('shortage-cancel').addEventListener('click',()=>{shortageStop=true;});
$('shortage-copy').addEventListener('click',async()=>{const text=shortageRows.map(i=>i.name+' '+i.shortage).join('\n');try{await navigator.clipboard.writeText(text);toast('已复制 '+shortageRows.length+' 种缺货物品');}catch(e){toast('复制失败，请手动选择表格复制',true);}});
