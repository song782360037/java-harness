package dev.javaharness.server.service;

import dev.javaharness.spi.HarnessException;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 桌面更新准备：协调写请求与定时提交，空闲时短暂阻止新工作。 */
@Component
public final class DesktopUpdateGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private volatile long preparedUntil;

    public DesktopUpdateGate(JdbcTemplate jdbc, @Value("${harness.desktop.update-token:}") String token) {
        this.jdbc = jdbc;
        this.enabled = !token.isBlank();
    }

    /** 写请求和定时检查持有共享锁；准备安装期间拒绝进入。 */
    public boolean enter() {
        if (!enabled) return true;
        if (!lock.readLock().tryLock()) return false;
        if (preparedUntil != 0 && System.nanoTime() - preparedUntil < 0) {
            lock.readLock().unlock();
            return false;
        }
        return true;
    }

    /** 释放一次成功进入的共享锁。 */
    public void leave() {
        if (enabled) lock.readLock().unlock();
    }

    /** 在所有外部提交退出后检查全部运行；失败时不改变运行状态。 */
    public void prepare() {
        if (!lock.writeLock().tryLock()) throw busy();
        try {
            if (count("SELECT COUNT(*) FROM harness_run WHERE status IN ('QUEUED','RUNNING','CANCELLING')") > 0
                    || count("SELECT COUNT(*) FROM harness_group_round WHERE status='RUNNING'") > 0
                    || count("SELECT COUNT(*) FROM harness_group_task WHERE status IN ('PENDING','RUNNING')") > 0
                    || count(
                                    "SELECT COUNT(*) FROM harness_command WHERE status IN ('REVIEWING','PENDING','APPROVED','RUNNING','STOPPING')")
                            > 0) throw busy();
            // 请求应答丢失时自动恢复接收工作，避免客户端永久停留在维护状态。
            preparedUntil = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 安装准备失败时立即恢复接收工作。 */
    public void release() {
        preparedUntil = 0;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private HarnessException busy() {
        return new HarnessException("BUSY", "仍有任务、群聊协作或命令正在进行，请完成或停止后再更新");
    }
}
