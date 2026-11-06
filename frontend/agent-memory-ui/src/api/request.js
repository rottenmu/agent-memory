import axios from 'axios'
import { ElMessage } from 'element-plus'

// agent-memory-ui 独立 axios 封装（与 web-shell 行为一致，无登录态时跳过 token）
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

request.interceptors.request.use(config => {
  const token = sessionStorage.getItem('token')
  if (token) {
    config.headers['Authorization'] = token
  }
  return config
})

request.interceptors.response.use(
  res => {
    const data = res.data
    if (data && data.code === 401) {
      sessionStorage.removeItem('token')
      ElMessage.error(data.msg || '登录已过期')
      return Promise.reject(new Error(data.msg))
    }
    return data
  },
  err => {
    // 独立应用无登录页，401 仅提示不跳转
    if (err.response?.status === 401) {
      ElMessage.error('接口鉴权失败（401）')
    }
    return Promise.reject(err)
  }
)

export default request
