package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.Workspace;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:workspace-browser-tests;DB_CLOSE_DELAY=-1",
            "harness.run-timeout=5s",
            "harness.model.api-key=",
            "harness.model.name=",
            "harness.session-naming.enabled=false"
        })
class WorkspaceBrowserApiIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    SessionStore store;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void listsDirectoryTreeAndPreviewsFilesWhileEnforcingSecurityBoundaries(@TempDir Path tempDir) throws Exception {
        Path root1 = Files.createDirectory(tempDir.resolve("repo-one"));
        Path root2 = Files.createDirectory(tempDir.resolve("repo-two"));
        Files.createDirectories(root1.resolve("src"));
        Files.createDirectories(root1.resolve(".git"));
        Files.writeString(root1.resolve(".git/HEAD"), "ref: refs/heads/master\n");
        Files.writeString(root1.resolve(".env"), "SECRET=123\n");
        Files.writeString(root1.resolve("README.md"), "# Demo\nLine 2\n");
        Files.writeString(root1.resolve("src/Main.java"), "class Main {}\n");
        Files.write(root1.resolve("blob.bin"), new byte[] {0x00, 0x01, 0x02, 0x03});
        Files.writeString(root1.resolve("large.txt"), "你".repeat(100_000));
        Files.writeString(root2.resolve("notes.txt"), "second root content");

        Workspace workspace =
                store.saveWorkspace("", "测试双根工作区", List.of(root1.toString(), root2.toString()), "folder", "#4b705b", 0);
        Session session = store.createSessionInWorkspace("浏览测试", workspace.id());

        // 根目录列表：目录优先、跳过 .git 和 .env。
        HttpResponse<String> treeRes = get("/api/sessions/" + session.id() + "/tree?root=1");
        assertThat(treeRes.statusCode()).isEqualTo(200);
        JsonNode entries = json.readTree(treeRes.body()).path("entries");
        List<String> names = new java.util.ArrayList<>();
        entries.forEach(node -> names.add(node.path("name").asText()));
        assertThat(names).containsExactly("src", "blob.bin", "large.txt", "README.md");
        assertThat(entries.get(0).path("directory").asBoolean()).isTrue();

        // 草稿工作区接口：支持读取第 2 个根目录的文件预览。
        HttpResponse<String> root2File = get("/api/workspaces/" + workspace.id() + "/file?root=2&path=notes.txt");
        assertThat(root2File.statusCode()).isEqualTo(200);
        JsonNode root2Preview = json.readTree(root2File.body());
        assertThat(root2Preview.path("binary").asBoolean()).isFalse();
        assertThat(root2Preview.path("content").asText()).isEqualTo("second root content");
        assertThat(root2Preview.path("totalLines").asInt()).isEqualTo(1);

        // 二进制文件检测。
        HttpResponse<String> binRes = get("/api/sessions/" + session.id() + "/file?root=1&path=blob.bin");
        assertThat(binRes.statusCode()).isEqualTo(200);
        JsonNode binPreview = json.readTree(binRes.body());
        assertThat(binPreview.path("binary").asBoolean()).isTrue();
        assertThat(binPreview.path("content").asText()).isEmpty();

        // 超大 UTF-8 文件安全截断（不因多字节截断误判为二进制）。
        HttpResponse<String> largeRes = get("/api/sessions/" + session.id() + "/file?root=1&path=large.txt");
        assertThat(largeRes.statusCode()).isEqualTo(200);
        JsonNode largePreview = json.readTree(largeRes.body());
        assertThat(largePreview.path("binary").asBoolean()).isFalse();
        assertThat(largePreview.path("truncated").asBoolean()).isTrue();
        assertThat(largePreview.path("content").asText()).startsWith("你你你");

        // 敏感文件与越界路径被拒绝。
        assertThat(get("/api/sessions/" + session.id() + "/file?root=1&path=.env")
                        .statusCode())
                .isEqualTo(400);
        assertThat(get("/api/sessions/" + session.id() + "/file?root=1&path=" + encode("../repo-two/notes.txt"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(get("/api/sessions/" + session.id() + "/tree?root=3").statusCode())
                .isEqualTo(400);
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
