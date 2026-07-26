import { ElNotification } from 'element-plus'

export const notify = {
  success(title: string, message: string): void {
    ElNotification({ title, message, type: 'success', duration: 4000 })
  },
  error(title: string, message: string): void {
    ElNotification({ title, message, type: 'error', duration: 0 })
  },
}
