package dev.battlecode.staging;
import org.junit.jupiter.api.*;
import dev.battlecode.botmatch.persistence.*;
import com.zaxxer.hikari.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real PostgreSQL suite. Test pool stays separate from the strict cloud config factory. */
@Timeout(90)
class StagingPostgresIT {
    Database admin,db;HikariDataSource pool;StagingHttpServer server;HttpClient client;String schema,root,role;
    final com.fasterxml.jackson.databind.ObjectMapper json=PersistenceJson.mapper();
    String session,nonce;
    @BeforeEach void open()throws Exception{
        System.setProperty("jdk.httpclient.allowRestrictedHeaders","host");
        String url=Objects.requireNonNull(System.getenv("BATTLECODE_TEST_DB_URL")),user=System.getenv("BATTLECODE_TEST_DB_USER"),password=System.getenv("BATTLECODE_TEST_DB_PASSWORD");
        admin=new Database(url,user,password);schema="staging_test_"+UUID.randomUUID().toString().replace("-","");
        admin.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}return null;});
        var migration=new Database(url+"?currentSchema="+schema,user,password);migration.migrate();migration.close();
        role="staging_app_"+UUID.randomUUID().toString().replace("-","");String rolePassword=UUID.randomUUID().toString();
        admin.transaction(c->{try(var s=c.createStatement()){
            s.execute("CREATE ROLE "+role+" LOGIN PASSWORD '"+rolePassword+"'");s.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
            s.execute("GRANT SELECT ON ALL TABLES IN SCHEMA "+schema+" TO "+role);
            s.execute("GRANT INSERT ON "+schema+".users,"+schema+".user_sessions,"+schema+".bots,"+schema+".bot_versions,"+schema+".idempotency_records TO "+role);
            s.execute("GRANT UPDATE ON "+schema+".user_sessions,"+schema+".bots TO "+role);s.execute("GRANT DELETE ON "+schema+".user_sessions TO "+role);
        }return null;});
        var cfg=new HikariConfig();cfg.setJdbcUrl(url+"?currentSchema="+schema);cfg.setUsername(role);cfg.setPassword(rolePassword);cfg.setMaximumPoolSize(4);cfg.setMinimumIdle(0);cfg.setConnectionTimeout(3000);
        pool=new HikariDataSource(cfg);db=new Database(pool);
        server=new StagingHttpServer(new StagingConfig(StagingPolicyTest.environment()),db,new InetSocketAddress("127.0.0.1",0));server.start();
        root="http://127.0.0.1:"+server.port();client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void close(){if(client!=null)client.close();if(server!=null)server.close();if(db!=null)db.close();if(pool!=null)pool.close();
        if(admin!=null){assertTrue(schema.matches("staging_test_[a-f0-9]{32}"));admin.transaction(c->{try(var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");if(role!=null){assertTrue(role.matches("staging_app_[a-f0-9]{32}"));s.execute("DROP OWNED BY "+role);s.execute("DROP ROLE "+role);}}return null;});admin.close();}}
    HttpResponse<String> call(String route,String method,String body,String token,boolean proxy)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(root+route)).timeout(Duration.ofSeconds(20)).header("Host","stage.onrender.com");
        if(proxy)b.header("Origin","https://stage.vercel.app").header("X-Staging-Proto","https").header("X-Staging-Proxy-Key",StagingPolicyTest.environment().get("STAGING_PROXY_SECRET")).header("X-Staging-Client","a".repeat(64));
        if(session!=null||nonce!=null)b.header("Cookie",(session==null?"":StagingHttpServer.COOKIE+"="+session+";")+(nonce==null?"":StagingHttpServer.NONCE+"="+nonce));
        if(token!=null)b.header("X-CSRF-Token",token);if(body!=null)b.header("Content-Type","application/json");
        var r=client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        for(String cookie:r.headers().allValues("Set-Cookie")){if(cookie.startsWith(StagingHttpServer.COOKIE+"="))session=cookie.split(";",2)[0].substring(StagingHttpServer.COOKIE.length()+1);if(cookie.startsWith(StagingHttpServer.NONCE+"="))nonce=cookie.split(";",2)[0].substring(StagingHttpServer.NONCE.length()+1);}
        return r;
    }
    String token()throws Exception{var r=call("/api/staging/auth/csrf","GET",null,null,true);assertEquals(200,r.statusCode(),r.body());return json.readTree(r.body()).path("csrfToken").asText();}
    void login(String name)throws Exception{
        String credentials=json.writeValueAsString(Map.of("username",name,"password","long-staging-test-account-password"));
        assertEquals(201,call("/api/staging/auth/register","POST",credentials,token(),true).statusCode());
        var r=call("/api/staging/auth/login","POST",credentials,token(),true);assertEquals(200,r.statusCode(),r.body());
        String cookie=r.headers().allValues("Set-Cookie").getFirst();assertTrue(cookie.contains("Secure; HttpOnly; SameSite=Strict"));assertTrue(cookie.contains("Path=/;"));assertFalse(cookie.contains("Domain="));
    }
    @Test void smokeUnknownExecutionProxyAndCsrf()throws Exception{
        assertEquals(200,call("/healthz","GET",null,null,false).statusCode());assertEquals(200,call("/readyz","GET",null,null,false).statusCode());
        for(String route:List.of("battles","ranked/queue","tournaments","execute","worker","admin"))assertEquals(404,call("/api/staging/"+route,"POST","{}",null,true).statusCode());
        assertEquals(403,call("/api/staging/auth/csrf","GET",null,null,false).statusCode());
        assertEquals(403,call("/api/staging/auth/register","POST","{}",null,true).statusCode());
        login("alice");
        var accepted=call("/api/staging/auth/me","GET",null,null,true);assertEquals(200,accepted.statusCode());
        assertEquals("https://stage.vercel.app",accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var forged=HttpRequest.newBuilder(URI.create(root+"/api/staging/auth/me")).header("Host","stage.onrender.com").header("Origin","https://attacker.example")
            .header("X-Staging-Proto","https").header("X-Staging-Proxy-Key",StagingPolicyTest.environment().get("STAGING_PROXY_SECRET")).timeout(Duration.ofSeconds(10)).build();
        assertEquals(403,client.send(forged,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403,call("/api/staging/auth/logout","POST","{}","tampered",true).statusCode());
        assertEquals(200,call("/api/staging/auth/logout","POST","{}",token(),true).statusCode());
        assertEquals(401,call("/api/staging/auth/me","GET",null,null,true).statusCode());
    }
    @Test void ownerVersionsDuplicateSizeAndNoRatingWrites()throws Exception{
        login("alice");String alice=session;
        String create=json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"name","A","source","function chooseMove(o){return o.legalMoves[0];}"));
        var first=call("/api/staging/submissions","POST",create,token(),true);assertEquals(201,first.statusCode(),first.body());String bot=json.readTree(first.body()).path("metadata").path("botId").asText();
        assertEquals(first.body(),call("/api/staging/submissions","POST",create,token(),true).body());
        var save=Map.of("requestId",UUID.randomUUID(),"expectedVersion",1,"name","Renamed","source","function chooseMove(o) { return o.legalMoves[0]; }");
        var saved=call("/api/staging/submissions/"+bot,"PUT",json.writeValueAsString(save),token(),true);assertEquals(200,saved.statusCode(),saved.body());assertEquals(2,json.readTree(saved.body()).path("versions").size());
        assertEquals(413,call("/api/staging/submissions","POST"," ".repeat(200001),token(),true).statusCode());
        assertEquals(400,call("/api/staging/submissions","POST","{\"name\":1,\"name\":2}",token(),true).statusCode());
        assertEquals(200,call("/api/staging/rankings","GET",null,null,true).statusCode());
        assertEquals(0,db.<Integer>transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM rating_periods")){r.next();return r.getInt(1);}}));
        assertThrows(Database.Unavailable.class,()->db.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("UPDATE rating_profiles SET elo=9999");}return null;}));
        assertThrows(Database.Unavailable.class,()->db.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("UPDATE bot_versions SET validation_status='FORGED'");}return null;}));
        session=null;nonce=null;login("bob");
        assertEquals(403,call("/api/staging/submissions/"+bot,"GET",null,null,true).statusCode());
        assertFalse(call("/api/staging/submissions","GET",null,null,true).body().contains(bot));
        assertEquals(404,call("/api/staging/matches/"+UUID.randomUUID(),"GET",null,null,true).statusCode());
        session=alice;assertEquals(200,call("/api/staging/submissions/"+bot,"GET",null,null,true).statusCode());
    }
    @Test void coldLeaseReconnectAndRateLimit()throws Exception{
        assertEquals(200,call("/readyz","GET",null,null,false).statusCode());
        admin.transaction(c->{try(var s=c.createStatement()){s.execute("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' AND objid=810102025");}return null;});
        assertEquals(200,call("/readyz","GET",null,null,false).statusCode());
        String credentials="{\"username\":\"missing\",\"password\":\"long-staging-test-account-password\"}";
        for(int i=0;i<10;i++)assertEquals(401,call("/api/staging/auth/login","POST",credentials,token(),true).statusCode());
        assertEquals(429,call("/api/staging/auth/login","POST",credentials,token(),true).statusCode());
    }
    @Test void expirationAndRegistrationLimit()throws Exception{
        login("alice");
        db.transaction(c->{try(var s=c.createStatement()){s.executeUpdate("UPDATE user_sessions SET expires_at=now()-interval '1 second'");}return null;});
        assertEquals(401,call("/api/staging/auth/me","GET",null,null,true).statusCode());
        session=null;nonce=null;
        for(String name:List.of("second","third")){
            String body=json.writeValueAsString(Map.of("username",name,"password","long-staging-test-account-password"));
            assertEquals(201,call("/api/staging/auth/register","POST",body,token(),true).statusCode());
        }
        assertEquals(429,call("/api/staging/auth/register","POST","{\"username\":\"fourth\",\"password\":\"long-staging-test-account-password\"}",token(),true).statusCode());
    }
    @Test void privilegedIdentityIsNotReady()throws Exception{
        try(var privileged=new StagingHttpServer(new StagingConfig(StagingPolicyTest.environment()),admin,new InetSocketAddress("127.0.0.1",0))){
            privileged.start();var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+privileged.port()+"/readyz")).timeout(Duration.ofSeconds(10)).build();
            assertEquals(503,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        }finally{admin.close();}
    }
    @Test void poolAcquisitionIsBounded()throws Exception{
        var held=new ArrayList<Connection>();try{
            for(int i=0;i<4;i++)held.add(pool.getConnection());
            assertTimeout(Duration.ofSeconds(5),()->assertThrows(SQLException.class,pool::getConnection));
        }finally{for(var connection:held)connection.close();}
    }
    @Test void secondInstanceCannotBecomeReady()throws Exception{
        assertEquals(200,call("/readyz","GET",null,null,false).statusCode());
        try(var otherDb=new Database(pool);var other=new StagingHttpServer(new StagingConfig(StagingPolicyTest.environment()),otherDb,new InetSocketAddress("127.0.0.1",0))){
            other.start();var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+other.port()+"/readyz")).timeout(Duration.ofSeconds(10)).build();
            assertEquals(503,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(200,call("/readyz","GET",null,null,false).statusCode());
    }
}
