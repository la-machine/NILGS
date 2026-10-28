// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The demonstration notice, on every screen and in both languages: nothing shown has legal
// effect (Baseline, gate G1).
import { Icon } from '../shared/ui/Icon'

export function DemoBanner() {
  return (
    <div className="demo-banner" role="note">
      <Icon name="info" size={16} />
      <span lang="fr">Démonstration — données fictives, aucune valeur juridique.</span>
      <span lang="en">Demonstration — synthetic data, no legal effect.</span>
    </div>
  )
}
