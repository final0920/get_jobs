'use client'

import { useState, useEffect } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiLogOut, BiSave, BiBriefcase, BiPlay, BiStop } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import PageHeader from '@/app/components/PageHeader'

interface YupaoConfig {
  id?: number
  keywords?: string
  cityCode?: string
  salary?: string
  sayHi?: string
  blackKeywords?: string
  companyBlacklist?: string
  degree?: string
  filterProxy?: number
}

export default function YupaoPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [loadingConfig, setLoadingConfig] = useState(true)

  const [config, setConfig] = useState<YupaoConfig>({
    keywords: '', cityCode: 'a180', salary: '', sayHi: '',
    blackKeywords: '', companyBlacklist: '', degree: '', filterProxy: 0,
  })

  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      setCheckingLogin(false)
      return
    }
    const client = createSSEWithBackoff('http://localhost:8888/api/jobs/login-status/stream', {
      onError: () => setCheckingLogin(false),
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.yupaoLoggedIn || false)
              setCheckingLogin(false)
            } catch {}
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === 'yupao') {
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch {}
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })
    return () => client.close()
  }, [])

  const parseListFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ')
      } catch {}
    }
    return t.replace(/，/g, ',')
  }

  const serializeListForDb = (display?: string): string => {
    const raw = (display || '').trim()
    if (!raw) return '[]'
    const tokens = raw.replace(/，/g, ',').split(',').map((s) => s.trim()).filter((s) => s.length > 0)
    return JSON.stringify(tokens)
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch('http://localhost:8888/api/yupao/config')
      const data = await res.json()
      if (data.config) {
        const c = { ...data.config }
        c.keywords = parseListFromDb(data.config.keywords)
        c.blackKeywords = parseListFromDb(data.config.blackKeywords)
        c.companyBlacklist = parseListFromDb(data.config.companyBlacklist)
        c.degree = parseListFromDb(data.config.degree)
        c.filterProxy = Number(data.config.filterProxy) === 1 ? 1 : 0
        if (!c.cityCode) c.cityCode = 'a180'
        setConfig(c)
      }
    } catch (e) {
      console.error('[鱼泡] 获取配置失败:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  useEffect(() => { fetchAllData() }, [])

  const handleStartDelivery = async () => {
    try {
      setIsDelivering(true)
      const response = await fetch('http://localhost:8888/api/yupao/start', { method: 'POST' })
      const data = await response.json()
      if (!data.success) setIsDelivering(false)
    } catch {
      setIsDelivering(false)
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/yupao/stop', { method: 'POST' })
      const data = await response.json()
      if (data.success) setIsDelivering(false)
    } catch {}
  }

  const triggerLogin = async () => {
    try {
      await fetch('http://localhost:8888/api/yupao/login', { method: 'POST' })
    } catch {}
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/yupao/logout', { method: 'POST' })
      const data = await response.json()
      setIsLoggedIn(false)
      setSaveResult({ success: data.success, message: data.success ? '已退出登录，Cookie已清空。' : data.message })
      setShowSaveDialog(true)
    } catch {
      setSaveResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  const handleSaveConfig = async () => {
    try {
      const payload = {
        ...config,
        keywords: serializeListForDb(config.keywords),
        blackKeywords: serializeListForDb(config.blackKeywords),
        companyBlacklist: serializeListForDb(config.companyBlacklist),
        degree: serializeListForDb(config.degree),
        filterProxy: config.filterProxy ? 1 : 0,
      }
      const response = await fetch('http://localhost:8888/api/yupao/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (response.ok) {
        try { await fetch('http://localhost:8888/api/cookie/save?platform=yupao', { method: 'POST' }) } catch {}
        await fetchAllData()
        setSaveResult({ success: true, message: '保存成功，配置已更新。' })
      } else {
        setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
      }
      setShowSaveDialog(true)
    } catch (error) {
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="鱼泡直聘配置"
        subtitle="配置鱼泡直聘平台的求职参数与过滤规则"
        iconClass="text-white"
        accentBgClass="bg-sky-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button onClick={triggerLogin} size="sm" className="rounded-full bg-gradient-to-r from-sky-500 to-cyan-500 hover:from-sky-600 hover:to-cyan-600 text-white px-4 shadow-lg">
                <BiPlay className="mr-1" /> 登录鱼泡直聘
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg">
                <BiStop className="mr-1" /> 停止投递
              </Button>
            ) : (
              <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg">
                <BiPlay className="mr-1" /> 开始投递
              </Button>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg">
              <BiLogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={handleSaveConfig} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg">
              <BiSave className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <BiBriefcase className="text-primary" />
            鱼泡直聘平台说明
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            <p className="text-sm text-muted-foreground">点击“登录鱼泡直聘”后，在弹出的浏览器中扫码或验证码登录（求职者身份）。</p>
            <p className="text-sm text-muted-foreground">投递为“直聘/打招呼”模式：进入职位详情后点击“免费聊”建立沟通并发送打招呼语。</p>
            <p className="text-sm text-muted-foreground">城市编码为鱼泡 a 码（如 a180=武汉），可在站点切换城市后从地址栏获取。</p>
          </div>
        </CardContent>
      </Card>

      <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <BiBriefcase className="text-primary" />
            配置参数
          </CardTitle>
        </CardHeader>
        <CardContent>
          {loadingConfig ? (
            <p className="text-sm text-muted-foreground">配置加载中...</p>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>搜索关键词（逗号分隔）</Label>
                <Input placeholder="如：Java, 后端, Spring" value={config.keywords || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label>城市编码（鱼泡 a 码）</Label>
                <Input placeholder="如：a180（武汉）" value={config.cityCode || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, cityCode: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label>薪资范围</Label>
                <Input placeholder="如：8000, 15000 或 不限" value={config.salary || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, salary: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label>学历过滤（仅投递这些学历，逗号分隔，留空=不限）</Label>
                <Input placeholder="如：大专, 本科" value={config.degree || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, degree: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label>黑名单关键词（命中标题则不投递）</Label>
                <Input placeholder="如：外包, 销售, 电话客服" value={config.blackKeywords || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, blackKeywords: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label>公司黑名单（命中公司名则不投递）</Label>
                <Input placeholder="如：XX科技, XX外包" value={config.companyBlacklist || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, companyBlacklist: e.target.value }))} />
              </div>
              <div className="space-y-2 md:col-span-2">
                <Label>打招呼语（免费聊首次沟通时发送）</Label>
                <textarea
                  className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  placeholder="您好，我对贵公司的岗位很感兴趣，期待沟通。"
                  value={config.sayHi || ''}
                  onChange={(e) => setConfig((c) => ({ ...c, sayHi: e.target.value }))}
                />
              </div>
              <div className="space-y-2">
                <Label>过滤代招岗位</Label>
                <label className="flex items-center gap-2 h-10 px-1 cursor-pointer select-none">
                  <input type="checkbox" className="h-4 w-4 accent-sky-500"
                    checked={config.filterProxy === 1}
                    onChange={(e) => setConfig((c) => ({ ...c, filterProxy: e.target.checked ? 1 : 0 }))} />
                  <span className="text-sm text-muted-foreground">开启后自动跳过标记为“代招/代理招聘”的岗位</span>
                </label>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className="text-red-500" /> 确认退出登录
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setShowLogoutDialog(false)} className="rounded-full px-4">取消</Button>
                <Button onClick={async () => { await triggerLogout(); setShowLogoutDialog(false) }} className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 text-white px-4">确认退出</Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                {saveResult.success ? '操作成功' : '操作失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
              <Button onClick={() => setShowSaveDialog(false)} className={`rounded-full px-4 ${saveResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
