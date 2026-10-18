import { Config } from '@remotion/cli/config';

// 输出配置：JPEG 中间帧更快，覆盖同名输出
Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
