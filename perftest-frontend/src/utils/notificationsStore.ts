import { notificationsData } from '../data/notifications'
import { loadJSON } from './persistentStore'

const KEY = 'pt_notifications'

export function getUnreadNotificationsCount(): number {
  const items = loadJSON(KEY, notificationsData)
  return items.filter((n) => !n.read).length
}
