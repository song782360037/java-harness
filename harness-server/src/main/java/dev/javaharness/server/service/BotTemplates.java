package dev.javaharness.server.service;

import dev.javaharness.spi.HarnessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bot 预设外形、配色、装扮目录、入职角色模板与确定性经验等级计算。 */
public final class BotTemplates {
    public record OutfitSpec(String id, String slot, int unlockLevel, String label) {}

    public static final List<OutfitSpec> OUTFITS = List.of(
            new OutfitSpec("partyHat", "head", 1, "派对帽"),
            new OutfitSpec("bronzeCrown", "head", 10, "青铜冠"),
            new OutfitSpec("silverCrown", "head", 30, "白银冠"),
            new OutfitSpec("goldCrown", "head", 60, "黄金冠"),
            new OutfitSpec("halo", "head", 90, "光环"),
            new OutfitSpec("rainbowCrown", "head", 99, "彩虹冠"),
            new OutfitSpec("roundGlasses", "face", 1, "圆框眼镜"),
            new OutfitSpec("sunglasses", "face", 10, "墨镜"),
            new OutfitSpec("monocle", "face", 60, "单片眼镜"),
            new OutfitSpec("bowTie", "neck", 1, "蝴蝶结"),
            new OutfitSpec("tie", "neck", 30, "领带"),
            new OutfitSpec("medal", "neck", 90, "勋章"));

    private static final Map<String, OutfitSpec> OUTFIT_MAP = new LinkedHashMap<>();

    static {
        for (OutfitSpec spec : OUTFITS) {
            OUTFIT_MAP.put(spec.id(), spec);
        }
    }
    public static final List<String> SHAPES = List.of(
            "orb",
            "cube",
            "radar",
            "visor",
            "astro",
            "clockwork",
            "fox",
            "cat",
            "owl",
            "bear",
            "bunny",
            "shiba",
            "leaf",
            "clover",
            "panda",
            "penguin",
            "frog",
            "octopus",
            "hex",
            "prism",
            "pill",
            "chef",
            "wizard",
            "coffee",
            "star",
            "cloud",
            "ghost",
            "dino",
            "duck",
            "rocket");
    public static final List<String> COLORS = List.of(
            "#276649", "#2d5d9c", "#b04a6f", "#6e56a8", "#c08238", "#2f7d7a", "#a63d34", "#4b5563", "#0284c7",
            "#d97706", "#0d9488", "#7c3aed");
    public static final Set<String> SHAPE_SET = Set.copyOf(SHAPES);

    public record RoleTemplate(
            String key, String name, String roleSummary, String systemPrompt, String shape, String color) {}

    public record Experience(int xp, int level, String tier, int currentLevelXp, int nextLevelXp, double progress) {}

    public static final String DEFAULT_MAIN_NAME = "小满";
    public static final String DEFAULT_MAIN_ROLE = "团队总管：统筹接活、拆解任务并派给合适的专职 Bot，也能按你的要求招募新成员";
    public static final String DEFAULT_MAIN_PROMPT = """
            你是用户桌面上的主 Bot（团队总管）。
            你的核心职责：
            1. 清晰承接用户的综合需求，简单咨询直接回答；
            2. 涉及查资料、读/写代码、跑命令、写报告等动手活时，优先拆解并调用 delegate_to_bot 派给合适的专职 Bot，或直接调用 start_task 在后台执行；
            3. 用户要求「招一个新 Bot / 建个助手」时，直接调用 create_bot 创建并介绍新成员；
            4. 回复中提及团队里的某个 Bot 时，请用 [Bot名称](bot:BotID) 格式，方便用户一键跳转。
            回答保持自然、干练、有条理。
            """;

    public static final List<RoleTemplate> STARTER_ROLES = List.of(
            new RoleTemplate(
                    "code-helper",
                    "编程搭档",
                    "负责代码分析、需求实现、重构与排错",
                    "你是一位严谨的软件工程搭档。收到代码排查、修改或实现需求时，通过 start_task 在后台阅读真实代码并给出可验证的结果。",
                    "cube",
                    "#2d5d9c"),
            new RoleTemplate(
                    "release-assistant",
                    "发版助理",
                    "整理变更日志、发版检查清单与版本汇报",
                    "你是一位细致的发版助理，擅长整理 Git 变更、生成中英文发布说明、核对发版风险清单并归档到资料库。",
                    "rocket",
                    "#6e56a8"),
            new RoleTemplate(
                    "bug-triage",
                    "Bug 排查员",
                    "定位异常根因、复现路径与修复建议",
                    "你是一位冷静的故障排查专家，接到问题后通过后台任务收集日志与源码证据，输出根因、影响范围和最小修复方案。",
                    "prism",
                    "#a63d34"),
            new RoleTemplate(
                    "doc-writer",
                    "文档整理官",
                    "沉淀架构文档、会议纪要与资料库笔记",
                    "你是一位结构清晰的技术文档整理官，擅长把零散讨论整理成可读性强的 Markdown 文档并写入资料库。",
                    "owl",
                    "#2f7d7a"),
            new RoleTemplate(
                    "daily-brief",
                    "每日简报员",
                    "汇总工作进展、待办事项与例行巡检简报",
                    "你是一位高效的简报助理，擅长按固定结构汇总每日/每周进展、阻塞项与下一步行动建议。",
                    "coffee",
                    "#c08238"));

    private BotTemplates() {}

    public static RoleTemplate find(String key) {
        if (key == null || key.isBlank()) return null;
        return STARTER_ROLES.stream()
                .filter(template -> template.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    public static String normalizeShape(String shape) {
        if (shape != null && SHAPE_SET.contains(shape)) return shape;
        return SHAPES.getFirst();
    }

    public static String normalizeColor(String color) {
        if (color != null && color.matches("#[0-9a-fA-F]{6}")) return color;
        return COLORS.getFirst();
    }

    public static boolean isOutfitUnlocked(String outfitId, int level, boolean mainBot) {
        OutfitSpec spec = OUTFIT_MAP.get(outfitId);
        if (spec == null) return false;
        if (mainBot && "goldCrown".equals(outfitId)) return true;
        return level >= spec.unlockLevel();
    }

    /** 校验装扮穿戴权限（主 Bot 始终拥有 goldCrown，其余按等级解锁，每个槽位最多 1 件），返回规范化列表。 */
    public static List<String> validateOutfit(List<String> requested, int level, boolean mainBot) {
        if (requested == null || requested.isEmpty()) return List.of();
        var bySlot = new LinkedHashMap<String, String>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) continue;
            String id = raw.strip();
            OutfitSpec spec = OUTFIT_MAP.get(id);
            if (spec == null) throw new HarnessException("INVALID", "未知的装扮部件：" + id);
            if (!isOutfitUnlocked(id, level, mainBot)) {
                throw new HarnessException("CONFLICT", "「" + spec.label() + "」需要达到 Lv." + spec.unlockLevel() + " 才能解锁");
            }
            bySlot.put(spec.slot(), id);
        }
        return new ArrayList<>(bySlot.values());
    }

    public static String toOutfitJson(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ids.get(i).replace("\"", "")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    /** 确定性计算 Bot 经验与 Lv.1–99 等级：对话 +10，做法 +50，按做法完成任务 +30，普通完成任务 +15。 */
    public static Experience experience(int turns, int playbooks, int playbookTasks, int totalCompletedTasks) {
        int plainTasks = Math.max(0, totalCompletedTasks - playbookTasks);
        int xp = Math.max(0, turns * 10 + playbooks * 50 + playbookTasks * 30 + plainTasks * 15);
        int level = 1;
        int floor = 0;
        while (level < 99) {
            int needed = xpForNextLevel(level);
            if (xp < floor + needed) break;
            floor += needed;
            level++;
        }
        int span = level >= 99 ? 1 : xpForNextLevel(level);
        double progress = level >= 99 ? 1.0 : Math.min(1.0, Math.max(0.0, (double) (xp - floor) / span));
        String tier = level >= 99
                ? "MASTER"
                : level >= 60 ? "EXPERT" : level >= 30 ? "SENIOR" : level >= 10 ? "ADEPT" : "NOVICE";
        return new Experience(xp, level, tier, xp - floor, span, progress);
    }

    private static int xpForNextLevel(int level) {
        return 40 + (level - 1) * 20;
    }
}
