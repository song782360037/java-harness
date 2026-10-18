import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { BRAND, CONTENT, FONT, TIMING } from '../constants';
import { Stage } from '../ui';

/**
 * 场景八：收尾（5s）
 * Logo 与主标题回到中心，副标题点明「单 JAR 启动」
 */
export const SceneOutro: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const logoScale = spring({
    frame,
    fps,
    config: { damping: 14, stiffness: 100 },
  });

  const titleOpacity = interpolate(frame, [10, 34], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const titleY = interpolate(frame, [10, 36], [30, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const subOpacity = interpolate(frame, [30, 55], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const glow = interpolate(frame, [40, 110], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <Stage glowX={50} glowY={45}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {/* 环形光晕 */}
        <div
          style={{
            position: 'absolute',
            width: 720,
            height: 720,
            borderRadius: '50%',
            border: `2px solid ${BRAND.accent}`,
            opacity: glow * 0.18,
            transform: `scale(${0.8 + glow * 0.35})`,
          }}
        />

        <div
          style={{
            transform: `scale(${logoScale})`,
            width: 108,
            height: 108,
            borderRadius: 26,
            backgroundColor: BRAND.primary,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 22px 55px rgba(62,140,114,0.45)',
            marginBottom: 36,
          }}
        >
          <svg width="60" height="60" viewBox="0 0 72 72" fill="none">
            <path
              d="M22 16v40M50 16v40M22 36h28"
              stroke={BRAND.text}
              strokeWidth="8"
              strokeLinecap="round"
            />
          </svg>
        </div>

        <div
          style={{
            fontSize: 96,
            fontWeight: 800,
            color: BRAND.text,
            letterSpacing: -2,
            opacity: titleOpacity,
            transform: `translateY(${titleY}px)`,
          }}
        >
          {CONTENT.ctaTitle}
        </div>

        <div
          style={{
            marginTop: 26,
            fontSize: 34,
            color: BRAND.accent,
            opacity: subOpacity,
            letterSpacing: 2,
          }}
        >
          {CONTENT.ctaSub}
        </div>

        <div
          style={{
            marginTop: 40,
            fontFamily: FONT.mono,
            fontSize: 24,
            color: BRAND.textMute,
            opacity: subOpacity,
          }}
        >
          java -jar harness-server-0.1.0-SNAPSHOT.jar
        </div>
      </div>
    </Stage>
  );
};
