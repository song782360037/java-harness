import React from 'react';
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { BRAND, FONT } from './constants';

/** 淡入 + 上滑 */
export const fadeSlideUp = (frame: number, start: number, duration = 20, distance = 28) => ({
  opacity: interpolate(frame, [start, start + duration], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  }),
  transform: `translateY(${interpolate(frame, [start, start + duration], [distance, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })}px)`,
});

/** 淡出 */
export const fadeOut = (frame: number, start: number, duration = 15) =>
  interpolate(frame, [start, start + duration], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

/** 深色底 + 网格 + 光晕，全片统一背景 */
export const Stage: React.FC<{
  children: React.ReactNode;
  glowX?: number;
  glowY?: number;
}> = ({ children, glowX = 50, glowY = 40 }) => {
  const frame = useCurrentFrame();
  // 光晕轻微漂移，避免画面死板
  const drift = Math.sin(frame / 90) * 3;
  return (
    <AbsoluteFill style={{ backgroundColor: BRAND.bg, fontFamily: FONT.sans }}>
      {/* 网格 */}
      <AbsoluteFill
        style={{
          backgroundImage: `linear-gradient(${BRAND.border} 1px, transparent 1px), linear-gradient(90deg, ${BRAND.border} 1px, transparent 1px)`,
          backgroundSize: '64px 64px',
          opacity: 0.5,
          maskImage: 'radial-gradient(ellipse at center, black 20%, transparent 75%)',
          WebkitMaskImage: 'radial-gradient(ellipse at center, black 20%, transparent 75%)',
        }}
      />
      {/* 光晕 */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at ${glowX + drift}% ${glowY}%, rgba(62,140,114,0.35) 0%, rgba(8,18,15,0) 55%)`,
        }}
      />
      <AbsoluteFill style={{ color: BRAND.text }}>{children}</AbsoluteFill>
    </AbsoluteFill>
  );
};

/** 顶部小标签 */
export const Kicker: React.FC<{ text: string; frame: number; start?: number }> = ({
  text,
  frame,
  start = 0,
}) => {
  const anim = fadeSlideUp(frame, start, 18, 14);
  return (
    <div
      style={{
        ...anim,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 12,
        padding: '10px 22px',
        borderRadius: 999,
        border: `1px solid ${BRAND.border}`,
        backgroundColor: BRAND.accentSoft,
        color: BRAND.accent,
        fontSize: 22,
        letterSpacing: 3,
        fontWeight: 600,
      }}
    >
      <span
        style={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          backgroundColor: BRAND.accent,
        }}
      />
      {text}
    </div>
  );
};

/** 浏览器窗口样式的截图容器 */
export const ScreenshotCard: React.FC<{
  src: string;
  width: number;
  style?: React.CSSProperties;
  showBar?: boolean;
}> = ({ src, width, style, showBar = true }) => {
  return (
    <div
      style={{
        width,
        borderRadius: 18,
        overflow: 'hidden',
        backgroundColor: '#F7FAF8',
        border: `1px solid rgba(255,255,255,0.16)`,
        boxShadow: '0 40px 90px rgba(0,0,0,0.55), 0 0 0 1px rgba(110,231,183,0.08)',
        ...style,
      }}
    >
      {showBar ? (
        <div
          style={{
            height: 34,
            backgroundColor: '#13231E',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            paddingLeft: 16,
          }}
        >
          {['#F87171', '#FBBF24', '#34D399'].map((c) => (
            <span key={c} style={{ width: 11, height: 11, borderRadius: '50%', backgroundColor: c }} />
          ))}
          <span
            style={{
              marginLeft: 14,
              fontSize: 15,
              color: BRAND.textMute,
              fontFamily: FONT.mono,
              letterSpacing: 0.5,
            }}
          >
            127.0.0.1:8080
          </span>
        </div>
      ) : null}
      <Img src={staticFile(src)} style={{ width: '100%', display: 'block' }} />
    </div>
  );
};

/** 底部署名条 */
export const Signature: React.FC<{ frame: number; start: number; text?: string }> = ({
  frame,
  start,
  text = 'Java Harness · 本地编程工作台',
}) => {
  const anim = fadeSlideUp(frame, start, 20, 16);
  return (
    <div
      style={{
        ...anim,
        position: 'absolute',
        right: 72,
        bottom: 52,
        fontSize: 20,
        color: BRAND.textMute,
        letterSpacing: 1.5,
        fontFamily: FONT.mono,
      }}
    >
      {text}
    </div>
  );
};

/** 动画的底部进度条 */
export const ProgressBar: React.FC<{ frame: number; total: number }> = ({ frame, total }) => {
  const pct = interpolate(frame, [0, total], [0, 100], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        bottom: 0,
        height: 4,
        width: `${pct}%`,
        backgroundColor: BRAND.accent,
        opacity: 0.85,
      }}
    />
  );
};
