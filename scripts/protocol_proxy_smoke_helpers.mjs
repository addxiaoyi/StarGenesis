import { createHash } from 'node:crypto'

export function offlinePlayerUuid(username) {
  const bytes = createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const value = bytes.toString('hex')
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`
}

export function componentText(component) {
  if (typeof component === 'string') {
    try {
      const parsed = JSON.parse(component)
      return parsed === component ? component : componentText(parsed)
    } catch {
      return component
    }
  }
  if (Array.isArray(component)) return component.map(componentText).join('')
  if (component && typeof component === 'object') {
    if (typeof component.value === 'string' && Object.keys(component).every(key => key === 'type' || key === 'value'))
      return component.value
    const ownText = typeof component.text === 'string' ? component.text : ''
    const extraText = componentText(component.extra ?? [])
    const childrenText = ownText === '' && extraText === ''
      ? Object.values(component).map(componentText).join('')
      : extraText
    return ownText + childrenText
  }
  return ''
}

export function inventoryItemText(item) {
  if (!item) return ''
  const fields = [item.customName, item.displayName, item.customLore, item.lore, item.nbt]
  return fields.map(componentText).filter(Boolean).join('\n')
}
