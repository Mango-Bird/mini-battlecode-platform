package dev.battlecode.botmatch.persistence;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.battlecode.botrunner.BotSubmission;
/** Called inside the same transaction as domain writes. Never logs response/source. */
public final class IdempotencyRepository {
    private final ObjectMapper json=PersistenceJson.mapper();
    public <T>T previous(Connection c,String scope,UUID request,String fingerprint,Class<T> type)throws Exception{
        try(var p=c.prepareStatement("SELECT payload_hash,response_snapshot::text FROM idempotency_records WHERE scope=? AND request_id=?")){p.setString(1,scope);p.setObject(2,request);try(var r=p.executeQuery()){if(!r.next())return null;if(!r.getString(1).equals(hash(fingerprint)))throw new IllegalStateException("Request ID conflict");return json.readValue(r.getString(2),type);}}
    }
    public void remember(Connection c,String scope,UUID request,String fingerprint,Object response)throws Exception{
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM idempotency_records")){r.next();if(r.getInt(1)>=4096)throw new IllegalStateException("Durable request capacity");}
        try(var p=c.prepareStatement("INSERT INTO idempotency_records(scope,request_id,payload_hash,response_snapshot) VALUES (?,?,?,?::jsonb)")){p.setString(1,scope);p.setObject(2,request);p.setString(3,hash(fingerprint));p.setString(4,json.writeValueAsString(response));p.executeUpdate();}
    }
    public static String hash(String text){return BotSubmission.hash(text.getBytes(StandardCharsets.UTF_8));}
}
