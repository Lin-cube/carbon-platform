<template>
  <!--
    Ken Burns 动态背景（纯装饰，不拦截点击）。

    分层：
      .kb-landscape  山谷图片，34s 缓慢缩放 + 轻微平移（scale 1 → 1.13，alternate 往返）
      .kb-veil       左侧浅白色渐变遮罩，保证品牌文字清晰（不覆盖全屏高透明白）
      .kb-glow       左上角极淡暖光，增加清晨层次

    要点：
      · inset:-5% 预留放大余量，避免动画过程露出边缘白边
      · background-size:cover + 固定定位，不拉伸变形
      · overflow:hidden 由父级 .login-page 负责，本组件不产生滚动条
      · 尊重 prefers-reduced-motion
  -->
  <div class="ken-burns-bg" aria-hidden="true">
    <div class="kb-landscape" :style="{ backgroundImage: `url(${bgUrl})` }"></div>
    <div class="kb-veil"></div>
    <div class="kb-glow"></div>
  </div>
</template>

<script setup lang="ts">
/**
 * 登录页 Ken Burns 背景。
 *
 * <p>背景图从参考设计内嵌的 webp 中提取，作为本地静态资源打包，
 * 不依赖任何外部图片链接。
 */
import bgUrl from '@/assets/login-valley.webp'
</script>

<style scoped>
.ken-burns-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

/* 山谷图片本体 */
.kb-landscape {
  position: absolute;
  inset: -5%;                     /* 放大余量：动画期间不会露出边缘 */
  background-position: center 49%;
  background-size: cover;
  background-repeat: no-repeat;
  transform-origin: center center;
  animation: kb-zoom 34s ease-in-out infinite alternate;
  will-change: transform;
  backface-visibility: hidden;    /* 避免部分浏览器出现闪烁 */
}

/* 左侧文字区域遮罩：从左到右逐渐透明，右侧保持山谷原貌 */
.kb-veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    rgba(250, 253, 249, 0.82) 0%,
    rgba(250, 253, 249, 0.66) 23%,
    rgba(250, 253, 249, 0.34) 42%,
    rgba(250, 253, 249, 0.05) 65%,
    rgba(250, 253, 249, 0.08) 100%
  );
}

/* 左上角极淡暖光 */
.kb-glow {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 0% 16%, rgba(255, 253, 244, 0.23), transparent 35%);
}

@keyframes kb-zoom {
  0% {
    transform: scale(1) translate3d(0, 0, 0);
  }
  100% {
    transform: scale(1.13) translate3d(-1.2%, 0.6%, 0);
  }
}

/* 窄屏：遮罩改为自下而上的竖向渐变，保证文字可读且山谷仍可见 */
@media (max-width: 820px) {
  .kb-veil {
    background: linear-gradient(
      180deg,
      rgba(250, 253, 249, 0.83) 0%,
      rgba(250, 253, 249, 0.43) 50%,
      rgba(250, 253, 249, 0.13) 100%
    );
  }
}

/* 尊重「减少动态效果」的系统偏好 */
@media (prefers-reduced-motion: reduce) {
  .kb-landscape {
    animation: none;
    will-change: auto;
    transform: none;
  }
}
</style>
