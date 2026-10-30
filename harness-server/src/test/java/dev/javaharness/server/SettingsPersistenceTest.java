package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.adapters.settings.JdbcSettingsStore;
import dev.javaharness.adapters.settings.LocalSecretCipher;
import dev.javaharness.adapters.settings.ModelParameters;
import dev.javaharness.adapters.settings.ProviderSettings;
import dev.javaharness.server.service.SettingsService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SettingsPersistenceTest {
    @TempDir
    Path directory;

    private static HarnessSettings initial(String key) {
        return new HarnessSettings(1, "https://api.example.com/v1", key, "model-a", true, 10, 120, 32000, 0, 5);
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, "sa", "");
    }

    private static JdbcSessionStore sessions(DriverManagerDataSource ds) {
        return new JdbcSessionStore(
                new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test
    void encryptedSettingsAndSessionWorkspacesSurviveFileDatabaseReopen() throws Exception {
        String url =
                "jdbc:h2:file:" + directory.resolve("persistent").toString().replace('\\', '/');
        Path keyFile = directory.resolve("settings.key");
        var ds = dataSource(url);
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var settings = new SettingsService(
                new JdbcSettingsStore(jdbc, new LocalSecretCipher(keyFile)), initial("secret-to-persist"));
        var session =
                sessions(ds).createSession("workspace", directory.toRealPath().toString());
        var updated = settings.save(
                new HarnessSettings(1, "https://new.example/v1", "", "model-b", true, 4, 600, 64000, 0, 8), false);
        assertThat(updated.apiKey()).isEqualTo("secret-to-persist");
        assertThat(jdbc.queryForObject("SELECT api_key FROM harness_model_provider", String.class))
                .contains("v1:")
                .doesNotContain("secret-to-persist");
        assertThat(Files.size(keyFile)).isEqualTo(32);
        jdbc.execute("SHUTDOWN");

        var reopened = dataSource(url);
        var restored = new SettingsService(
                new JdbcSettingsStore(new JdbcTemplate(reopened), new LocalSecretCipher(keyFile)),
                initial("ignored-environment-key"));
        assertThat(restored.current()).isEqualTo(updated);
        assertThat(sessions(reopened).snapshot(session.id()).session().workspace())
                .isEqualTo(directory.toRealPath().toString());
        assertThat(restored.current().toString()).doesNotContain("secret-to-persist");
        new JdbcTemplate(reopened).execute("SHUTDOWN");
    }

    @Test
    void missingOrWrongKeyFailsWithoutReplacingKeyOrExposingCredentials() throws Exception {
        Path key = directory.resolve("original.key");
        var cipher = new LocalSecretCipher(key);
        String encrypted = cipher.encrypt("hidden-credential");
        assertThat(cipher.encrypt("hidden-credential")).isNotEqualTo(encrypted);
        Path missing = directory.resolve("missing.key");
        assertThatThrownBy(() -> new LocalSecretCipher(missing).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("hidden-credential")
                .hasNoCause();
        assertThat(missing).doesNotExist();
        var wrong = new LocalSecretCipher(directory.resolve("wrong.key"));
        wrong.encrypt("different");
        assertThatThrownBy(() -> wrong.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        assertThat(cipher.decrypt(encrypted)).isEqualTo("hidden-credential");
    }

    @Test
    void keyRetainReplaceClearValidationAndOptimisticConflict() {
        var ds = dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).load().migrate();
        var storage = new JdbcSettingsStore(new JdbcTemplate(ds), new LocalSecretCipher(directory.resolve("key")));
        var settings = new SettingsService(storage, initial("first"));
        var second = settings.save(initial("second"), false);
        assertThat(second.revision()).isGreaterThan(1);
        assertThat(settings.save(second.withKeyAndRevision("", second.revision()), false)
                        .apiKey())
                .isEqualTo("second");
        assertThatThrownBy(() -> storage.save(initial("stale"), 1)).isInstanceOf(HarnessException.class);
        assertThat(settings.current().apiKey()).isEqualTo("second");
        var before = settings.current();
        assertThatThrownBy(() -> settings.save(
                        new HarnessSettings(
                                before.revision(), "http://remote.example", "", "model", true, 10, 120, 32000, 0, 5),
                        false))
                .isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> settings.save(before, true)).isInstanceOf(HarnessException.class);
        assertThat(settings.current()).isEqualTo(before);
        assertThat(settings.save(before.withKeyAndRevision("", before.revision()), true)
                        .apiKey())
                .isEmpty();
        assertThat(SettingsService.info(settings.current()).configured()).isFalse();
    }

    @Test
    void migrationBackfillsHistoricalWorkspaceWithoutChangingOldRuns() {
        var ds = dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).target("4").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO harness_session(id,title,created_at) VALUES ('old','old',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO harness_session(id,title,created_at) VALUES ('empty','empty',CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at,provider,model,workspace)
                VALUES ('run','old','request','hello','NORMAL','COMPLETED','answer','',CURRENT_TIMESTAMP,'openai-compatible','old-model','old-workspace')
                """);
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(sessions(ds).snapshot("old").session().workspace()).isEqualTo("old-workspace");
        assertThat(sessions(ds).snapshot("empty").session().workspace()).isEmpty();
        assertThat(sessions(ds).run("run").output()).isEqualTo("answer");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM harness_model_profile", Integer.class))
                .isZero();
    }

    @Test
    void v5ConfigurationMigratesWithoutReencryptingAndMultipleProfilesSurviveReopen() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("profiles").toString().replace('\\', '/');
        var ds = dataSource(url);
        Flyway.configure().dataSource(ds).target("5").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        Path keyFile = directory.resolve("profile.key");
        var cipher = new LocalSecretCipher(keyFile);
        String encrypted = cipher.encrypt("legacy-key");
        String legacy = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(initial(encrypted).withKeyAndRevision(encrypted, 7));
        jdbc.update("INSERT INTO harness_settings(id,revision,settings_json) VALUES (1,7,?)", legacy);
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(jdbc.queryForObject(
                        "SELECT settings_json FROM harness_model_profile WHERE id='default'", String.class))
                .isEqualTo(legacy);
        var service = new SettingsService(new JdbcSettingsStore(jdbc, cipher), initial("ignored"));
        assertThat(service.current().apiKey()).isEqualTo("legacy-key");
        assertThat(service.current().revision()).isEqualTo(7);
        var added = service.saveProfile(
                null, "Deep analysis", "high", initial("second-key").withKeyAndRevision("second-key", 0), false);
        assertThat(service.profile("default").reasoningEffort()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT settings_json FROM harness_model_profile WHERE id=?", String.class, added.id()))
                .doesNotContain("second-key");
        jdbc.execute("SHUTDOWN");
        var reopened = dataSource(url);
        var restored = new SettingsService(
                new JdbcSettingsStore(new JdbcTemplate(reopened), new LocalSecretCipher(keyFile)),
                initial("ignored-again"));
        assertThat(restored.profiles()).hasSize(2);
        assertThat(restored.profile(added.id())).isEqualTo(added);
        assertThat(restored.snapshot(new ModelSelection(added.id(), null))
                        .selection()
                        .reasoningEffort())
                .isEqualTo("high");
        assertThat(restored.snapshot(new ModelSelection(added.id(), ""))
                        .selection()
                        .reasoningEffort())
                .isEmpty();
        assertThatThrownBy(() -> restored.deleteProfile("default", 7)).isInstanceOf(HarnessException.class);
        restored.deleteProfile(added.id(), 1);
        assertThat(restored.profiles()).hasSize(1);
        assertThatThrownBy(() -> restored.snapshot(new ModelSelection(added.id(), "low")))
                .isInstanceOf(HarnessException.class);
        new JdbcTemplate(reopened).execute("SHUTDOWN");
    }

    @Test
    void v6MigrationGroupsIdenticalProvidersAndRemovesConnectionFieldsFromModels() throws Exception {
        var ds = dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).target("6").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cipher = new LocalSecretCipher(directory.resolve("migration.key"));
        for (String id : new String[] {"default", "sibling", "other-account", "other-url"}) {
            String key = cipher.encrypt(id.equals("other-account") ? "different-key" : "shared-key");
            var old = new HarnessSettings(
                    3,
                    id.equals("other-url") ? "https://other.example/v1" : "https://shared.example/v1",
                    key,
                    id,
                    true,
                    10,
                    120,
                    32000,
                    0,
                    5);
            jdbc.update(
                    "INSERT INTO harness_model_profile(id,revision,display_name,reasoning_effort,settings_json) VALUES (?,3,?,'high',?)",
                    id,
                    id,
                    mapper.writeValueAsString(old));
        }
        Flyway.configure().dataSource(ds).load().migrate();
        var storage = new JdbcSettingsStore(jdbc, cipher);
        assertThat(storage.providers()).hasSize(3);
        assertThat(storage.profile("default").providerId())
                .isEqualTo(storage.profile("sibling").providerId());
        assertThat(storage.profile("other-account").providerId())
                .isNotEqualTo(storage.profile("default").providerId());
        assertThat(storage.profile("other-url").providerId())
                .isNotEqualTo(storage.profile("default").providerId());
        assertThat(storage.profile("default").settings().apiKey()).isEqualTo("shared-key");
        assertThat(jdbc.queryForList("SELECT settings_json FROM harness_model_profile", String.class))
                .allSatisfy(
                        value -> assertThat(value).doesNotContain("baseUrl", "apiKey", "connectTimeoutSeconds", "v1:"));
        assertThat(new JdbcSettingsStore(jdbc, cipher).providers()).hasSize(3);
    }

    @Test
    void providerUpdateAppliesToAllModelsButExistingSnapshotsKeepOriginalCredentials() throws Exception {
        var ds = dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var service = new SettingsService(
                new JdbcSettingsStore(jdbc, new LocalSecretCipher(directory.resolve("shared.key"))),
                initial("old-key"));
        String providerId = service.profile("default").providerId();
        var sibling = service.saveModel(
                null, providerId, "Sibling", "high", new ModelParameters("sibling-model", 600, 64000, 0, 5), 0);
        var captured = service.snapshot(new ModelSelection(sibling.id(), null));
        var previous = service.provider(providerId);
        var changed = service.saveProvider(
                new ProviderSettings(
                        providerId,
                        previous.revision(),
                        "Renamed provider",
                        "https://new.example/v1",
                        "new-key",
                        true,
                        5),
                false);
        assertThat(service.profile("default").settings().apiKey()).isEqualTo("new-key");
        assertThat(service.profile(sibling.id()).settings().apiKey()).isEqualTo("new-key");
        assertThat(service.profile(sibling.id()).settings().baseUrl()).isEqualTo("https://new.example/v1");
        assertThat(service.profile(sibling.id()).settings().runTimeoutSeconds()).isEqualTo(600);
        assertThat(captured.selection().reasoningEffort()).isEqualTo("high");
        assertThat(captured.timeout()).isEqualTo(java.time.Duration.ofSeconds(600));
        assertThatThrownBy(() -> service.saveModel(
                        sibling.id(),
                        providerId,
                        "stale",
                        "",
                        new ModelParameters("stale", 120, 32000, 0, 5),
                        sibling.settings().revision()))
                .isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> service.deleteProvider(providerId, changed.revision()))
                .isInstanceOf(HarnessException.class);
        var cleared = service.saveProvider(
                new ProviderSettings(
                        providerId, changed.revision(), changed.displayName(), changed.baseUrl(), "", true, 5),
                true);
        assertThat(service.profiles()).allMatch(p -> p.settings().apiKey().isEmpty());
        assertThat(jdbc.queryForObject(
                        "SELECT api_key FROM harness_model_provider WHERE id=?", String.class, providerId))
                .isEmpty();
        assertThatThrownBy(() -> service.saveProvider(previous, false)).isInstanceOf(HarnessException.class);
        assertThat(cleared.apiKey()).isEmpty();
    }
}
