import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { createHandler } from '../api/staging/[...path].mjs';
const env={STAGING_BFF_MODE:'SAFE_STAGING_WEB_ONLY',STAGING_BACKEND_ORIGIN:'https://stage.onrender.com',STAGING_FRONTEND_ORIGIN:'https://stage.vercel.app',STAGING_PROXY_SECRET:'a0b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1'};
async function request(path,method='GET',headers={},body='',fetcher=async()=>new Response('{}')){
 const req=Readable.from([body]);Object.assign(req,{url:'/api/staging/'+path,method,headers:{host:'stage.vercel.app',...headers}});
 const res={statusCode:0,headers:{},setHeader(k,v){this.headers[k]=v;},end(v){this.body=String(v);}};
 await createHandler(env,fetcher)(req,res);return res;
}
test('proxy refuses execution, unknown paths and unsupported methods before fetch',async()=>{
 for(const path of ['execute','battles','ranked/queue','tournaments','worker','../local/bots','%2e%2e/local/bots']){
  const result=await request(path,'POST',{origin:env.STAGING_FRONTEND_ORIGIN},'{}',()=>{throw new Error('must not fetch');});assert.equal(result.statusCode,404);
 }
});
test('proxy rejects hostile origin, absent mutation Origin and spoofed host',async()=>{
 assert.equal((await request('auth/me','GET',{origin:'https://attacker.example'})).statusCode,403);
 assert.equal((await request('auth/login','POST',{'content-type':'application/json'})).statusCode,403);
 assert.equal((await request('auth/me','GET',{host:'spoofed.example'})).statusCode,403);
});
test('proxy overwrites security headers and forwards host-only cookies without exposing shared key',async()=>{
 let forwarded;
 const res=await request('auth/login','POST',{origin:env.STAGING_FRONTEND_ORIGIN,'content-type':'application/json',cookie:'__Host-battlecode_pre_auth=nonce','x-csrf-token':'token','x-staging-proxy-key':'attacker'},'{}',async(url,options)=>{
  forwarded=options;assert.equal(url,'https://stage.onrender.com/api/staging/auth/login');
  return new Response('{}',{headers:{'Set-Cookie':'__Host-battlecode_session=session; Secure; HttpOnly; SameSite=Strict; Path=/'}});
 });
 assert.equal(res.statusCode,200);assert.equal(forwarded.headers['X-Staging-Proxy-Key'],env.STAGING_PROXY_SECRET);
 assert.equal(forwarded.headers['X-Staging-Proto'],'https');assert.equal(forwarded.redirect,'error');
 assert.match(res.headers['Set-Cookie'][0],/Secure; HttpOnly/);assert.equal(res.headers['Cache-Control'],'no-store');
 assert.ok(!JSON.stringify(res).includes(env.STAGING_PROXY_SECRET));
});
test('proxy bounds payloads and hides transport errors',async()=>{
 assert.equal((await request('submissions','POST',{origin:env.STAGING_FRONTEND_ORIGIN,'content-type':'application/json'},'x'.repeat(200001))).statusCode,413);
 const res=await request('auth/me','GET',{},'',async()=>{throw new Error('sensitive credentials');});assert.equal(res.statusCode,503);assert.ok(!res.body.includes('sensitive'));
});
