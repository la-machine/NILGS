// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Application shell placeholder. Layouts, routing, FR/EN switching and the
// citizen and back-office areas arrive with E-07; until then only the
// demonstration notice, which must stay visible on every screen, is shown.
function App() {
  return (
    <>
      <div className="demo-banner" role="note">
        <span lang="fr">Démonstration — données fictives, aucune valeur juridique.</span>
        <span lang="en">Demonstration — synthetic data, no legal effect.</span>
      </div>
      <main>
        <h1>SNIGF / NILGS</h1>
        <p lang="fr">Système National Intégré de Gouvernance Foncière</p>
        <p lang="en">National Integrated Land Governance System</p>
      </main>
    </>
  )
}

export default App
