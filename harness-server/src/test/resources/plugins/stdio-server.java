import java.nio.file.*;
import java.io.*;
import java.util.regex.*;

/** Standalone protocol fixture: a blocking tool allows real cancellation and process cleanup checks. */
class Fixture {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(System.getenv("PLUGIN_DATA"), "pid.txt"), Long.toString(ProcessHandle.current().pid()));
        var reader = new BufferedReader(new InputStreamReader(System.in)); String line;
        while ((line = reader.readLine()) != null) {
            var id = Pattern.compile("\"id\"\\s*:\\s*([^,}]+)").matcher(line);
            if (!id.find()) continue;
            String result;
            if (line.contains("initialize")) result = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
            else if (line.contains("tools/list")) result = "{\"tools\":[{\"name\":\"report\",\"description\":\"wait\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"],\"additionalProperties\":false}}]}";
            else { Thread.sleep(30000); result = "{\"content\":[{\"type\":\"text\",\"text\":\"late\"}]}"; }
            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}"); System.out.flush();
        }
    }
}
