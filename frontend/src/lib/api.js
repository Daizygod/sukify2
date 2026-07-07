import axios from 'axios'

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8088'

// Sanctum SPA cookie auth: send cookies, mirror the XSRF-TOKEN cookie into the
// X-XSRF-TOKEN header on unsafe requests.
const api = axios.create({
  baseURL: `${API_URL}/api`,
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { Accept: 'application/json' },
})

let csrfReady = false

/** Fetch the CSRF cookie once before the first mutating request. */
export async function ensureCsrf() {
  if (csrfReady) return
  await axios.get(`${API_URL}/sanctum/csrf-cookie`, { withCredentials: true })
  csrfReady = true
}

// Guarantee the CSRF cookie exists before any write.
api.interceptors.request.use(async (config) => {
  const method = (config.method || 'get').toLowerCase()
  if (['post', 'put', 'patch', 'delete'].includes(method)) {
    await ensureCsrf()
  }
  return config
})

export { API_URL }
export default api
