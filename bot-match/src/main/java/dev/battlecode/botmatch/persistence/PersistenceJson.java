package dev.battlecode.botmatch.persistence;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.time.Instant;
public final class PersistenceJson {
    private PersistenceJson(){}
    public static ObjectMapper mapper(){return new ObjectMapper().registerModule(new SimpleModule()
        .addSerializer(Instant.class,new JsonSerializer<Instant>(){public void serialize(Instant v,JsonGenerator g,SerializerProvider p)throws IOException{g.writeString(v.toString());}})
        .addDeserializer(Instant.class,new JsonDeserializer<Instant>(){public Instant deserialize(JsonParser p,DeserializationContext c)throws IOException{return Instant.parse(p.getValueAsString());}}));}
}
