<template>
  <el-button
    :type="type"
    :loading="loading"
    :disabled="disabled"
    @click="confirmAction"
  >
    <slot />
  </el-button>
</template>

<script setup lang="ts">
import { ElMessageBox } from 'element-plus'

const props = withDefaults(
  defineProps<{
    title: string
    message: string
    confirmLabel?: string
    type?: 'primary' | 'danger' | 'warning'
    loading?: boolean
    disabled?: boolean
  }>(),
  { confirmLabel: '确认', type: 'primary', loading: false, disabled: false },
)
const emit = defineEmits<{ confirm: [] }>()

async function confirmAction(): Promise<void> {
  try {
    await ElMessageBox.confirm(props.message, props.title, {
      confirmButtonText: props.confirmLabel,
      cancelButtonText: '取消',
      type: props.type === 'danger' ? 'warning' : 'info',
      autofocus: false,
    })
    emit('confirm')
  } catch {
    // Cancellation is an expected keyboard- and pointer-accessible path.
  }
}
</script>
