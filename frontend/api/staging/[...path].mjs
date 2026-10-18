import { createHmac } from 'node:crypto';
export const config = { api: { bodyParser: false }, maxDuration: 60 };
const allowed = (method, path) => ({
  GET: /^(auth\/(csrf|me)|submissions|submissions\/[a-f0-9-]{36}|match-history|matches\/[a-f0-9-]{36}|bot-profiles\/[a-f0-9-]{36}|rankings|rankings\/glicko2|rankings\/[a-f0-9-]{36}\/history)$/,
  POST: /^(auth\/(register|login|logout)|submissions|submissions\/[a-f0-9-]{36}\/validate)$/,
  PUT: /^submissions\/[a-f0-9-]{36}$/, DELETE: /^submissions\/[a-f0-9-]{36}$/,
}[method]?.test(path) ?? false);
function configuration(env) {
  if (env.STAGING_BFF_MODE !== 'SAFE_STAGING_WEB_ONLY') throw new Error('Disabled');
  const backend = new URL(env.STAGING_BACKEND_ORIGIN), frontend = new URL(env.STAGING_FRONTEND_ORIGIN);
  for (const url of [backend, frontend]) if (url.protocol !== 'https:' || !url.hostname.includes('.') || url.username || url.password || url.pathname !== '/' || url.search || url.hash || url.port || url.hostname.endsWith('.localhost') || /^[0-9.]+$/.test(url.hostname)) throw new Error('Configuration');
  const secret = env.STAGING_PROXY_SECRET;
  if (!secret || !/^[A-Za-z0-9_-]{43,128}$/.test(secret) || new Set(secret).size < 12 || /(replace|example|password|change)/i.test(secret)) throw new Error('Configuration');
  return { backend, frontend, secret };
}
// Injectable transport for tests. Deployed entrypoint always uses process.env and native fetch.
export function createHandler(env, fetcher = fetch) {
  return async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');res.setHeader('X-Content-Type-Options','nosniff');
    const fail = (status, message) => { res.statusCode = status;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({error:message})); };
    try {
      const { backend, frontend, secret } = configuration(env);
      if (req.headers.host !== frontend.host || (req.headers.origin && req.headers.origin !== frontend.origin)) return fail(403,'Request denied');
      if (req.method !== 'GET' && req.headers.origin !== frontend.origin) return fail(403,'Origin required');
      const input = new URL(req.url, frontend.origin);
      if (!input.pathname.startsWith('/api/staging/') || input.pathname.includes('%') || input.pathname.includes('//')) return fail(404,'Not found');
      const path = input.pathname.slice('/api/staging/'.length);
      if (!allowed(req.method,path)) return fail(404,'Not found');
      if (input.search && !(path === 'match-history' && /^\?page=(0|[1-9][0-9]{0,2})$/.test(input.search) && Number(input.searchParams.get('page')) <= 200)) return fail(400,'Invalid query');
      const headers = { Origin:frontend.origin, 'X-Staging-Proxy-Key':secret, 'X-Staging-Proto':'https' };
      // Vercel owns this platform header. Browser X-Forwarded-For is never used.
      const client = String(req.headers['x-vercel-forwarded-for'] ?? 'unknown').slice(0,128);
      headers['X-Staging-Client'] = createHmac('sha256',secret).update(client).digest('hex');
      if (req.headers.cookie) headers.Cookie = String(req.headers.cookie);
      if (req.headers['x-csrf-token']) headers['X-CSRF-Token'] = String(req.headers['x-csrf-token']);
      let body;
      if (req.method !== 'GET') {
        if (req.headers['content-type'] !== 'application/json') return fail(400,'JSON required');
        const chunks=[];let size=0;
        for await (const chunk of req) { size+=Buffer.byteLength(chunk);if(size>200000)return fail(413,'Request too large');chunks.push(Buffer.from(chunk)); }
        body=Buffer.concat(chunks);headers['Content-Type']='application/json';
      }
      const response = await fetcher(backend.origin + '/api/staging/' + path + input.search, {
        method:req.method, headers, body, redirect:'error', signal:AbortSignal.timeout(55000),
      });
      const chunks=[];let bytes=0;
      if(response.body)for await(const chunk of response.body){bytes+=chunk.length;if(bytes>4194304)throw new Error('Response limit');chunks.push(Buffer.from(chunk));}
      const cookies=response.headers.getSetCookie();
      if(cookies.length)res.setHeader('Set-Cookie',cookies);
      res.statusCode=response.status;res.setHeader('Content-Type','application/json; charset=utf-8');
      const requestId=response.headers.get('X-Request-ID');if(requestId&&/^[a-f0-9-]{36}$/.test(requestId))res.setHeader('X-Request-ID',requestId);
      res.end(Buffer.concat(chunks));
    } catch { fail(503,'Staging API temporarily unavailable. Please retry.'); }
  };
}
export default async function handler(req,res){return createHandler(process.env)(req,res);}
