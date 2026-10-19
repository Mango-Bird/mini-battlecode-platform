import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { createHandler } from '../api/staging-bff.mjs';

const env={STAGING_BFF_MODE:'SAFE_STAGING_WEB_ONLY',STAGING_BACKEND_ORIGIN:'https://stage.onrender.com',STAGING_FRONTEND_ORIGIN:'https://stage.vercel.app',STAGING_PROXY_SECRET:'a0b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1'};
const deployment=JSON.parse(await readFile(new URL('../vercel.json',import.meta.url),'utf8'));
const bot='12345678-1234-1234-1234-123456789abc';

// Exercise both URL shapes accepted by the runtime adapter. Official Vercel
// compiler validation and the deployed smoke check are separate from this model.
function rewritten(path){
 const input=new URL('/api/staging/'+path,env.STAGING_FRONTEND_ORIGIN);
 const suffix=input.pathname.slice('/api/staging/'.length);
 const route=deployment.rewrites[0];
 return route.destination.replace(':__staging_path*',encodeURIComponent(suffix))+(input.search?'&'+input.search.slice(1):'');
}
async function invoke(url,{method='GET',headers={},body='',query,fetcher}={}){
 let calls=[];
 const req=Readable.from([Buffer.from(body)]);
 Object.assign(req,{url,method,query,headers:{host:'stage.vercel.app',...headers}});
 const res={headers:{},statusCode:0,setHeader(key,value){this.headers[key]=value;},end(value){this.body=String(value);}};
 await createHandler(env,async(target,options)=>{
  calls.push({target,options});return fetcher?fetcher(target,options):new Response('{}');
 })(req,res);
 return {res,calls};
}

test('deployment configs agree, use a static function and exclude API from SPA fallback',async()=>{
 assert.deepEqual(deployment,JSON.parse(await readFile(new URL('../vercel.staging.json',import.meta.url),'utf8')));
 assert.deepEqual(deployment.rewrites[0],{source:'/api/staging/:__staging_path*',destination:'/api/staging-bff?__staging_path=:__staging_path*'});
 await readFile(new URL('../api/staging-bff.mjs',import.meta.url));
 const spa=new RegExp('^'+deployment.rewrites[1].source+'$');
 for(const path of ['/api/staging/test','/api/staging/auth/csrf',`/api/staging/submissions/${bot}/validate`,'/api/staging-bff'])assert.equal(spa.test(path),false);
 for(const path of ['/','/login','/bot-workspace','/matches/'+bot])assert.equal(spa.test(path),true);
});

test('nested CSRF routes reach upstream and preserve JSON status and host-only cookies',async()=>{
 for(const url of ['/api/staging/auth/csrf',rewritten('auth/csrf')]){
  const {res,calls}=await invoke(url,{fetcher:async()=>new Response(JSON.stringify({csrfToken:'test-only-token'}),{headers:{'Set-Cookie':'__Host-battlecode_pre_auth=test; Path=/; Secure; HttpOnly; SameSite=Strict'}})});
  assert.equal(res.statusCode,200);assert.equal(calls[0].target,env.STAGING_BACKEND_ORIGIN+'/api/staging/auth/csrf');
  assert.equal(JSON.parse(res.body).csrfToken,'test-only-token');assert.match(res.headers['Content-Type'],/^application\/json/);
  assert.match(res.headers['Set-Cookie'][0],/Secure; HttpOnly; SameSite=Strict/);
 }
});

test('unauthenticated auth/me returns upstream JSON401 rather than a platform404',async()=>{
 const {res,calls}=await invoke(rewritten('auth/me'),{fetcher:async()=>new Response('{"error":"Authentication required"}',{status:401})});
 assert.equal(res.statusCode,401);assert.equal(calls[0].target,env.STAGING_BACKEND_ORIGIN+'/api/staging/auth/me');
 assert.equal(JSON.parse(res.body).error,'Authentication required');assert.match(res.headers['Content-Type'],/^application\/json/);
});

test('one, two and three segment GET paths retain canonical upstream URLs',async()=>{
 for(const path of ['submissions','rankings/glicko2','submissions/'+bot,'rankings/'+bot+'/history','matches/'+bot]){
  const {res,calls}=await invoke(rewritten(path));assert.equal(res.statusCode,200);
  assert.equal(calls[0].target,env.STAGING_BACKEND_ORIGIN+'/api/staging/'+path);
 }
});

test('POST rewrite preserves exact UTF-8 JSON bytes, cookies, CSRF and method',async()=>{
 const body=JSON.stringify({requestId:bot,expectedVersion:1,source:'// tiếng Việt\r\nfunction chooseMove(o) { return o.legalMoves[0]; }'});
 for(const path of ['auth/register','auth/login','submissions',`submissions/${bot}/validate`]){
  const {res,calls}=await invoke(rewritten(path),{method:'POST',body,headers:{origin:env.STAGING_FRONTEND_ORIGIN,'content-type':'application/json',cookie:'__Host-battlecode_pre_auth=test','x-csrf-token':'test-csrf'}});
  assert.equal(res.statusCode,200);const request=calls[0];assert.equal(request.options.method,'POST');
  assert.deepEqual(request.options.body,Buffer.from(body));assert.equal(request.options.headers.Cookie,'__Host-battlecode_pre_auth=test');
  assert.equal(request.options.headers['X-CSRF-Token'],'test-csrf');assert.equal(request.target,env.STAGING_BACKEND_ORIGIN+'/api/staging/'+path);
 }
});

test('public history query survives rewrite and internal query never reaches Render',async()=>{
 for(const page of ['0','1','200']){
  const {res,calls}=await invoke(rewritten('match-history?page='+page));assert.equal(res.statusCode,200);
  assert.equal(calls[0].target,env.STAGING_BACKEND_ORIGIN+'/api/staging/match-history?page='+page);
 }
 const {res,calls}=await invoke('/api/staging-bff',{query:{__staging_path:'match-history',page:'2'}});
 assert.equal(res.statusCode,200);assert.equal(calls[0].target,env.STAGING_BACKEND_ORIGIN+'/api/staging/match-history?page=2');
});

test('invalid queries, duplicate or conflicting routing parameters fail before fetch',async()=>{
 for(const url of [rewritten('auth/csrf?extra=1'),rewritten('match-history?page=201'),rewritten('match-history?page=1&page=2'),rewritten('match-history?%70age=1'),
  '/api/staging-bff?__staging_path=auth%2Fcsrf&__staging_path=auth%2Fme',
  '/api/staging/auth/csrf?__staging_path=auth%2Fme']){
  const {res,calls}=await invoke(url);assert.equal(res.statusCode,400,url);assert.equal(calls.length,0);
 }
 const conflict=await invoke(rewritten('auth/csrf'),{query:{__staging_path:'auth/me'}});assert.equal(conflict.res.statusCode,400);assert.equal(conflict.calls.length,0);
 const duplicate=await invoke('/api/staging-bff',{query:{__staging_path:['auth/csrf','auth/me']}});assert.equal(duplicate.res.statusCode,400);
});

test('rewritten unknown and execution routes still return BFF JSON404',async()=>{
 for(const path of ['test','ranked/queue','worker','execute','tournaments','submissions/'+bot+'/execute']){
  const {res,calls}=await invoke(rewritten(path));assert.equal(res.statusCode,404);assert.deepEqual(JSON.parse(res.body),{error:'Not found'});assert.equal(calls.length,0);
 }
});

test('rewrite cannot bypass Host, Origin, body size or method allowlist',async()=>{
 for(const options of [{headers:{host:'attacker.example'}},{headers:{origin:'https://attacker.example'}},{method:'POST'},{method:'PATCH',headers:{origin:env.STAGING_FRONTEND_ORIGIN}}]){
  const {res,calls}=await invoke(rewritten('auth/csrf'),options);assert.ok([403,404].includes(res.statusCode));assert.equal(calls.length,0);
 }
 const large=await invoke(rewritten('submissions'),{method:'POST',body:'x'.repeat(200001),headers:{origin:env.STAGING_FRONTEND_ORIGIN,'content-type':'application/json'}});
 assert.equal(large.res.statusCode,413);assert.equal(large.calls.length,0);
});
