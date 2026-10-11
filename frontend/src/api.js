export async function requestJson(url, options) {
  const response = await fetch(url, options)
  let data
  try {
    data = await response.json()
  } catch {
    throw new Error(`HTTP ${response.status}`)
  }
  if (!response.ok || data?.status === 'error' || data?.ok === false) {
    throw new Error(data?.message || data?.error || `HTTP ${response.status}`)
  }
  return data
}
