import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { BRAND, CONTENT, FONT, TIMING } from '../constants';
import { Stage } from '../ui';

/**
 * 场景一：开场（5s）
 * Logo 弹入 → 主标题上滑 → 副标题与标语淡入 → 整体淡出
 */
export const SceneHook: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const d = TIMING.scenes.hook.duration;

  // Logo 弹入
  const logoScale = spring({
    frame,
    fps,
    config: { damping: 13, stiffness: 110, mass: 0.8 },
  });

  // 主标题上滑
  const titleY = interpolate(frame, [12, 42], [46, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const titleOpacity = interpolate(frame, [12, 40], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 副标题
  const subOpacity = interpolate(frame, [34, 58], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 标语 + 分隔线
  const lineWidth = interpolate(frame, [55, 85], [0, 320], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const hookOpacity = interpolate(frame, [70, 95], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // 收尾淡出
  const out = interpolate(frame, [d - 18, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <Stage glowX={50} glowY={35}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: out,
        }}
      >
        {/* Logo */}
        <div
          style={{
            transform: `scale(${logoScale})`,
            width: 132,
            height: 132,
            borderRadius: 32,
            backgroundColor: BRAND.primary,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: `0 24px 60px rgba(62,140,114,0.45)`,
            marginBottom: 44,
          }}
        >
          {/* 简笔 H 字标 */}
          <svg width="72" height="72" viewBox="0 0 72 72" fill="none">
            <path
              d="M22 16v40M50 16v40M22 36h28"
              stroke={BRAND.text}
              strokeWidth="8"
              strokeLinecap="round"
            />
          </svg>
        </div>

        {/* 主标题 */}
        <div
          style={{
            fontSize: 108,
            fontWeight: 800,
            letterSpacing: -2,
            color: BRAND.text,
            transform: `translateY(${titleY}px)`,
            opacity: titleOpacity,
            lineHeight: 1.05,
          }}
        >
          {CONTENT.product}
        </div>

        {/* 副标题 */}
        <div
          style={{
            marginTop: 22,
            fontSize: 33,
            color: BRAND.textDim,
            opacity: subOpacity,
            letterSpacing: 1,
          }}
        >
          {CONTENT.tagline}
        </div>

        {/* 分隔线 */}
        <div
          style={{
            marginTop: 46,
            width: lineWidth,
            height: 2,
            backgroundColor: BRAND.accent,
            opacity: 0.7,
            borderRadius: 2,
          }}
        />

        {/* 标语 */}
        <div
          style={{
            marginTop: 34,
            fontSize: 38,
            fontWeight: 600,
            color: BRAND.accent,
            opacity: hookOpacity,
            letterSpacing: 2,
            fontFamily: FONT.sans,
          }}
        >
          {CONTENT.hook}
          <span style={{ color: BRAND.textMute, margin: '0 14px' }}>·</span>
          {CONTENT.hookSub}
        </div>
      </div>
    </Stage>
  );
};
