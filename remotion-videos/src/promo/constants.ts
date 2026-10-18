// ==================== 可编辑常量区 ====================
// 所有文案、配色、时间轴集中在此处，改这里即可调整视频内容。

/** 品牌与配色（取自 Java Harness 工作台自身的墨绿主题） */
export const BRAND = {
  bg: '#08120F', // 深墨绿背景
  bgSoft: '#0D1B17',
  surface: '#12241E',
  border: 'rgba(110, 231, 183, 0.14)',
  primary: '#3E8C72', // 主色
  accent: '#6EE7B7', // 薄荷强调色
  accentSoft: 'rgba(110, 231, 183, 0.12)',
  text: '#F2F7F5',
  textDim: '#9DB3AC',
  textMute: '#6B837C',
  danger: '#F87171',
};

/** 字体：优先使用 Windows/macOS 自带中文字体，避免渲染时联网 */
export const FONT = {
  sans: '"Microsoft YaHei", "PingFang SC", "Hiragino Sans GB", "Noto Sans SC", sans-serif',
  mono: '"JetBrains Mono", "Cascadia Mono", Consolas, "Courier New", monospace',
};

/** 文案 */
export const CONTENT = {
  product: 'Java Harness',
  tagline: 'Java 原生 Agent 运行时 · 本地 Web 编程工作台',
  hook: '让 AI 在你的代码库里干活',
  hookSub: '每一步，都看得见',

  pillars: [
    {
      title: '本地运行',
      desc: '服务跑在 127.0.0.1，模型地址与 Key 自行配置，Key 以 AES-GCM 加密落库。',
      icon: 'shield',
    },
    {
      title: '工作区感知',
      desc: '目录浏览、文件读取、文本搜索、Git 状态，直接读写你本机的真实项目。',
      icon: 'folder',
    },
    {
      title: '每步可见',
      desc: '工具调用、模型请求快照、文件 Diff，全链路留痕，随时回查。',
      icon: 'eye',
    },
  ],

  overviewPoints: [
    { title: '会话按工作区归组', desc: '左侧栏管理多个项目目录' },
    { title: '真实多轮问答', desc: '自动携带历史与完整工具轮次' },
    { title: '模型与强度可切换', desc: '输入框底部即选即用' },
  ],

  toolsCaption: '每一次工具调用都记录在案',
  toolsDesc: 'list_directory / read_file / search_files —— 分步执行、状态可见、结果可查。',

  diffCaption: '改动先给 Diff，确认后才写盘',
  diffDesc: '增删行逐行展示，冲突与重复应用都会被拦住。',

  modelsCaption: '模型服务，自己说了算',
  modelsDesc: 'OpenAI、DeepSeek 或任意 Chat Completions 兼容网关，保存即生效。',

  techStack: [
    'JDK 21',
    'Spring Boot 3.5',
    'Vue 3',
    'TypeScript',
    'Vite',
    'H2 + Flyway',
    'SSE 流式',
    'OpenAI 兼容协议',
  ],

  modules: [
    { name: 'harness-spi', desc: 'Session / Run / Event 契约' },
    { name: 'harness-core', desc: '调度、上下文预算、取消与超时' },
    { name: 'harness-adapters', desc: '模型 HTTP 适配 · 工作区工具 · JDBC 存储' },
    { name: 'harness-server', desc: 'Spring Boot 装配 · REST / SSE 入口' },
    { name: 'harness-web', desc: 'Vue 3 + TypeScript 工作台前端' },
  ],

  ctaTitle: 'Java Harness',
  ctaSub: '本地编程工作台 · 单 JAR 启动',
};

/** 素材（均为真实运行界面截图） */
export const SHOTS = {
  welcome: 'assets/hd-01-welcome.png',
  chat: 'assets/hd-02-chat.png',
  runDetail: 'assets/hd-03-rundetail.png',
  diff: 'assets/hd-04-diff.png',
  provider: 'assets/hd-05-provider.png',
};

/** 时间轴（@30fps，总 60s） */
export const TIMING = {
  fps: 30,
  totalFrames: 1800,
  scenes: {
    hook: { from: 0, duration: 150 },
    pillars: { from: 150, duration: 240 },
    overview: { from: 390, duration: 270 },
    tools: { from: 660, duration: 270 },
    diff: { from: 930, duration: 270 },
    models: { from: 1200, duration: 240 },
    tech: { from: 1440, duration: 210 },
    outro: { from: 1650, duration: 150 },
  },
};

export const SCENE_ORDER = [
  { key: 'hook', label: '开场' },
  { key: 'pillars', label: '三个支点' },
  { key: 'overview', label: '界面总览' },
  { key: 'tools', label: '工具可见' },
  { key: 'diff', label: 'Diff 审核' },
  { key: 'models', label: '模型配置' },
  { key: 'tech', label: '技术栈' },
] as const;
