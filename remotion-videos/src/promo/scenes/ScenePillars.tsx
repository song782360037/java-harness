import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { BRAND, CONTENT, TIMING } from '../constants';
import { Kicker, Stage, fadeSlideUp } from '../ui';

/** 线条图标 */
const PillarIcon: React.FC<{ name: string; color: string }> = ({ name, color }) => {
  const common = {
    width: 40,
    height: 40,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: color,
    strokeWidth: 1.7,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  if (name === 'shield') {
    return (
      <svg {...common}>
        <path d="M12 3l7 3v6c0 4.5-3 7.7-7 9-4-1.3-7-4.5-7-9V6l7-3Z" />
        <path d="M9.5 12l1.8 1.8 3.4-3.6" />
      </svg>
    );
  }
  if (name === 'folder') {
    return (
      <svg {...common}>
        <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12Z" />
      <circle cx="12" cy="12" r="2.8" />
    </svg>
  );
};

/**
 * 场景二：三个支点（8s）
 * 标题 + 三张能力卡片依次弹入
 */
export const ScenePillars: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const d = TIMING.scenes.pillars.duration;

  const out = interpolate(frame, [d - 18, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <Stage glowX={30} glowY={30}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: out,
          padding: '0 80px',
        }}
      >
        <Kicker text="定位" frame={frame} start={0} />

        <div
          style={{
            ...fadeSlideUp(frame, 14, 22),
            marginTop: 34,
            fontSize: 74,
            fontWeight: 800,
            color: BRAND.text,
            letterSpacing: -1,
          }}
        >
          不是又一个聊天框
        </div>

        <div
          style={{
            ...fadeSlideUp(frame, 30, 22),
            marginTop: 16,
            fontSize: 30,
            color: BRAND.textDim,
          }}
        >
          它是一个能读你项目、改你文件、并且全程留痕的编程 Agent 运行时
        </div>

        {/* 三张卡片 */}
        <div
          style={{
            display: 'flex',
            gap: 34,
            marginTop: 66,
            width: '100%',
            justifyContent: 'center',
          }}
        >
          {CONTENT.pillars.map((p, i) => {
            const delay = 50 + i * 18;
            const s = spring({
              frame: frame - delay,
              fps,
              config: { damping: 14, stiffness: 110 },
            });
            const opacity = interpolate(frame, [delay, delay + 18], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            });
            return (
              <div
                key={p.title}
                style={{
                  width: 452,
                  padding: '40px 38px 44px',
                  borderRadius: 22,
                  border: `1px solid ${BRAND.border}`,
                  backgroundColor: BRAND.surface,
                  opacity,
                  transform: `translateY(${(1 - s) * 46}px) scale(${0.94 + s * 0.06})`,
                  boxShadow: '0 26px 60px rgba(0,0,0,0.4)',
                }}
              >
                <div
                  style={{
                    width: 78,
                    height: 78,
                    borderRadius: 20,
                    backgroundColor: BRAND.accentSoft,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 28,
                  }}
                >
                  <PillarIcon name={p.icon} color={BRAND.accent} />
                </div>
                <div
                  style={{
                    fontSize: 38,
                    fontWeight: 700,
                    color: BRAND.text,
                    marginBottom: 16,
                  }}
                >
                  {p.title}
                </div>
                <div
                  style={{
                    fontSize: 23,
                    lineHeight: 1.65,
                    color: BRAND.textDim,
                  }}
                >
                  {p.desc}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </Stage>
  );
};
