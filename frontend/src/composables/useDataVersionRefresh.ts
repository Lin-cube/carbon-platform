import { onActivated } from 'vue'
import { useAppStore } from '@/stores/app'

/**
 * 数据版本刷新（配合 keep-alive 使用）。
 *
 * <h3>要解决的问题</h3>
 * MainLayout 用 {@code <keep-alive :max="6">} 缓存页面，导入新数据后页面不会重新挂载，
 * {@code onMounted} 不会再执行，于是看板/3D/查询/统计/异常全都停留在旧数据。
 *
 * <h3>为什么用 onActivated 而不是 watch(dataVersion)</h3>
 * 两者同时存在会在「导入后切回页面」时各触发一次，造成重复请求。
 * 被缓存的组件即使不可见也会响应 watch，会在后台白白刷新；
 * 因此统一改为「重新激活时比对版本号」，只有数据真的变了才刷新。
 *
 * <h3>并发与陈旧响应</h3>
 * <ul>
 *   <li>{@code inFlight}：同一次激活期间只允许一个刷新任务，避免连点或重复激活打爆接口；</li>
 *   <li>刷新前后比对 {@code dataVersion}：若刷新期间又发生了新的数据变更，
 *       则**不推进**已处理版本，下次激活会再刷一次，
 *       避免把「基于旧数据的响应」标记成本次刷新完成。</li>
 * </ul>
 */
export function useDataVersionRefresh(refresh: () => void | Promise<void>) {
  const app = useAppStore()
  /** 已经处理过的数据版本（初始化取当前值，因此首次挂载不会重复刷新） */
  let seenVersion = app.dataVersion
  let inFlight = false

  /** 手动触发一次（不受版本号限制），页面内的「刷新」按钮也可复用 */
  async function run(force = false): Promise<void> {
    if (inFlight) return
    if (!force && app.dataVersion === seenVersion) return

    inFlight = true
    const versionAtStart = app.dataVersion
    try {
      await refresh()
      // 只有刷新期间没有产生更新的版本，才认为本次结果是最新的
      if (app.dataVersion === versionAtStart) {
        seenVersion = app.dataVersion
      }
    } finally {
      inFlight = false
    }
  }

  onActivated(() => {
    void run()
  })

  return { run, isRefreshing: () => inFlight }
}
