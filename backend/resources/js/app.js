import { createApp, h } from 'vue'
import { createInertiaApp } from '@inertiajs/vue3'
import AdminLayout from './Layouts/AdminLayout.vue'

createInertiaApp({
  title: (title) => (title ? `${title} · Sukify Admin` : 'Sukify Admin'),
  resolve: (name) => {
    const pages = import.meta.glob('./Pages/**/*.vue', { eager: true })
    const page = pages[`./Pages/${name}.vue`]
    // Auth pages opt out of the chrome by starting with "Auth/".
    if (page.default.layout === undefined && !name.startsWith('Auth/')) {
      page.default.layout = AdminLayout
    }
    return page
  },
  setup({ el, App, props, plugin }) {
    createApp({ render: () => h(App, props) })
      .use(plugin)
      .mount(el)
  },
  progress: { color: '#1ed760' },
})
