import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

export default function LoginPage() {
  const [username, setUsername] = useState('')
  const navigate = useNavigate()

  const handleEnter = () => {
    const trimmed = username.trim()
    if (!trimmed) return
    localStorage.setItem('username', trimmed)
    navigate('/chat')
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') handleEnter()
  }

  return (
    <div className="h-dvh overflow-hidden bg-gray-100 flex flex-col items-center justify-center px-4">
      <div className="w-full max-w-md bg-white rounded-2xl p-8 shadow-md">
        <h1 className="text-3xl font-bold text-gray-900 text-center mb-2">Raft Messaging</h1>
        <p className="text-gray-400 text-center mb-8 text-sm">Enter a username to join the chat</p>

        <form autoComplete="off" onSubmit={(e) => { e.preventDefault(); handleEnter() }}>
          <input
            type="text"
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="new-password"
            autoCorrect="off"
            autoCapitalize="off"
            autoFocus
            className="w-full bg-gray-100 text-gray-900 placeholder-gray-400 rounded-xl px-4 py-3 text-base focus:outline-none focus:ring-2 focus:ring-red-500 mb-4"
          />

          <button
            type="submit"
            disabled={!username.trim()}
            className="w-full bg-red-500 hover:bg-red-600 disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed text-white font-semibold rounded-xl py-3 text-base transition-colors"
          >
            Enter Chat
          </button>
        </form>
      </div>
    </div>
  )
}
