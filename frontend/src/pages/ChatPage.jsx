import { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

const API_URL = import.meta.env.VITE_API_URL

function generateId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

// Mirrors java.lang.String.hashCode so the id we assign locally matches
// the messageId the gateway will compute for the same string.
function javaStringHashCode(str) {
  let h = 0
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(31, h) + str.charCodeAt(i) | 0
  }
  return h
}

function formatTime(timestamp) {
  return new Date(timestamp).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
  })
}

function Spinner() {
  return (
    <div className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin opacity-60" />
  )
}

// Merge a server message into existing list, keyed by logIndex.
// Preserves local-only (pending/error) messages that have no logIndex yet.
function mergeMessage(prev, serverMsg) {
  const existingIdx = prev.findIndex(m => m.logIndex === serverMsg.logIndex)
  if (existingIdx >= 0) {
    const next = prev.slice()
    next[existingIdx] = { ...serverMsg, status: 'sent' }
    return next
  }
  // Check if this replaces a local pending message (match by id)
  const localIdx = prev.findIndex(m => m.id === serverMsg.id && !m.logIndex)
  if (localIdx >= 0) {
    const next = prev.slice()
    next[localIdx] = { ...serverMsg, status: 'sent' }
    return next
  }
  return [...prev, { ...serverMsg, status: 'sent' }]
}

function sortByOrder(list) {
  // Messages with logIndex sort by it; local-only ones fall to the end by timestamp.
  return list.slice().sort((a, b) => {
    if (a.logIndex && b.logIndex) return a.logIndex - b.logIndex
    if (a.logIndex) return -1
    if (b.logIndex) return 1
    return new Date(a.timestamp) - new Date(b.timestamp)
  })
}

export default function ChatPage() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [contextMenu, setContextMenu] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const messagesEndRef = useRef(null)
  const scrollContainerRef = useRef(null)
  const isAtBottomRef = useRef(true)
  const longPressTimer = useRef(null)
  const lastSeenIndexRef = useRef(0)
  const eventSourceRef = useRef(null)
  const navigate = useNavigate()
  const username = localStorage.getItem('username')

  useEffect(() => {
    if (!username) navigate('/')
  }, [username, navigate])

  const fetchSnapshot = useCallback(async () => {
    try {
      const res = await fetch(`${API_URL}/message`)
      if (!res.ok) return
      const data = await res.json()
      let maxIdx = 0
      for (const m of data) if (m.logIndex > maxIdx) maxIdx = m.logIndex
      lastSeenIndexRef.current = maxIdx
      setMessages(prev => {
        let next = prev
        for (const m of data) next = mergeMessage(next, m)
        return sortByOrder(next)
      })
    } catch {
      // server unavailable
    }
  }, [])

  useEffect(() => {
    fetchSnapshot()

    const es = new EventSource(`${API_URL}/stream`)
    eventSourceRef.current = es

    es.addEventListener('message', (evt) => {
      let msg
      try { msg = JSON.parse(evt.data) } catch { return }
      if (typeof msg.logIndex !== 'number') return

      const prevSeen = lastSeenIndexRef.current
      // Gap detection: SSE skipped an index → resync from snapshot.
      if (msg.logIndex > prevSeen + 1) {
        fetchSnapshot()
        return
      }
      if (msg.logIndex > prevSeen) lastSeenIndexRef.current = msg.logIndex
      setMessages(prev => sortByOrder(mergeMessage(prev, msg)))
    })

    es.onerror = () => {
      // EventSource auto-reconnects; nothing to do here.
    }

    return () => {
      es.close()
      eventSourceRef.current = null
    }
  }, [fetchSnapshot])

  const handleScroll = () => {
    const el = scrollContainerRef.current
    if (!el) return
    isAtBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 50
  }

  useEffect(() => {
    if (isAtBottomRef.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  const sendMessage = async (content) => {
    const clientId = generateId()
    // Precompute the serverId so local state and SSE events use the same key.
    const serverId = String(javaStringHashCode(clientId))
    const message = {
      id: serverId,
      timestamp: new Date().toISOString(),
      username,
      content,
      status: 'pending',
    }

    setMessages(prev => [...prev, message])

    try {
      const res = await fetch(`${API_URL}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...message, id: clientId }),
      })

      if (res.ok) {
        setMessages(prev =>
          prev.map(m => m.id === serverId && !m.logIndex ? { ...m, status: 'sent' } : m)
        )
      } else {
        setMessages(prev =>
          prev.map(m => m.id === serverId ? { ...m, status: 'error' } : m)
        )
      }
    } catch {
      // network unavailable — message stays pending
    }
  }

  const handleRefresh = useCallback(async () => {
    if (refreshing) return
    setRefreshing(true)
    setMessages([])
    lastSeenIndexRef.current = 0
    await fetchSnapshot()
    setRefreshing(false)
  }, [refreshing, fetchSnapshot])

  const handleSend = () => {
    const trimmed = input.trim()
    if (!trimmed) return
    sendMessage(trimmed)
    setInput('')
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const openContextMenu = (e, msg) => {
    if (msg.username !== username || msg.deleted || msg.status === 'pending') return
    e.preventDefault()
    const { clientX, clientY } = e.touches ? e.touches[0] : e
    setContextMenu({ msgId: msg.id, x: clientX, y: clientY })
  }

  const handleTouchStart = (e, msg) => {
    if (msg.username !== username || msg.deleted || msg.status === 'pending') return
    const touch = e.touches[0]
    longPressTimer.current = setTimeout(() => {
      setContextMenu({ msgId: msg.id, x: touch.clientX, y: touch.clientY })
    }, 500)
  }

  const cancelLongPress = () => {
    clearTimeout(longPressTimer.current)
  }

  const deleteMessage = async (msgId) => {
    setContextMenu(null)
    setMessages(prev =>
      prev.map(m => m.id === msgId ? { ...m, deleted: true } : m)
    )
    try {
      await fetch(`${API_URL}/message/${msgId}`, { method: 'DELETE' })
    } catch {
      // SSE will deliver the final state; on failure, refresh recovers.
    }
  }

  return (
    <div className="fixed inset-0 bg-gray-100 flex justify-center px-4 py-4">
      {contextMenu && (
        <div
          className="fixed inset-0 z-50"
          onMouseDown={() => setContextMenu(null)}
          onTouchStart={() => setContextMenu(null)}
        >
          <div
            className="absolute bg-white rounded-xl shadow-xl border border-gray-200 overflow-hidden"
            style={{
              top: contextMenu.y,
              left: contextMenu.x,
              transform: 'translate(-110%, -50%)',
            }}
            onMouseDown={e => e.stopPropagation()}
            onTouchStart={e => e.stopPropagation()}
          >
            <button
              onClick={() => deleteMessage(contextMenu.msgId)}
              className="flex items-center gap-2 px-5 py-3 text-red-600 hover:bg-red-50 active:bg-red-100 text-sm font-medium w-full whitespace-nowrap"
            >
              🗑️ Delete message
            </button>
          </div>
        </div>
      )}
      <div className="w-full max-w-md flex flex-col h-full min-h-0">

        {/* Header */}
        <div className="flex items-center justify-between py-3 mb-2 shrink-0">
          <h1 className="text-xl font-bold text-gray-900">Chat</h1>
          <div className="flex items-center gap-2">
            <button
              onClick={handleRefresh}
              disabled={refreshing}
              className={`text-gray-400 hover:text-gray-600 disabled:cursor-not-allowed text-xl px-2 py-1 transition-colors${refreshing ? ' animate-spin' : ''}`}
              title="Refresh messages"
            >
              ↻
            </button>
            <button
              onClick={() => { localStorage.removeItem('username'); navigate('/') }}
              className="text-gray-400 hover:text-gray-600 text-sm transition-colors"
            >
              @{username} &times;
            </button>
          </div>
        </div>

        {/* Message list */}
        <div ref={scrollContainerRef} onScroll={handleScroll} className="flex-1 overflow-y-auto mb-3 space-y-2 pr-1 min-h-0">
          {messages.length === 0 && (
            <p className="text-center text-gray-400 text-sm mt-10">No messages yet...</p>
          )}

          {messages.map(msg => {
            const isOwn = msg.username === username
            const isError = msg.status === 'error'

            if (isError) {
              return (
                <div key={msg.id} className="flex justify-end">
                  <div className="max-w-[75%] rounded-2xl px-4 py-2 bg-red-100 border border-red-400 text-red-700 rounded-br-sm shadow-sm">
                    <p className="text-xs font-semibold mb-1">Server greška</p>
                    <p className="text-sm line-through opacity-60">
                      {msg.content === 'smile' ? '😊'
                        : msg.content === 'wow' ? '😮'
                        : msg.content === 'skull' ? '💀'
                        : msg.content === 'heart' ? '❤️'
                        : msg.content === 'fire' ? '🔥'
                        : msg.content === 'thumbsup' ? '👍'
                        : msg.content}
                    </p>
                    <span className="text-xs text-red-400">{formatTime(msg.timestamp)}</span>
                  </div>
                </div>
              )
            }

            return (
              <div key={msg.id} className={`flex ${isOwn ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[75%] rounded-2xl px-4 py-2 shadow-sm transition-opacity duration-300 ${
                    isOwn
                      ? 'bg-red-500 text-white rounded-br-sm'
                      : 'bg-white text-gray-900 rounded-bl-sm'
                  } ${msg.status === 'pending' ? 'opacity-60' : 'opacity-100'} ${isOwn && !msg.deleted && msg.status !== 'pending' ? 'cursor-pointer select-none' : ''}`}
                  onContextMenu={e => openContextMenu(e, msg)}
                  onTouchStart={e => handleTouchStart(e, msg)}
                  onTouchEnd={cancelLongPress}
                  onTouchMove={cancelLongPress}
                >
                  {!isOwn && (
                    <p className="text-xs font-semibold text-red-500 mb-1">{msg.username}</p>
                  )}
                  <p className={`text-sm leading-snug ${msg.deleted ? 'italic opacity-60' : ''}`}>
                    {msg.deleted
                      ? 'message is deleted by user'
                      : msg.content === 'smile' ? '😊'
                      : msg.content === 'wow' ? '😮'
                      : msg.content === 'skull' ? '💀'
                      : msg.content === 'heart' ? '❤️'
                      : msg.content === 'fire' ? '🔥'
                      : msg.content === 'thumbsup' ? '👍'
                      : msg.content}
                  </p>
                  <div className={`flex items-center gap-1 mt-1 ${isOwn ? 'justify-end' : 'justify-start'}`}>
                    <span className={`text-xs ${isOwn ? 'text-red-200' : 'text-gray-400'}`}>
                      {formatTime(msg.timestamp)}
                    </span>
                    {isOwn && (
                      msg.status === 'pending'
                        ? <Spinner />
                        : <span className="text-xs text-red-200">&#10003;</span>
                    )}
                  </div>
                </div>
              </div>
            )
          })}

          <div ref={messagesEndRef} />
        </div>

        {/* Text input */}
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Type a message..."
          autoComplete="new-password"
          autoCorrect="off"
          autoCapitalize="off"
          className="w-full bg-white text-gray-900 placeholder-gray-400 rounded-xl px-4 py-3 text-base focus:outline-none focus:ring-2 focus:ring-red-500 mb-3 shrink-0 shadow-sm"
        />

        {/* Action buttons */}
        <div className="flex flex-col gap-2 pb-12 shrink-0">
          <button
            onClick={handleSend}
            disabled={!input.trim()}
            className="w-full bg-red-500 hover:bg-red-600 disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed text-white font-semibold rounded-xl py-3 text-base transition-colors"
          >
            Send
          </button>
          <div className="grid grid-cols-3 gap-2">
            {[
              { key: 'smile',    emoji: '😊' },
              { key: 'wow',      emoji: '😮' },
              { key: 'skull',    emoji: '💀' },
              { key: 'heart',    emoji: '❤️' },
              { key: 'fire',     emoji: '🔥' },
              { key: 'thumbsup', emoji: '👍' },
            ].map(({ key, emoji }) => (
              <button
                key={key}
                onClick={() => sendMessage(key)}
                className="bg-gray-200 hover:bg-gray-300 active:bg-gray-400 rounded-xl py-3 text-2xl transition-colors"
              >
                {emoji}
              </button>
            ))}
          </div>
        </div>

      </div>

    </div>
  )
}
