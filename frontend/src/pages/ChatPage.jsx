import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'

const API_URL = 'http://localhost:8080/message'
const POLL_INTERVAL = 2000

function generateId() {
  return `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
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

export default function ChatPage() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const messagesEndRef = useRef(null)
  const navigate = useNavigate()
  const username = localStorage.getItem('username')

  useEffect(() => {
    if (!username) navigate('/')
  }, [username, navigate])

  // ─── Polling ────────────────────────────────────────────────────────────────
  // Every POLL_INTERVAL ms, fetch all messages from the server.
  // Server messages are marked 'sent'. Local pending messages not yet seen
  // by the server are preserved until the server confirms them (by id).
  // useEffect(() => {
  //     const fetchMessages = async () => {
  //       try {
  //         const res = await fetch(API_URL)
  //         if (res.ok) {
  //           const data = await res.json()
  //           setMessages(prev => {
  //             const serverIds = new Set(data.map(m => m.id))
  //             // Keep any pending messages not yet reflected in server response
  //             const stillPending = prev.filter(
  //                 m => m.status === 'pending' && !serverIds.has(m.id)
  //             )
  //             const serverMessages = data.map(m => ({ ...m, status: 'sent' }))
  //             return [...serverMessages, ...stillPending].sort(
  //                 (a, b) => new Date(a.timestamp) - new Date(b.timestamp)
  //             )
  //           })
  //         }
  //       } catch {
  //         // server unavailable
  //       }
  //     }
  //
  //   fetchMessages()
  //   const interval = setInterval(fetchMessages, POLL_INTERVAL)
  //   return () => clearInterval(interval)
  // }, [])
  // ────────────────────────────────────────────────────────────────────────────

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = async (content) => {
    const message = {
      id: generateId(),
      timestamp: new Date().toISOString(),
      username,
      content,
      status: 'pending',
    }

    // Optimistically add message to the list as pending
    setMessages(prev => [...prev, message])

    try {
      await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(message),
      })
      // Mark as sent after server confirms
      setMessages(prev =>
        prev.map(m => (m.id === message.id ? { ...m, status: 'sent' } : m))
      )
    } catch {
      // server unavailable — message stays pending
    }
  }

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

  return (
    <div className="fixed inset-0 bg-gray-100 flex justify-center px-4 py-4">
      <div className="w-full max-w-md flex flex-col h-full min-h-0">

        {/* Header */}
        <div className="flex items-center justify-between py-3 mb-2 shrink-0">
          <h1 className="text-xl font-bold text-gray-900">Chat</h1>
          <button
            onClick={() => { localStorage.removeItem('username'); navigate('/') }}
            className="text-gray-400 hover:text-gray-600 text-sm transition-colors"
          >
            @{username} &times;
          </button>
        </div>

        {/* Message list */}
        <div className="flex-1 overflow-y-auto mb-3 space-y-2 pr-1 min-h-0">
          {messages.length === 0 && (
            <p className="text-center text-gray-400 text-sm mt-10">No messages yet...</p>
          )}

          {messages.map(msg => {
            const isOwn = msg.username === username
            return (
              <div key={msg.id} className={`flex ${isOwn ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[75%] rounded-2xl px-4 py-2 shadow-sm transition-opacity duration-300 ${
                    isOwn
                      ? 'bg-red-500 text-white rounded-br-sm'
                      : 'bg-white text-gray-900 rounded-bl-sm'
                  } ${msg.status === 'pending' ? 'opacity-60' : 'opacity-100'}`}
                >
                  {!isOwn && (
                    <p className="text-xs font-semibold text-red-500 mb-1">{msg.username}</p>
                  )}
                  <p className="text-sm leading-snug">
                    {msg.content === 'skull' ? '💀' : msg.content === 'wow' ? '😮' : msg.content}
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
        <div className="flex gap-2 pb-12 shrink-0">
          <button
            onClick={handleSend}
            disabled={!input.trim()}
            className="flex-1 bg-red-500 hover:bg-red-600 disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed text-white font-semibold rounded-xl py-3 text-base transition-colors"
          >
            Send
          </button>
          <button
            onClick={() => sendMessage('skull')}
            className="bg-gray-200 hover:bg-gray-300 active:bg-gray-400 text-gray-800 rounded-xl py-3 px-5 text-xl transition-colors"
            title="Send skull"
          >
            &#128128;
          </button>
          <button
            onClick={() => sendMessage('wow')}
            className="bg-gray-200 hover:bg-gray-300 active:bg-gray-400 text-gray-800 rounded-xl py-3 px-5 text-xl transition-colors"
            title="Send wow"
          >
            &#128558;
          </button>
        </div>

      </div>
    </div>
  )
}
