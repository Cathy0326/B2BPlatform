<script setup lang="ts">
import type { NuxtError } from '#app'
const props = defineProps<{ error: NuxtError }>()
useSeoMeta({ title: () => (props.error.statusCode === 404 ? 'Not found' : 'Error') })
</script>

<template>
  <NuxtLayout>
    <div class="card err">
      <p class="eyebrow">Error {{ error.statusCode }}</p>
      <h1>{{ error.statusCode === 404 ? "We couldn't find that page." : 'Something went wrong.' }}</h1>
      <p class="muted">{{ error.statusMessage }}</p>
      <button class="btn btn-primary" @click="clearError({ redirect: '/' })">Back to equipment</button>
    </div>
  </NuxtLayout>
</template>

<style scoped>
.err {
  text-align: center;
  padding: 48px 16px;
}
</style>
