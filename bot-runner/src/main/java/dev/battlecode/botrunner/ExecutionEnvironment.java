package dev.battlecode.botrunner;
import java.util.Map;
/** Defense in depth; safe staging also has no executor in its composition or route registry. */
public final class ExecutionEnvironment {
    private ExecutionEnvironment(){}
    public static void requireLocal(Map<String,String> env){
        if(!"local".equals(env.getOrDefault("BATTLECODE_ENV","local"))||env.containsKey("RENDER")
            ||"SAFE_STAGING_WEB_ONLY".equals(env.get("BATTLECODE_MODE"))||"WEB_ONLY".equals(env.get("BATTLECODE_MODE")))
            throw new IllegalStateException("Bot execution is unavailable in this runtime");
    }
}
