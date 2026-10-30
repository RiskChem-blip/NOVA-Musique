# Nova Musique (Android, WebView + assets/index.html)

## État actuel (oct. 2026)
- Refonte visuelle façon Nova STREAM, thème Bordeaux : intro « Supernova » (une fois par session),
  barre haute avec logo et boutons ronds, vitrine « N°1 de ton mois », tuiles « Quelle envie ? »,
  Top 10 à chiffres détourés, carrousels de titres, barre du bas pleine largeur.
- Lecture via le lecteur YouTube officiel, **toujours visible** (règles YouTube) :
  à la place de la pochette dans le lecteur ; ailleurs, fenêtre flottante 200×200 déplaçable
  (coin au choix) ou pause (« dans le lecteur seulement »). Cadre aux couleurs de l'appli en option.
  Pause quand l'appli passe en arrière-plan ; écran allumé pendant la lecture.
  Pas de lecteur masqué, pas de lecture YouTube en arrière-plan, pas de blocage des pubs.
- Clé YouTube : secret GitHub `YOUTUBE_API_KEY` → BuildConfig.YT_KEY ; une clé collée dans
  Comptes la remplace.
- Fichiers du téléphone : lecture native (Media3) en arrière-plan, hors ligne.
- Scrobbling Last.fm + ListenBrainz (file d'attente hors ligne).

## Backlog
- Webradios et Jamendo (audio seul, arrière-plan autorisé)
- Minuterie de mise en veille
