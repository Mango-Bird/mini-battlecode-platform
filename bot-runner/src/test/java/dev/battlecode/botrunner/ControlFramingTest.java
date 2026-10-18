package dev.battlecode.botrunner;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ControlFramingTest {
    byte[] bytes(String body) throws IOException {
        var output=new ByteArrayOutputStream();var stream=new DataOutputStream(output);var payload=body.getBytes(StandardCharsets.UTF_8);
        stream.writeInt(payload.length);stream.write(payload);return output.toByteArray();
    }
    @Test void fragmentedHeaderAndBodyReadFully() throws Exception {
        var stream=new ByteArrayInputStream(bytes("{\"stage\":\"STARTED\"}")) {
            public synchronized int read(byte[] b,int off,int len) {return super.read(b,off,Math.min(1,len));}
        };
        assertEquals("STARTED",TrustedQuickJsRunner.frame(new DataInputStream(stream)).path("stage").asText());
    }
    @Test void successiveFramesStaySeparated() throws Exception {
        var output=new ByteArrayOutputStream();output.write(bytes("{}"));output.write(bytes("{\"n\":2}"));
        var input=new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(0,TrustedQuickJsRunner.frame(input).size());assertEquals(2,TrustedQuickJsRunner.frame(input).path("n").asInt());
    }
    @Test void validatesLengthBeforeBodyAllocation() {
        for(int length:new int[]{0,-1,4097,Integer.MAX_VALUE}) {
            byte[] header=java.nio.ByteBuffer.allocate(4).putInt(length).array();
            assertThrows(IOException.class,()->TrustedQuickJsRunner.frame(new DataInputStream(new ByteArrayInputStream(header))));
        }
    }
    @Test void truncatedAndDuplicateControlJsonRejected() throws Exception {
        assertThrows(EOFException.class,()->TrustedQuickJsRunner.frame(new DataInputStream(new ByteArrayInputStream(new byte[]{0,0,0,2,123}))));
        assertThrows(IOException.class,()->TrustedQuickJsRunner.frame(new DataInputStream(new ByteArrayInputStream(bytes("{\"n\":1,\"n\":2}")))));
        assertThrows(IOException.class,()->TrustedQuickJsRunner.frame(new DataInputStream(new ByteArrayInputStream(bytes("{} {}")))));
    }
}
