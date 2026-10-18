package dev.battlecode.botmatch.persistence;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
/** JDBC transactions bounded by connect/socket/statement/lock timeouts. No SQL error details escape. */
public final class Database implements AutoCloseable {
    public interface Work<T>{T run(Connection connection) throws Exception;}
    private final String url,user,password;
    private Connection lease;
    private javax.sql.DataSource source;
    public Database(String url,String user,String password){
        if(url==null||!url.startsWith("jdbc:postgresql://")||user==null||password==null||password.isBlank())throw new IllegalArgumentException("PostgreSQL configuration required");
        if(java.net.URI.create(url.substring(5)).getUserInfo()!=null||url.toLowerCase(Locale.ROOT).matches(".*[?&](?:password|user|sslpassword)=.*"))throw new IllegalArgumentException("Database credentials must use environment fields, not JDBC URL");
        this.url=url;this.user=user;this.password=password;
    }
    public static Database environment(){return new Database(System.getenv("BATTLECODE_DB_URL"),System.getenv("BATTLECODE_DB_USER"),System.getenv("BATTLECODE_DB_PASSWORD"));}
    /** Additive adapter for a separately configured bounded staging pool. Local configuration is unchanged. */
    public Database(javax.sql.DataSource source){this.url=null;this.user=null;this.password=null;this.source=Objects.requireNonNull(source);}
    public Connection open() throws SQLException {
        Properties p=new Properties();if(source==null){p.setProperty("user",user);p.setProperty("password",password);}p.setProperty("connectTimeout","5");p.setProperty("socketTimeout","15");
        var c=source==null?DriverManager.getConnection(url,p):source.getConnection();try{try(var s=c.createStatement()){s.execute("SET statement_timeout='10s'");s.execute("SET lock_timeout='5s'");}return c;}catch(SQLException e){c.close();throw e;}
    }
    public void migrate(){Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").cleanDisabled(true).load().migrate();}
    /** One local scheduler process per database. Connection loss invalidates the lease and fails closed. */
    public synchronized void acquireLease(){
        if(lease!=null)throw new IllegalStateException("Lease already held");
        try {lease=open();try(var s=lease.createStatement();var r=s.executeQuery("SELECT pg_try_advisory_lock(810102025)")){r.next();if(!r.getBoolean(1)){lease.close();lease=null;throw new IllegalStateException("Another local application owns this database");}}}
        catch(SQLException e){throw new Unavailable();}
    }
    public synchronized void checkLease(){if(lease==null)throw new Unavailable();try(var s=lease.createStatement();var r=s.executeQuery("SELECT 1")){r.next();}catch(SQLException e){throw new Unavailable();}}
    /** Only the stateless staging composition uses this after a suspended database disconnects. */
    public synchronized void reconnectLease(){
        try{checkLease();return;}catch(Unavailable lost){if(lease!=null)try{lease.close();}catch(SQLException ignored){}lease=null;acquireLease();}
    }
    public <T>T transaction(Work<T> work){
        try(var c=open()) {c.setAutoCommit(false);try{T response=work.run(c);c.commit();return response;}catch(Exception e){c.rollback();if(e instanceof RuntimeException runtime)throw runtime;throw new Unavailable();}}
        catch(SQLException e){throw new Unavailable();}
    }
    public static void lock(Connection c,String scope)throws SQLException{try(var p=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){p.setString(1,scope);p.execute();}}
    public synchronized void close(){if(lease!=null)try{lease.close();}catch(SQLException ignored){}lease=null;}
    public static final class Unavailable extends RuntimeException {public Unavailable(){super("Database unavailable; operation refused");}}
}
