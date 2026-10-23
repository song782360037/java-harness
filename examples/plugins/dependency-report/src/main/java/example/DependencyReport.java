package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Standalone MCP example: analyzes supplied XML without accessing the workspace or running Maven. */
public final class DependencyReport {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Start the official SDK stdio server; stdout is reserved for protocol messages. */
    public static void main(String[] args) throws Exception {
        var mapper = new JacksonMcpJsonMapper(JSON);
        var tool = McpSchema.Tool.builder().name("summarize_dependencies").description("Summarize dependency declarations in supplied POM XML; does not resolve Maven dependencies.")
                .inputSchema(mapper, """
                        {"type":"object","properties":{"pomXml":{"type":"string","maxLength":14000}},"required":["pomXml"],"additionalProperties":false}
                        """).build();
        var server = McpServer.sync(new StdioServerTransportProvider(mapper)).serverInfo("dependency-report", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tool(tool, (exchange, input) -> summarize(input.get("pomXml"))).build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        new CountDownLatch(1).await();
    }

    /** Parse declarations with external entities and DTD access disabled. */
    private static McpSchema.CallToolResult summarize(Object value) {
        if (!(value instanceof String xml) || xml.length() > 14000) return new McpSchema.CallToolResult("需要不超过 14000 字符的 pomXml", true);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            var nodes = document.getElementsByTagName("dependency"); var declarations = new ArrayList<Map<String, String>>();
            for (int i = 0; i < nodes.getLength() && i < 100; i++) {
                Element dependency = (Element) nodes.item(i); var fields = new LinkedHashMap<String, String>();
                for (String field : List.of("groupId", "artifactId", "version", "scope")) fields.put(field, child(dependency, field));
                declarations.add(fields);
            }
            return new McpSchema.CallToolResult(JSON.writeValueAsString(Map.of("declarations", declarations, "truncated", nodes.getLength() > 100,
                    "note", "仅汇总 XML 中的 dependency 声明；包含 dependencyManagement/profile，未解析继承、属性、激活条件或传递依赖")), false);
        } catch (Exception error) { return new McpSchema.CallToolResult("POM XML 格式不正确或包含禁止的 DTD", true); }
    }

    /** Read only a dependency element's direct child. */
    private static String child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && element.getTagName().equals(name)) return element.getTextContent().strip();
        return "";
    }
}
