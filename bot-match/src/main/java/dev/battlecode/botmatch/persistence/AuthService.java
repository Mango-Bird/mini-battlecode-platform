package dev.battlecode.botmatch.persistence;
import at.favre.lib.crypto.bcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import dev.battlecode.botrunner.BotSubmission;
/** Account credentials never leave this service. Cookie token is returned only to the HTTP controller. */
public final class AuthService {
    public record User(UUID id,String username){}
    public record Login(User user,String token,Instant expiresAt){@Override public String toString(){return "Login["+user.id()+"]";}}
    private final Database db;private final Clock clock;private final Duration lifetime;
    private final String dummy=BCrypt.withDefaults().hashToString(12,"invalid-account-timing-password".toCharArray());
    private final Map<String,Window> limits=new HashMap<>();
    private record Window(long since,int count){}
    public AuthService(Database db){this(db,Clock.systemUTC(),Duration.ofHours(8));}
    public AuthService(Database db,Clock clock,Duration lifetime){this.db=db;this.clock=clock;this.lifetime=lifetime;}
    public synchronized void throttle(String address,String operation){
        long now=clock.millis();limits.entrySet().removeIf(e->now-e.getValue().since>=60000);
        String key=address+":"+operation;var window=limits.get(key);
        if(limits.size()>=128&&!limits.containsKey(key))throw new RateLimited();
        int max=operation.equals("register")?3:10;
        if(window!=null&&window.count>=max)throw new RateLimited();
        limits.put(key,new Window(window==null?now:window.since,window==null?1:window.count+1));
    }
    private static String username(String name){if(name==null||!name.matches("[a-z0-9_]{3,32}"))throw new IllegalArgumentException("Username: 3–32 lowercase letters, digits or underscores");return name;}
    private static void password(String value){if(value==null||value.length()<15||value.getBytes(StandardCharsets.UTF_8).length>72||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Password: at least 15 characters, at most 72 UTF-8 bytes");
        try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));}catch(java.nio.charset.CharacterCodingException e){throw new IllegalArgumentException("Invalid password encoding");}
    }
    public User register(String name,String password){db.checkLease();username(name);password(password);
        String hash=BCrypt.withDefaults().hashToString(12,password.toCharArray());
        return db.transaction(c->{Database.lock(c,"account-registration");
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM users")){r.next();if(r.getInt(1)>=128)throw new IllegalStateException("Account capacity");}
            UUID id=UUID.randomUUID();try(var p=c.prepareStatement("INSERT INTO users(id,username,password_hash) VALUES (?,?,?) ON CONFLICT(username) DO NOTHING")){p.setObject(1,id);p.setString(2,name);p.setString(3,hash);if(p.executeUpdate()!=1)throw new IllegalStateException("Account could not be created");}
            return new User(id,name);
        });
    }
    public Login login(String name,String password){
        db.checkLease();
        if(name==null||password==null||password.getBytes(StandardCharsets.UTF_8).length>72)throw new Unauthorized();
        try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(password));}catch(java.nio.charset.CharacterCodingException e){throw new Unauthorized();}
        record Credential(User user,String hash){}
        Credential credential=db.transaction(c->{try(var p=c.prepareStatement("SELECT id,username,password_hash FROM users WHERE username=?")){p.setString(1,name);try(var r=p.executeQuery()){return r.next()?new Credential(new User(r.getObject(1,UUID.class),r.getString(2)),r.getString(3)):null;}}});
        boolean valid=BCrypt.verifyer().verify(password.toCharArray(),credential==null?dummy:credential.hash).verified;
        if(!valid||credential==null)throw new Unauthorized();
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=HexFormat.of().formatHex(bytes);Instant expiry=clock.instant().plus(lifetime);
        db.transaction(c->{Database.lock(c,"session:"+credential.user.id());
            try(var p=c.prepareStatement("DELETE FROM user_sessions WHERE user_id=? AND (revoked_at IS NOT NULL OR expires_at<=?)")){p.setObject(1,credential.user.id());p.setObject(2,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));p.executeUpdate();}
            try(var p=c.prepareStatement("SELECT count(*) FROM user_sessions WHERE user_id=?")){p.setObject(1,credential.user.id());try(var r=p.executeQuery()){r.next();if(r.getInt(1)>=16)throw new IllegalStateException("Session capacity");}}
            try(var p=c.prepareStatement("INSERT INTO user_sessions(id,user_id,token_hash,expires_at) VALUES (?,?,?,?)")){p.setObject(1,UUID.randomUUID());p.setObject(2,credential.user.id());p.setString(3,hash(token));p.setObject(4,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC));p.executeUpdate();}return null;
        });return new Login(credential.user,token,expiry);
    }
    public User require(String token){db.checkLease();if(token==null||!token.matches("[a-f0-9]{64}"))throw new Unauthorized();
        return db.transaction(c->{try(var p=c.prepareStatement("SELECT u.id,u.username FROM user_sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?")){p.setString(1,hash(token));p.setObject(2,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));try(var r=p.executeQuery()){if(!r.next())throw new Unauthorized();return new User(r.getObject(1,UUID.class),r.getString(2));}}});
    }
    public void logout(String token){db.checkLease();if(token==null||!token.matches("[a-f0-9]{64}"))return;
        db.transaction(c->{try(var p=c.prepareStatement("UPDATE user_sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL")){p.setObject(1,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));p.setString(2,hash(token));p.executeUpdate();}return null;});
    }
    private static String hash(String token){return BotSubmission.hash(token.getBytes(StandardCharsets.US_ASCII));}
    public static final class Unauthorized extends RuntimeException {public Unauthorized(){super("Authentication required or invalid credentials");}}
    public static final class RateLimited extends RuntimeException {public RateLimited(){super("Too many authentication attempts; retry later");}}
}
