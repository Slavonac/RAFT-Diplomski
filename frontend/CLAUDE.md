# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm install        # Install dependencies (run once)
npm run dev        # Start Vite dev server (http://localhost:5173)
npm run build      # Production build
npm run preview    # Preview production build
```

## Architecture

Vite + React 18 + Tailwind CSS v3 SPA with React Router v6.

**Entry points:** `index.html` -> `src/main.jsx` -> `src/App.jsx`

**Routing (BrowserRouter):**
- `/` — `LoginPage.jsx`: username input, saves to `localStorage`, navigates to `/chat`
- `/chat` — `ChatPage.jsx`: full chat UI, redirects to `/` if no username in localStorage

**API integration** (`ChatPage.jsx`):
- `GET http://localhost:3000/api/messages` — polled every 2 seconds via `setInterval` in `useEffect`
- `POST http://localhost:3000/api/messages` — sends `{ id, timestamp, username, content }` on Send / Skull / Wow buttons

**Message ID generation:** `Date.now() + random 6-digit number`

**Styling:** mobile-first, dark theme (`bg-gray-950`/`bg-gray-900`), `max-w-md mx-auto`, `100dvh` layout in chat to keep buttons pinned above the mobile keyboard.
