<template>
  <div class="login-page">
    <div class="login-visual">
      <div class="visual-title">碳能智控运维平台</div>
      <div class="visual-sub">Carbon Energy Intelligent Control &amp; O&amp;M Platform</div>
      <ul class="visual-list">
        <li>能耗数据统一接入与治理</li>
        <li>多维查询统计与自动化报表</li>
        <li>3D 园区监控与异常告警</li>
        <li>“小E” AI 助手智能问答诊断</li>
      </ul>
    </div>

    <div class="login-form-wrap">
      <div class="login-card">
        <h2>用户登录</h2>
        <p class="tip">请输入账号密码进入系统</p>

        <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="onSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" size="large" placeholder="用户名" :prefix-icon="User" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" size="large" type="password" show-password
                      placeholder="密码" :prefix-icon="Lock" />
          </el-form-item>
          <el-button type="primary" size="large" style="width: 100%" :loading="loading"
                     @click="onSubmit">
            登录
          </el-button>
        </el-form>

        <el-divider>演示账号（点击填充）</el-divider>
        <div class="demo-accounts">
          <el-tag v-for="a in demoAccounts" :key="a.username" class="demo-tag"
                  :type="a.type" effect="plain" @click="fill(a)">
            {{ a.label }}：{{ a.username }} / {{ a.password }}
          </el-tag>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
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
  { label: '管理员', username: 'admin', password: 'admin123', type: 'danger' as const },
  { label: '运维人员', username: 'operator', password: 'operator123', type: 'warning' as const },
  { label: '只读用户', username: 'viewer', password: 'viewer123', type: 'info' as const }
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
.login-page {
  display: flex;
  height: 100vh;
  background: linear-gradient(135deg, #0b1220 0%, #16233d 55%, #1d3a63 100%);
}

.login-visual {
  flex: 1.2;
  padding: 60px 56px;
  color: #e6edf7;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.visual-title {
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 1px;
  background: linear-gradient(90deg, #6fb3ff, #22d3ee);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.visual-sub {
  margin-top: 8px;
  font-size: 12px;
  color: #7c95bb;
  letter-spacing: 0.5px;
}

.visual-list {
  margin: 32px 0 0;
  padding: 0;
  list-style: none;
}

.visual-list li {
  padding: 9px 0 9px 22px;
  position: relative;
  font-size: 14px;
  color: #b9cbe6;
}

.visual-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 16px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22d3ee;
}

.login-form-wrap {
  flex: 0.8;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 12px;
  padding: 34px 32px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.28);
}

.login-card h2 { margin: 0 0 4px; font-size: 21px; color: #1f2937; }
.tip { margin: 0 0 22px; font-size: 12px; color: #9ca3af; }

.demo-accounts { display: flex; flex-direction: column; gap: 7px; }
.demo-tag { cursor: pointer; width: 100%; text-align: center; }

@media (max-width: 900px) {
  .login-visual { display: none; }
  .login-form-wrap { flex: 1; }
}
</style>
