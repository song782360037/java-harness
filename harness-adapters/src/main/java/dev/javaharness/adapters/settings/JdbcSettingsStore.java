package dev.javaharness.adapters.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Single-process settings owner. Provider/model changes and legacy conversion are atomic. */
public final class JdbcSettingsStore {
    private final JdbcTemplate jdbc;
    private final LocalSecretCipher secrets;
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionTemplate tx;
    public JdbcSettingsStore(JdbcTemplate jdbc, LocalSecretCipher secrets) {
        this.jdbc = jdbc; this.secrets = secrets;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        migrateProfiles();
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception error) { throw new IllegalStateException("配置存储数据无效"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("配置无法保存"); }
    }
    private static HarnessException conflict() { return new HarnessException("CONFLICT", "配置已更新，请重新加载后编辑"); }
    private static String normalizedUrl(String value) { return value.strip().replaceAll("/+$", ""); }

    private void migrateProfiles() {
        tx.executeWithoutResult(status -> {
            record Legacy(String id, String name, String settings) {}
            var rows = jdbc.query("SELECT id,display_name,settings_json FROM harness_model_profile WHERE provider_id IS NULL ORDER BY CASE WHEN id='default' THEN 0 ELSE 1 END,id",
                    (rs, row) -> new Legacy(rs.getString(1), rs.getString(2), rs.getString(3)));
            for (var row : rows) {
                var old = decode(row.settings(), HarnessSettings.class);
                String key = secrets.decrypt(old.apiKey());
                var match = matchingProvider(old.baseUrl(), key, old.apiKeyRequired(), old.connectTimeoutSeconds());
                String providerId;
                if (match != null) providerId = match.id();
                else {
                    providerId = row.id().equals("default") ? "default" : UUID.randomUUID().toString();
                    String name = row.id().equals("default") ? "默认提供商" : row.name();
                    jdbc.update("INSERT INTO harness_model_provider VALUES (?,?,?,?,?,?,?)", providerId, 1,
                            name, normalizedUrl(old.baseUrl()), old.apiKey(), old.apiKeyRequired(), old.connectTimeoutSeconds());
                }
                jdbc.update("UPDATE harness_model_profile SET provider_id=?,settings_json=? WHERE id=?", providerId, encode(ModelParameters.from(old)), row.id());
            }
        });
    }
    private ProviderSettings matchingProvider(String url, String key, boolean required, int timeout) {
        return providers().stream().filter(p -> p.baseUrl().equals(normalizedUrl(url)) && p.apiKey().equals(key)
                && p.apiKeyRequired() == required && p.connectTimeoutSeconds() == timeout).findFirst().orElse(null);
    }
    private ProviderSettings readProvider(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProviderSettings(rs.getString("id"), rs.getLong("revision"), rs.getString("display_name"), rs.getString("base_url"),
                secrets.decrypt(rs.getString("api_key")), rs.getBoolean("api_key_required"), rs.getInt("connect_timeout_seconds"));
    }
    public synchronized List<ProviderSettings> providers() {
        return jdbc.query("SELECT * FROM harness_model_provider ORDER BY display_name,id", (rs, row) -> readProvider(rs));
    }
    public synchronized ProviderSettings provider(String id) {
        var rows = jdbc.query("SELECT * FROM harness_model_provider WHERE id=?", (rs, row) -> readProvider(rs), id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "提供商不存在，请重新选择");
        return rows.getFirst();
    }
    public synchronized ProviderSettings saveProvider(ProviderSettings requested) {
        return tx.execute(status -> {
            String id = requested.id();
            String encrypted = secrets.encrypt(requested.apiKey());
            if (id == null) {
                id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO harness_model_provider VALUES (?,?,?,?,?,?,?)", id, 1, requested.displayName(),
                        normalizedUrl(requested.baseUrl()), encrypted, requested.apiKeyRequired(), requested.connectTimeoutSeconds());
            } else {
                if (jdbc.update("UPDATE harness_model_provider SET revision=revision+1,display_name=?,base_url=?,api_key=?,api_key_required=?,connect_timeout_seconds=? WHERE id=? AND revision=?",
                        requested.displayName(), normalizedUrl(requested.baseUrl()), encrypted, requested.apiKeyRequired(), requested.connectTimeoutSeconds(), id, requested.revision()) != 1) throw conflict();
                // The assembled model configuration changed too; reject stale model/legacy edits.
                jdbc.update("UPDATE harness_model_profile SET revision=revision+1 WHERE provider_id=?", id);
            }
            return provider(id);
        });
    }
    public synchronized void deleteProvider(String id, long revision) {
        tx.executeWithoutResult(status -> {
            provider(id);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM harness_model_profile WHERE provider_id=?", Integer.class, id) > 0)
                throw new HarnessException("CONFLICT", "请先删除或移动该提供商下的模型");
            if (jdbc.update("DELETE FROM harness_model_provider WHERE id=? AND revision=?", id, revision) != 1) throw conflict();
        });
    }
    public synchronized HarnessSettings current() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM harness_model_profile WHERE id='default'", Integer.class) == 0 ? null : profile("default").settings();
    }
    public synchronized void initialize(HarnessSettings initial) {
        if (current() != null) return;
        tx.executeWithoutResult(status -> {
            var provider = saveProvider(new ProviderSettings(null, 0, "默认提供商", initial.baseUrl(), initial.apiKey(), initial.apiKeyRequired(), initial.connectTimeoutSeconds()));
            jdbc.update("INSERT INTO harness_model_profile(id,revision,display_name,reasoning_effort,settings_json,provider_id) VALUES ('default',1,'默认模型','',?,?)",
                    encode(ModelParameters.from(initial)), provider.id());
        });
    }
    private ModelProfile readProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
        var provider = provider(rs.getString("provider_id"));
        return new ModelProfile(rs.getString("id"), rs.getString("display_name"), rs.getString("reasoning_effort"), provider.id(), provider.displayName(),
                decode(rs.getString("settings_json"), ModelParameters.class).resolve(rs.getLong("revision"), provider));
    }
    public synchronized List<ModelProfile> profiles() {
        return jdbc.query("SELECT * FROM harness_model_profile ORDER BY CASE WHEN id='default' THEN 0 ELSE 1 END,display_name,id", (rs, row) -> readProfile(rs));
    }
    public synchronized ModelProfile profile(String id) {
        var rows = jdbc.query("SELECT * FROM harness_model_profile WHERE id=?", (rs, row) -> readProfile(rs), id);
        if (rows.isEmpty()) throw new HarnessException("NOT_FOUND", "模型配置不存在，请重新选择模型");
        return rows.getFirst();
    }
    public synchronized ModelProfile saveModel(String id, String providerId, String name, String effort, ModelParameters parameters, long revision) {
        provider(providerId);
        if (id == null) {
            id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO harness_model_profile(id,revision,display_name,reasoning_effort,settings_json,provider_id) VALUES (?,1,?,?,?,?)",
                    id, name, effort, encode(parameters), providerId);
        } else if (jdbc.update("UPDATE harness_model_profile SET revision=revision+1,display_name=?,reasoning_effort=?,settings_json=?,provider_id=? WHERE id=? AND revision=?",
                name, effort, encode(parameters), providerId, id, revision) != 1) throw conflict();
        return profile(id);
    }
    /** Compatibility for the former single-model settings endpoint. */
    public synchronized HarnessSettings save(HarnessSettings settings, long expectedRevision) {
        var profile = profile("default");
        return saveProfile("default", profile.displayName(), profile.reasoningEffort(), settings, expectedRevision).settings();
    }
    public synchronized ModelProfile saveProfile(String id, String name, String effort, HarnessSettings settings, long revision) {
        return tx.execute(status -> {
            var model = profile(id);
            if (model.settings().revision() != revision) throw conflict();
            var old = provider(model.providerId());
            saveProvider(new ProviderSettings(old.id(), old.revision(), old.displayName(), settings.baseUrl(), settings.apiKey(), settings.apiKeyRequired(), settings.connectTimeoutSeconds()));
            return saveModel(id, old.id(), name, effort, ModelParameters.from(settings), profile(id).settings().revision());
        });
    }
    public synchronized ModelProfile createProfile(String name, String effort, HarnessSettings settings) {
        return tx.execute(status -> {
            var shared = matchingProvider(settings.baseUrl(), settings.apiKey(), settings.apiKeyRequired(), settings.connectTimeoutSeconds());
            if (shared == null) shared = saveProvider(new ProviderSettings(null, 0, name, settings.baseUrl(), settings.apiKey(), settings.apiKeyRequired(), settings.connectTimeoutSeconds()));
            return saveModel(null, shared.id(), name, effort, ModelParameters.from(settings), 0);
        });
    }
    public synchronized void deleteProfile(String id, long revision) {
        if ("default".equals(id)) throw new HarnessException("INVALID", "默认模型配置需要保留，可以编辑其内容");
        profile(id);
        if (jdbc.update("DELETE FROM harness_model_profile WHERE id=? AND revision=?", id, revision) != 1) throw conflict();
    }
}
