package dev.battlecode.botmatch;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.battlecode.rulesimpl.board.Board;
import dev.battlecode.botrunner.*;
import dev.battlecode.botmatch.tournament.*;
import dev.battlecode.botmatch.persistence.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent local REST transport. Does not alter TCP 1.0 or web-1. */
public final class LocalArenaHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final LocalArenaService arena;
    private final SubmissionRepository submissions;
    private final AuthService auth;
    private final Database database;
    private final BattleHistoryService battleHistory;
    private final dev.battlecode.botmatch.ranked.MatchmakingService matchmaking;
    private final dev.battlecode.botmatch.ranked.RankedMatchCoordinator rankedCoordinator;
    private final LocalWorkspaceSessions sessions=new LocalWorkspaceSessions();
    private final boolean sourcePrivacy;
    private final TournamentService tournaments;
    private final Set<String> origins;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),Thread.ofPlatform().daemon(true).name("local-arena-http-").factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(32768).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addSerializer(java.time.Instant.class,
            new com.fasterxml.jackson.databind.JsonSerializer<java.time.Instant>() {
                public void serialize(java.time.Instant value,JsonGenerator generator,SerializerProvider provider) throws IOException {generator.writeString(value.toString());}
            }));
    public LocalArenaHttpServer(boolean localDevelopment,int port,Set<String> origins,LocalArenaService arena) throws IOException {
        this(localDevelopment,port,origins,arena,false);
    }
    public LocalArenaHttpServer(boolean localDevelopment,int port,Set<String> origins,LocalArenaService arena,boolean sourcePrivacy) throws IOException {
        this(localDevelopment,port,origins,arena,sourcePrivacy,null);
    }
    public LocalArenaHttpServer(boolean localDevelopment,int port,Set<String> origins,LocalArenaService arena,boolean sourcePrivacy,Database database) throws IOException {
        dev.battlecode.botmatch.production.ProductionPreflight.requireLocalRuntime(System.getenv());
        if(!localDevelopment)throw new IllegalArgumentException("Explicit local development required");
        if(origins.isEmpty())throw new IllegalArgumentException("Origin allowlist required");
        for(String origin:origins) {
            URI uri=URI.create(origin);
            if(!"http".equals(uri.getScheme())||!Set.of("localhost","127.0.0.1").contains(uri.getHost())
                ||uri.getPort()<1||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getUserInfo()!=null
                ||!(uri.getPath()==null||uri.getPath().isEmpty()))throw new IllegalArgumentException("Only exact loopback development origins allowed");
        }
        this.origins=Set.copyOf(origins);this.arena=arena;this.sourcePrivacy=sourcePrivacy;this.database=database;
        if(database!=null&&!sourcePrivacy)throw new IllegalArgumentException("Account persistence requires source authorization");
        this.submissions=database==null?new LocalSubmissionStore():new PostgresSubmissionRepository(database);
        this.auth=database==null?null:new AuthService(database);
        this.tournaments=database==null?new TournamentService(arena):new TournamentService(arena,new PostgresTournamentRepository(database),submissions);
        this.battleHistory=database==null?null:new BattleHistoryService(database,arena);
        this.matchmaking=database==null?null:new dev.battlecode.botmatch.ranked.MatchmakingService(database,submissions,arena::userBotsExecutable);
        if(battleHistory!=null)arena.observeManualSnapshots(battleHistory::record);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);
        server.setExecutor(workers);server.createContext("/api/local/",this::handle);
        try{this.rankedCoordinator=matchmaking==null?null:new dev.battlecode.botmatch.ranked.RankedMatchCoordinator(arena,submissions,matchmaking);}
        catch(RuntimeException failure){server.stop(0);workers.shutdownNow();throw failure;}
    }
    public void start() {server.start();}
    public int port() {return server.getAddress().getPort();}
    private void handle(HttpExchange exchange) {
        try(exchange) {
          try {
            String host=exchange.getRequestHeaders().getFirst("Host");
            if(!Set.of("127.0.0.1:"+port(),"localhost:"+port()).contains(host==null?"":host)) {send(exchange,403,Map.of("error","Invalid Host"));return;}
            String origin=exchange.getRequestHeaders().getFirst("Origin");
            if(!origins.contains(origin==null?"":origin)) {send(exchange,403,Map.of("error","Origin required/denied"));return;}
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin",origin);
            exchange.getResponseHeaders().set("Vary","Origin");
            if(auth!=null)exchange.getResponseHeaders().set("Access-Control-Allow-Credentials","true");
            if(exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, PUT, DELETE");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type, X-Battlecode-Workspace");
                exchange.sendResponseHeaders(204,-1);return;
            }
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            int historyPage=0;String query=exchange.getRequestURI().getRawQuery();
            if(query!=null){if(!path.equals("/api/local/match-history")||!query.matches("page=(0|[1-9][0-9]{0,2})"))throw new IllegalArgumentException("Query parameters");historyPage=Integer.parseInt(query.substring(5));if(historyPage>200)throw new IllegalArgumentException("History page");}
            if(path.startsWith("/api/local/auth/")){handleAuth(exchange,path,method);return;}
            if(path.equals("/api/local/match-history")||path.startsWith("/api/local/matches/")||path.startsWith("/api/local/bot-profiles/")){
                if(database==null||auth==null)throw new AuthService.Unauthorized();UUID viewer=identity(exchange);var read=new CompetitiveReadService(database);
                if(method.equals("GET")&&path.equals("/api/local/match-history")){send(exchange,200,Map.of("matches",read.history(viewer,historyPage),"page",historyPage));return;}
                if(method.equals("GET")&&path.startsWith("/api/local/matches/")){send(exchange,200,read.match(viewer,UUID.fromString(path.substring("/api/local/matches/".length()))));return;}
                if(method.equals("GET")&&path.startsWith("/api/local/bot-profiles/")){UUID bot=UUID.fromString(path.substring("/api/local/bot-profiles/".length()));var result=new LinkedHashMap<>(read.profile(viewer,bot));result.put("history",new dev.battlecode.botmatch.rating.RatingService(database).history(bot));result.put("recentMatches",read.history(viewer).stream().filter(m->bot.equals(m.a().botId())||bot.equals(m.b().botId())).limit(10).toList());send(exchange,200,result);return;}
                send(exchange,404,Map.of("error","Endpoint not found"));return;
            }
            if(path.startsWith("/api/local/ranked/")){
                if(auth==null||matchmaking==null)throw new AuthService.Unauthorized();UUID owner=identity(exchange);
                if(path.equals("/api/local/ranked/queue")&&method.equals("POST")){
                    var data=tournamentBody(exchange);fields(data,Set.of("requestId","botId","version","sourceSha256"));
                    send(exchange,201,matchmaking.join(owner,id(data,"requestId"),id(data,"botId"),expectedSelection(data),text(data,"sourceSha256")));return;}
                if(path.equals("/api/local/ranked/queue/me")&&method.equals("GET")){var entry=matchmaking.me(owner);var response=new LinkedHashMap<String,Object>();response.put("entry",entry);response.put("ratings",matchmaking.ratings(owner));send(exchange,200,response);return;}
                if(path.equals("/api/local/ranked/queue/me")&&method.equals("DELETE")){var data=tournamentBody(exchange);fields(data,Set.of("requestId"));send(exchange,200,matchmaking.cancel(owner,id(data,"requestId")));return;}
                if(path.equals("/api/local/ranked/matches")&&method.equals("GET")){send(exchange,200,Map.of("matches",matchmaking.matches(owner)));return;}
                if(path.startsWith("/api/local/ranked/matches/")&&method.equals("GET")){send(exchange,200,matchmaking.match(owner,UUID.fromString(path.substring("/api/local/ranked/matches/".length()))));return;}
                send(exchange,404,Map.of("error","Endpoint not found"));return;
            }
            if(path.startsWith("/api/local/rankings")&&method.equals("GET")){
                if(database==null)throw new IllegalStateException("PostgreSQL required");
                identity(exchange);
                var ratings=new dev.battlecode.botmatch.rating.RatingService(database);
                if(path.equals("/api/local/rankings")||path.equals("/api/local/rankings/glicko2")){
                    send(exchange,200,Map.of("rankings",ratings.leaderboard("ott-board-9x9-v1","ott-board-9x9-v1",path.endsWith("glicko2")),"policyVersion","rating-v1","gameMode","ott-board-9x9-v1","rulesVersion","ott-board-9x9-v1"));return;}
                String[] ratingPath=path.split("/");
                if(ratingPath.length==6&&ratingPath[5].equals("history")){UUID bot=UUID.fromString(ratingPath[4]);send(exchange,200,Map.of("events",ratings.history(bot),"periods",ratings.periodHistory(bot)));return;}
                send(exchange,404,Map.of("error","Endpoint not found"));return;
            }
            if(path.equals("/api/local/workspace-session")&&method.equals("GET")) {
                if(auth!=null)throw new AuthService.Unauthorized();
                send(exchange,200,Map.of("workspaceToken",sessions.issue(exchange.getRequestHeaders().getFirst("X-Battlecode-Workspace"))));return;
            }
            if(path.equals("/api/local/execution-capabilities")&&method.equals("GET")) {
                send(exchange,200,arena.executionCapabilities());return;
            }
            if(path.equals("/api/local/tournaments")||path.startsWith("/api/local/tournaments/")) {
                handleTournament(exchange,path,method);return;
            }
            if(path.equals("/api/local/submissions")||path.startsWith("/api/local/submissions/")) {
                handleSubmission(exchange,path,method);return;
            }
            if(path.equals("/api/local/bots")&&method.equals("GET")) {
                send(exchange,200,Map.of("bots",LocalArenaService.BOTS,"initialBoard",Board.initial().pieces(),"submissions",
                    submissions.snapshots().stream().map(this::catalogView).toList(),"execution",arena.executionCapabilities()));return;
            }
            if(path.equals("/api/local/battles")&&method.equals("POST")) {
                UUID owner=auth!=null?identity(exchange):null;
                if(!"application/json".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Type")))throw new IllegalArgumentException("application/json required");
                byte[] body=exchange.getRequestBody().readNBytes(2049);
                if(body.length>2048) {send(exchange,413,Map.of("error","Payload too large"));return;}
                JsonNode data=json.readTree(body);
                fields(data,Set.of("botA","botB","requestId"));
                var request=scopedRequest(id(data,"requestId"),owner);
                java.util.function.Supplier<LocalArenaService.Snapshot> launch;
                if(data.get("botA").isTextual()&&data.get("botB").isTextual())
                    launch=()->arena.start(text(data,"botA"),text(data,"botB"),request);
                else {
                    if(!sourcePrivacy||!arena.userBotsExecutable()) {send(exchange,409,Map.of("error","User bot execution refused: mandatory sandbox/ownership gate not verified"));return;}
                    launch=()->arena.startSaved(selection(data.get("botA")),selection(data.get("botB")),request);
                }
                var snapshot=battleHistory==null?launch.get():battleHistory.start(owner,request,selectionKey(data.get("botA"))+":"+selectionKey(data.get("botB")),launch);
                send(exchange,201,snapshot);return;
            }
            if(path.equals("/api/local/battles")&&method.equals("GET")&&battleHistory!=null){send(exchange,200,Map.of("battles",battleHistory.listOwned(identity(exchange))));return;}
            String[] parts=path.split("/");
            if(method.equals("GET")&&parts.length>=5&&parts[3].equals("battles")) {
                var snapshot=battleHistory==null?arena.get(UUID.fromString(parts[4])):battleHistory.getOwned(identity(exchange),UUID.fromString(parts[4]));
                if(parts.length==5) {send(exchange,200,snapshot);return;}
                if(parts.length==6&&parts[5].equals("moves")) {send(exchange,200,Map.of("id",snapshot.id(),"moves",snapshot.moves(),"frames",snapshot.frames()));return;}
            }
            send(exchange,404,Map.of("error","Endpoint not found"));
          }catch(AuthService.Unauthorized e) {send(exchange,401,Map.of("error","Authentication required or invalid credentials"));}
          catch(AuthService.RateLimited e) {exchange.getResponseHeaders().set("Retry-After","60");send(exchange,429,Map.of("error","Too many authentication attempts"));}
          catch(Database.Unavailable e) {send(exchange,503,Map.of("error","Database unavailable; operation refused"));}
          catch(PayloadTooLarge e) {send(exchange,413,Map.of("error","Payload too large"));}
          catch(SecurityException e) {send(exchange,403,Map.of("error","Workspace source access denied"));}
          catch(NoSuchElementException e) {send(exchange,404,Map.of("error","Resource not found"));}
          catch(IllegalStateException e) {send(exchange,409,Map.of("error","Conflict or capacity reached; reload latest state"));}
          catch(IllegalArgumentException|JsonProcessingException|java.time.DateTimeException e) {send(exchange,400,Map.of("error","Invalid request; check fields, UTF-8 and limits"));}
          catch(Exception e) {send(exchange,503,Map.of("error","Local runner unavailable"));}
        }catch(Exception ignored) { /* Transport errors must not affect a match. */ }
    }
    private UUID identity(HttpExchange exchange){return auth==null?sessions.identity(exchange.getRequestHeaders().getFirst("X-Battlecode-Workspace")):auth.require(cookie(exchange)).id();}
    private static String cookie(HttpExchange exchange){
        String found=null;for(String header:exchange.getRequestHeaders().getOrDefault("Cookie",List.of()))for(String part:header.split(";")){
            String[] pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals("battlecode_session")){if(found!=null)throw new AuthService.Unauthorized();found=pair[1];}
        }return found;
    }
    private void handleAuth(HttpExchange exchange,String path,String method)throws IOException {
        if(auth==null){send(exchange,503,Map.of("error","Start the PostgreSQL-backed local application"));return;}
        if(path.equals("/api/local/auth/me")&&method.equals("GET")){send(exchange,200,Map.of("user",auth.require(cookie(exchange))));return;}
        if(path.equals("/api/local/auth/logout")&&method.equals("POST")){
            fields(tournamentBody(exchange),Set.of());auth.logout(cookie(exchange));
            exchange.getResponseHeaders().add("Set-Cookie","battlecode_session=; Path=/api/local/; HttpOnly; SameSite=Strict; Max-Age=0");send(exchange,200,Map.of("loggedOut",true));return;
        }
        if(method.equals("POST")&&(path.equals("/api/local/auth/register")||path.equals("/api/local/auth/login"))){
            String operation=path.endsWith("register")?"register":"login";auth.throttle(exchange.getRemoteAddress().getAddress().getHostAddress(),operation);
            var data=tournamentBody(exchange);fields(data,Set.of("username","password"));
            if(operation.equals("register")){send(exchange,201,Map.of("user",auth.register(text(data,"username"),text(data,"password"))));return;}
            var login=auth.login(text(data,"username"),text(data,"password"));
            // HTTP loopback only. HTTPS deployment is deliberately unavailable in this transport.
            exchange.getResponseHeaders().add("Set-Cookie","battlecode_session="+login.token()+"; Path=/api/local/; HttpOnly; SameSite=Strict; Max-Age=28800");
            send(exchange,200,Map.of("user",login.user(),"expiresAt",login.expiresAt()));return;
        }
        send(exchange,404,Map.of("error","Endpoint not found"));
    }
    private void handleSubmission(HttpExchange exchange,String path,String method) throws IOException {
        String[] parts=path.split("/",-1);
        UUID owner=sourcePrivacy?identity(exchange):null;
        if(parts.length==4&&method.equals("GET")) {send(exchange,200,Map.of("submissions",submissions.snapshots().stream()
            .filter(d->!sourcePrivacy||owner.equals(submissions.ownerOf(d.metadata().botId()))).map(this::catalogView).toList()));return;}
        if(parts.length==4&&method.equals("POST")) {
            JsonNode data=body(exchange);fields(data,Set.of("requestId","name","source"));
            var detail=submissions.createOwned(owner,scopedRequest(id(data,"requestId"),owner),text(data,"name"),text(data,"source"));
            if(sourcePrivacy)authorize(detail.metadata().botId(),owner);
            send(exchange,201,detailView(detail));return;
        }
        if(parts.length>=5&&parts.length<=6) {
            UUID bot=canonicalId(parts[4]);
            if(parts.length==6&&parts[5].equals("eligibility")&&method.equals("GET")) {
                var m=metadataView(submissions.get(bot).metadata());
                send(exchange,200,Map.of("botId",bot,"executionEligibility",m.executionEligibility(),"capabilities",arena.executionCapabilities()));return;
            }
            authorize(bot,owner);
            if(parts.length==5&&method.equals("GET")) {send(exchange,200,detailView(submissions.get(bot)));return;}
            if(parts.length==5&&method.equals("PUT")) {
                JsonNode data=body(exchange);fields(data,Set.of("requestId","expectedVersion","name","source"));
                send(exchange,200,detailView(submissions.saveOwned(owner,scopedRequest(id(data,"requestId"),owner),bot,expected(data),text(data,"name"),text(data,"source"))));return;
            }
            if(parts.length==5&&method.equals("DELETE")||parts.length==6&&parts[5].equals("validate")&&method.equals("POST")) {
                JsonNode data=body(exchange);fields(data,Set.of("requestId","expectedVersion"));
                if(method.equals("DELETE"))send(exchange,200,submissions.deleteOwned(owner,scopedRequest(id(data,"requestId"),owner),bot,expected(data)));
                else {
                    var v=submissions.validateOwned(owner,scopedRequest(id(data,"requestId"),owner),bot,expected(data));
                    send(exchange,200,new LocalSubmissionStore.Validation(v.botId(),v.version(),v.sourceSha256(),v.validationStatus(),
                        v.staticValidation(),v.runtimeVerification(),eligibility(),(sourcePrivacy&&arena.userBotsExecutable())
                        ?"OS sandbox verified; this source has not been runtime-tested":v.reason()));
                }
                return;
            }
        }
        send(exchange,404,Map.of("error","Endpoint not found"));
    }
    private void handleTournament(HttpExchange exchange,String path,String method) throws IOException {
        if(!sourcePrivacy)throw new SecurityException("Secure local composition required");
        UUID viewer=identity(exchange);
        String[] parts=path.split("/",-1);
        if(parts.length==4&&method.equals("GET")){send(exchange,200,Map.of("tournaments",tournaments.list(),"viewerOwnerId",viewer));return;}
        if(parts.length==4&&method.equals("POST")) {
            var data=tournamentBody(exchange);fields(data,data!=null&&data.has("registrationDeadline")?Set.of("requestId","name","format","maxParticipants","registrationDeadline","seed","maxDrawRematches")
                :Set.of("requestId","name","format","maxParticipants","seed","maxDrawRematches"));
            var deadline=data.path("registrationDeadline");
            var config=new TournamentConfig(text(data,"name"),TournamentFormat.valueOf(text(data,"format")),number(data,"maxParticipants"),
                deadline.isNull()||deadline.isMissingNode()?null:java.time.Instant.parse(text(data,"registrationDeadline")),longNumber(data,"seed"),number(data,"maxDrawRematches"));
            send(exchange,201,tournaments.create(viewer,id(data,"requestId"),config));return;
        }
        if(parts.length<5||parts.length>7){send(exchange,404,Map.of("error","Endpoint not found"));return;}
        UUID tournament=canonicalId(parts[4]);
        if(method.equals("GET")) {
            var detail=tournaments.get(tournament);
            if(parts.length==5){send(exchange,200,Map.of("tournament",detail,"canManage",detail.ownerId().equals(viewer),"viewerOwnerId",viewer));return;}
            if(parts.length==6)switch(parts[5]) {
                case "matches" -> {send(exchange,200,Map.of("matches",detail.rounds().stream().flatMap(r->r.matches().stream()).toList()));return;}
                case "standings" -> {send(exchange,200,Map.of("standings",detail.standings()));return;}
                case "bracket" -> {send(exchange,200,Map.of("rounds",detail.rounds(),"result",detail.result()==null?Map.of():detail.result()));return;}
            }
            if(parts.length==7&&parts[5].equals("matches")){
                UUID match=canonicalId(parts[6]);var selected=detail.rounds().stream().flatMap(r->r.matches().stream()).filter(m->m.matchId().equals(match)).findFirst().orElseThrow(()->new NoSuchElementException("Match"));
                if(database!=null&&!detail.entries().stream().anyMatch(e->e.ownerId().equals(viewer)&&(e.entryId().equals(selected.entryA())||e.entryId().equals(selected.entryB()))))throw new NoSuchElementException("Match");
                send(exchange,200,tournaments.matchSnapshot(tournament,match));return;
            }
        }
        if(method.equals("POST")&&parts.length==6) {
            var data=tournamentBody(exchange);String operation=parts[5];
            if(operation.equals("register")) {
                fields(data,Set.of("requestId","botId","version","sourceSha256"));UUID bot=id(data,"botId");authorize(bot,viewer);
                int version=expectedSelection(data);String hash=text(data,"sourceSha256");
                send(exchange,200,tournaments.register(viewer,id(data,"requestId"),tournament,bot+":"+version+":"+hash,
                    ()->submissions.get(bot).metadata().name(),()->submissions.freezeVersion(bot,version,hash)));return;
            }
            fields(data,Set.of("requestId"));
            send(exchange,200,operation.equals("withdraw")?tournaments.withdraw(viewer,id(data,"requestId"),tournament)
                :tournaments.transition(viewer,id(data,"requestId"),tournament,operation));return;
        }
        send(exchange,404,Map.of("error","Endpoint not found"));
    }
    private int expectedSelection(JsonNode data){int version=number(data,"version");if(version<1||version>16)throw new IllegalArgumentException("Version");return version;}
    private static int number(JsonNode data,String field){var n=data.get(field);if(!n.isIntegralNumber()||!n.canConvertToInt())throw new IllegalArgumentException("Integer");return n.intValue();}
    private static long longNumber(JsonNode data,String field){var n=data.get(field);if(!n.isIntegralNumber()||!n.canConvertToLong())throw new IllegalArgumentException("Long");return n.longValue();}
    private JsonNode tournamentBody(HttpExchange exchange) throws IOException {
        if(!"application/json".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Type")))throw new IllegalArgumentException("Content type");
        byte[] bytes=exchange.getRequestBody().readNBytes(4097);if(bytes.length>4096)throw new PayloadTooLarge();
        try{return json.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());}
        catch(CharacterCodingException e){throw new IllegalArgumentException("Invalid UTF-8");}
    }
    private String eligibility(){return sourcePrivacy&&arena.userBotsExecutable()?"EXECUTABLE":"NOT_EXECUTABLE";}
    private void authorize(UUID bot,UUID owner) {
        if(sourcePrivacy&&!Objects.equals(owner,submissions.ownerOf(bot)))throw new SecurityException("Not owner");
    }
    private UUID scopedRequest(UUID request,UUID owner) {
        return owner==null?request:UUID.nameUUIDFromBytes((owner+":"+request).getBytes(StandardCharsets.UTF_8));
    }
    private Map<String,Object> catalogView(LocalSubmissionStore.Detail detail) {
        var m=detail.metadata();
        return Map.of("botId",m.botId(),"name",m.name(),"latestVersion",m.latestVersion(),
            "sourceSha256",m.sourceSha256(),"executionEligibility",eligibility(),
            "createdAt",m.createdAt(),"updatedAt",m.updatedAt(),"validationStatus",m.validationStatus(),
            "versions",detail.versions().stream().map(v->Map.of(
                "version",v.version(),"sourceSha256",v.sourceSha256())).toList());
    }
    private LocalSubmissionStore.Metadata metadataView(LocalSubmissionStore.Metadata m) {
        return new LocalSubmissionStore.Metadata(m.botId(),m.name(),m.createdAt(),m.updatedAt(),m.latestVersion(),m.sourceSha256(),m.validationStatus(),eligibility());
    }
    private LocalSubmissionStore.Detail detailView(LocalSubmissionStore.Detail d) {
        String eligibility=eligibility();
        return new LocalSubmissionStore.Detail(metadataView(d.metadata()),d.versions().stream()
            .map(v->new LocalSubmissionStore.Version(v.version(),v.source(),v.sourceSha256(),v.createdAt(),v.validationStatus(),eligibility)).toList());
    }
    private BotSubmission selection(JsonNode value) {
        if(value.isTextual()) {
            String fixture=value.textValue();if(!LocalArenaService.BOTS.contains(fixture))throw new IllegalArgumentException("Unknown fixture");
            return TrustedBotFixtures.load(TrustedBotFixtures.Fixture.valueOf(fixture));
        }
        fields(value,Set.of("botId","version","sourceSha256"));
        JsonNode version=value.get("version");
        if(!version.isIntegralNumber()||!version.canConvertToInt()||version.intValue()<1||version.intValue()>16)
            throw new IllegalArgumentException("Version");
        return submissions.freezeVersion(id(value,"botId"),version.intValue(),text(value,"sourceSha256"));
    }
    private String selectionKey(JsonNode value){if(value.isTextual()){if(!LocalArenaService.BOTS.contains(value.textValue()))throw new IllegalArgumentException("Unknown fixture");return value.textValue();}
        fields(value,Set.of("botId","version","sourceSha256"));String hash=text(value,"sourceSha256");if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Hash");return id(value,"botId")+":"+expectedSelection(value)+":"+hash;
    }
    private static final class PayloadTooLarge extends IllegalArgumentException {}
    private JsonNode body(HttpExchange exchange) throws IOException {
        if(!"application/json".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Type")))
            throw new IllegalArgumentException("Content type");
        byte[] bytes=exchange.getRequestBody().readNBytes(210001);
        if(bytes.length>210000)throw new PayloadTooLarge();
        String decoded;
        try {decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new IllegalArgumentException("Invalid UTF-8");}
        return json.readTree(decoded);
    }
    private static void fields(JsonNode data,Set<String> required) {
        if(data==null||!data.isObject()||data.size()!=required.size())throw new IllegalArgumentException("Fields");
        for(String field:required)if(!data.has(field))throw new IllegalArgumentException("Fields");
    }
    private static String text(JsonNode data,String field) {
        if(!data.get(field).isTextual())throw new IllegalArgumentException("Text required");return data.get(field).textValue();
    }
    private static int expected(JsonNode data) {
        var value=data.get("expectedVersion");
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1||value.intValue()>16)
            throw new IllegalArgumentException("Version");return value.intValue();
    }
    private static UUID id(JsonNode data,String field) {return canonicalId(text(data,field));}
    private static UUID canonicalId(String text) {
        UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException("Canonical UUID required");return id;
    }
    private void send(HttpExchange exchange,int status,Object body) throws IOException {
        byte[] bytes=json.writeValueAsBytes(body);
        if(bytes.length>4*1024*1024){status=503;bytes=json.writeValueAsBytes(Map.of("error","Response capacity exceeded"));}
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    public void close() {
        if(!closed.compareAndSet(false,true))return;
        server.stop(0);workers.shutdownNow();
        try {workers.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        try {if(rankedCoordinator!=null)rankedCoordinator.close();tournaments.close();}finally {try{arena.close();}finally{if(database!=null)database.close();}}
    }
}
