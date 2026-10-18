import React from 'react';
import { AbsoluteFill, Sequence, useCurrentFrame } from 'remotion';
import { TIMING } from './constants';
import { ProgressBar } from './ui';
import { SceneHook } from './scenes/SceneHook';
import { ScenePillars } from './scenes/ScenePillars';
import { SceneOverview } from './scenes/SceneOverview';
import { SceneTools } from './scenes/SceneTools';
import { SceneDiff } from './scenes/SceneDiff';
import { SceneModels } from './scenes/SceneModels';
import { SceneTech } from './scenes/SceneTech';
import { SceneOutro } from './scenes/SceneOutro';

/**
 * Java Harness 宣传片主合成
 * 总时长 60s @30fps，共 8 个场景，全部界面素材来自真实运行的工作台截图
 */
export const PromoComposition: React.FC = () => {
  const frame = useCurrentFrame();
  const s = TIMING.scenes;

  return (
    <AbsoluteFill style={{ backgroundColor: '#08120F' }}>
      <Sequence from={s.hook.from} durationInFrames={s.hook.duration}>
        <SceneHook />
      </Sequence>
      <Sequence from={s.pillars.from} durationInFrames={s.pillars.duration}>
        <ScenePillars />
      </Sequence>
      <Sequence from={s.overview.from} durationInFrames={s.overview.duration}>
        <SceneOverview />
      </Sequence>
      <Sequence from={s.tools.from} durationInFrames={s.tools.duration}>
        <SceneTools />
      </Sequence>
      <Sequence from={s.diff.from} durationInFrames={s.diff.duration}>
        <SceneDiff />
      </Sequence>
      <Sequence from={s.models.from} durationInFrames={s.models.duration}>
        <SceneModels />
      </Sequence>
      <Sequence from={s.tech.from} durationInFrames={s.tech.duration}>
        <SceneTech />
      </Sequence>
      <Sequence from={s.outro.from} durationInFrames={s.outro.duration}>
        <SceneOutro />
      </Sequence>

      {/* 全局进度条 */}
      <ProgressBar frame={frame} total={TIMING.totalFrames} />
    </AbsoluteFill>
  );
};
