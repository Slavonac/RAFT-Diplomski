import { useState } from 'react'

const API_URL = import.meta.env.VITE_API_URL

const NODE_COUNT = 5

export default function AdminPage() {
  const [pausedNodes, setPausedNodes] = useState(new Set())
  const [loadingNodes, setLoadingNodes] = useState(new Set())
  const [error, setError] = useState(null)

  const toggleNode = async (nodeId) => {
    if (loadingNodes.has(nodeId)) return

    setLoadingNodes(prev => new Set([...prev, nodeId]))
    setError(null)

    const isPaused = pausedNodes.has(nodeId)
    const action = isPaused ? 'resume' : 'pause'

    try {
      const response = await fetch(`${API_URL}/node/${nodeId}/${action}`, {
        method: 'POST',
      })

      if (response.ok) {
        setPausedNodes(prev => {
          const next = new Set(prev)
          if (isPaused) {
            next.delete(nodeId)
          } else {
            next.add(nodeId)
          }
          return next
        })
      } else {
        setError(`Node ${nodeId}: ${action} failed (${response.status})`)
      }
    } catch (e) {
      setError(`Node ${nodeId}: could not reach gateway`)
    } finally {
      setLoadingNodes(prev => {
        const next = new Set(prev)
        next.delete(nodeId)
        return next
      })
    }
  }

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col items-center justify-center gap-8 p-4">
      <h1 className="text-2xl font-bold text-gray-800">Raft Cluster Admin</h1>

      <div className="flex flex-wrap justify-center gap-4">
        {Array.from({ length: NODE_COUNT }, (_, i) => {
          const isPaused = pausedNodes.has(i)
          const isLoading = loadingNodes.has(i)
          return (
            <button
              key={i}
              onClick={() => toggleNode(i)}
              disabled={isLoading}
              className={[
                'w-24 h-24 rounded-full font-bold text-white text-sm transition-all select-none',
                isPaused
                  ? 'bg-red-500 hover:bg-red-600 active:bg-red-700'
                  : 'bg-green-500 hover:bg-green-600 active:bg-green-700',
                isLoading ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer shadow-md hover:shadow-lg',
              ].join(' ')}
            >
              {isLoading ? '...' : `Node ${i}`}
              <br />
              <span className="text-xs font-normal">{isPaused ? 'paused' : 'running'}</span>
            </button>
          )
        })}
      </div>

      <p className="text-gray-500 text-sm text-center">
        Click a node to pause it · Click again to resume
      </p>

      {error && (
        <div className="bg-red-100 border border-red-300 text-red-700 rounded px-4 py-2 text-sm">
          {error}
        </div>
      )}
    </div>
  )
}
