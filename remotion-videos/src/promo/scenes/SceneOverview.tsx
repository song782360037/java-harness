import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { BRAND, CONTENT, FONT, SHOTS, TIMING } from '../constants';
import { Kicker, ScreenshotCard, Stage, fadeSlideUp } from '../ui';

/**
 * 场景三：界面总览（9s）
 * 真实工作台截图从右侧滑入并缓慢推近，左侧逐条点出界面结构
 */
export const SceneOverview: React.FC = () => {
  const frame = useCurrentFrame();
  const d = TIMING.scenes.overview.duration;

  const out = interpolate(frame, [d - 18, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 截图入场：从右滑入 + 轻微推近
  const cardX = interpolate(frame, [0, 34], [120, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardOpacity = interpolate(frame, [0, 30], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardScale = interpolate(frame, [0, d], [0.96, 1.04], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <Stage glowX={72} glowY={40}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          alignItems: 'center',
          padding: '0 0 0 92px',
          opacity: out,
        }}
      >
        {/* 左侧文字 */}
        <div style={{ width: 560, flexShrink: 0 }}>
          <Kicker text="真实界面" frame={frame} start={6} />

          <div
            style={{
              ...fadeSlideUp(frame, 20, 22),
              marginTop: 32,
              fontSize: 64,
              fontWeight: 800,
              color: BRAND.text,
              lineHeight: 1.15,
              letterSpacing: -1,
            }}
          >
            这是运行中的
            <br />
            工作台
          </div>

          <div style={{ marginTop: 44 }}>
            {CONTENT.overviewPoints.map((pt, i) => {
              const delay = 52 + i * 20;
              const anim = fadeSlideUp(frame, delay, 20, 22);
              return (
                <div key={pt.title} style={{ ...anim, display: 'flex', gap: 18, marginBottom: 30 }}>
                  <div
                    style={{
                      width: 30,
                      height: 30,
                      borderRadius: 9,
                      border: `1.5px solid ${BRAND.accent}`,
                      color: BRAND.accent,
                      flexShrink: 0,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 16,
                      fontFamily: FONT.mono,
                      marginTop: 4,
                    }}
                  >
                    {i + 1}
                  </div>
                  <div>
                    <div style={{ fontSize: 28, fontWeight: 700, color: BRAND.text }}>
                      {pt.title}
                    </div>
                    <div style={{ fontSize: 21, color: BRAND.textMute, marginTop: 6 }}>
                      {pt.desc}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* 右侧截图 */}
        <div
          style={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-start',
            paddingLeft: 40,
            opacity: cardOpacity,
            transform: `translateX(${cardX}px) scale(${cardScale})`,
          }}
        >
          <ScreenshotCard src={SHOTS.chat} width={960} />
        </div>
      </div>
    </Stage>
  );
};
