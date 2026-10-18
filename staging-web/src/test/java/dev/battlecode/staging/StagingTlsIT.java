package dev.battlecode.staging;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(30)
class StagingTlsIT {
    @Test void verifiedCertificateAndHostnameRequired()throws Exception{
        String url=Objects.requireNonNull(System.getenv("BATTLECODE_TEST_TLS_URL"));
        Properties p=new Properties();p.setProperty("user",System.getenv("BATTLECODE_TEST_DB_USER"));p.setProperty("password",System.getenv("BATTLECODE_TEST_DB_PASSWORD"));
        p.setProperty("sslmode","verify-full");p.setProperty("sslfactory","org.postgresql.ssl.DefaultJavaSSLFactory");p.setProperty("connectTimeout","3");
        try(var c=DriverManager.getConnection(url,p);var s=c.createStatement();var r=s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
        // Test certificate trusts localhost only, not the loopback numeric address.
        assertThrows(SQLException.class,()->DriverManager.getConnection(url.replace("localhost","127.0.0.1"),p));
        // The default pgJDBC trust store does not trust this disposable self-signed certificate.
        p.remove("sslfactory");assertThrows(SQLException.class,()->DriverManager.getConnection(url,p));
    }
}
