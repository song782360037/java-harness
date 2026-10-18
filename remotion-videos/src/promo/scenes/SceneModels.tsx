import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { BRAND, CONTENT, FONT, SHOTS, TIMING } from '../constants';
import { Kicker, ScreenshotCard, Stage, fadeSlideUp } from '../ui';

/**
 * 场景六：模型与提供商配置（8s）
 * 真实设置页截图 + 取景框圈出提供商表单，说明「地址与 Key 自己配、加密保存」
 */
export const SceneModels: React.FC = () => {
  const frame = useCurrentFrame();
  const d = TIMING.scenes.models.duration;

  const out = interpolate(frame, [d - 18, d], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const cardOpacity = interpolate(frame, [0, 26], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardX = interpolate(frame, [0, 30], [-90, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const cardScale = interpolate(frame, [0, d], [1.0, 1.03], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const boxOpacity = interpolate(frame, [56, 80], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const CARD_W = 1180;

  return (
    <Stage glowX={30} glowY={45}>
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
          <Kicker text="自由接入" frame={frame} start={0} />
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
            {CONTENT.modelsCaption}
          </div>
          <div
            style={{
              ...fadeSlideUp(frame, 30, 22),
              marginTop: 16,
              fontSize: 26,
              color: BRAND.textDim,
            }}
          >
            {CONTENT.modelsDesc}
          </div>
        </div>

        <div
          style={{
            position: 'relative',
            marginTop: 96,
            opacity: cardOpacity,
            transform: `translateX(${cardX}px) scale(${cardScale})`,
          }}
        >
          <ScreenshotCard src={SHOTS.provider} width={CARD_W} />

          {/* 取景框：提供商表单 */}
          <div
            style={{
              position: 'absolute',
              left: '33%',
              top: '32%',
              width: '47.4%',
              height: '46%',
              border: `3px solid ${BRAND.accent}`,
              borderRadius: 12,
              boxShadow: `0 0 0 4px rgba(110,231,183,0.16), 0 0 34px rgba(110,231,183,0.45)`,
              opacity: boxOpacity,
            }}
          />

          {/* 脱敏说明 */}
          <div
            style={{
              position: 'absolute',
              left: '33%',
              top: -54,
              padding: '8px 16px',
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
            地址与 Key 仅存本机 · AES-GCM 加密
          </div>
        </div>
      </div>
    </Stage>
  );
};
