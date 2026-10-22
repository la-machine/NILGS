// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Entry point: global styles and the application shell (src/README.md explains the layout).
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './styles/index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
