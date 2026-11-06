<template>
  <!-- agent-memory-ui 独立应用：MemoryDashboard.vue 自带 dsh 布局与 Tab 切换，此处仅作为路由出口 -->
  <router-view />
</template>

<script setup>
</script>

<style>
/* 全局基础样式：白底 + 系统字体（与 web-shell 环境对齐） */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
  background: #f5f5f5;
  font-family: 'Inter', -apple-system, 'SF Pro Display', 'Segoe UI', system-ui, sans-serif;
  color: #171717;
  font-size: 14px;
  line-height: 1.55;
}
* {
  box-sizing: border-box;
}
</style>
