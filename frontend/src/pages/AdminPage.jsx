import { useState, useEffect } from 'react'

const API_URL = import.meta.env.VITE_API_URL
const NODE_COUNT = 5

const sha256 = async (text) => {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('')
}

const ADMIN_HASH = sha256(String.fromCharCode(104, 114, 97, 110, 97))

function PasswordGate({ onSuccess }) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)
    const [inputHash, storedHash] = await Promise.all([sha256(password), ADMIN_HASH])
    setLoading(false)
    if (inputHash === storedHash) {
      sessionStorage.setItem('adminAuth', '1')
      onSuccess()
    } else {
      setError('Pogrešna šifra')
      setPassword('')
    }
  }

  return (
    <div className="min-h-screen bg-gray-100 flex items-center justify-center">
      <form
        onSubmit={handleSubmit}
        className="bg-white rounded-xl shadow-md p-8 w-80 flex flex-col gap-4"
      >
        <h1 className="text-xl font-bold text-center text-gray-800">Admin Panel</h1>
        <input
          type="password"
          value={password}
          onChange={e => { setPassword(e.target.value); setError('') }}
          placeholder="Unesite šifru"
          className="border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400 text-gray-800"
          autoFocus
        />
        {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        <button
          type="submit"
          disabled={loading || !password}
          className="bg-blue-500 hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded px-4 py-2 font-semibold transition-colors"
        >
          {loading ? 'Proveravam...' : 'Prijava'}
        </button>
      </form>
    </div>
  )
}

export default function AdminPage() {
  const [authenticated, setAuthenticated] = useState(
    () => sessionStorage.getItem('adminAuth') === '1'
  )
  const [pausedNodes, setPausedNodes] = useState(new Set())
  const [loadingNodes, setLoadingNodes] = useState(new Set())
  const [error, setError] = useState(null)
  const [leaderId, setLeaderId] = useState(null)
  const [clearing, setClearing] = useState(false)
  const [clearSuccess, setClearSuccess] = useState(false)
  const [nodeLogs, setNodeLogs] = useState(Array(NODE_COUNT).fill(null))
  const [logsLoading, setLogsLoading] = useState(false)

  useEffect(() => {
    if (!authenticated) return
    const fetchLeader = () => {
      fetch(`${API_URL}/node/leader`)
        .then(r => r.json())
        .then(data => setLeaderId(data.leaderId))
        .catch(() => setLeaderId(null))
    }
    fetchLeader()
    const interval = setInterval(fetchLeader, 2000)
    return () => clearInterval(interval)
  }, [authenticated])

  const fetchLogs = async () => {
    setLogsLoading(true)
    const results = await Promise.all(
      Array.from({ length: NODE_COUNT }, (_, i) =>
        fetch(`${API_URL}/node/${i}/log`)
          .then(r => r.ok ? r.json() : null)
          .catch(() => null)
      )
    )
    setNodeLogs(results)
    setLogsLoading(false)
  }

  useEffect(() => {
    if (authenticated) fetchLogs()
  }, [authenticated])

  const toggleNode = async (nodeId) => {
    if (loadingNodes.has(nodeId)) return
    setLoadingNodes(prev => new Set([...prev, nodeId]))
    setError(null)
    const isPaused = pausedNodes.has(nodeId)
    const action = isPaused ? 'resume' : 'pause'
    try {
      const response = await fetch(`${API_URL}/node/${nodeId}/${action}`, { method: 'POST' })
      if (response.ok) {
        setPausedNodes(prev => {
          const next = new Set(prev)
          isPaused ? next.delete(nodeId) : next.add(nodeId)
          return next
        })
      } else {
        setError(`Node ${nodeId}: ${action} neuspešan (${response.status})`)
      }
    } catch {
      setError(`Node ${nodeId}: nije moguće kontaktirati gateway`)
    } finally {
      setLoadingNodes(prev => {
        const next = new Set(prev)
        next.delete(nodeId)
        return next
      })
    }
  }

  const handleClearMessages = async () => {
    if (!window.confirm('Da li ste sigurni da želite da obrišete sve poruke?')) return
    setClearing(true)
    setError(null)
    setClearSuccess(false)
    try {
      const response = await fetch(`${API_URL}/message`, { method: 'DELETE' })
      if (response.ok) {
        setClearSuccess(true)
        setTimeout(() => setClearSuccess(false), 3000)
      } else {
        setError('Brisanje poruka neuspešno')
      }
    } catch {
      setError('Nije moguće kontaktirati gateway')
    } finally {
      setClearing(false)
    }
  }

  const maxIndex = Math.max(
    0,
    ...nodeLogs.map(n => {
      if (!n?.entries?.length) return 0
      return n.entries[n.entries.length - 1].index
    })
  )

  const entryMaps = nodeLogs.map(n => {
    if (!n?.entries) return {}
    const map = {}
    for (const e of n.entries) map[e.index] = e
    return map
  })

  if (!authenticated) {
    return <PasswordGate onSuccess={() => setAuthenticated(true)} />
  }

  return (
    <div className="min-h-screen bg-gray-100 p-4">
      <div className="max-w-5xl mx-auto flex flex-col gap-5">

        {/* Header */}
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold text-gray-800">Raft Cluster Admin</h1>
          <button
            onClick={fetchLogs}
            disabled={logsLoading}
            className={`text-gray-500 hover:text-gray-700 disabled:cursor-not-allowed text-2xl px-3 py-1 transition-colors${logsLoading ? ' animate-spin' : ''}`}
            title="Osveži logove"
          >
            ↻
          </button>
        </div>

        {/* Controls */}
        <div className="flex items-center gap-4 flex-wrap">
          <button
            onClick={handleClearMessages}
            disabled={clearing}
            className="bg-red-600 hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed text-white font-semibold px-5 py-2 rounded-lg shadow transition-colors text-sm"
          >
            {clearing ? 'Brisanje...' : 'Ukloni sve poruke'}
          </button>
          {clearSuccess && (
            <span className="text-green-600 text-sm font-medium">Sve poruke su uspešno obrisane</span>
          )}
        </div>

        {error && (
          <div className="bg-red-100 border border-red-300 text-red-700 rounded px-4 py-2 text-sm">
            {error}
          </div>
        )}

        {/* Log table */}
        <div className="overflow-x-auto rounded-xl shadow bg-white">
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-gray-200">
                {Array.from({ length: NODE_COUNT }, (_, i) => {
                  const isPaused = pausedNodes.has(i)
                  const isLoading = loadingNodes.has(i)
                  const isLeader = leaderId === i
                  const nodeState = nodeLogs[i]?.nodeState
                  return (
                    <th key={i} className="p-4 text-center w-1/5">
                      <div className="flex flex-col items-center gap-1">
                        <button
                          onClick={() => toggleNode(i)}
                          disabled={isLoading}
                          className={[
                            'w-20 h-20 rounded-full font-bold text-white text-xs transition-all select-none',
                            isPaused
                              ? 'bg-red-500 hover:bg-red-600 active:bg-red-700'
                              : 'bg-green-500 hover:bg-green-600 active:bg-green-700',
                            isLoading ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer shadow-md hover:shadow-lg',
                            isLeader ? 'ring-4 ring-yellow-400 ring-offset-2' : '',
                          ].join(' ')}
                        >
                          {isLoading ? '...' : `Node ${i}`}
                          <br />
                          <span className="font-normal text-[10px]">{isPaused ? 'paused' : 'running'}</span>
                        </button>
                        {isLeader && (
                          <span className="text-xs font-semibold text-yellow-600 bg-yellow-100 px-2 py-0.5 rounded-full">
                            LEADER
                          </span>
                        )}
                        {nodeState && !isLeader && (
                          <span className="text-[10px] text-gray-400">{nodeState}</span>
                        )}
                      </div>
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody>
              {maxIndex === 0 ? (
                <tr>
                  <td colSpan={NODE_COUNT} className="text-center py-10 text-gray-400 text-sm">
                    {logsLoading ? 'Učitavanje...' : 'Nema unosa u logu'}
                  </td>
                </tr>
              ) : (
                Array.from({ length: maxIndex }, (_, idx) => {
                  const logIndex = idx + 1
                  return (
                    <tr key={logIndex} className="border-t border-gray-100">
                      {Array.from({ length: NODE_COUNT }, (_, nodeId) => {
                        const entry = entryMaps[nodeId][logIndex]
                        if (!entry) {
                          return <td key={nodeId} className="px-2 py-1 bg-gray-50" />
                        }
                        const label = entry.commandType === 'ADD'
                          ? `ADD ${entry.user} ${entry.content}`
                          : `DEL ${entry.content}`
                        return (
                          <td
                            key={nodeId}
                            title={label}
                            className={`px-2 py-1 text-xs font-mono truncate max-w-0 w-1/5 ${
                              entry.committed
                                ? 'bg-green-100 text-green-800'
                                : 'bg-red-100 text-red-800'
                            }`}
                          >
                            {label}
                          </td>
                        )
                      })}
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        <p className="text-gray-400 text-xs text-center">
          Kliknite čvor da ga pauzirate · Kliknite ponovo da nastavite · Zeleno = commitovano · Crveno = nije commitovano
        </p>

      </div>
    </div>
  )
}
