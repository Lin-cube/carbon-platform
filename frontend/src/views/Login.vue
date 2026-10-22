<template>
  <div class="login-page">
    <!-- ══════════ 背景层（纯装饰，不拦截点击） ══════════ -->
    <div class="bg-layer" aria-hidden="true">
      <!-- 左上方一片宽阔柔和的清晨自然光 -->
      <div class="bg-light"></div>
      <!-- 左侧边缘的植物叶片投影（低饱和灰绿，虚化） -->
      <div class="leaf-shadow leaf-shadow--a"></div>
      <div class="leaf-shadow leaf-shadow--b"></div>
      <div class="leaf-shadow leaf-shadow--c"></div>
    </div>

    <div class="login-shell">
      <!-- ══════════ 左侧：品牌展示 ══════════ -->
      <section class="brand">
        <div class="brand-logo">
          <svg class="logo-mark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 1.8c5.9 6.3 5.9 14.1 0 20.4C6.1 15.9 6.1 8.1 12 1.8Z"
                  fill="currentColor" opacity=".92"/>
            <path d="M12 4.6v15.9" stroke="#ffffff" stroke-width="1.05"
                  stroke-linecap="round" opacity=".62"/>
            <path d="M12 9.4 8.6 7.2M12 13.6l-3.4-2.2M12 9.4l3.4-2.2M12 13.6l3.4-2.2"
                  stroke="#ffffff" stroke-width=".85" stroke-linecap="round" opacity=".45"/>
          </svg>
          <span class="brand-name">碳能智控</span>
        </div>

        <h1 class="brand-title">
          让能源管理<br />
          <span class="brand-title-accent">更轻松一点。</span>
        </h1>

        <p class="brand-sub">看清每一份能耗，让绿色运维成为日常。</p>

        <!-- 唯一视觉主体：磨砂玻璃叶片 -->
        <div class="brand-decor" aria-hidden="true">
          <div class="glass-leaf">
            <span class="glass-leaf-vein"></span>
          </div>
          <div class="glass-leaf-glow"></div>
        </div>
      </section>

      <!-- ══════════ 右侧：登录卡片 ══════════ -->
      <section class="form-side">
        <!-- 小屏时显示的精简品牌 -->
        <div class="brand-mobile">
          <svg class="logo-mark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 1.8c5.9 6.3 5.9 14.1 0 20.4C6.1 15.9 6.1 8.1 12 1.8Z"
                  fill="currentColor" opacity=".92"/>
            <path d="M12 4.6v15.9" stroke="#ffffff" stroke-width="1.05" stroke-linecap="round" opacity=".62"/>
          </svg>
          <span class="brand-name">碳能智控</span>
        </div>

        <div class="login-card">
          <h2 class="card-title">欢迎回来</h2>
          <p class="card-sub">登录碳能智控，开启高效运维</p>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top"
                   class="login-form" @keyup.enter="onSubmit">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" size="large" placeholder="请输入用户名"
                        :prefix-icon="User" autocomplete="username" />
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" size="large" type="password" show-password
                        placeholder="请输入密码" :prefix-icon="Lock"
                        autocomplete="current-password" />
            </el-form-item>

            <el-button class="login-btn" size="large" :loading="loading" @click="onSubmit">
              {{ loading ? '登录中…' : '登 录' }}
            </el-button>
          </el-form>

          <div class="demo-block">
            <span class="demo-label">演示账号（点击填充）</span>
            <div class="demo-accounts">
              <button v-for="a in demoAccounts" :key="a.username" type="button"
                      class="demo-chip" @click="fill(a)">
                <span class="demo-chip-role">{{ a.label }}</span>
                <span class="demo-chip-cred">{{ a.username }} / {{ a.password }}</span>
              </button>
            </div>
          </div>
        </div>

        <p class="page-foot">碳能智控运维平台 · Carbon Energy O&amp;M Platform</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 登录页。
 *
 * <p>本次仅重做视觉层，以下逻辑保持不变：
 * <ul>
 *   <li>登录接口：{@code userStore.login(username, password)} → POST /api/auth/login</li>
 *   <li>表单校验：用户名、密码非空（Element Plus FormRules，失焦触发）</li>
 *   <li>认证：token 由 store 写入 localStorage，由 axios 拦截器附加</li>
 *   <li>跳转：优先使用 ?redirect= 查询参数，否则进 /dashboard</li>
 *   <li>回车提交、加载态、演示账号一键填充</li>
 * </ul>
 *
 * <p><b>关于验证码</b>：本项目当前没有验证码功能——
 * AuthController 只有 /login、/me、/logout，LoginRequest 也只有 username/password。
 * SecurityConfig 里虽然白名单了 /api/auth/captcha，但后端并未实现该接口。
 * 因此这里不放验证码输入框（不放无法使用的控件）。
 */
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: 'admin', password: 'admin123' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const demoAccounts = [
  { label: '管理员', username: 'admin', password: 'admin123' },
  { label: '运维人员', username: 'operator', password: 'operator123' },
  { label: '只读用户', username: 'viewer', password: 'viewer123' }
]

function fill(a: { username: string; password: string }) {
  form.username = a.username
  form.password = a.password
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.login(form.username, form.password)
      ElMessage.success('登录成功')
      const redirect = (route.query.redirect as string) || '/dashboard'
      router.push(redirect)
    } catch {
      /* 错误提示已由拦截器统一处理 */
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
/* ════════════════════════════ 设计变量 ════════════════════════════ */
.login-page {
  --bg-warm: #f7f9f6;          /* 暖白主背景 */
  --sage-50: #eef4ef;          /* 极淡鼠尾草 */
  --sage-100: #e5eee7;         /* 鼠尾草绿 */
  --sage-200: #d3e0d7;         /* 稍深，用于边框 */
  --green: #35866b;            /* 主色：沉稳清新绿（用于图标、强调大字） */
  --green-btn: #327f66;        /* 按钮底色：比主色深一档，白字对比度 4.81:1 达 AA */
  --green-dark: #2c755c;       /* hover：5.52:1 */
  --ink: #253d35;              /* 标题：深灰绿 */
  --ink-soft: #46584f;         /* 正文 */
  --muted: #5d6e66;            /* 辅助文字：对背景 5.10:1，达 AA */
  --hairline: rgba(53, 134, 107, 0.13);

  position: relative;
  min-height: 100vh;
  background: var(--bg-warm);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ════════════════════════════ 背景层 ════════════════════════════ */
.bg-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;   /* 装饰层绝不拦截点击 */
  overflow: hidden;
}

/* 左上方宽阔柔和的清晨自然光 */
.bg-light {
  position: absolute;
  top: -26%;
  left: -14%;
  width: 72vw;
  height: 78vh;
  background:
    radial-gradient(46% 52% at 42% 40%, rgba(255, 250, 236, 0.95) 0%, rgba(255, 250, 236, 0) 72%),
    radial-gradient(58% 58% at 60% 55%, rgba(229, 238, 231, 0.72) 0%, rgba(229, 238, 231, 0) 70%);
  filter: blur(6px);
  animation: light-breathe 26s ease-in-out infinite;
}

/* 植物叶片投影：用 border-radius 做出叶形，再虚化 */
.leaf-shadow {
  position: absolute;
  background: rgba(122, 152, 133, 0.22);
  filter: blur(26px);
  will-change: transform;
}

.leaf-shadow--a {
  left: -110px;
  top: 16%;
  width: 330px;
  height: 210px;
  border-radius: 0 76% 0 76%;
  transform: rotate(-16deg);
  animation: sway-a 11s ease-in-out infinite;
}

.leaf-shadow--b {
  left: -140px;
  top: 46%;
  width: 420px;
  height: 260px;
  border-radius: 0 72% 0 72%;
  background: rgba(122, 152, 133, 0.17);
  transform: rotate(-8deg);
  animation: sway-b 14s ease-in-out infinite;
}

.leaf-shadow--c {
  left: -70px;
  bottom: 4%;
  width: 250px;
  height: 170px;
  border-radius: 0 78% 0 78%;
  background: rgba(122, 152, 133, 0.13);
  transform: rotate(-26deg);
  animation: sway-a 17s ease-in-out infinite reverse;
}

/* ════════════════════════════ 布局骨架 ════════════════════════════ */
.login-shell {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1180px;
  padding: 48px 56px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(380px, 440px);
  gap: 72px;
  align-items: center;
}

/* ════════════════════════════ 左侧品牌 ════════════════════════════ */
.brand {
  position: relative;
  padding-bottom: 12px;
  animation: fade-up 0.62s cubic-bezier(0.22, 0.61, 0.36, 1) both;
}

.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--green);
}

.logo-mark {
  width: 25px;
  height: 25px;
  transform: rotate(18deg);     /* 叶尖斜指，比正立的更自然 */
  flex-shrink: 0;
}

.brand-name {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink);
}

.brand-title {
  margin: 42px 0 0;
  font-size: clamp(30px, 3.4vw, 44px);
  line-height: 1.42;
  font-weight: 500;          /* 不用过粗 */
  letter-spacing: 0.02em;
  color: var(--ink);
}

.brand-title-accent {
  color: var(--green);
}

.brand-sub {
  margin: 22px 0 0;
  max-width: 22em;
  font-size: 15px;
  line-height: 1.95;
  color: var(--muted);
}

/* 唯一的视觉主体：磨砂玻璃叶片 */
.brand-decor {
  position: relative;
  margin-top: 56px;
  height: 168px;
  width: 300px;
  pointer-events: none;
}

.glass-leaf {
  position: absolute;
  left: 10px;
  top: 10px;
  width: 196px;
  height: 130px;
  /* 两个对角圆、两个对角尖 —— 得到自然的叶形 */
  border-radius: 68% 6% 68% 6%;
  transform: rotate(-26deg);
  background:
    linear-gradient(135deg,
      rgba(255, 255, 255, 0.88) 0%,
      rgba(226, 237, 229, 0.72) 46%,
      rgba(196, 216, 203, 0.58) 100%);
  border: 1px solid rgba(53, 134, 107, 0.2);
  backdrop-filter: blur(16px) saturate(118%);
  -webkit-backdrop-filter: blur(16px) saturate(118%);
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.95),
    inset -10px -14px 30px rgba(53, 134, 107, 0.07),
    0 20px 48px -20px rgba(37, 61, 53, 0.3);
  animation: leaf-sway 13s ease-in-out infinite;
}

/* 主叶脉：贯穿叶片的一道细弧 */
.glass-leaf-vein {
  position: absolute;
  left: 50%;
  top: 8%;
  width: 1px;
  height: 84%;
  background: linear-gradient(
    180deg,
    rgba(53, 134, 107, 0.05) 0%,
    rgba(53, 134, 107, 0.3) 45%,
    rgba(53, 134, 107, 0.05) 100%);
  transform: rotate(-26deg);
  transform-origin: top center;
}

/* 叶柄：从左下角伸出的短茎 */
.glass-leaf::after {
  content: '';
  position: absolute;
  left: -34px;
  bottom: -8px;
  width: 44px;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg,
    rgba(53, 134, 107, 0) 0%, rgba(53, 134, 107, 0.34) 100%);
  transform: rotate(-26deg);
}

.glass-leaf-glow {
  position: absolute;
  left: 118px;
  top: 34px;
  width: 190px;
  height: 124px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 249, 232, 0.9) 0%, rgba(255, 249, 232, 0) 70%);
  filter: blur(14px);
}

/* ════════════════════════════ 右侧表单 ════════════════════════════ */
.form-side {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;           /* 允许在 grid 中收缩，否则长文本会把容器撑出屏幕 */
  animation: fade-up 0.62s cubic-bezier(0.22, 0.61, 0.36, 1) 0.08s both;
}

.brand-mobile {
  display: none;
}

.login-card {
  width: 100%;
  max-width: 440px;
  min-width: 0;
  padding: 40px 40px 32px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.76);
  border: 1px solid var(--hairline);
  backdrop-filter: blur(18px) saturate(118%);
  -webkit-backdrop-filter: blur(18px) saturate(118%);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 26px 64px -28px rgba(37, 61, 53, 0.22),
    0 4px 16px -8px rgba(37, 61, 53, 0.08);
}

.card-title {
  margin: 0;
  font-size: 25px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ink);
}

.card-sub {
  margin: 9px 0 30px;
  font-size: 13.5px;
  color: var(--muted);
}

/* ── 表单 ── */
.login-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-form :deep(.el-form-item.is-required .el-form-item__label::before) {
  display: none;          /* 去掉红星，保持清爽；校验失败时仍会显示红色错误文案 */
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: 7px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-soft);
  line-height: 1.4;
}

.login-form :deep(.el-input__wrapper) {
  height: 48px;
  padding: 0 14px;
  border-radius: 12px;
  background: var(--sage-50);
  box-shadow: 0 0 0 1px var(--sage-200) inset;
  transition: box-shadow 0.2s ease, background 0.2s ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  background: #eaf1ec;
  box-shadow: 0 0 0 1px #c3d5c9 inset;
}

/* 聚焦：细绿边框 + 极淡外圈 */
.login-form :deep(.el-input__wrapper.is-focus) {
  background: #ffffff;
  box-shadow:
    0 0 0 1px var(--green) inset,
    0 0 0 4px rgba(53, 134, 107, 0.1);
}

.login-form :deep(.el-input__inner) {
  font-size: 14.5px;
  color: var(--ink);
}

.login-form :deep(.el-input__inner::placeholder) {
  color: #7b9084;   /* 对浅绿底 3.05:1，占位符可辨识 */
}

.login-form :deep(.el-input__prefix) {
  color: #6f8479;
  font-size: 16px;
}

/* 校验错误：不挤乱布局 */
.login-form :deep(.el-form-item__error) {
  padding-top: 5px;
  font-size: 12px;
  color: #c0503f;
}

.login-form :deep(.el-form-item.is-error .el-input__wrapper) {
  background: #fdf4f2;
  box-shadow: 0 0 0 1px rgba(192, 80, 63, 0.55) inset;
}

/* ── 登录按钮 ── */
.login-btn {
  width: 100%;
  height: 48px;
  margin-top: 4px;
  border: none;
  border-radius: 12px;
  background: var(--green-btn);
  color: #ffffff;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.14em;
  transition: background 0.2s ease, transform 0.12s ease, box-shadow 0.2s ease;
  box-shadow: 0 8px 20px -10px rgba(53, 134, 107, 0.6);
}

.login-btn:hover,
.login-btn:focus {
  background: var(--green-dark);
  color: #ffffff;
}

.login-btn:active {
  transform: translateY(1px);
  box-shadow: 0 4px 12px -8px rgba(53, 134, 107, 0.7);
}

/* ── 演示账号 ── */
.demo-block {
  margin-top: 26px;
  padding-top: 20px;
  border-top: 1px solid rgba(37, 61, 53, 0.08);
}

.demo-label {
  display: block;
  margin-bottom: 11px;
  font-size: 12px;
  color: var(--muted);
  letter-spacing: 0.04em;
}

.demo-accounts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.demo-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 9px 13px;
  border: 1px solid rgba(53, 134, 107, 0.14);
  border-radius: 10px;
  background: rgba(238, 244, 239, 0.7);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.demo-chip:hover {
  border-color: rgba(53, 134, 107, 0.42);
  background: rgba(229, 238, 231, 0.95);
}

.demo-chip-role {
  flex-shrink: 0;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-soft);
}

.demo-chip-cred {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 12px;
  color: #5d6e66;
  font-variant-numeric: tabular-nums;
}

.page-foot {
  margin: 22px 0 0;
  max-width: 100%;
  font-size: 11.5px;
  color: var(--muted);
  letter-spacing: 0.03em;
  text-align: center;
  overflow-wrap: anywhere;
}

/* ════════════════════════════ 动效 ════════════════════════════ */
@keyframes fade-up {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* 背景光照在长周期内轻微变化，整体亮度保持稳定 */
@keyframes light-breathe {
  0%, 100% { opacity: 0.9; transform: scale(1) translate3d(0, 0, 0); }
  50%      { opacity: 1;   transform: scale(1.035) translate3d(6px, 4px, 0); }
}

/* 叶片投影被微风吹动，只有几像素位移 */
@keyframes sway-a {
  0%, 100% { transform: rotate(-16deg) translate3d(0, 0, 0); }
  50%      { transform: rotate(-14.4deg) translate3d(4px, -3px, 0); }
}

@keyframes sway-b {
  0%, 100% { transform: rotate(-8deg) translate3d(0, 0, 0); }
  50%      { transform: rotate(-9.6deg) translate3d(-4px, 3px, 0); }
}

@keyframes leaf-sway {
  0%, 100% { transform: rotate(-12deg) translate3d(0, 0, 0); }
  50%      { transform: rotate(-11deg) translate3d(2px, -3px, 0); }
}

/* 尊重「减少动态效果」偏好 */
@media (prefers-reduced-motion: reduce) {
  .brand,
  .form-side,
  .bg-light,
  .leaf-shadow,
  .glass-leaf {
    animation: none !important;
  }
  .login-btn,
  .login-form :deep(.el-input__wrapper) {
    transition: none !important;
  }
}

/* ════════════════════════════ 响应式 ════════════════════════════ */
/* 中等屏幕：左侧收窄，装饰缩小 */
@media (max-width: 1080px) {
  .login-shell {
    gap: 48px;
    padding: 40px;
    grid-template-columns: minmax(0, 1fr) minmax(360px, 420px);
  }
  .brand-decor {
    height: 128px;
    width: 240px;
    margin-top: 40px;
  }
  .glass-leaf {
    width: 186px;
    height: 122px;
  }
  .glass-leaf-glow {
    left: 100px;
    top: 38px;
    width: 140px;
    height: 92px;
  }
}

/* 手机 / 平板竖屏：隐藏大部分左侧装饰，保留品牌名与完整表单 */
@media (max-width: 880px) {
  /* 小屏改用最朴素的块级流式布局：
     不依赖 flex 居中 / grid 列宽 / max-width，从根上避免横向溢出 */
  .login-page {
    display: block;
    min-height: 100vh;
    overflow-x: hidden;
    overflow-y: auto;
  }
  .login-shell {
    display: block;
    width: 100%;
    max-width: none;
    padding: 28px 16px 36px;
    margin: 0;
  }
  .brand {
    display: none;              /* 左侧品牌整体隐藏 */
  }
  .bg-light {
    width: 130vw;
    height: 56vh;
    top: -18%;
    left: -35%;
  }
  .leaf-shadow--a { left: -150px; top: 8%; opacity: 0.8; }
  .leaf-shadow--b { display: none; }
  .leaf-shadow--c { left: -130px; bottom: 0; opacity: 0.7; }

  .brand-mobile {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 26px;
    color: var(--green);
    animation: fade-up 0.5s ease both;
  }
  .brand-mobile .logo-mark {
    width: 22px;
    height: 22px;
  }
  .brand-mobile .brand-name {
    font-size: 15.5px;
  }

  .form-side {
    display: block;
    width: 100%;
    min-width: 0;
  }
  .login-card {
    width: 100%;
    max-width: none;
    padding: 26px 18px 22px;
    border-radius: 20px;
    background: rgba(255, 255, 255, 0.9);
  }
  .card-title {
    font-size: 22px;
  }
  .card-sub {
    margin-bottom: 24px;
  }
  /* 小屏下键盘弹出时不挤压表单 */
  .login-form :deep(.el-input__wrapper) {
    height: 46px;
  }
  .login-btn {
    height: 46px;
  }
  .page-foot {
    margin-top: 18px;
    text-align: center;
  }
}

/* 超小屏：进一步压缩留白 */
@media (max-width: 380px) {
  .login-shell {
    padding: 22px 14px 28px;
  }
  .login-card {
    padding: 24px 16px 20px;
  }
  .demo-chip {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
  .demo-chip-cred {
    width: 100%;
  }
}
</style>
