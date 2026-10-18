import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { BRAND, CONTENT, FONT, SHOTS, TIMING } from '../constants';
import { Kicker, ScreenshotCard, Stage, fadeSlideUp } from '../ui';

/**
 * 场景四：工具调用全程可见（9s）
 * 居中展示执行详情面板的真实截图，并用取景框圈出「第 N 步 · 工具名」列表
 */
export const SceneTools: React.FC = () => {
  const frame = useCurrentFrame();
  const d = TIMING.scenes.tools.duration;

  const out = interpolate(frame, [d - 18, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const cardOpacity = interpolate(frame, [6, 34], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardScale = interpolate(frame, [6, 40], [1.05, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 取景框：圈出右侧执行详情面板中的工具列表
  const boxOpacity = interpolate(frame, [72, 96], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const boxScale = interpolate(frame, [72, 96], [1.06, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const CARD_W = 1240;

  return (
    <Stage glowX={50} glowY={30}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          opacity: out,
        }}
      >
        {/* 标题区 */}
        <div style={{ marginTop: 40, textAlign: 'center' }}>
          <Kicker text="工具可见" frame={frame} start={0} />
          <div
            style={{
              ...fadeSlideUp(frame, 16, 22),
              marginTop: 26,
              fontSize: 62,
              fontWeight: 800,
              color: BRAND.text,
              letterSpacing: -1,
            }}
          >
            {CONTENT.toolsCaption}
          </div>
          <div
            style={{
              ...fadeSlideUp(frame, 32, 22),
              marginTop: 16,
              fontSize: 26,
              color: BRAND.textDim,
              fontFamily: FONT.sans,
            }}
          >
            {CONTENT.toolsDesc}
          </div>
        </div>

        {/* 截图 + 取景框 */}
        <div
          style={{
            position: 'relative',
            marginTop: 100,
            opacity: cardOpacity,
            transform: `scale(${cardScale})`,
          }}
        >
          <ScreenshotCard src={SHOTS.runDetail} width={CARD_W} />

          {/* 取景框（百分比坐标，适配截图缩放） */}
          <div
            style={{
              position: 'absolute',
              left: '77%',
              top: '10.5%',
              width: '21.5%',
              height: '88%',
              border: `3px solid ${BRAND.accent}`,
              borderRadius: 14,
              boxShadow: `0 0 0 4px rgba(110,231,183,0.16), 0 0 34px rgba(110,231,183,0.45)`,
              opacity: boxOpacity,
              transform: `scale(${boxScale})`,
            }}
          />
          {/* 取景框标签 */}
          <div
            style={{
              position: 'absolute',
              left: '77%',
              top: -54,
              padding: '8px 18px',
              borderRadius: 10,
              backgroundColor: BRAND.accent,
              color: '#06231A',
              fontSize: 19,
              fontWeight: 700,
              whiteSpace: 'nowrap',
              opacity: boxOpacity,
              fontFamily: FONT.sans,
            }}
          >
            第 1 步 · list_directory · 已完成
          </div>
        </div>
      </div>
    </Stage>
  );
};
