package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.group.GroupStore;
import dev.javaharness.server.service.TaskActivityService;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:task-activity;DB_CLOSE_DELAY=-1",
            "harness.automations.enabled=false",
            "harness.session-naming.enabled=false"
        })
class TaskActivityApiIntegrationTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "harness.settings.key-file",
                () -> directory.resolve("settings.key").toString());
    }

    @Autowired
    TaskActivityService activity;

    @Autowired
    SessionStore sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @LocalServerPort
    int port;

    @AfterEach
    void recover() {
        sessions.recoverInterruptedRuns();
        jdbc.update("UPDATE harness_group_round SET status='STOPPED' WHERE status='RUNNING'");
    }

    @Test
    void summarizesAcrossSessionsWithoutReturningModelContent() throws Exception {
        String first = run("任务一");
        String second = run("任务二");
        var response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs/activity"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(response.body()).path("active")).hasSize(2);
        assertThat(activity.snapshot().active())
                .extracting(TaskActivityService.Task::id)
                .contains(first, second);
        assertThat(response.body()).doesNotContain("private prompt", "output", "beforeContent");
    }

    @Test
    void pendingCommandsAndQuestionsAreRealAttentionAndHaveStableKeys() {
        String id = run("需要处理");
        sessions.transition(id, RunStatus.RUNNING, "");
        String question = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_question(id,run_id,call_id,question,options,multi_select,status,created_at)
                VALUES (?,?,?,'选择方案','[]',false,'PENDING',?)
                """, question, id, question, OffsetDateTime.now());
        assertThat(task(id).status()).isEqualTo("WAITING_ANSWER");
        assertThat(task(id).attentionKey()).isEqualTo(question);
        String command = command(id);
        assertThat(task(id).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(task(id).attentionKey()).contains(command, question);
        jdbc.update("UPDATE harness_command SET status='REVIEWING' WHERE id=?", command);
        assertThat(task(id).status()).isEqualTo("WAITING_ANSWER");
        jdbc.update("UPDATE harness_question SET status='ANSWERED' WHERE id=?", question);
        assertThat(task(id).status()).isEqualTo("RUNNING");
        sessions.transition(id, RunStatus.COMPLETED, "");
        assertThat(activity.snapshot().active())
                .extracting(TaskActivityService.Task::id)
                .doesNotContain(id);
        assertThat(activity.snapshot().recent())
                .anyMatch(value -> value.id().equals(id) && value.status().equals("COMPLETED"));
    }

    @Test
    void groupPlanningIsVisibleAndMemberRunsDoNotDuplicateTheRound() {
        String group = UUID.randomUUID().toString();
        String round = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_group(id,name,color,host_profile_id,max_turns,created_at,revision)
                VALUES (?,'协作组','#123456','default',3,?,0)
                """, group, OffsetDateTime.now());
        jdbc.update(
                "INSERT INTO harness_group_round(id,group_id,status,turns,detail,created_at) VALUES (?,?,'RUNNING',0,'',?)",
                round,
                group,
                OffsetDateTime.now());
        assertThat(task(round).source()).isEqualTo("GROUP");
        String member = run("隐藏成员");
        String session = sessions.run(member).sessionId();
        jdbc.update("UPDATE harness_session SET group_id=? WHERE id=?", group, session);
        sessions.transition(member, RunStatus.RUNNING, "");
        command(member);
        assertThat(task(round).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(activity.snapshot().active())
                .extracting(TaskActivityService.Task::id)
                .doesNotContain(member);
    }

    @Test
    void childAttentionIsFoldedIntoParentAndCancellationDoesNotClaimApprovalIsPossible() {
        String parent = run("父任务");
        String child = run("子任务");
        jdbc.update("UPDATE harness_run SET parent_run_id=? WHERE id=?", parent, child);
        sessions.transition(parent, RunStatus.RUNNING, "");
        sessions.transition(child, RunStatus.RUNNING, "");
        command(child);
        assertThat(task(parent).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(activity.snapshot().active())
                .extracting(TaskActivityService.Task::id)
                .doesNotContain(child);
        sessions.transition(parent, RunStatus.CANCELLING, "");
        assertThat(task(parent).status()).isEqualTo("CANCELLING");
    }

    @Test
    void recentHistoryIsBoundedAndDoesNotTruncateActiveTasks() {
        for (int i = 0; i < 55; i++) {
            String id = run("历史" + i);
            sessions.transition(id, RunStatus.RUNNING, "");
            sessions.transition(id, RunStatus.COMPLETED, "");
        }
        String active = run("仍在排队");
        assertThat(activity.snapshot().recent()).hasSize(50);
        assertThat(task(active).status()).isEqualTo("QUEUED");
    }

    @Test
    void oldGroupEndingWithoutNewMessagesIsFirstEvenAfterFiftyOtherCompletions() {
        String group = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_group(id,name,color,host_profile_id,max_turns,created_at,revision)
                VALUES (?,'长时间规划','#123456','default',3,?,0)
                """, group, OffsetDateTime.now().minusDays(1));
        var groups = new GroupStore(jdbc);
        String round = groups.startRound(group).id();
        jdbc.update(
                "UPDATE harness_group_round SET created_at=?,state_updated_at=? WHERE id=?",
                OffsetDateTime.now().minusDays(1),
                OffsetDateTime.now().minusDays(1),
                round);
        for (int i = 0; i < 51; i++) {
            String id = run("期间完成的任务" + i);
            sessions.transition(id, RunStatus.RUNNING, "");
            sessions.transition(id, RunStatus.COMPLETED, "");
            jdbc.update(
                    "UPDATE harness_run SET state_updated_at=? WHERE id=?",
                    OffsetDateTime.now().minusHours(1),
                    id);
        }
        groups.updateRound(round, "FAILED", 0, "规划失败");
        assertThat(activity.snapshot().recent().getFirst().id()).isEqualTo(round);
        assertThat(activity.snapshot().recent().getFirst().status()).isEqualTo("FAILED");
    }

    @Test
    void taskQueryDoesNotWaitForTheRuntimeCoordinationLock() throws Exception {
        synchronized (sessions) {
            assertThat(CompletableFuture.supplyAsync(() -> activity.snapshot()).get(3, TimeUnit.SECONDS))
                    .isNotNull();
        }
    }

    @Test
    void automationNameAndSourceArePreserved() {
        String run = run("自动创建的会话");
        String automation = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_automation(id,name,prompt,workspace_id,model_profile_id,schedule_kind,
                    schedule_time,schedule_weekday,cron,allow_commands,enabled,created_at,revision)
                VALUES (?,'每日检查','test','','default','MANUAL','09:00',1,'',false,true,?,1)
                """, automation, OffsetDateTime.now());
        jdbc.update("""
                INSERT INTO harness_automation_run(id,automation_id,trigger_kind,started_at,status,detail,run_id)
                VALUES (?,?,'MANUAL',?,'STARTED','',?)
                """, UUID.randomUUID().toString(), automation, OffsetDateTime.now(), run);
        assertThat(task(run).source()).isEqualTo("AUTOMATION");
        assertThat(task(run).title()).isEqualTo("每日检查");
    }

    @Test
    void v28HistoryGetsItsLastKnownStateTimeWithoutLosingRecords() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).target("28").load().migrate();
        var old = new JdbcTemplate(ds);
        var created = OffsetDateTime.parse("2026-11-01T10:00:00Z");
        var finished = created.plusHours(1);
        old.update("INSERT INTO harness_session(id,title,workspace,created_at) VALUES ('s','legacy','',?)", created);
        for (String id : new String[] {"with-event", "without-event"}) old.update("""
                    INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model,workspace)
                    VALUES (?,'s',?,'old','NORMAL','COMPLETED','answer','',?,'test','test','')
                    """, id, id, created);
        old.update("""
                INSERT INTO harness_event(session_id,seq,run_id,event_type,status,event_text,created_at)
                VALUES ('s',1,'with-event','STATE','COMPLETED','',?)
                """, finished);
        old.update("""
                INSERT INTO harness_group(id,name,color,host_profile_id,max_turns,created_at,revision)
                VALUES ('g','legacy','#123456','default',3,?,0)
                """, created);
        for (String id : new String[] {"with-message", "without-message"}) old.update("""
                    INSERT INTO harness_group_round(id,group_id,status,turns,detail,created_at)
                    VALUES (?,'g','COMPLETED',1,'',?)
                    """, id, created);
        old.update("""
                INSERT INTO harness_group_message(group_id,seq,id,round_id,author,author_name,content,created_at)
                VALUES ('g',1,'m','with-message','MEMBER','legacy','answer',?)
                """, finished);
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(old.queryForObject(
                        "SELECT state_updated_at FROM harness_run WHERE id='with-event'", OffsetDateTime.class))
                .isEqualTo(finished);
        assertThat(old.queryForObject(
                        "SELECT state_updated_at FROM harness_run WHERE id='without-event'", OffsetDateTime.class))
                .isEqualTo(created);
        assertThat(old.queryForObject(
                        "SELECT state_updated_at FROM harness_group_round WHERE id='with-message'",
                        OffsetDateTime.class))
                .isEqualTo(finished);
        assertThat(old.queryForObject(
                        "SELECT state_updated_at FROM harness_group_round WHERE id='without-message'",
                        OffsetDateTime.class))
                .isEqualTo(created);
        assertThat(old.queryForObject("SELECT output FROM harness_run WHERE id='with-event'", String.class))
                .isEqualTo("answer");
        old.execute("SHUTDOWN");
    }

    private String run(String title) {
        var session = sessions.createSession(title);
        return sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "private prompt",
                        "NORMAL",
                        new ModelInfo("test", "test", true))
                .run()
                .id();
    }

    private String command(String runId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_command(id,run_id,call_id,command_text,directory,shell,purpose,timeout_seconds,status,created_at)
                VALUES (?,?,?,'private command','.','powershell','',10,'PENDING',?)
                """, id, runId, id, OffsetDateTime.now());
        return id;
    }

    private TaskActivityService.Task task(String id) {
        return activity.snapshot().active().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
