// Run the real browser script with a small DOM and HTTP fixture. Never uses production credentials.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element {
 constructor(){this.handlers={};this.dataset={};this.children=[];this.value='';this.hidden=false;this.classList={toggle(){}};}
 addEventListener(type,handler){(this.handlers[type]??=[]).push(handler);}append(...children){this.children.push(...children);}replaceChildren(...children){this.children=children;}focus(){}select(){this.selected=true;}contains(){return false;}
 async fire(type,event={}){for(const h of this.handlers[type]||[])await h({target:this,preventDefault(){},...event});}
}
const html=fs.readFileSync('src/main/resources/web/index.html','utf8'),elements=new Map([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
const pages=['overview','regions','orders','access'].map(page=>Object.assign(new Element(),{dataset:{page}})),views=pages.map(p=>Object.assign(new Element(),{dataset:{view:p.dataset.page},hidden:p.dataset.page!=='overview'}));
let copied='',fallback=0,url='http://example.com:23456';const calls=[];
let workflow={returnEntry:{x:80,y:50,z:-23},boxSupply:{x:129,y:53,z:-21},packingRoundSlots:25,maxCarriedParcels:25};
elements.get('workflow-form').elements=Object.fromEntries(Object.keys(workflow).map(key=>[key,new Element()]));
const context=vm.createContext({Node:Element,console,Date,Error,Number,JSON,Set,Map,Promise,crypto:require('node:crypto').webcrypto,
 document:{hidden:false,getElementById:id=>{assert(elements.has(id),`Missing DOM ${id}`);return elements.get(id);},createElement:()=>new Element(),querySelectorAll:selector=>selector==='[data-page]'?pages:selector==='[data-view]'?views:[],querySelector:selector=>selector.includes('data-view')?views[3]:pages[3],execCommand:()=>{fallback++;return true;}},
 sessionStorage:{getItem:()=> 'fixture-token',setItem(){},removeItem(){}},location:{reload(){}},navigator:{clipboard:{writeText:async text=>{copied=text;}}},setInterval(){},confirm:()=>true,
 FormData:class{constructor(form){this.form=form;}get(name){return this.form.data[name];}},
 fetch:async(path,options)=>{calls.push({path,options});let body={};if(path.endsWith('capabilities'))body={modVersion:'0.5.0',connectionState:'online',features:{}};else if(path.endsWith('dashboard'))body={regions:[],orders:[],scan:{},workflow};else if(path.endsWith('workflow')){workflow=JSON.parse(options.body);body={saved:true};}else if(path.endsWith('network'))body={state:url?'ready':'stopped',publicUrl:url,localUrl:'http://127.0.0.1:54321'};else if(path.endsWith('connect')){assert.deepEqual(JSON.parse(options.body),{});body={command:'/swrequest connect swc2.fixture',publicUrl:url};}return {ok:true,json:async()=>body};}
});
(async()=>{
 vm.runInContext(fs.readFileSync('src/main/resources/web/app.js','utf8'),context);await new Promise(setImmediate);await new Promise(setImmediate);
 const workflowForm=elements.get('workflow-form'),fields=workflowForm.elements;
 assert.equal(fields.returnEntry.value,'80,50,-23');assert.equal(fields.boxSupply.value,'129,53,-21');
 fields.returnEntry.value='12，64，-7';fields.boxSupply.value='20 65 -8';fields.packingRoundSlots.value='23';fields.maxCarriedParcels.value='20';await workflowForm.fire('submit');
 assert.deepEqual(workflow,{returnEntry:{x:12,y:64,z:-7},boxSupply:{x:20,y:65,z:-8},packingRoundSlots:23,maxCarriedParcels:20});
 fields.returnEntry.value='0,0,0';await vm.runInContext('load()',context);assert.equal(fields.returnEntry.value,'12,64,-7');
 await pages[3].fire('click');assert.match(elements.get('frp-status').textContent,/已连接/);
 const form=elements.get('connect-form');form.data={};await form.fire('submit');assert.equal(copied,'/swrequest connect swc2.fixture');assert.equal(elements.get('connect-command').value,copied);
 context.navigator.clipboard.writeText=async()=>{throw Error('Clipboard unavailable on public HTTP');};await elements.get('copy-connect').fire('click');assert.equal(fallback,1);assert(elements.get('connect-command').selected);
 url=null;await elements.get('frp-stop').fire('click');assert.equal(elements.get('connect-command').value,'');assert.match(elements.get('frp-status').textContent,/已停止/);
 assert(calls.some(c=>c.path.endsWith('admin/frp/stop')));assert(!calls.some(c=>c.path.includes('inventory')));
 console.log('SUPERWY_CONNECTION_WEB_OK: real app.js workflow coordinates and limits load/save/reload, scoped command generation, secure clipboard and public HTTP fallback, stale command cleared, no inventory polling');
})().catch(error=>{console.error(error);process.exitCode=1;});
