package dev.battlecode.staging;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import dev.battlecode.botmatch.persistence.*;
import dev.battlecode.botmatch.rating.RatingService;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Safe route composition: no arena, engine, coordinator, runner, worker factory or TCP listener. */
public final class StagingHttpServer implements AutoCloseable {
    static final String COOKIE="__Host-battlecode_session", NONCE="__Host-battlecode_pre_auth";
    private final HttpServer http;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
        Thread.ofPlatform().name("safe-staging-http-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final StagingConfig config;
    private final Database db;
    private final AuthService auth;
    private final PostgresSubmissionRepository bots;
    private final CompetitiveReadService reads;
    private final Csrf csrf;
    private final ObjectMapper json=PersistenceJson.mapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AtomicLong requests=new AtomicLong(), failures=new AtomicLong(), authFailures=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();
    public StagingHttpServer(StagingConfig config,Database db,InetSocketAddress address) throws IOException {
        this.config=config;this.db=db;auth=new AuthService(db);bots=new PostgresSubmissionRepository(db);reads=new CompetitiveReadService(db);
        csrf=new Csrf(config.csrfSecret,Clock.systemUTC());
        json.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(32768).build());
        http=HttpServer.create(address,16);http.setExecutor(executor);http.createContext("/",this::handle);
    }
    public void start(){http.start();}
    public int port(){return http.getAddress().getPort();}
    /** Fixed route patterns checked before DB or business calls. No fallback to local controllers. */
    static boolean allowed(String method,String route){
        return switch(method){
            case "GET" -> route.matches("auth/(csrf|me)|submissions|submissions/[a-f0-9-]{36}|match-history|matches/[a-f0-9-]{36}|bot-profiles/[a-f0-9-]{36}|rankings|rankings/glicko2|rankings/[a-f0-9-]{36}/history");
            case "POST" -> route.matches("auth/(register|login|logout)|submissions|submissions/[a-f0-9-]{36}/validate");
            case "PUT","DELETE" -> route.matches("submissions/[a-f0-9-]{36}");
            default -> false;
        };
    }
    private void handle(HttpExchange x){String requestId=UUID.randomUUID().toString();int status=500;long start=System.nanoTime();requests.incrementAndGet();
        try(x){x.getResponseHeaders().set("X-Request-ID",requestId);x.getResponseHeaders().set("Cache-Control","no-store");
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
            try{
                String path=x.getRequestURI().getRawPath(),method=x.getRequestMethod();
                if(method.equals("GET")&&path.equals("/healthz")){status=200;send(x,status,Map.of("status","ok"));return;}
                if(method.equals("GET")&&path.equals("/readyz")){ready();status=200;send(x,status,Map.of("status","ready"));return;}
                verifyProxy(x);
                if(!path.startsWith("/api/staging/")||path.contains("%")||path.contains("//"))throw new NoSuchElementException();
                String route=path.substring("/api/staging/".length());
                if(method.equals("OPTIONS")){
                    String requested=single(x,"Access-Control-Request-Method");
                    if(!allowed(requested==null?"":requested,route))throw new NoSuchElementException();
                    String headers=single(x,"Access-Control-Request-Headers");
                    if(headers!=null)for(String header:headers.toLowerCase(Locale.ROOT).split(","))
                        if(!Set.of("content-type","x-csrf-token").contains(header.trim()))throw new SecurityException();
                    cors(x);x.getResponseHeaders().set("Access-Control-Allow-Methods",requested);
                    x.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type, X-CSRF-Token");status=204;x.sendResponseHeaders(status,-1);return;
                }
                if(!allowed(method,route))throw new NoSuchElementException();
                cors(x);String query=x.getRequestURI().getRawQuery();int page=0;
                if(query!=null){if(!route.equals("match-history")||!query.matches("page=(0|[1-9][0-9]{0,2})"))throw new IllegalArgumentException();page=Integer.parseInt(query.substring(5));if(page>200)throw new IllegalArgumentException();}
                ready();String session=cookie(x,COOKIE);
                if(route.equals("auth/csrf")){
                    if(session!=null)try{auth.require(session);}catch(AuthService.Unauthorized expired){cookie(x,COOKIE,"",0);session=null;}
                    String nonce=cookie(x,NONCE);if(nonce==null||!nonce.matches("[a-f0-9]{64}")){nonce=Csrf.random();cookie(x,NONCE,nonce,1800);}
                    status=200;send(x,status,Map.of("csrfToken",csrf.issue(session==null?"pre:"+nonce:"session:"+session)));return;
                }
                JsonNode body=null;
                if(!method.equals("GET")){
                    String binding=session==null?"pre:"+cookie(x,NONCE):"session:"+session;
                    if(!csrf.valid(binding,single(x,"X-CSRF-Token")))throw new SecurityException();
                    body=body(x);
                }
                if(route.equals("auth/register")||route.equals("auth/login")){
                    fields(body,Set.of("username","password"));String op=route.substring(5);
                    // Authenticated proxy controls this bounded pseudonymous client key. Never trust X-Forwarded-For.
                    String client=single(x,"X-Staging-Client");if(client==null||!client.matches("[a-f0-9]{64}"))throw new SecurityException();
                    auth.throttle(client,op);auth.throttle("global",op);
                    if(op.equals("register")){status=201;send(x,status,Map.of("user",auth.register(text(body,"username"),text(body,"password"))));return;}
                    var login=auth.login(text(body,"username"),text(body,"password"));cookie(x,COOKIE,login.token(),28800);cookie(x,NONCE,"",0);
                    status=200;send(x,status,Map.of("user",login.user(),"expiresAt",login.expiresAt()));return;
                }
                var user=auth.require(session);UUID owner=user.id();Object response;
                if(route.equals("auth/me"))response=Map.of("user",user);
                else if(route.equals("auth/logout")){fields(body,Set.of());auth.logout(session);cookie(x,COOKIE,"",0);cookie(x,NONCE,"",0);response=Map.of("loggedOut",true);}
                else if(route.equals("submissions")){
                    if(method.equals("GET"))response=Map.of("submissions",list(owner));
                    else{fields(body,Set.of("requestId","name","source"));response=bots.createOwned(owner,id(body,"requestId"),text(body,"name"),text(body,"source"));}
                } else if(route.startsWith("submissions/")){
                    UUID bot=uuid(route.split("/")[1]);if(!owner.equals(bots.ownerOf(bot)))throw new SecurityException();
                    if(method.equals("GET"))response=bots.get(bot);
                    else if(method.equals("PUT")){fields(body,Set.of("requestId","expectedVersion","name","source"));response=bots.saveOwned(owner,id(body,"requestId"),bot,version(body),text(body,"name"),text(body,"source"));}
                    else{fields(body,Set.of("requestId","expectedVersion"));response=method.equals("DELETE")?bots.deleteOwned(owner,id(body,"requestId"),bot,version(body)):bots.validateOwned(owner,id(body,"requestId"),bot,version(body));}
                }else if(route.equals("match-history"))response=Map.of("matches",reads.history(owner,page),"page",page);
                else if(route.startsWith("matches/"))response=reads.match(owner,uuid(route.substring(8)));
                else if(route.startsWith("bot-profiles/")){
                    UUID bot=uuid(route.substring(13));var profile=new LinkedHashMap<>(reads.profile(owner,bot));
                    profile.put("history",new RatingService(db).history(bot));profile.put("recentMatches",reads.history(owner).stream().filter(m->bot.equals(m.a().botId())||bot.equals(m.b().botId())).limit(10).toList());response=profile;
                }else if(route.endsWith("/history")){
                    UUID bot=uuid(route.split("/")[1]);reads.profile(owner,bot);response=Map.of("events",new RatingService(db).history(bot),"periods",List.of());
                }else response=Map.of("rankings",new RatingService(db).leaderboardSnapshot("ott-board-9x9-v1","ott-board-9x9-v1",route.equals("rankings/glicko2")));
                status=method.equals("POST")&&route.equals("submissions")?201:200;send(x,status,response);
            }catch(AuthService.Unauthorized denied){authFailures.incrementAndGet();status=401;send(x,status,Map.of("error","Authentication required or invalid credentials"));}
            catch(AuthService.RateLimited limited){status=429;x.getResponseHeaders().set("Retry-After","60");send(x,status,Map.of("error","Too many authentication attempts"));}
            catch(SecurityException denied){status=403;send(x,status,Map.of("error","Request denied"));}
            catch(NoSuchElementException missing){status=404;send(x,status,Map.of("error","Not found"));}
            catch(TooLarge large){status=413;send(x,status,Map.of("error","Request too large"));}
            catch(IllegalArgumentException invalid){status=400;send(x,status,Map.of("error","Invalid request"));}
            catch(Database.Unavailable unavailable){status=503;System.out.println("{\"event\":\"database_unavailable\",\"requestId\":\""+requestId+"\"}");send(x,status,Map.of("error","Database temporarily unavailable"));}
            catch(IllegalStateException conflict){status=409;send(x,status,Map.of("error","Operation conflicts with current state or capacity"));}
            catch(Exception internal){status=500;send(x,status,Map.of("error","Request failed"));}
        }catch(IOException ignored){}finally{if(status>=400)failures.incrementAndGet();
            System.out.println("{\"event\":\"request\",\"requestId\":\""+requestId+"\",\"status\":"+status+",\"durationMs\":"+(System.nanoTime()-start)/1000000+"}");}
    }
    private void ready(){try{db.reconnectLease();}catch(IllegalStateException leaseConflict){throw new Database.Unavailable();}db.transaction(c->{
        try(var s=c.createStatement();var r=s.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")){if(!r.next()||!"5".equals(r.getString(1)))throw new Database.Unavailable();}
        try(var s=c.createStatement();var r=s.executeQuery("SELECT rolsuper OR rolcreaterole OR rolcreatedb OR has_schema_privilege(current_schema(),'CREATE') OR has_table_privilege('rating_profiles','INSERT,UPDATE,DELETE') OR has_table_privilege('bot_versions','UPDATE,DELETE') FROM pg_roles WHERE rolname=current_user")){
            if(!r.next()||r.getBoolean(1))throw new Database.Unavailable();
        }return null;});}
    private void verifyProxy(HttpExchange x){String secret=single(x,"X-Staging-Proxy-Key");
        if(secret==null||!MessageDigest.isEqual(config.proxySecret.getBytes(StandardCharsets.US_ASCII),secret.getBytes(StandardCharsets.US_ASCII))
            ||!config.host.equals(single(x,"Host"))||!"https".equals(single(x,"X-Staging-Proto"))
            ||!config.origin.equals(single(x,"Origin")))throw new SecurityException();
    }
    private void cors(HttpExchange x){x.getResponseHeaders().set("Access-Control-Allow-Origin",config.origin);x.getResponseHeaders().set("Access-Control-Allow-Credentials","true");x.getResponseHeaders().set("Vary","Origin");}
    private List<?> list(UUID owner){List<UUID> ids=db.transaction(c->{var result=new ArrayList<UUID>();try(var p=c.prepareStatement("SELECT id FROM bots WHERE owner_id=? AND deleted_at IS NULL ORDER BY created_at,id LIMIT 32")){p.setObject(1,owner);try(var r=p.executeQuery()){while(r.next())result.add(r.getObject(1,UUID.class));}}return List.copyOf(result);});return ids.stream().map(id->bots.get(id).metadata()).toList();}
    static String single(HttpExchange x,String name){var values=x.getRequestHeaders().get(name);if(values==null)return null;if(values.size()!=1)throw new SecurityException();return values.getFirst();}
    private static String cookie(HttpExchange x,String name){String found=null;for(String header:x.getRequestHeaders().getOrDefault("Cookie",List.of()))for(String part:header.split(";")){String[] p=part.trim().split("=",2);if(p.length==2&&p[0].equals(name)){if(found!=null)throw new SecurityException();found=p[1];}}return found;}
    private static void cookie(HttpExchange x,String name,String value,int age){x.getResponseHeaders().add("Set-Cookie",name+"="+value+"; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age="+age);}
    private JsonNode body(HttpExchange x)throws IOException{
        if(!"application/json".equalsIgnoreCase(single(x,"Content-Type")))throw new IllegalArgumentException();
        byte[] bytes=x.getRequestBody().readNBytes(200001);if(bytes.length>200000)throw new TooLarge();
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node=json.readTree(text);if(node==null||!node.isObject())throw new IllegalArgumentException();return node;
        }catch(IOException invalid){throw new IllegalArgumentException();}
    }
    private static void fields(JsonNode body,Set<String> keys){var actual=new HashSet<String>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(keys))throw new IllegalArgumentException();}
    private static String text(JsonNode body,String key){if(!body.path(key).isTextual())throw new IllegalArgumentException();return body.path(key).textValue();}
    private static UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
    private static UUID id(JsonNode body,String key){return uuid(text(body,key));}
    private static int version(JsonNode body){var v=body.path("expectedVersion");if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<1||v.intValue()>16)throw new IllegalArgumentException();return v.intValue();}
    private void send(HttpExchange x,int code,Object value)throws IOException{byte[] data=json.writeValueAsBytes(value);if(data.length>4194304)throw new IOException("Response limit");x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(code,data.length);x.getResponseBody().write(data);}
    public void diagnostics(){System.out.println("{\"event\":\"runtime\",\"requests\":"+requests.get()+",\"failures\":"+failures.get()+",\"authFailures\":"+authFailures.get()+",\"heapUsedBytes\":"+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+"}");}
    @Override public void close(){if(closed.compareAndSet(false,true)){http.stop(2);executor.shutdown();try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException interrupted){executor.shutdownNow();Thread.currentThread().interrupt();}}}
    private static class TooLarge extends RuntimeException{}
}
