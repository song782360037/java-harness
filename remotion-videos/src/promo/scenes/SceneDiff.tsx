import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { BRAND, CONTENT, FONT, SHOTS, TIMING } from '../constants';
import { Kicker, ScreenshotCard, Stage, fadeSlideUp } from '../ui';

/**
 * 场景五：Diff 审核（9s）
 * 真实文件修改卡片，取景框锁定增删行，配一条「改动不落盘」的安全说明
 */
export const SceneDiff: React.FC = () => {
  const frame = useCurrentFrame();
  const d = TIMING.scenes.diff.duration;

  const out = interpolate(frame, [d - 18, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const cardOpacity = interpolate(frame, [0, 28], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardY = interpolate(frame, [0, 32], [-46, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardScale = interpolate(frame, [0, d], [1.0, 1.04], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const boxOpacity = interpolate(frame, [64, 88], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const CARD_W = 1240;

  return (
    <Stage glowX={40} glowY={55}>
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
        <div style={{ marginTop: 44, textAlign: 'center' }}>
          <Kicker text="改动可控" frame={frame} start={0} />
          <div
            style={{
              ...fadeSlideUp(frame, 14, 22),
              marginTop: 22,
              fontSize: 56,
              fontWeight: 800,
              color: BRAND.text,
              letterSpacing: -1,
            }}
          >
            {CONTENT.diffCaption}
          </div>
          <div
            style={{
              ...fadeSlideUp(frame, 30, 22),
              marginTop: 16,
              fontSize: 26,
              color: BRAND.textDim,
            }}
          >
            {CONTENT.diffDesc}
          </div>
        </div>

        <div
          style={{
            position: 'relative',
            marginTop: 40,
            opacity: cardOpacity,
            transform: `translateY(${cardY}px) scale(${cardScale})`,
          }}
        >
          <ScreenshotCard src={SHOTS.diff} width={CARD_W} />

          {/* 取景框：diff 代码区 */}
          <div
            style={{
              position: 'absolute',
              left: '32.2%',
              top: '48.6%',
              width: '48.4%',
              height: '15.9%',
              border: `3px solid ${BRAND.accent}`,
              borderRadius: 12,
              boxShadow: `0 0 0 4px rgba(110,231,183,0.16), 0 0 34px rgba(110,231,183,0.45)`,
              opacity: boxOpacity,
            }}
          />

          {/* 状态徽标标注 */}
          <div
            style={{
              position: 'absolute',
              left: '32.2%',
              top: '65.5%',
              padding: '9px 16px',
              borderRadius: 10,
              backgroundColor: '#DCFCE7',
              color: '#166534',
              fontSize: 19,
              fontWeight: 700,
              whiteSpace: 'nowrap',
              opacity: boxOpacity,
              fontFamily: FONT.sans,
              boxShadow: '0 8px 24px rgba(0,0,0,0.25)',
            }}
          >
            已应用 · 写盘前需人工确认
          </div>
        </div>
      </div>
    </Stage>
  );
};
