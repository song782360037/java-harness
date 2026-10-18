import React from 'react';
import { Composition } from 'remotion';
import { PromoComposition } from './promo/PromoComposition';
import { TIMING } from './promo/constants';

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="java-harness-promo"
        component={PromoComposition}
        durationInFrames={TIMING.totalFrames}
        fps={TIMING.fps}
        width={1920}
        height={1080}
      />
    </>
  );
};
