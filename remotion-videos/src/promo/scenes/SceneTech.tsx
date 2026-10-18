import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { BRAND, CONTENT, FONT, TIMING } from '../constants';
import { Kicker, Stage, fadeSlideUp } from '../ui';

/**
 * 场景七：技术栈与工程结构（7s）
 * 技术标签逐颗弹出，随后列出五个 Maven 模块的职责
 */
export const SceneTech: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const d = TIMING.scenes.tech.duration;

  const out = interpolate(frame, [d - 16, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <Stage glowX={50} glowY={40}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: out,
          padding: '0 110px',
        }}
      >
        <Kicker text="技术形态" frame={frame} start={0} />

        <div
          style={{
            ...fadeSlideUp(frame, 12, 22),
            marginTop: 30,
            fontSize: 60,
            fontWeight: 800,
            color: BRAND.text,
            letterSpacing: -1,
          }}
        >
          JDK 21 之上的分层架构
        </div>

        {/* 技术标签 */}
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 16,
            justifyContent: 'center',
            marginTop: 44,
            maxWidth: 1380,
          }}
        >
          {CONTENT.techStack.map((t, i) => {
            const delay = 34 + i * 9;
            const s = spring({
              frame: frame - delay,
              fps,
              config: { damping: 14, stiffness: 130 },
            });
            const opacity = interpolate(frame, [delay, delay + 12], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            });
            return (
              <div
                key={t}
                style={{
                  padding: '14px 30px',
                  borderRadius: 999,
                  border: `1px solid ${BRAND.border}`,
                  backgroundColor: BRAND.accentSoft,
                  color: BRAND.accent,
                  fontSize: 26,
                  fontWeight: 600,
                  opacity,
                  transform: `scale(${0.7 + s * 0.3})`,
                  fontFamily: FONT.sans,
                }}
              >
                {t}
              </div>
            );
          })}
        </div>

        {/* 模块列表 */}
        <div style={{ marginTop: 52, width: '100%', maxWidth: 1420 }}>
          {CONTENT.modules.map((m, i) => {
            const delay = 108 + i * 14;
            const anim = fadeSlideUp(frame, delay, 18, 20);
            return (
              <div
                key={m.name}
                style={{
                  ...anim,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 26,
                  padding: '15px 26px',
                  marginBottom: 12,
                  borderRadius: 14,
                  backgroundColor: BRAND.bgSoft,
                  border: `1px solid ${BRAND.border}`,
                }}
              >
                <span
                  style={{
                    fontFamily: FONT.mono,
                    fontSize: 24,
                    fontWeight: 700,
                    color: BRAND.accent,
                    width: 260,
                  }}
                >
                  {m.name}
                </span>
                <span style={{ fontSize: 23, color: BRAND.textDim }}>{m.desc}</span>
              </div>
            );
          })}
        </div>
      </div>
    </Stage>
  );
};
