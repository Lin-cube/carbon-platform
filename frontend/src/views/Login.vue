<template>
  <div class="login-page">
    <!-- 全屏 Ken Burns 动态背景（独立组件） -->
    <KenBurnsBackground />

    <main class="shell">
      <!-- ══════════ 左侧：品牌展示 ══════════ -->
      <section class="intro">
        <div class="brand">
          <svg viewBox="0 0 32 32" aria-hidden="true">
            <path d="M25 4C11 7 6 16 12 27c14-3 19-12 13-23Z" fill="#58a78b" />
            <path
              d="M13 27C16 18 20 13 25 7"
              stroke="#e9fff2"
              stroke-width="1.5"
              fill="none"
            />
          </svg>
          碳能智控
        </div>

        <div class="eyebrow">SMART ENERGY · GREEN FUTURE</div>

        <h1 class="headline">
          让能源管理<br />
          <span>更轻松一点。</span>
        </h1>
      </section>

      <!-- ══════════ 右侧：登录卡片 ══════════ -->
      <section class="aside">
        <div class="card">
          <h2 class="card-title">欢迎回来</h2>
          <p class="caption">登录碳能智控，开启高效运维</p>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            class="login-form"
            @keyup.enter="onSubmit"
          >
            <el-form-item label="用户名" prop="username">
              <el-input
                v-model="form.username"
                :prefix-icon="User"
                placeholder="请输入用户名"
                autocomplete="username"
              />
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                :prefix-icon="Lock"
                placeholder="请输入密码"
                autocomplete="current-password"
              />
            </el-form-item>

            <button type="button" class="sign-in" :disabled="loading" @click="onSubmit">
              {{ loading ? '登录中…' : '登 录' }}
            </button>
          </el-form>

          <div class="divide"></div>
          <div class="demo-title">演示账号（点击填充）</div>
          <button
            v-for="a in demoAccounts"
            :key="a.username"
            type="button"
            class="demo"
            @click="fill(a)"
          >
            <span>{{ a.label }}</span>
            <small>{{ a.username }} / {{ a.password }}</small>
          </button>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 登录页。
 *
 * <p>本次为视觉改造：布局与配色按参考设计还原，业务逻辑一律保持不变：
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
 * 因此这里不放验证码输入框。
 */
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import KenBurnsBackground from '@/components/KenBurnsBackground.vue'

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
      /* 错误提示已由 axios 拦截器统一处理 */
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
/* ════════════════════════════ 设计变量 ════════════════════════════ */
.login-page {
  --ink: #243e36;
  --green: #358b70;
  --quiet: #6f817a;
  --border: #d5e4d9;

  position: relative;
  min-height: 100svh;
  overflow-x: hidden;          /* 防止横向滚动条；纵向可滚动 */
  isolation: isolate;
  background: #f7faf7;
  color: var(--ink);
  font-family: Inter, -apple-system, BlinkMacSystemFont, 'PingFang SC',
    'Microsoft YaHei', sans-serif;
}

/* ════════════════════════════ 布局骨架 ════════════════════════════ */
.shell {
  position: relative;
  z-index: 1;
  min-height: 100svh;
  width: min(1250px, 92%);
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 424px;
  align-items: center;
  gap: clamp(44px, 7vw, 135px);
  padding: 42px 0;
}

/* ════════════════════════════ 左侧品牌 ════════════════════════════ */
.intro {
  margin-top: 0;
  padding-left: clamp(0px, 4vw, 65px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  letter-spacing: 0.045em;
  font-size: 16px;
  margin-bottom: 54px;
  color: var(--ink);
}

.brand svg {
  width: 25px;
  height: 25px;
  flex-shrink: 0;
}

.eyebrow {
  color: #328b6f;
  letter-spacing: 2.2px;
  font-weight: 700;
  margin-bottom: 16px;
  font-size: 11px;
}

.headline {
  margin: 0;
  font-size: clamp(42px, 4.2vw, 65px);
  line-height: 1.31;
  letter-spacing: -2.6px;
  font-weight: 760;
  text-shadow: 0 1px 16px rgba(255, 255, 255, 0.22);
}

.headline span {
  color: #2d9173;
}

/* ════════════════════════════ 右侧卡片 ════════════════════════════ */
.aside {
  justify-self: stretch;
  min-width: 0;
}

.card {
  padding: 42px 39px 38px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.85);
  box-shadow:
    0 23px 70px rgba(19, 67, 48, 0.19),
    0 1px 5px rgba(44, 92, 74, 0.08);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.card-title {
  margin: 0;
  font-size: 27px;
  font-weight: 750;
  letter-spacing: -0.6px;
  color: var(--ink);
}

.caption {
  margin: 9px 0 35px;
  font-size: 13px;
  color: #7a8a80;
}

/* ── 表单（Element Plus 外观对齐参考设计） ── */
.login-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-form :deep(.el-form-item.is-required .el-form-item__label::before) {
  display: none;               /* 去掉红星，保持清爽 */
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: 11px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--ink);
}

.login-form :deep(.el-input__wrapper) {
  height: 52px;
  padding: 0 12px;
  border-radius: 11px;
  background: #f5f9f6;
  box-shadow: 0 0 0 1px var(--border) inset;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  background: #f0f6f2;
  box-shadow: 0 0 0 1px #c3d5c9 inset;
}

/* 聚焦：柔和的绿色边框 + 极淡外圈 */
.login-form :deep(.el-input__wrapper.is-focus) {
  background: #ffffff;
  box-shadow:
    0 0 0 1px #4aaf8a inset,
    0 0 0 3px #53b89524;
}

.login-form :deep(.el-input__inner) {
  font-size: 14px;
  color: var(--ink);
}

.login-form :deep(.el-input__inner::placeholder) {
  color: #7b9084;
}

.login-form :deep(.el-input__prefix) {
  color: #7d9a8b;
  font-size: 16px;
}

.login-form :deep(.el-input__suffix) {
  color: #6c887b;
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
.sign-in {
  height: 52px;
  width: 100%;
  margin-top: 4px;
  background: #34866b;
  color: #ffffff;
  border: none;
  border-radius: 11px;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 4px;
  cursor: pointer;
  font-family: inherit;
  box-shadow: 0 5px 12px #246d5220;
  transition: transform 0.2s ease, background 0.2s ease;
}

.sign-in:hover:not(:disabled) {
  transform: translateY(-1px);
  background: #3d9878;          /* 悬停轻微变亮 */
}

.sign-in:active:not(:disabled) {
  transform: translateY(0);
}

.sign-in:disabled {
  opacity: 0.72;
  cursor: not-allowed;
}

/* ── 演示账号 ── */
.divide {
  height: 1px;
  background: #e9efeb;
  margin: 30px 0 19px;
}

.demo-title {
  font-size: 12px;
  color: #89978e;
  margin: 0 0 11px;
}

.demo {
  width: 100%;
  border: 1px solid #d8e5dc;
  background: #f4f8f5;
  color: #3d544b;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-radius: 9px;
  margin-top: 8px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.18s ease, border-color 0.18s ease;
}

.demo:hover {
  background: #e9f4ec;
  border-color: #c8dccf;
}

.demo small {
  font-size: 11px;
  color: #7d8b83;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* ════════════════════════════ 响应式 ════════════════════════════ */
@media (max-width: 1080px) {
  .shell {
    gap: clamp(30px, 5vw, 72px);
    grid-template-columns: minmax(0, 1fr) 400px;
  }
  .card {
    padding: 36px 30px 32px;
  }
}

@media (max-width: 820px) {
  .shell {
    grid-template-columns: 1fr;
    width: min(520px, 90%);
    gap: 28px;
    padding: 38px 0 58px;
  }
  .intro {
    padding: 0;
  }
  .brand {
    margin-bottom: 26px;
  }
  .headline {
    font-size: clamp(36px, 9vw, 50px);
    letter-spacing: -1.8px;
  }
  .card {
    padding: 29px 24px;
    border-radius: 20px;
  }
}

@media (max-width: 380px) {
  .card {
    padding: 26px 18px;
  }
  .card-title {
    font-size: 24px;
  }
  .demo {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
}

/* 尊重「减少动态效果」偏好 */
@media (prefers-reduced-motion: reduce) {
  .sign-in {
    transition: none;
  }
  .login-form :deep(.el-input__wrapper) {
    transition: none;
  }
}
</style>
