package dev.javaharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.*;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:workspaces-api;DB_CLOSE_DELAY=-1", "harness.model.name=", "harness.tools.max-steps=0"
})
class WorkspaceApiIntegrationTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("harness.settings.key-file", () -> directory.resolve("settings.key").toString());
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SessionStore sessions;
    @Autowired FileChangeService changes;
    @Autowired CommandService commands;
    private final HttpClient client = HttpClient.newHttpClient();
    Path first, second;
    @BeforeEach void directories() throws Exception {
        Path parent = Files.createDirectory(directory.resolve(UUID.randomUUID().toString()));
        first = Files.createDirectory(parent.resolve("项目一")).toRealPath();
        second = Files.createDirectory(parent.resolve("项目二")).toRealPath();
        Files.writeString(first.resolve("same.txt"), "first-root\n");
        Files.writeString(second.resolve("same.txt"), "second-root\n");
    }
    private HttpResponse<String> post(String path, Object value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<String, Object> body(String name, List<String> roots, long revision) {
        return Map.of("name", name, "directories", roots, "icon", "rocket", "color", "#6655ee", "revision", revision);
    }
    private Workspace workspace() throws Exception {
        var response = post("/workspaces", body("前后端联合工作区", List.of(first.toString(), second.toString()), 0));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readValue(response.body(), Workspace.class);
    }
    private Session session(Workspace workspace) throws Exception {
        var response = post("/sessions", Map.of("title", "独立会话名", "workspaceId", workspace.id()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readValue(response.body(), Session.class);
    }
    private Run start(Session session) {
        Run run = sessions.submit(session.id(), UUID.randomUUID().toString(), "test", "NORMAL", new ModelInfo("test", "test", true)).run();
        return sessions.transition(run.id(), RunStatus.RUNNING, "");
    }
    private WorkspaceTools tools(Workspace workspace) throws Exception {
        String snapshot = WorkspaceRoots.encode(workspace.directories());
        var roots = new ArrayList<WorkspaceTools>();
        for (int i = 0; i < workspace.directories().size(); i++) {
            WorkspaceTools root = new GitWorkspaceTools(new LocalWorkspaceTools(Path.of(workspace.directories().get(i)), Duration.ofSeconds(10)), Duration.ofSeconds(10));
            root = new ReviewWorkspaceTools(root, changes, snapshot, "@" + (i + 1) + "/");
            roots.add(new CommandWorkspaceTools(root, commands, snapshot, "@" + (i + 1) + "/"));
        }
        return new MultiWorkspaceTools(snapshot, roots);
    }
    private WorkspaceTools.Result call(WorkspaceTools tools, Run run, String name, Object arguments) throws Exception {
        return tools.execute(run.id(), new ToolCall(UUID.randomUUID().toString(), name, json.writeValueAsString(arguments)), () -> {});
    }

    @Test void namedWorkspaceIsSharedAndMetadataUpdatesPreserveTitlesAndHistory() throws Exception {
        Workspace workspace = workspace();
        Session a = session(workspace), b = session(workspace);
        Run run = start(a);
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        var result = post("/workspaces/" + workspace.id(), body("自定义名称", workspace.directories(), workspace.revision()));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(sessions.snapshot(a.id()).session().title()).isEqualTo("独立会话名");
        assertThat(sessions.snapshot(b.id()).session().workspaceId()).isEqualTo(workspace.id());
        assertThat(sessions.run(run.id()).workspace()).isEqualTo(a.workspace());
        assertThat(sessions.workspace(workspace.id()).name()).isEqualTo("自定义名称");
        assertThat(post("/workspaces/" + workspace.id(), body("覆盖", workspace.directories(), workspace.revision())).statusCode()).isEqualTo(409);
    }

    @Test void validatesEmptyDuplicateMissingAndRelativeDirectoriesWithoutCreatingWorkspaces() throws Exception {
        int before = sessions.workspaces().size();
        for (List<String> roots : List.of(List.<String>of(), List.of(first.toString(), first.toString()), List.of("relative/path"), List.of(first.resolve("missing").toString()), List.of("")))
            assertThat(post("/workspaces", body("invalid", roots, 0)).statusCode()).isEqualTo(400);
        assertThat(post("/workspaces", body(" ", List.of(first.toString()), 0)).statusCode()).isEqualTo(400);
        assertThat(sessions.workspaces()).hasSize(before);
        assertThat(post("/sessions", Map.of("title", "bad", "workspaceId", UUID.randomUUID().toString())).statusCode()).isEqualTo(404);
    }

    @Test void directoryEditsAreBlockedByAnotherBoundSessionsActiveRunAndPendingReview() throws Exception {
        Workspace workspace = workspace();
        Session a = session(workspace), b = session(workspace);
        Run run = start(b);
        var body = body("修改目录", List.of(first.toString()), workspace.revision());
        assertThat(post("/workspaces/" + workspace.id(), body).statusCode()).isEqualTo(409);
        FileChange proposal = changes.propose(run.id(), "change", run.workspace(), "@2/same.txt", "replace", "second-root", "changed");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        assertThat(post("/workspaces/" + workspace.id(), body).statusCode()).isEqualTo(409);
        changes.reject(proposal.id());
        assertThat(post("/workspaces/" + workspace.id(), body).statusCode()).isEqualTo(200);
        assertThat(sessions.snapshot(a.id()).session().workspace()).isEqualTo(first.toString());
        assertThat(sessions.snapshot(b.id()).session().workspace()).isEqualTo(first.toString());
        assertThat(sessions.run(run.id()).workspace()).isEqualTo(WorkspaceRoots.encode(workspace.directories()));
    }

    @Test void switchingSessionsDoesNotOrphanPendingReviewsInTheirOriginalWorkspace() throws Exception {
        Workspace workspace = workspace(); Session session = session(workspace); Run run = start(session);
        FileChange proposal = changes.propose(run.id(), "pending", run.workspace(), "@2/same.txt", "replace", "second-root", "updated");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        var response = post("/workspaces", body("另一个工作区", List.of(first.toString()), 0));
        assertThat(response.statusCode()).isEqualTo(201);
        Workspace other = json.readValue(response.body(), Workspace.class);
        sessions.selectWorkspace(session.id(), other.id());
        assertThat(post("/workspaces/" + other.id(), body("另一个工作区", List.of(second.toString()), other.revision())).statusCode()).isEqualTo(200);
        assertThat(post("/workspaces/" + workspace.id(), body("调整目录", List.of(first.toString()), workspace.revision())).statusCode()).isEqualTo(409);
        sessions.selectWorkspace(session.id(), workspace.id());
        assertThat(changes.apply(proposal.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.readString(second.resolve("same.txt"))).isEqualTo("updated\n");
    }

    @Test void explicitDirectoryRoutesSameNamedFilesAndRejectsMissingRootsAndTraversal() throws Exception {
        Workspace workspace = workspace(); Run run = start(session(workspace)); WorkspaceTools tools = tools(workspace);
        assertThat(call(tools, run, "read_file", Map.of("root", "1", "path", "same.txt")).output()).contains("first-root").doesNotContain("second-root");
        assertThat(call(tools, run, "read_file", Map.of("root", "2", "path", "same.txt")).output()).contains("second-root").doesNotContain("first-root");
        assertThat(call(tools, run, "read_file", Map.of("path", "same.txt")).success()).isFalse();
        assertThat(call(tools, run, "read_file", Map.of("root", "3", "path", "same.txt")).success()).isFalse();
        assertThat(call(tools, run, "read_file", Map.of("root", "1", "path", "../项目二/same.txt")).success()).isFalse();
        assertThat(call(tools, run, "read_file", Map.of("root", "1", "path", ".env")).success()).isFalse();
        for (ToolDefinition definition : tools.definitions()) {
            assertThat(json.valueToTree(definition.parameters()).path("required").toString()).contains("root");
            assertThat(definition.description()).contains(first.toString(), second.toString());
        }
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
    }

    @Test void fileProposalRemembersDirectoryAndApplyChangesOnlyTheSelectedFile() throws Exception {
        Workspace workspace = workspace(); Run run = start(session(workspace)); WorkspaceTools tools = tools(workspace);
        assertThat(call(tools, run, "propose_file_change", Map.of("root", "2", "path", "same.txt", "operation", "replace", "old_text", "second-root", "new_text", "updated")).success()).isTrue();
        FileChange proposal = changes.list(run.id()).getFirst();
        assertThat(proposal.path()).isEqualTo("@2/same.txt");
        assertThat(Files.readString(second.resolve("same.txt"))).contains("second-root");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
        assertThat(changes.apply(proposal.id()).status()).isEqualTo("APPLIED");
        assertThat(Files.readString(first.resolve("same.txt"))).contains("first-root");
        assertThat(Files.readString(second.resolve("same.txt"))).isEqualTo("updated\n");
    }

    @Test void commandApprovalKeepsSelectedDirectoryAndRejectsUnqualifiedOrOutsidePaths() throws Exception {
        Workspace workspace = workspace(); Run run = start(session(workspace));
        assertThatThrownBy(() -> commands.propose(run.id(), "bad", run.workspace(), "echo invalid", ".", "test", 10)).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> commands.propose(run.id(), "bad2", run.workspace(), "echo invalid", "@2/../..", "test", 10)).isInstanceOf(HarnessException.class);
        var command = commands.propose(run.id(), "good", run.workspace(), LocalCommandRunner.WINDOWS ? "[Console]::WriteLine($PWD.Path)" : "pwd", "@2/.", "验证执行目录", 10);
        assertThat(command.directory()).isEqualTo(second.toString());
        assertThat(command.status()).isEqualTo("PENDING");
        commands.approve(command.id());
        assertThat(commands.awaitAndExecute(command, () -> {}).success()).isTrue();
        assertThat(commands.list(run.id()).getFirst().stdout()).contains(second.toString()).doesNotContain(first.toString());
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
    }

    @Test void gitToolsSelectIndependentRepositories() throws Exception {
        assertThat(GitWorkspaceToolsTest.git(first, "init", "-q", "-b", "alpha", ".")).isZero();
        assertThat(GitWorkspaceToolsTest.git(second, "init", "-q", "-b", "beta", ".")).isZero();
        Workspace workspace = workspace(); Run run = start(session(workspace)); WorkspaceTools tools = tools(workspace);
        assertThat(call(tools, run, "git_status", Map.of("root", "1")).output()).contains("alpha").doesNotContain("beta");
        assertThat(call(tools, run, "git_status", Map.of("root", "2")).output()).contains("beta").doesNotContain("alpha");
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
    }

    @Test void legacySessionApiStillUsesRelativeFilePathsAndCanSwitchToChatOnly() throws Exception {
        var created = post("/sessions", Map.of("title", "旧接口", "workspace", first.toString()));
        assertThat(created.statusCode()).isEqualTo(201);
        Session session = json.readValue(created.body(), Session.class);
        assertThat(session.workspace()).isEqualTo(first.toString());
        assertThat(session.workspaceId()).isNotEmpty();
        assertThat(post("/sessions/" + session.id() + "/workspace", Map.of("workspaceId", "")).statusCode()).isEqualTo(200);
        assertThat(sessions.snapshot(session.id()).session().workspace()).isEmpty();
        assertThat(sessions.workspace(session.workspaceId()).directories()).containsExactly(first.toString());
    }

    @Test void migratesOfflineLegacySessionsOnceWithoutRewritingTheirPaths() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).target("9").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        String offline = directory.resolve("offline-project").toString();
        for (String id : List.of("old-a", "old-b"))
            jdbc.update("INSERT INTO harness_session(id,title,created_at,workspace) VALUES (?,?,?,?)", id, "original", OffsetDateTime.now(ZoneOffset.UTC), offline);
        Flyway.configure().dataSource(ds).load().migrate();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var migrated = new JdbcSessionStore(jdbc, tx);
        assertThat(migrated.workspaces()).hasSize(1);
        assertThat(migrated.workspaces().getFirst().name()).isEqualTo("offline-project");
        assertThat(migrated.snapshot("old-a").session().workspace()).isEqualTo(offline);
        assertThat(migrated.snapshot("old-a").session().workspaceId()).isEqualTo(migrated.snapshot("old-b").session().workspaceId());
        assertThat(new JdbcSessionStore(jdbc, tx).workspaces()).hasSize(1);
    }
}
