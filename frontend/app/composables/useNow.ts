/**
 * A clock that ticks every second on the client only.
 * Starts at 0 during SSR so server and client render the same markup, then updates on mount.
 */
export function useNow(intervalMs = 1000) {
  const now = ref(0)
  let timer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    now.value = Date.now()
    timer = setInterval(() => (now.value = Date.now()), intervalMs)
  })
  onBeforeUnmount(() => clearInterval(timer))
  return now
}
